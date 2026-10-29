# Connecting GitHub

Varun Studio uses a **fine-grained Personal Access Token (PAT)** that you create
yourself. There is no OAuth app, no client id, no client secret and no service
account anywhere in this project — nothing privileged ships inside the APK.

You can do all of this from your phone.

> **Repository access needs an internet connection.** AI inference does not —
> it runs entirely on device and works in airplane mode.

---

## Why a fine-grained PAT

| Approach | Why it was or was not used |
|---|---|
| **Fine-grained PAT** | **Used.** Zero setup, you choose the exact repositories and the exact permissions, it can expire, and it can be revoked from the phone in seconds. |
| OAuth Device Flow | Not used as the primary path. It requires registering a GitHub OAuth app, which means a client id baked into the APK — a worse security posture for a single-user offline-first tool, and more setup for you. |
| Classic PAT | Not recommended. Scope is account-wide, not per-repository. |
| GitHub App / installation token | Not used. Requires a private key, which must never be embedded in a distributed APK. |

---

## Creating the token on your phone

1. Open **github.com** in your browser and sign in.
2. Tap your avatar → **Settings**.
3. Scroll to **Developer settings** (at the very bottom).
4. **Personal access tokens** → **Fine-grained tokens**.
5. Tap **Generate new token**.

Direct link: `https://github.com/settings/personal-access-tokens/new`

### Fill in the form

| Field | What to enter |
|---|---|
| **Token name** | `Varun Studio (phone)` — something you will recognise later |
| **Expiration** | Your choice. Shorter is safer; you can regenerate when it lapses. |
| **Resource owner** | Your own account, unless you need an organisation's repos |
| **Repository access** | **Only select repositories** — pick just the ones you need. Avoid *All repositories* unless you genuinely want that. |

### Permissions

Under **Permissions → Repository permissions**, set only these:

| Permission | Level | Needed for |
|---|---|---|
| **Metadata** | Read-only | Always required. Listing and reading repository details. |
| **Contents** | **Read-only** | Browsing files, reading code, history, search, importing a workspace |
| **Contents** | **Read and write** | *Only* if you want to create branches and push commits |

**Nothing else.** Leave every other permission on *No access*.

The app never needs, and will warn you about:

- **Administration** — repository settings, collaborators
- **Delete** access of any kind
- **Account** or profile scopes
- **Pull requests** — optional; only needed if you want PR workflows

### Generate and copy

Tap **Generate token**. GitHub shows the token **once**, as a string beginning
`github_pat_`. Copy it immediately — if you navigate away you must regenerate.

---

## Connecting in the app

1. Open Varun Studio → **Repos** tab.
2. Tap **Connect**.
3. Paste the token into the field. It is masked as you type.
4. The screen shows which permissions to select, so you can check before
   committing.
5. Tap **Connect**.

The app calls `api.github.com/user`, verifies the token works, probes the scopes
it actually received, and stores the token **encrypted**.

If the token carries broader permissions than needed, the app tells you. It does
not silently accept an over-privileged token without saying so.

---

## How the token is stored

`core/security/SecureVault.kt`:

- A symmetric **AES-256-GCM** key is generated inside the **Android Keystore**.
  On most devices this is hardware-backed and the key material never becomes
  readable by the app, let alone by anything else on the phone.
- The token is encrypted with that key before it is written to disk.
- If the Keystore is unavailable on your device, the app **says so** and refuses
  to store a token rather than falling back to plaintext. Settings → Security
  shows the current state.
- `android:allowBackup="false"` in the manifest, plus backup and
  data-extraction rules, so the token is never copied into a cloud backup or a
  device transfer.

The token is only ever sent to `api.github.com` as a bearer header. It is never
logged: `core/AppLog.kt` runs every message through a redactor that strips
bearer headers, `github_pat_`/`ghp_` tokens, private key blocks and JWTs — even
when verbose logging is switched on.

---

## Disconnecting

**Repos → Disconnect.** This deletes the stored token, login and scopes from the
device immediately.

To revoke it properly, also delete the token on GitHub:
`https://github.com/settings/personal-access-tokens`. Revoking there is what
actually invalidates it; deleting locally only removes your copy.

**Settings → Security → Erase all local data** wipes everything: models,
workspaces, chats, the token and all settings.

---

## What the app does with access

### Read operations

- List your repositories (public and private, per the token's scope)
- Search repositories
- Browse the file/folder tree at any branch or commit
- Read file contents and metadata
- List branches (including which are protected) and commit history
- Search code across a repository
- Download a repository archive, or only the specific files you select

### Write operations — always gated

Writes go through the **GitHub Git Data API** (blob → tree → commit → update
ref). There is no local `.git` directory and no JGit; this keeps the app small
and avoids needing a full git implementation on a phone.

Every write is constrained:

- **`force=false` is hard-coded.** The app cannot force-push.
- **Branch and ref deletion is not implemented at all.** There is no code path
  that does it.
- **Commits to the default branch are refused** unless you explicitly turn that
  guard off in Settings, and even then each commit needs confirmation.
- **A dedicated branch is created first** for significant changes.
- **Push always requires explicit confirmation**, and the dialog shows the exact
  list of files and the commit message before anything is sent.
- `commitAndPush(..., requireExplicitConfirmation = true, allowDefaultBranch =
  false)` — the safety is a parameter with a safe default, not a comment.
- Stale-write detection: `updateSingleFile` passes the blob's `expectedSha`, so
  a 409 conflict **blocks** the operation instead of overwriting someone else's
  change.

Input validation is unit-tested exhaustively: branch names are restricted to
`^[A-Za-z0-9][A-Za-z0-9._\-/]{0,199}$` with `..`, `refs/` prefixes, trailing
slashes, `.lock` suffixes and `@{` sequences all rejected; paths reject absolute
forms, traversal, NUL bytes, empty segments, over-long strings and anything
inside `.git`, `.hg` or `.svn`. Files are capped at 8 MB.

---

## Rate limits

Unauthenticated GitHub API use is heavily rate-limited, and authenticated use is
limited to 5,000 requests/hour. The app reads the rate-limit headers from every
response and shows the remaining budget, so you can see when you are running
low rather than getting mystery failures.

Browsing a large repository tree file-by-file consumes requests quickly. Prefer
**Import selected files** or **Import whole repository** (one archive download)
over tapping through many folders.

---

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| **401 Bad credentials** | Token expired, was revoked, or was copied incompletely. Regenerate and reconnect. |
| **403 Resource not accessible** | The token lacks permission for that repository, or the repository was not included in "Only select repositories". |
| **404 on a private repo** | Fine-grained tokens return 404 rather than 403 for repos they cannot see. Check the repository selection. |
| **Empty repo list** | The token has no repository access, or you selected none. Metadata (read) is required. |
| **Push blocked: default branch** | Working as intended. Create a branch first, or change the Settings guard. |
| **Push blocked: stale file** | The file changed on GitHub since you read it. Re-read it and re-apply your change. |
| **Organisation repos missing** | The organisation may require fine-grained PAT approval. An owner must approve it under organisation settings. |
| **"Keystore unavailable"** | Your device cannot provide hardware-backed encryption. The app will not store a token. Do not connect GitHub on this device. |
