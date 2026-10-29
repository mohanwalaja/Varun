# Testing and verification

This document is deliberately blunt about what was **proven**, what was only
**written**, and what **could not be checked at all**.

The environment where this code was **written** had no `javac`, no Android SDK,
no Gradle distribution and no access to Maven Central, so nothing could be
compiled locally at all. Every compilation and test execution reported below
happened in GitHub Actions. That constraint shaped the order of the work, and
it is stated here rather than hidden.

---

## Summary

| Layer | Method | Status |
|---|---|---|
| Native C++ engine | Compiled **and executed** on a Linux host against a real llama.cpp build and a real GGUF file | ✅ **50 / 50 assertions passed** |
| JNI bridge | Compiled `-Werror` against a real `jni.h`; exported symbols enumerated with `nm` | ✅ **13 entry points confirmed** |
| Kotlin / Compose app | Compiled by Gradle 8.9 + Kotlin 2.0.21 + AGP 8.7.3 on a GitHub Actions runner, then packaged and signed | ✅ **APK built** — `Varun-Studio-1.0.0-arm64-v8a.apk`, 28 MB |
| Kotlin unit tests | 9 suites, executed by `:app:testDebugUnitTest` on a CI runner | ✅ **207 tests, 0 failures** |
| Android emulator | Not available | ❌ **Blocked** |
| Physical device | Not available | ❌ **Blocked** |
| On-device inference speed / quality | Requires a physical device | ❌ **Unverified** |

---

## What was actually executed

### Native engine host tests — 50 / 50 passed

`tools/hosttest/host_engine_test.cpp` is compiled against the **exact shipping
file** `app/src/main/cpp/llama_session.cpp` — not a copy, not a mock — with
`-Wall -Wextra -Werror`, linked to a host build of llama.cpp `b11979`, and run
against a real GGUF model.

Reproduce it:

```bash
./tools/hosttest/run_host_tests.sh
```

That script clones llama.cpp at the pinned commit, builds it, synthesises a
tiny valid GGUF, compiles the harness and runs it. It also runs as the
`host-tests` job in CI.

**Checks covered (50 assertions):**

| Area | What is asserted |
|---|---|
| Runtime identity | `RuntimeVersion()` reports the pinned session ABI version |
| Capability probing | mmap support is reported, not assumed |
| Error handling | Missing model file returns a real error, not a crash |
| State machine | Generating with no model loaded returns `kNotLoaded` |
| Load path | Load succeeds; metadata (vocab, ctx, layers, embd, params, ftype) is populated |
| Double-load | Loading twice is rejected cleanly rather than leaking |
| Multi-session | A second independent session works concurrently |
| Tokenizer | Tokenize → detokenize round-trips |
| Chat template | `llama_chat_apply_template` produces a non-empty wrapped prompt |
| Streaming | The token callback fires and receives incrementing counts |
| Statistics | Tokens/second is computed from microseconds and is non-zero |
| Determinism | Greedy decoding (temperature 0) reproduces identical output |
| **Cancellation** | Setting the cancel flag stops generation and returns `kCancelled` |
| **Callback stop** | The callback returning `false` stops generation with `kStopped` |
| Stop strings | A configured stop string terminates generation |
| Context overflow | An oversized prompt returns `kContextFull` rather than corrupting memory |
| Empty input | An empty prompt is handled without a crash |
| Recovery | The session is usable again after an error |
| KV estimate | `EstimateKvCacheBytes` matches the expected formula |
| Context reset | `ResetContext` clears state and allows a fresh generation |
| Unload | `Unload` is idempotent |
| **Thread safety** | `Unload` from another thread mid-generation completes **without deadlock** |

Three real bugs were found and fixed only because this harness was compiled and
run rather than reviewed:

1. `llama_sampler_init_penalties` takes `n_vocab` as its **first** argument.
   Omitting it is a hard compile error.
2. `llama_tokenize` returns the **negated** required buffer size on a short
   probe, not a positive count. Handling `<= 0` as failure silently produced
   zero tokens.
3. Integer-millisecond rate math reported **0.0 tokens/sec** for sub-millisecond
   requests. Timing had to move to microseconds.

### JNI bridge — compiled clean, symbols verified

`app/src/main/cpp/llama_bridge.cpp` was compiled with `-Werror` against a real
`jni.h`, and the resulting object file was inspected:

```
nm --defined-only llama_bridge.o | grep Java_dev_varun_studio
```

13 `Java_dev_varun_studio_ai_LlamaNative_*` symbols were confirmed present.

This matters because the natives are declared as **top-level `external fun`** in
a file annotated `@file:JvmName("LlamaNative")`. That makes Kotlin emit *static*
native methods, which is why the C++ signatures take a `jclass` rather than a
`jobject`. Get that pairing wrong and the app crashes with
`UnsatisfiedLinkError` at first use — it cannot be caught by inspection alone.

