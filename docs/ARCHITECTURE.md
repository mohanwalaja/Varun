# Architecture

Design decisions and the reasons behind them. This is written for someone who
wants to understand *why* the code looks the way it does, particularly given the
constraint that it was developed with no compiler available.

---

## The governing constraint

The target user has an **8 GB Android phone and no computer**. That single fact
drove almost every decision:

- No build can happen locally → the build must be reproducible in CI, from a
  phone, with no local state.
- RAM is shared with Android itself → memory must be estimated *before* loading,
  and the app must be able to release the model at any moment.
- Storage is finite → one `.so` per ABI, no bundled model, capped imports.
- There is no second screen and no keyboard → every flow must work one-handed in
  portrait, and every destructive action must be confirmable on a small display.
- Mistakes are expensive (no easy recovery, no dev tools attached) → the app must
  be conservative: back up before writing, refuse before guessing, and say when
  it does not know.

---

## Layering

```
UI (Compose screens)
  └─ ViewModels (ui/vm)          StateFlow<UiState>, one immutable state object each
       └─ Repositories (data/repo)   Settings, Model, Workspace, GitHub, Chat, Task
            ├─ LlamaEngine (ai)        JNI facade over the C++ runtime
            ├─ SecureVault (core)      AndroidKeyStore AES-256-GCM
            ├─ VarunDatabase (data/db) SQLiteOpenHelper, 7 tables
            ├─ Workspace helpers       PathGuard, DiffEngine, EditJournal,
            │                          CodeSearch, ArchiveExtractor
            └─ GitHubClient/GitOps     REST + Git Data API
```

MVVM with repositories, Kotlin coroutines and `StateFlow` throughout. No
blocking work on the main thread; inference runs on `Dispatchers.Default` inside
a foreground service.

Each ViewModel exposes exactly one `ui: StateFlow<UiState>` holding an immutable
data class. Screens collect it and render. That makes the whole UI a pure
function of state, which is the only way to reason about a Compose app you
cannot run.

`AppContainer` wires dependencies by hand and exposes a
`ViewModelProvider.Factory`. No Hilt, no Koin.

---

## Dependency minimisation

This is the most unusual thing about the project, and it was deliberate.

| Not used | Used instead | Why |
|---|---|---|
| Room | `SQLiteOpenHelper` | Room needs KSP and code generation — two more things to fail in a build nobody can run locally. Seven tables with hand-written SQL is less code than the Room setup. |
| Moshi / Gson | `org.json` | `org.json` is already in the Android platform. Adding a JSON library for one REST client is not worth the APK size or the annotation processor. |
| Hilt / Dagger | `AppContainer` | A hand-written container is ~100 lines and completely transparent. |
| androidx.security-crypto | `SecureVault` | That library is deprecated, wraps Tink, and pulls in a large dependency tree for one AES-GCM key. |
| AppCompat | `LocaleHelper` + framework `LocaleManager` | A Compose-only app does not need AppCompat. Per-app language is framework API 33+ with a `ContextWrapper` fallback below. |
| JGit | GitHub Git Data API | JGit is a huge dependency and a full local object store is neither needed nor desirable on a phone. |

Every one of these also reduces the chance of a build failure that cannot be
debugged locally. Fewer moving parts was worth more than convenience.

---

## The native runtime

### Why one shared library

`libvarunllama.so` statically links llama.cpp and ggml. The alternative —
shipping `libllama.so`, `libggml.so`, `libggml-base.so` and `libggml-cpu.so`
separately — quadruples the JNI library set, complicates load order, and
increases APK size through duplicated ELF overhead.

### Why `GGML_NATIVE` is OFF

This is the single most important flag in the native build. `GGML_NATIVE=ON`
compiles for the *build host's* instruction set. On a GitHub Actions x86_64
runner cross-compiling to ARM, or even on an ARM runner with newer extensions
than the target phone, the result crashes with `SIGILL` on load. Forcing it OFF
produces a library that runs on any arm64-v8a device.

### Why CPU-only backends

Vulkan and OpenCL backends exist and would be faster. They were rejected
because:

- The GPU driver compatibility matrix across thousands of phone models is
  unmaintainable for a project with no device to test on.
- A driver bug becomes a native crash with no useful stack.
- CPU-only is predictable, and predictability matters more than speed here.

This is a real performance trade-off, made deliberately, and it is documented as
a limitation rather than hidden.

