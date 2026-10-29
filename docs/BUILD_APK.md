# Building the APK

## The short answer: you do not need a computer

This project is built entirely by **GitHub Actions**. Pushing to the repository
triggers `.github/workflows/build-apk.yml`, which compiles the native runtime
with the Android NDK, assembles a signed debug APK, runs the tests, and
publishes the APK to the
**[`apk-latest` release](https://github.com/mohanwalaja/Varun/releases/tag/apk-latest)**.

That is the supported path, and it is how the published APK was produced.

---

## Building from a phone, with no computer at all

You can trigger a build entirely from GitHub's **mobile web UI**:

1. Open `github.com/mohanwalaja/Varun` in your phone's browser.
2. Make any change — edit a file, or add a trivial line to `README.md` — and
   **Commit changes** to the branch. A push is what triggers the workflow.
3. Open the **Actions** tab and tap the newest run.
4. Wait. A cold build takes roughly **25–45 minutes**; a cached one is much
   faster.
5. When it turns green, open the
   [`apk-latest` release](https://github.com/mohanwalaja/Varun/releases/tag/apk-latest)
   and download the APK from **Assets**.

Alternatively, tap **Actions → Build APK → Run workflow** to start a build
without changing any file. That button offers two options: skip the native host
tests to save minutes, and choose which ABIs to build.

If you cannot see the **Actions** tab, repository Actions may be disabled. On
the phone: **Settings → Actions → General → Allow all actions and reusable
workflows**.

### Why builds can be slow

llama.cpp is compiled **from source** for `arm64-v8a` on every cold build —
several hundred C++ translation units. The workflow caches the native build tree
(`app/.cxx`) keyed on the pinned llama.cpp commit and the contents of
`app/src/main/cpp/`, so a rebuild that does not touch the C++ layer is fast.

Changing `app/src/main/cpp/CMakeLists.txt`, `llama_session.cpp` or
`llama_bridge.cpp` invalidates that cache and costs a full rebuild.

---

## What the workflow does

| Job | Purpose | Runs |
|---|---|---|
| `host-tests` | Compiles the **shipping** `llama_session.cpp` with `-Wall -Wextra -Werror` against a host llama.cpp build and **executes** the 50-assertion harness | In parallel |
| `android` | Installs the SDK/NDK, assembles the APK, verifies `libvarunllama.so` is inside it, runs the JVM unit tests, publishes a per-suite table | In parallel |
| `release` | Publishes the APK and its SHA-256 to the rolling `apk-latest` release | After `android` |

Both build jobs run concurrently so a failure in one does not hide the other.

### Toolchain versions

These are pinned and must stay in sync across `app/build.gradle.kts`,
`app/src/main/cpp/CMakeLists.txt` and the workflow `env:` block.

| Component | Version |
|---|---|
| Gradle | 8.9 (wrapper, checked in) |
| Android Gradle Plugin | 8.7.3 |
| Kotlin | 2.0.21 |
| JDK | 17 (Temurin) |
| compileSdk / targetSdk | 35 |
| minSdk | 26 (Android 8.0) |
| NDK | 27.2.12479018 |
| CMake | 3.22.1 |
| Compose BOM | 2024.10.01 |
| llama.cpp | `b11979` = commit `bf48103ad164d919fbad6a28359c3f728e4ad942` (MIT) |

---

## Building on a machine with the Android SDK

If you do have a computer with the SDK, NDK and CMake installed:

```bash
# Installable APK -> app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleDebug

# Kotlin unit tests (9 suites)
./gradlew :app:testDebugUnitTest

# Native engine tests - clones and builds llama.cpp, ~20 min cold
./tools/hosttest/run_host_tests.sh
```

Useful properties:

```bash
# Build for more than one ABI (much slower, much bigger APK)
./gradlew :app:assembleDebug -PVARUN_ABIS=arm64-v8a,armeabi-v7a

# Override the toolchain
./gradlew :app:assembleDebug \
  -PVARUN_NDK_VERSION=27.2.12479018 \
  -PVARUN_CMAKE_VERSION=3.22.1

# Offline / air-gapped: use a llama.cpp tree you already have
./gradlew :app:assembleDebug -PVARUN_LLAMA_CPP_SRC=/path/to/llama.cpp
```

`VARUN_LLAMA_CPP_SRC` is the escape hatch for building with no network access
to GitHub. The same variable is honoured by
`tools/hosttest/run_host_tests.sh` via the environment.

**Only `arm64-v8a` is built by default.** Adding `armeabi-v7a` doubles native
build time and APK size, and 32-bit ARM is increasingly marginal. Add `x86_64`
only if you want to run in an emulator.

---

## Signing

### What is published

The release APK is **debug-signed**. That is deliberate: it is fully functional,
installable, and it is the right artifact for sideloading. CI caches
`~/.android/debug.keystore` between runs so consecutive builds share a signing
key and install over the top of each other.

### Producing a release-signed APK

Release signing is read from `keystore.properties` at the repository root. That
file is **git-ignored and must never be committed.**

1. Create a keystore. On a computer:

   ```bash
   keytool -genkeypair -v \
     -keystore varun-release.jks \
     -alias varun \
     -keyalg RSA -keysize 2048 -validity 10000
   ```

2. Create `keystore.properties` at the repository root:

   ```properties
   storeFile=/absolute/path/to/varun-release.jks
   storePassword=your-store-password
   keyAlias=varun
   keyPassword=your-key-password
   ```

3. Build:

   ```bash
   ./gradlew :app:assembleRelease
   ```

   With that file present the release variant is signed automatically. Without
   it, the release variant still assembles but is left **unsigned** and cannot
   be installed. The debug variant is always installable either way.

4. To do this in CI, add the keystore as a base64 repository secret, decode it
   in a workflow step, and write `keystore.properties` from secrets. **Keep the
   keystore backed up** — if you lose it, users must uninstall (losing their
   models and workspaces) before they can install a new build.

### Never commit these

`keystore.properties`, `*.jks`, `*.keystore`, `local.properties`, and any GitHub
token. All are in `.gitignore`. There is no embedded secret anywhere in the
source tree — `GitHubClient` accepts a `tokenProvider: () -> String?` lambda
precisely so that a baked-in credential is not even expressible.

---

## Verifying a built APK

The `android` job does this automatically and writes the result to the job
summary:

```bash
APK=app/build/outputs/apk/debug/app-debug.apk

# The native runtime must be inside the APK
unzip -l "$APK" | grep 'lib/.*\.so'
#   expected: lib/arm64-v8a/libvarunllama.so

sha256sum "$APK"

# Manifest facts
"$ANDROID_HOME/build-tools/35.0.0/aapt2" dump badging "$APK" | head -20
```

If `libvarunllama.so` is missing, the native build silently did not run and the
app will report the runtime as unavailable. The workflow treats that as a hard
failure.

---

## The native build, in detail

`app/src/main/cpp/CMakeLists.txt` fetches llama.cpp at the pinned commit via
`FetchContent` and builds **one** shared library per ABI:

```
libvarunllama.so   =  llama_session.cpp
                   +  llama_bridge.cpp
                   +  llama (static)
                   +  ggml  (static)
```

Static-linking llama.cpp and ggml means the APK ships a single `.so` per ABI
instead of four, which keeps the download small.

Upstream options are trimmed to exactly what an inference-only Android app
needs: no tests, no examples, no server, no tools, no curl, no OpenSSL. ggml
backends are CPU-only — no Vulkan, OpenCL, Metal, CUDA or SYCL. That avoids a
GPU driver compatibility matrix across thousands of phone models.

`GGML_NATIVE` is forced **OFF**. This is important: the APK runs on unknown ARM
cores, so compiling for the build host's instruction set would produce a
library that crashes with `SIGILL` on other phones.

Hardening and size flags: `-fvisibility=hidden` with explicit `JNIEXPORT` on the
13 JNI entry points, `-ffunction-sections -fdata-sections` with
`-Wl,--gc-sections`, `-Wl,--exclude-libs,ALL` so llama.cpp symbols are not
re-exported, and `-fno-exceptions -fno-rtti`.

### Why llama.cpp and not an alternative

| Runtime | Verdict |
|---|---|
| **llama.cpp + JNI** | **Chosen.** MIT, GGUF is the de-facto open model format, first-class official Android arm64 support (upstream publishes Android prebuilts), builds as a static library with CPU-only backends, and the C API is stable enough to pin. |
| MLC LLM | Larger toolchain, TVM codegen step, fewer small coder models readily available as GGUF, harder to pin reproducibly. |
| MediaPipe LLM Inference | Google-controlled model zoo, limited format flexibility, less control over sampling and context. |
| ONNX Runtime Mobile | No comparable ecosystem of small quantized coder models. |

The deciding factors were license, GGUF model availability, build reproducibility
from a pinned commit, and memory control on an 8 GB phone.

---

## Repository layout

```
.
├── .github/workflows/build-apk.yml   the only build pipeline
├── app/
│   ├── build.gradle.kts              SDKs, ABIs, CMake wiring, signing, deps
│   ├── proguard-rules.pro            JNI keep rules (minify is currently off)
│   └── src/
│       ├── main/cpp/                 native runtime
│       ├── main/kotlin/              the app
│       ├── main/res/                 themes, strings (EN + TA), locales_config
│       └── test/kotlin/              9 JVM unit-test suites
├── gradle/                           version catalog + wrapper
├── tools/hosttest/                   executable native engine harness
├── docs/                             this documentation
└── settings.gradle.kts
```

The Gradle wrapper (`gradlew`, `gradle/wrapper/`) is committed, so a build never
depends on a locally installed Gradle version.