### Synthetic GGUF model — what it does and does not prove

`tools/hosttest/make_synthetic_gguf.py` builds a structurally valid GGUF (2
layers, 320-token vocab, 64-dim embeddings, ~502 KB) using the upstream `gguf`
Python package.

**It proves:** the file parses, the architecture loads, tensors allocate, the
sampler chain runs, tokens stream, cancellation works, memory is released.

**It does not prove:** output quality. The weights are random, so the text is
gibberish **by design**. Any claim that this validated model intelligence would
be false.

The real upstream test model (`karpathy/tinyllamas`) was unavailable because
huggingface.co is not reachable from the build environment, so the synthetic
file was written as a substitute.

---

## Kotlin unit tests — 9 suites, 207 executed in CI

| Suite | Target | Focus |
|---|---|---|
| `PathGuardTest` | `workspace/PathGuard` | Traversal, absolute paths, NUL injection, `.git`, symlink escape, over-long paths, sanitisation |
| `DiffEngineTest` | `workspace/DiffEngine` | Line numbering, hunks, unified-diff shape, word diff, similarity, large-input fallback |
| `EditJournalTest` | `workspace/EditJournal` | Backup-before-write ordering, rollback, refusal paths, journal durability, encode/decode round-trip |
| `ArchiveExtractorTest` | `workspace/ArchiveExtractor` | **Zip-slip rejection**, tar symlink refusal, entry/byte budgets, corrupt input |
| `CodeSearchTest` | `workspace/CodeSearch` | Matching, case, whole-word, regex, truncation, skip rules, binary sniffing |
| `MemoryEstimatorTest` | `ai/MemoryEstimator` | KV-cache parity with C++, quant table, fit policy, context affordability |
| `ModelCatalogTest` | `ai/ModelCatalog` | Catalogue completeness, URL allowlist, **credential-bearing URL rejection** |
| `PromptLibraryTest` | `agent/PromptLibrary` | Budget enforcement, truncation disclosure, edit-block parsing, injection resistance |
| `GitHubGitOpsValidationTest` | `github/GitHubGitOps` | Branch-name and path validation for every write path |

These compile against API signatures that were read directly out of the source
files, and every resource reference, ViewModel member access and intra-project
import was cross-checked mechanically before writing them.

**They have now actually been run.** The `android` CI job executes
`:app:testDebugUnitTest` on a GitHub-hosted runner and publishes a per-suite
table to the job summary.

- First real execution (run `40849511839`): **207 tests, 199 passed, 8 failed.**
- Current state (run `40852797898`): **207 tests, 0 failures**, and the same run
  built, verified and published the APK.

Each of the 8 failures was traced to its root cause before deciding whether the
source or the test was wrong. **Three were real defects in shipped source:**

| Where | Defect |
|---|---|
| `PathGuard.sanitizeFileName` | `../../etc/passwd` sanitised to `_.._etc_passwd`, which still contains `..`. Separately, the reserved-name check ran *after* `trim('.')`, so `.git` was disguised as `git` and passed as something that merely looks safe. Dot runs are now collapsed and the caller's original spelling is checked too. |
| `ArchiveExtractor.extractZip` | A file that is not a zip makes `ZipInputStream` report "no entries" rather than throw, so a corrupt archive returned `Success(filesWritten = 0)` — a silently empty workspace with no explanation. The ZIP container magic is now verified first, accepting both `PK\x03\x04` and the `PK\x05\x06` of a valid entry-less archive. |
| `ModelCatalog` | The TinyLlama entry declared `tinyllama-1.1b-chat-v1.0-q4_k_m.gguf` while its URL fetches `tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf` — TheBloke's convention puts a **dot** before the quantization. `fileName` is the name the download is saved under, so a mismatch makes the model manager misleading and any name-based verification meaningless. |

The other five were **test bugs, and the implementation was deliberately left
alone**:

- `DiffEngine.hunks` returns **8** lines for a one-line change with 3 lines of
  context, because a change is *two* diff lines (`-old` and `+new`). The test
  said 7. Traced through `hunks()`: `slice = lines[1..8]`.
- The `estimateParams` expectation rested on wrong arithmetic:
  `(1_140_000_000 − 2 MiB) ÷ (4.85 / 8)` = **1_876_953_151**, i.e. ~1.88 B, not
  the 1.6 B the test asserted. The implementation does exactly what it documents.
- The catalogue URL check demanded a byte-identical filename, but Hugging Face
  paths are case-sensitive and bartowski really publishes
  `Llama-3.2-1B-Instruct-Q4_K_M.gguf`. Now compared case-insensitively, which
  preserves the real intent: the URL must point at the declared file.
