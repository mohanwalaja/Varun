# Known limitations and troubleshooting

This document exists so you are not surprised. Everything here is a real
limitation, not a caveat added for completeness.

---

## Limitations

### 1. No code execution — nothing can be verified by running it

The app cannot run a build, a test suite, a linter, a formatter or a dependency
scanner. There is no sandbox, no compiler toolchain and no interpreter on board.

**Consequence:** no AI-proposed fix is ever verified. The system prompt requires
the model to mark unverifiable claims `[UNTESTED]`, forbids it from claiming a
bug is fixed, and the UI surfaces those markers in a banner. The audit report
states explicitly that no build, test or scan was executed.

This is the single biggest limitation, and it is inherent: running arbitrary
code from a model on a phone would be a security disaster anyway.

### 2. No local git

There is no `.git` object store. History is **read** from GitHub and commits are
**constructed** through the Git Data API (blob → tree → commit → update ref).

You cannot:

- `git rebase`, `git merge`, `git cherry-pick` or resolve merge conflicts
- See history for a workspace that was imported from a zip rather than GitHub
- Commit while offline
- Use git hooks, submodules, LFS or worktrees
- Sign commits

Your local workspace is a plain directory of files with a journal of what the
app changed. That journal — not git — is the record of local edits.

### 3. Small model, small context

A 1.5B model at 4-bit quantization with a 2048-token context is not a desktop
coding assistant. See [MODELS.md](MODELS.md) for what to realistically expect.

Large files are **truncated** to fit, and the truncation is disclosed to you and
to the model. Files that do not fit at all are **dropped**, and the prompt lists
them by name in an `OMITTED` section rather than pretending they were read.

Whole-codebase reasoning is not possible. You must narrow to specific files.

### 4. Speed is unproven and will not match a desktop

Inference speed on a phone CPU has **not been measured** — no physical device
was available during development. Expect a few tokens per second, not tens.
First model load is slow. Long generations drain battery and heat the device.

Do not expect desktop-level performance. The app does not promise it.

### 5. Thermal and battery throttling

Sustained inference heats a phone. Android throttles the CPU under thermal
pressure, so generation slows down over a long session. In battery-saver mode
the app can pause generation. Settings exposes both behaviours as switches.

Long generations may also be paused by Doze when the screen is off. A foreground
service mitigates this, but Android's power management on some OEM ROMs is
aggressive and not fully controllable.

### 6. Import size caps

Repository import is capped (default 128 MB, configurable) and archive
extraction has hard budgets: 512 MB total, 20,000 entries, 64 MB per file.
Oversized files in the editor are capped at 2 MB.

These exist because a phone's storage and RAM are finite and a runaway import
would make the app unusable.

### 7. Repository content is treated as hostile

Files, READMEs, code comments and model output are all **untrusted data**. The
system prompt explicitly refuses to follow instructions embedded in context.

This is a mitigation, not a guarantee. A small model can still be steered by
cleverly crafted repository content. **Always review a diff before approving
it.** The app never writes without per-file approval, which is the real defence.

### 8. Tamil translations are unreviewed

The 215 Tamil strings were machine-drafted and have **not** been reviewed by a
fluent speaker. Expect awkward or wrong phrasing. English is complete and is the
fallback.

### 9. Nothing has been run on a device or emulator

No emulator, no `adb`, no physical phone was available. No screenshot has been
taken, no layout has been visually checked, no touch interaction has been
tested. The UI is written for portrait, touch and soft-keyboard use, but that is
intent, not verification.

See [TESTING.md](TESTING.md) for the full matrix.

### 10. Single-ABI by default

Only `arm64-v8a` is built. A 32-bit-only device (increasingly rare) or an x86
emulator will not find a native library. Add ABIs with `-PVARUN_ABIS=...` —
see [BUILD_APK.md](BUILD_APK.md).

---

## Troubleshooting

### Installation

