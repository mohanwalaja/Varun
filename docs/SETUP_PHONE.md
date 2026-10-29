# Setting up Varun Studio on your phone

This guide assumes you have **only an Android phone** — no computer, no Android
Studio, no USB cable. Everything here happens on the device.

Requirements: Android 8.0 (API 26) or newer, 64-bit ARM (every phone sold since
roughly 2017), about **3 GB of free storage** for the app plus a model, and an
internet connection for the initial download only.

---

## 1. Install the APK

1. Open **[the latest release](https://github.com/mohanwalaja/Varun/releases/tag/apk-latest)**
   in your phone's browser.
2. Under **Assets**, tap the file named `Varun-Studio-<version>-arm64-v8a.apk`.
3. Android will warn: *"For your security, your phone isn't allowed to install
   unknown apps from this source."* Tap **Settings**, then switch on **Allow
   from this source** for your browser. This is a one-time permission.
4. Go back and tap the download, then **Install**.
5. Launch **Varun Studio**.

**If the release has no APK attached**, the build has not finished or has
failed. Open the
[Actions tab](https://github.com/mohanwalaja/Varun/actions) and look at the
newest run: a green tick means the APK is on the release page, a red cross
means the build failed and the log will say why.

### Verifying the download is intact

Each release also carries a `.sha256` file. If you want to check the APK was
not corrupted or tampered with, install any checksum app from the Play Store,
compute the SHA-256 of the downloaded APK, and compare it with the value in the
release notes and the `.sha256` file. They must match exactly.

### Updating later

New builds are signed with the **same key** (CI caches the debug keystore), so
you can install a newer APK straight over the top and keep your models,
workspaces and settings.

If Android ever says *"App not installed"* or *"signature conflict"*, it means
that APK was signed with a different key. You will have to uninstall first —
**which deletes your models and workspaces.** Re-download from the release page
rather than using an old copy.

---

## 2. First run

On launch the app asks for notification permission. This is optional and is
used only to keep a long generation alive while the screen is off. Denying it
does not break anything.

The Home screen shows:

- **Runtime status** — whether `libvarunllama.so` loaded. If it did not, the
  reason is shown here and in Settings → Diagnostics.
- **Device RAM and free storage**
- **Thermal and battery state**

Nothing is downloaded and no model is loaded at this point. The app does nothing
until you tell it to.

---

## 3. Get a model

You have two options. **Both require your explicit consent — the app never
downloads or loads a model on its own.**

### Option A: download from the in-app catalogue (easiest)

1. Tap **Models** in the bottom navigation.
2. Find **Qwen2.5 Coder 1.5B Instruct · Q4_K_M** — it is marked as recommended.
3. Read the consent dialog. It shows the size (~1.14 GB), the license
   (Apache-2.0) and the exact source URL.
4. Tap **Download**. Progress is shown and you can cancel at any time.

This uses your data connection. If you are on mobile data, consider waiting for
Wi-Fi — 1.14 GB is a large download.

**Starting smaller?** Pick **Qwen2.5 Coder 0.5B** (~400 MB) first. It is the
fastest way to confirm the runtime works on your specific phone before you
commit to a bigger download.

### Option B: import a GGUF you already have

1. Tap **Import** on the Models screen.
2. Android's file picker opens. Navigate to your `.gguf` file and tap it.
3. The app copies it into its own storage and reads the real header to compute
   an accurate memory estimate.

The file is accessed through the Storage Access Framework, so the app never
needs permission to browse your phone.

### Which model?

| Model | Size | Good for | Notes |
|---|---|---|---|
| Qwen2.5-Coder **0.5B** Q4_K_M | ~400 MB | First smoke test | Fast, weakest output |
| **Qwen2.5-Coder 1.5B** Q4_K_M | ~1.14 GB | **Daily use — recommended** | Best size/quality balance for 8 GB |
| Qwen2.5-Coder 3B Q4_K_M | ~1.9 GB | Harder reasoning | Slow; close every other app first |
| Gemma 3 1B Q4_K_M | ~800 MB | General instructions | Weaker at code; **not** Apache licensed |
| Llama 3.2 1B Q4_K_M | ~750 MB | General use | **Llama Community License applies** |
| TinyLlama 1.1B Q4_K_M | ~670 MB | Smoke test only | 2k context — too small for real files |

Full licensing detail: [MODELS.md](MODELS.md).

---

## 4. Load the model

1. On the Models screen, look at the estimate banner next to the model:
   - **OK** — comfortable, plenty of headroom
   - **TIGHT** — will probably work; close other apps first
   - **UNLIKELY** — very likely to fail or thrash. The app will not auto-load it.
2. Tap **Load**.
3. Wait for progress to finish. First load is the slowest because the file is
   read and the KV cache is allocated.

Loading happens on a background thread. The UI stays responsive and you can
cancel.

**If it fails with an out-of-memory error:**

1. Go to **Settings → Inference**.
2. Tap **Apply a conservative profile for this device**. This drops context to
   512–1024, threads to 1–2, and reduces how much file content is sent.
3. Close every other app (especially browsers and camera apps) and try again.
4. If it still fails, use the 0.5B model instead.

An 8 GB phone does not have 8 GB available to one app. Android keeps a large
reserve, and other running apps hold the rest. The app estimates against
*currently available* RAM, not total RAM.

---

## 5. Use the assistant

1. Tap **Assistant**.
2. Optionally attach files: tap the paperclip and choose files from your
   workspace. **Only attached files are sent to the model.**
3. Choose a task chip — *Explain*, *Find bugs*, *Security review*, *Suggest
   fix*, *Generate code*, *Refactor*, *Write tests*, *Review dependencies*,
   *Explain build error*, *Repository audit* — or just type a question.
4. Tap send. Tokens stream in as they are generated. **Stop** cancels at any
   time.

The model runs entirely on your phone. Airplane mode works.

### Reviewing proposed changes

When the model proposes an edit, a panel appears listing each file. For every
file you can:

- See a **diff** of original vs proposed
- **Approve** or **Reject** it individually
- **Apply approved** — which backs up the original first, then writes

Nothing is written until you approve it, and every applied change is backed up
and journaled so you can roll it back.

### Read the honesty markers

The model is instructed to mark claims it cannot verify with `[UNTESTED]`. The
app surfaces those markers in a banner. **There is no code execution in this
app**, so no test, build or linter ever runs. Treat every proposed fix as a
suggestion to review, not a verified repair.

---

## 6. Connect GitHub (optional)

Only needed if you want to browse or edit a repository.

1. On your phone, open
   **github.com → Settings → Developer settings → Personal access tokens →
   Fine-grained tokens → Generate new token**.
2. Set a repository scope (only the repos you need).
3. Under **Permissions → Repository permissions**, grant:
   - **Contents** — *Read and write* (only if you want to push; otherwise Read-only)
   - **Metadata** — *Read-only* (always required)
4. Generate and **copy the token immediately** — GitHub shows it once.
5. In Varun Studio, go to **Repos → Connect**, paste the token, and connect.

The token is encrypted with an Android Keystore key and never leaves the phone
except in requests to `api.github.com`. There is no embedded client secret or
service account anywhere in the app.

Full walkthrough: [GITHUB_ACCESS.md](GITHUB_ACCESS.md).

To disconnect: **Repos → Disconnect**. This deletes the stored token.

> Browsing repositories needs internet. AI inference does not.

---

## 7. Work on a repository

1. **Repos** → pick a repository.
2. Browse the **Files**, **Branches**, **Commits** and **Search** tabs.
3. To work locally, either:
   - select individual files in the tree and tap **Import selected**, or
   - tap **Import whole repository** (downloads an archive and extracts it)
4. Open the **Workspace** tab to browse, edit, search and review your local copy.

For a large repository, import **only the files you need**. Importing
everything is capped in Settings (default 128 MB) and a small model cannot read
a large codebase anyway.

### Saving and pushing

- Edit a file in the Workspace **Editor** tab and tap **Save**. The original is
  preserved and the change is journaled.
- Review everything under **Changes** with a diff against the original.
- Roll back any change from the **Journal** tab.
- To push: create a branch first (**Branches** tab), then confirm the push
  dialog, which shows the exact file list and commit message.

**The app will not:** force-push, delete a branch, or commit to the default
branch unless you explicitly override the safety setting. Push always requires
confirmation.

---

## 8. Language and appearance

**Settings → Appearance**:

- **Theme** — system, light or dark
- **Language** — system, English, or தமிழ் (Tamil)
- **Text size** — 0.85× to 1.4×

On Android 13+, the language change applies immediately. On Android 8–12,
restart the app for it to take full effect.

> The Tamil translations were machine-drafted and have **not** been reviewed by
> a fluent speaker. Expect rough phrasing and please report anything wrong.

---

## 9. Freeing space and memory

- **Unload the model** — Settings → Security → *Unload model*. Releases RAM
  immediately without deleting anything.
- **Delete a model** — Models screen → *Delete*. Frees storage.
- **Delete a workspace** — Workspace screen → *Delete*.
- **Clear old backups** — backups accumulate as you apply changes.
- **Erase everything** — Settings → Security → *Erase all local data*. Deletes
  models, workspaces, chats, the stored token and all settings.

The app also releases the model automatically when Android reports critical
memory pressure, unless you enable *Keep model loaded in background* in
Settings. This is why the app survives instead of being killed.

Check your usage any time under **Settings → Diagnostics**, which also shows a
copyable summary if you need to ask for help. That output is passed through a
redactor that strips tokens, bearer headers, private keys and JWTs.

---

## Troubleshooting

See **[LIMITATIONS.md](LIMITATIONS.md)** for the full list, including RAM and
storage problems, model-loading failures, thermal throttling and what the app
genuinely cannot do.