- The `EditJournal` diff test never created the file it claimed to diff against.
  `applyApproved` reads the original from **disk**, not from
  `proposal.originalText`, because the file may change between proposal and
  approval and the backup is taken from disk too. That is the correct design.
- The `PromptLibrary` "dropped files" test built a scenario that drops nothing:
  an oversized file is **truncated and kept** by `applyBudget`, not dropped.
  Fixed by forcing a real drop via `maxFiles = 1`, and a companion test was
  added for the truncation path, since a fragment passed off as a whole file is
  the more dangerous of the two.

Before CI ever ran, writing these tests had already found two further defects:

- `ChatRepository.updateAssistantMessage` inserted a **new row with
  `sessionId = 0`** instead of updating the streamed message. That would have
  violated the foreign key and lost the assistant's reply. Rewritten as a real
  `UPDATE`.
- `ModelCatalog.checkUrl` did not reject `https://user:pass@host/model.gguf`.
  Embedded credentials would have been sent on the wire and written into the
  download log. A `userInfo` check was added.

### Static cross-checks that were run

Because there was no compiler, these were verified mechanically instead:

- **All 166 `R.string.*` references** resolve against `res/values/strings.xml`.
- **English and Tamil string sets match exactly** (215 each, no orphans in
  either direction, so no `ExtraTranslation` lint failure).
- **All format-string argument counts** match their placeholders.
- **All manifest resource references** (`@mipmap`, `@string`, `@style`, `@xml`) resolve.
- **All 306 `ui.*` / `vm.*` member accesses** across 7 screens resolve against
  the 7 ViewModels' declared `UiState` fields and methods.
- **All 252 intra-project imports** resolve to real declarations.
- **No duplicate non-private top-level declarations** in any package.

These catch the classes of error that are most likely when a large amount of
code is written without a compiler. They are **not** a substitute for one.

---

## What is blocked, and why

### No JVM in the authoring environment — closed by running CI

Nothing could be compiled where the code was written:

- `dl.google.com` (Android SDK, JDK) — **blocked**
- `services.gradle.org` (Gradle distributions) — **blocked**
- `repo1.maven.org` (Maven Central, all dependencies) — **blocked**
- `release-assets.githubusercontent.com` (Temurin JDK, Kotlin compiler, llama.cpp
  Android prebuilts) — **blocked**, `curl: (35) SSL_ERROR_SYSCALL`
- Every PyPI JDK candidate (`jdk`, `openjdk`, `temurin`, `javac`, `ecj`) —
  **404 or a placeholder package**

Only `codeload.github.com` source tarballs and PyPI's own index were reachable.
**This gap was closed by building in GitHub Actions instead**, which is why
`.github/workflows/build-apk.yml` is part of the deliverable rather than a
convenience: it is the only toolchain that has ever compiled this code, and the
APK in the `apk-latest` release is its output.

One consequence of that same network block is still open: **the finished APK
cannot be pulled back into the authoring environment.** The release-asset host
fails the TLS handshake, and at 28 MB the APK is far above the contents API's
1 MB inline limit. The APK is therefore delivered only from the GitHub Release
page, which a phone browser reaches directly. It has not been mirrored anywhere
else.

### No emulator, no device

There is no Android emulator, no `adb`, and no physical phone in this
environment. Nothing about the running app has been observed: no screenshot, no
layout check, no touch interaction, no measurement of tokens per second.

### Consequence for the honesty requirement

The brief requires that local inference not be claimed to work until tested on a
real compatible Android device. **That test has not happened.** The app's own
Home and Settings screens state this to the user at runtime rather than implying
the feature is proven.

---

## Acceptance criteria matrix

Status key:
- ✅ **Verified** — proven by execution
- 🟡 **Implemented, unverified** — the code exists and was statically checked, but has not been run
- ⛔ **Blocked** — cannot be verified in this environment
- ❌ **Not met**