| Problem | Fix |
|---|---|
| *"App not installed"* | Signature conflict with an existing install. Uninstall the old one first — **this deletes models and workspaces.** |
| *"Parse error"* | The download was truncated. Re-download and compare the SHA-256 against the release notes. |
| *"Unknown sources"* blocked | Allow it for your browser: Settings → Apps → your browser → Install unknown apps. |
| App opens then closes immediately | Check Settings → Diagnostics on the next launch. Most often the native library did not load — see below. |
| `INSTALL_FAILED_OLDER_SDK` | Your Android is older than 8.0. minSdk is 26. |

### The native runtime did not load

Home and Settings → Diagnostics report whether `libvarunllama.so` loaded and, if
not, why.

| Cause | Fix |
|---|---|
| Device is not 64-bit ARM | Diagnostics shows the supported ABIs. If `arm64-v8a` is absent, this build cannot run. |
| APK was built for a different ABI | Rebuild with `-PVARUN_ABIS=` including your ABI. |
| `SIGILL` on load | Should not happen — `GGML_NATIVE` is forced OFF. If it does, report the device model. |

### Model loading and memory

| Problem | Fix |
|---|---|
| **Out of memory on load** | Settings → *Apply a conservative profile*. Close every other app. Try the 0.5B model. |
| Estimate says **UNLIKELY** | The app will not auto-load it. Free RAM, lower context, or choose a smaller model. |
| Load hangs | Large models take time on first load. Progress is reported; cancel and retry with a lower context. |
| App killed during generation | Android reclaimed memory. Lower context length and thread count, and disable *Keep model loaded in background*. |
| *"Context full"* | Your prompt plus history exceeded the context window. Reduce history turns in Settings, or shorten the input. |
| Download stalls | Cancel and retry. Partial downloads use a `.part` file and are renamed only on completion. |
| Not enough storage | The app requires ~250 MB free beyond the model size. Delete a workspace or an old model. |

**Remember:** an 8 GB phone does not give one app 8 GB. Android keeps a large
reserve and other apps hold the rest. Estimates are against *currently
available* RAM minus a 700 MB reserve, not total RAM.

### Generation quality

| Problem | Fix |
|---|---|
| Output is gibberish | Temperature too high. Set it to 0.1–0.2 for code. |
| Output repeats itself | Raise the repeat penalty slightly (1.1–1.3), or lower temperature. |
| Answer cut off mid-sentence | Raise *Max tokens* in Settings. It costs time and RAM. |
| Ignores the attached file | The file may have been dropped for budget. Check the `OMITTED` list in the response context. |
| Does not stop generating | Stop strings differ per model. The defaults cover ChatML, Llama and Gemma templates; a custom model may need another. |
| Very slow | Lower context and thread count. Check thermal state in Settings → Diagnostics. |

### GitHub

See the troubleshooting table in [GITHUB_ACCESS.md](GITHUB_ACCESS.md).

The most common issues: an expired token (401), a token that does not include
the repository (404 on private repos, 403 otherwise), and rate limiting after
browsing a large tree file-by-file.

### Workspace and edits

| Problem | Fix |
|---|---|
| Editor says file is read-only | It exceeds the 2 MB editor cap, or it is not valid UTF-8 text. |
| Search returns nothing | Binary files and `build/`, `node_modules/`, `.git/` and hidden directories are skipped. Enable *include hidden* for dot-directories. |
| Save refused | The path failed validation. Relative paths only; nothing may escape the workspace. |
| Want the original back | Workspace → **Journal** tab → roll back the entry. Backups are taken before every approved write. |
| Backups using storage | Clear old backups from the Journal tab. |

---

## Reporting a problem

Copy the diagnostics block from **Settings → Diagnostics → Show diagnostics →
Copy**. It contains your device model, Android version, ABIs, RAM figures,
storage usage, thermal state, the loaded model's real geometry and your
inference settings.

It is passed through a redactor that strips tokens, bearer headers, private keys
and JWTs, so it is safe to paste into an issue. **Check it yourself before
posting anyway.**

Open an issue at `https://github.com/mohanwalaja/Varun/issues` with that block
plus what you did and what happened.