### The JNI contract

Natives are declared as **top-level `external fun`** in a file annotated
`@file:JvmName("LlamaNative")`:

```kotlin
@file:JvmName("LlamaNative")
package dev.varun.studio.ai

external fun nativeLoadModel(...): Long
```

Top-level functions make Kotlin emit **static** native methods, which is why the
C++ signatures take a `jclass` rather than a `jobject`:

```cpp
JNIEXPORT jlong JNICALL
Java_dev_varun_studio_ai_LlamaNative_nativeLoadModel(JNIEnv* env, jclass, ...)
```

Moving these into an `object` or `class` would change them to instance methods
and every symbol lookup would fail with `UnsatisfiedLinkError` at first use.
There is a comment in the source saying so, and the ProGuard rules keep the
class and the callback interfaces by name for the same reason.

### Errors are values, not exceptions

`LlamaEngine` **never throws**. Failures come back as data:

```kotlin
sealed interface EngineLoadResult { data class Success(...); data class Failure(kind, message) }
data class GenerateOutcome(code: GenerationResultCode, tokensGenerated, error, stats)
```

Result codes (`kOk`, `kCancelled`, `kStopped`, `kMaxTokens`, `kContextFull`,
`kDecodeFailed`, `kOutOfMemory`, `kNotLoaded`, `kInvalidSampler`) are matched by
**ordinal** between C++ and Kotlin. The enum order is therefore part of the ABI,
and the ProGuard rules forbid reordering or stripping it.

Exceptions are also disabled in the native build (`-fno-exceptions -fno-rtti`),
so a C++ exception crossing into the JVM would be undefined behaviour anyway.
Returning values is the only correct design here.

### Cancellation

A JNI handle is a heap `NativeHandle { Session; std::atomic<bool> cancel; }`.
Cancellation sets the atomic flag, which the generation loop checks per token.
A host test proves that calling `Unload` **from another thread while generation
is running** completes without deadlock — the classic way to hang an inference
runtime.

### Silencing upstream logs

llama.cpp writes to stderr via its own log callback. On Android that is noise in
logcat and can leak model internals, so `LlamaLogSink` redirects it.

---

## Safety architecture

### One choke point for paths

`workspace/PathGuard.kt` is the **only** place a relative path becomes a `File`.
It rejects empty, absolute, traversal, NUL-containing, reserved (`.git`),
over-long and drive-letter paths, then **re-verifies the canonical path** after
resolution so a symlink cannot escape the root.

Every writer goes through it: the editor, archive extraction, model-proposed
edits, journal backups. There is no second path-resolution implementation
anywhere in the codebase. That is what makes the guarantee auditable.

### Backup before write, always

`EditJournal.applyApproved` is the only function that writes a model-proposed
change. Its order is fixed:

1. Validate the path through `PathGuard`
2. Refuse symlinks and directories
3. **Back up the original** — if the backup fails, abort without writing
4. Write to a temp file, `fsync`, then atomically rename
5. Journal the application with a SHA-256 of the new content

If any step fails, the target file is untouched. A unit test asserts that the
`BACKUP` journal entry precedes the `APPLIED` entry, because that ordering *is*
the safety property.

### Approval is structural, not advisory

`CodingAgent` has no direct file-write capability. Its only route to disk is
`EditJournal.applyApproved`, which requires an approved `Proposal`. There is no
code path where a model's output reaches the filesystem without passing a
per-file approval flag.

### Git writes are constrained by construction

- `force=false` is hard-coded in every ref update
- Branch and ref deletion is **not implemented** — there is no code path to call
- `commitAndPush(..., requireExplicitConfirmation, allowDefaultBranch = false)`
  makes the safe behaviour the default parameter
- `updateSingleFile` sends the blob's `expectedSha`, so a 409 conflict **blocks**
  rather than overwriting a concurrent change
- Branch-name and path validators are pure functions with exhaustive unit tests

### Prompt-injection resistance

The system prompt states that all context — including comments and READMEs — is
untrusted data and that instructions embedded in it must not be followed.
`parseProposedEdits` is strict: malformed blocks are dropped, empty bodies are
dropped, and a block whose body contains an injected instruction is still parsed
as exactly one file edit (unit-tested).

This is mitigation, not proof. A small model can still be steered. The real
defence is that nothing is written without a human approving a visible diff.

---

## Memory management