| # | Criterion | Status | Evidence / gap |
|---|---|---|---|
| 1 | Native Android app (Kotlin + Compose), not a website, PWA or mockup | ✅ | Compiled by Kotlin 2.0.21 + AGP 8.7.3 and packaged into a real APK whose manifest, DEX and `lib/arm64-v8a/libvarunllama.so` were verified in CI. No web runtime of any kind is present. |
| 2 | A real installable APK can be produced without the user owning a computer | ✅ | Run `40852797898` produced `Varun-Studio-1.0.0-arm64-v8a.apk` (28 MB, SHA-256 `907682a3…218b`) and published it to the `apk-latest` Release, reachable from a phone browser. **First actual install on a device is still pending — see the note below.** |
| 3 | All AI inference runs on device; no cloud AI calls | ✅ | Inference goes only through JNI → llama.cpp. No network code exists in the AI path. Engine execution proven by host tests. |
| 4 | GGUF models importable from phone storage (SAF) | 🟡 | `ActivityResultContracts.OpenDocument` + `ModelRepository.importFromUri`. No storage permissions requested. Not exercised on a device. |
| 5 | Model manager shows size/format/status/estimated memory; never auto-downloads or auto-loads | 🟡 | Estimator unit-tested for arithmetic; consent dialog gates every download; `download(entry, userConsented)` refuses without consent. UI not run. |
| 6 | Memory estimation refuses models that will not fit; OOM handled; model unloadable | ✅🟡 | Estimator + fit policy verified by unit tests and mirrored in C++ (`EstimateKvCacheBytes` parity asserted). Actual OOM behaviour on a device is ⛔. |
| 7 | Inference off the main thread, with progress and a working stop | ✅ | `Dispatchers.Default`, foreground service, and cancellation proven by host test (`kCancelled` at 3 tokens; unload-from-another-thread without deadlock). |
| 8 | GitHub auth via user-supplied fine-grained PAT, encrypted; no secret embedded in the APK | 🟡 | `SecureVault` uses AndroidKeyStore AES-256-GCM. `GitHubClient` takes a `tokenProvider` lambda, so a baked-in secret is not expressible. Keystore behaviour ⛔ without a device. |
| 9 | List repos, browse files/folders/branches/commits, read code, search | 🟡 | Full REST client written and validation-tested. **Never called against the live API** — no token was available. |
| 10 | Local workspace from GitHub or archive; browse, search, select files for AI, edit | 🟡 | `ArchiveExtractor` and `CodeSearch` have unit tests including zip-slip. Not run on a device. |
| 11 | Diff shown original vs proposed; per-file approval required before applying | ✅🟡 | `DiffEngine` behaviour verified by unit tests; `CodingAgent` only writes through `EditJournal.applyApproved`. Approval UI not exercised. |
| 12 | Transactional edits with backups, journal and rollback | ✅🟡 | Backup-before-write ordering, rollback restoration and refusal paths are unit-tested. |
| 13 | Path traversal blocked on every write path | ✅ | `PathGuard` unit-tested (traversal, absolute, NUL, `.git`, symlink, length) and used by archive extraction, which is separately tested for zip-slip. |
| 14 | Commit/push need explicit confirmation; never force-push, delete branches, or silently touch the default branch | 🟡 | `force=false` hard-coded; validators unit-tested; `commitAndPush(..., requireExplicitConfirmation, allowDefaultBranch=false)`. **No live write was performed.** |
| 15 | AI inspects real files, cites real paths, reports untested changes honestly | ✅🟡 | System prompt hard-codes the rules and is unit-tested; `[UNTESTED]` extraction tested; `parseProposedEdits` tested including injection resistance. Real model output ⛔ without a device. |
| — | Light + dark themes | 🟡 | `Theme.kt` with system/light/dark. Not rendered. |
| — | English + Tamil | 🟡 | 215 strings in each, verified to match; `locales_config.xml` wired into the manifest. Tamil translations were machine-drafted and **have not been reviewed by a fluent speaker**. |
| — | Portrait / touch / keyboard optimised | ⛔ | Cannot be assessed without a device or emulator. |

---

## Automated vs emulator vs real device

The brief asks that these be distinguished explicitly:

| Category | Result |
|---|---|
| **Automated — executed here** | 50 native C++ assertions passed. JNI compile `-Werror` clean, 13 symbols confirmed. Static cross-checks on resources, imports and ViewModel access all clean. |
| **Automated — CI only** | Kotlin compilation, Compose compilation, 9 unit-test suites, APK assembly, APK content verification. **Results are not known at the time of writing.** |
| **Emulator** | Nothing. No emulator was available. |
| **Real device** | Nothing. No phone was available. |

---

## How to close the gaps yourself

If you install the APK, you can settle the open questions in a few minutes:

1. **Does the runtime load?** Settings → Diagnostics shows whether
   `libvarunllama.so` loaded and its version. If it did not, the reason is
   printed there.
2. **How fast is it?** Load Qwen2.5-Coder-0.5B first (fastest), ask any
   question, and read the tokens/second figure shown under the reply.
3. **Does it fit?** The Models screen shows an estimate and a `OK / TIGHT /
   UNLIKELY` banner **before** you load anything.
4. **Does GitHub work?** Connect with a fine-grained PAT and open a repo.
5. **Are edits safe?** Change one file, review the diff, approve it, then roll
   it back from the Journal tab.

Please report what you see. Everything above marked 🟡 or ⛔ becomes ✅ or ❌ the
moment someone runs it on a real phone.
