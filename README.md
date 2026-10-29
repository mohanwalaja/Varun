# Varun Studio

**An on-device AI coding assistant for Android.** Native Kotlin + Jetpack
Compose, with a real llama.cpp inference runtime compiled into the APK.

Built for one specific situation: **you have an 8 GB Android phone and no
computer.** Every AI response is generated locally on the phone. No cloud AI
API, no account, no per-token cost, no source code leaving the device.

---

## Download the APK

**[github.com/mohanwalaja/Varun/releases/tag/apk-latest](https://github.com/mohanwalaja/Varun/releases/tag/apk-latest)**

Open that link in your phone's browser, tap the `.apk` under **Assets**, allow
"install unknown apps" for your browser, and install. Full instructions:
**[docs/SETUP_PHONE.md](docs/SETUP_PHONE.md)**.

The APK is built by GitHub Actions from this source tree on every push. There
is no separately-maintained binary — what you download is what is in this repo.

> **The APK ships with no model.** Nothing is downloaded without your explicit
> consent. See [Models](#models) below.

---

## What it actually does

| Area | Capability |
|---|---|
| **Local inference** | GGUF models via llama.cpp (pinned `b11979`, MIT), compiled to a single `libvarunllama.so` per ABI and called through JNI. Works fully offline. |
| **Model manager** | Import a `.gguf` from phone storage (SAF), or download from a curated in-app catalogue with consent, checksum, progress and cancel. Shows size, quant, format and an estimated RAM footprint **before** loading. |
| **AI assistant** | Explain code, find bugs, security review, suggest fixes, generate code, refactor, review dependencies, explain build errors, write tests, produce a repository audit report. |
| **GitHub** | Connect with a fine-grained personal access token. List and search repos, browse files and folders, read contents, browse branches and commit history, run code search. |
| **Local workspace** | Import a repo (whole or selected files) or a zip/tar archive. Browse, edit, search locally, and select specific files as AI context. |
| **Safe editing** | Every change is proposed, diffed against the original, and applied only after per-file approval — with an automatic backup, a change journal, and rollback. |
| **Git writes** | Create a branch and commit through the GitHub Git Data API. Push only after an explicit confirmation that shows the exact file list and commit message. |

---

## Honesty policy

This project states plainly what was verified and what was not. The short
version:

- ✅ **The native engine is verified by execution.** `tools/hosttest/` compiles
  the *shipping* `llama_session.cpp` with `-Wall -Wextra -Werror` and runs a
  50-assertion harness against a real (synthetic) GGUF model: load, tokenize,
  generate, stream, cancel, stop strings, context-full handling, KV sizing,
  unload-from-another-thread. See [docs/TESTING.md](docs/TESTING.md).
- ✅ **The pure-Kotlin layers are covered by JVM unit tests** — diff engine,
  path guard, memory estimator, edit journal, archive extraction, code search,
  prompt library, model catalogue, git-op validation.
- ⚠️ **On-device inference has not been measured on a physical phone.** Nobody
  involved had one available. Speed, thermals and real model quality are
  **unverified** until you run it. The app says so in its own UI rather than
  pretending.
- ⚠️ **The AI cannot run your tests.** There is no code execution in this app.
  Every model-proposed fix is labelled `[UNTESTED]` and the app refuses to
  claim a bug is fixed.

The full verified / partially-verified / blocked matrix, including all 15
acceptance criteria, is in **[docs/TESTING.md](docs/TESTING.md)**.

---

## Models

No model is bundled, and none is downloaded automatically.

**Recommended starting point for an 8 GB phone:**

| | |
|---|---|
| Model | **Qwen2.5-Coder-1.5B-Instruct** |
| Quantization | **Q4_K_M** |
| File | `qwen2.5-coder-1.5b-instruct-q4_k_m.gguf` |
| Size | ~1.14 GB |
| License | Apache-2.0 (redistribution permitted) |
| Source | `https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF` |

The in-app catalogue also lists Qwen2.5-Coder 0.5B and 3B, Gemma 3 1B, Llama
3.2 1B and TinyLlama 1.1B. Each entry carries its own license — **Gemma and
Llama are NOT Apache/MIT** and have publisher terms you must accept yourself.
Details: **[docs/MODELS.md](docs/MODELS.md)**.

> An 8 GB phone does **not** mean an 8 GB model fits. Android gives a single
> app far less than total RAM, and the KV cache grows with context length. The
> app estimates the real footprint from the GGUF header and refuses to
> auto-load anything rated `UNLIKELY` to fit.

---

## Security model

- **No secrets in the APK.** No client id, no client secret, no GitHub App key,
  no service token, no embedded API key of any kind. Verified by construction:
  `GitHubClient` takes a `tokenProvider: () -> String?` lambda, so a baked-in
  credential is not even expressible.
- **Your GitHub token is encrypted at rest** with an AES-256-GCM key held in the
  Android Keystore (`core/security/SecureVault.kt`). If the Keystore is
  unavailable on your device, the app says so and refuses to store a token.
- **Minimum permissions.** The app needs Contents (read/write) and Metadata
  (read). It warns if your token carries broader scopes. Administration,
  delete and account scopes are never required.
- **Repo content is untrusted.** Files, READMEs, comments and model output are
  all treated as data. The system prompt explicitly refuses to follow
  instructions embedded in context.
- **Path traversal is blocked** at a single choke point (`workspace/PathGuard.kt`)
  used by the editor, archive extraction and every model-proposed edit.
  Symlinks are rejected, absolute paths are rejected, `.git` is rejected, and
  the canonical path is re-verified after resolution.
- **Git writes are constrained.** `force=false` always. Never force-pushes,
  never deletes a branch or ref, and refuses to commit to the default branch
  unless you explicitly override it.
- **No storage permissions.** File access is entirely through the Storage
  Access Framework, so the app cannot browse your phone.
- **Backup is disabled** (`allowBackup=false`) so tokens, models and workspaces
  are never copied into a cloud backup.

---

## Architecture

```
app/src/main/
├── cpp/                     native inference runtime
│   ├── llama_session.{h,cpp}  llama.cpp wrapper: load, tokenize, sample,
│   │                          stream, cancel, KV estimate, unload
│   ├── llama_bridge.cpp       JNI layer (13 exported entry points)
│   └── CMakeLists.txt         fetches llama.cpp at a pinned commit,
│                              statically links it into ONE libvarunllama.so
├── kotlin/dev/varun/studio/
│   ├── ai/                  LlamaEngine, MemoryEstimator, ModelCatalog,
│   │                        NativeLoader, InferenceForegroundService
│   ├── agent/               PromptLibrary (task prompts + budget), CodingAgent
│   ├── workspace/           PathGuard, DiffEngine, EditJournal, CodeSearch,
│   │                        ArchiveExtractor
│   ├── github/              GitHubClient (REST), GitHubGitOps (Git Data API),
│   │                        GitHubModels
│   ├── data/
│   │   ├── db/              SQLiteOpenHelper, 7 tables, no Room
│   │   └── repo/            Settings, Model, Workspace, GitHub, Chat, Task
│   ├── core/                AppLog (with redaction), DeviceMemory,
│   │                        LocaleHelper, security/SecureVault
│   └── ui/                  theme/, components/, vm/ (MVVM), screens/, NavGraph
└── res/                     themes, strings (EN + TA), locales_config
tools/hosttest/              executable host harness for the C++ engine
```

**MVVM + repositories**, Kotlin coroutines and `StateFlow` throughout, no
blocking work on the main thread.

**Deliberately minimal dependencies.** No Room, no KSP, no Hilt, no Moshi/Gson,
no androidx.security-crypto, no appcompat, no JGit. Each is replaced by a small
hand-written equivalent (`SQLiteOpenHelper`, `AppContainer`, `org.json`,
`SecureVault`, `LocaleHelper`, the GitHub REST API). This keeps the APK small,
the build fast, and the surface auditable on a phone.

---

## Building it yourself

You do not need a computer. Pushing to this repository triggers GitHub Actions,
which builds the APK and publishes it to the Release. That is the supported
path and it is what produced the APK above.

If you do have a machine with the Android SDK, NDK 27.2.12479018 and CMake
3.22.1:

```bash
./gradlew :app:assembleDebug                 # APK
./gradlew :app:testDebugUnitTest             # Kotlin unit tests
./tools/hosttest/run_host_tests.sh           # native engine tests
```

Full details, including how to build and sign from a phone using GitHub's
mobile web UI, and how to produce a release-signed APK:
**[docs/BUILD_APK.md](docs/BUILD_APK.md)**.

---

## Documentation

| Document | Contents |
|---|---|
| [docs/SETUP_PHONE.md](docs/SETUP_PHONE.md) | Install and first-run walkthrough, phone only |
| [docs/BUILD_APK.md](docs/BUILD_APK.md) | Building the APK, signing, CI, building from a phone |
| [docs/MODELS.md](docs/MODELS.md) | Model choice, sizes, licensing, where to get GGUF files |
| [docs/GITHUB_ACCESS.md](docs/GITHUB_ACCESS.md) | Creating a fine-grained PAT with minimum scopes |
| [docs/TESTING.md](docs/TESTING.md) | What was verified, what was not, the 15-criteria matrix |
| [docs/LIMITATIONS.md](docs/LIMITATIONS.md) | Known limitations and troubleshooting |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Design decisions and rationale |

---

## Known limitations

The important ones, not an exhaustive list — see
[docs/LIMITATIONS.md](docs/LIMITATIONS.md):

- **No local git.** There is no `.git` object store on the phone. History is
  read from GitHub and commits are constructed through the Git Data API. You
  cannot `git rebase`, resolve merge conflicts, or work offline against history.
- **No code execution.** No build, no test runner, no linter. Nothing can be
  verified by running it, so the app never claims a fix works.
- **Small model, small context.** A 1.5B model at 2048 tokens is not a desktop
  coding assistant. Large files are truncated and the truncation is disclosed.
- **Speed is unproven.** Expect roughly a few tokens/second on a phone CPU.
  Do not expect desktop performance; the app does not promise it.

---

## License

Varun Studio source: see [LICENSE](LICENSE).

Bundled/linked components: **llama.cpp** and **ggml** (MIT, built from source at
a pinned commit — no prebuilt binary is redistributed), **Jetpack Compose**,
**AndroidX**, **Kotlin** and **OkHttp** (Apache-2.0).

Models are licensed separately by their publishers and are **not** redistributed
by this project. See [docs/MODELS.md](docs/MODELS.md).