The estimator is the interesting part, because it is used **before** loading:

```
total = model bytes
      + KV cache        2 × layers × kv_heads × head_dim × ctx × 2
      + compute buffers batch × embd × 4 × 8   (floored at 16 MB)
      + overhead        48 MB + 8 MB per thread
```

The KV formula is duplicated in C++ (`Session::EstimateKvCacheBytes`) and a unit
test asserts the two produce identical numbers. Duplicated arithmetic that can
silently diverge is a bug waiting to happen, so the parity is pinned.

Fit policy: **OK** ≤ 60% of available RAM, **TIGHT** ≤ 85%, **UNLIKELY** above
that. The 60/85 split exists because Android kills processes that push the
system into pressure — requiring headroom is what keeps the app alive instead of
being terminated mid-generation.

Estimates use RAM **currently available**, minus a 700 MB reserve for Android,
not total device RAM. `MemoryInfo.usableForModelBytes` encodes that.

`onTrimMemory(RUNNING_CRITICAL)` releases the model unless the user explicitly
enabled *Keep model loaded*. The default is to survive rather than to stay fast.

---

## Data

`SQLiteOpenHelper`, version 1, seven tables: `models`, `chat_sessions`,
`chat_messages`, `tasks`, `change_journal`, `audit_reports`, `settings`. Foreign
keys cascade, so deleting a session removes its messages.

The edit journal is deliberately **not** in SQLite. It is an append-only line
file (`.varun-journal.log`) next to the backups it references, so it survives
even if the database is wiped or corrupted, and so it can be inspected with any
text editor. `Entry.decode` tolerates a truncated final line — the realistic
result of a crash mid-write — rather than failing to load the whole history.

Credentials never touch the database. They live only in `SecureVault`.

---

## Localisation

English and Tamil, 215 strings each, verified to match exactly in both
directions so there are no `MissingTranslation` or `ExtraTranslation` failures.

Per-app language uses the framework `LocaleManager` on API 33+ and a
`ContextWrapper` (`LocaleHelper.wrap`) below that, applied in
`attachBaseContext`. `res/xml/locales_config.xml` is declared in the manifest so
Android 13+ lists the app in system per-app language settings.

One subtlety worth recording: **numeric formatting is pinned to `Locale.US`**
while date formatting stays locale-aware. Under `ta_IN` the decimal separator
differs, so `"%.1f".format(x)` would render `12,3 tok/s` — inconsistent for the
user and unparseable for logs and tests. Display dates are localised on purpose;
numbers are not.

---

## What was learned building without a compiler

No `javac`, no Android SDK, no Gradle distribution and no Maven Central were
reachable. That shaped the process:

1. **The C++ layer was verified by execution, not review.** Building llama.cpp
   for the host and running the shipping session code against a real GGUF found
   three genuine bugs (`llama_sampler_init_penalties` argument order, the negated
   `llama_tokenize` probe return, integer-millisecond rate math reporting 0.0
   tok/s). None would have been caught by reading.

2. **A synthetic GGUF was written to make that possible.** The upstream test
   model lives on Hugging Face, which was unreachable. Random weights mean the
   output is gibberish by design — it validates plumbing, not intelligence, and
   the docs say so.

3. **Static cross-checks substituted for a type checker.** Every `R.string`
   reference, manifest resource, format-string argument count, intra-project
   import and ViewModel member access was verified mechanically. That caught a
   `ChatRepository` method inserting a row with `sessionId = 0` (a foreign-key
   violation that would have lost every assistant reply) and a missing
   credential check in `ModelCatalog.checkUrl`.

4. **Fabricated APIs were the main hazard.** Writing Compose code without a
   compiler produces plausible-but-nonexistent calls: a `material3`
   `SelectionContainer`, an `itemsIndexed` that does not exist, a coroutine
   `launch` shim with the wrong signature, an `AppCompatDelegate` in a project
   with no AppCompat dependency. Each was found by re-reading the real source.
   `ExposedDropdownMenuBox`/`menuAnchor` was replaced with a plain
   `DropdownMenu` because its signature is unstable across material3 versions.

5. **APIs were read, never assumed.** Every ViewModel signature, enum ordinal and
   struct field used by a screen was extracted from the actual source file before
   being referenced.

The honest consequence: **the first real Kotlin compilation happens in GitHub
Actions.** See [TESTING.md](TESTING.md).
