# QuickGit — a GitHub Desktop-style git client for Android

A native Android app (Kotlin + Jetpack Compose) for cloning, browsing, staging,
committing, pushing/pulling, branching, and resolving merge conflicts on git
repositories — the mobile equivalent of GitHub Desktop. Git operations run
locally on-device via [JGit](https://www.eclipse.org/jgit/); nothing is sent
to any third-party server other than the git remote you configure.

Also ships as a **native desktop app** (Compose Multiplatform + JGit) for Linux
(and package targets for Windows/macOS).

## What's implemented

### Repositories & local git
- **Repo list** — locally cloned repos, current branch, dirty-state indicator; import a folder; create a new local repository
- **Clone** — HTTPS (PAT) or SSH (imported private key), live progress; clone from connected GitHub / GitLab / Gerrit accounts or paste a URL
- **Changes / staging** — staged, unstaged, and untracked files; stage/unstage per file or all; discard unstaged changes; commit message + commit
- **Push / Pull / Fetch** — with auth-required detection that routes you to Credentials
- **Diff viewer** — colorized unified diff for working-tree, staged, or a historical commit
- **History** — scrollable commit log (message, author, short SHA, date); open a commit’s file tree or a file at that revision
- **Branches** — list local + remote branches; create, checkout, delete
- **Merge conflicts** — conflicting files after a failed pull; per-file keep ours / keep theirs / hand-edit; commit the merge or abort
- **Files browser** — walk the working tree; open files in the built-in editor
- **Code editor** — monospace editing with line numbers and syntax highlighting; **Find** (keyword search, match count, previous/next); **Undo / Redo** (coalesced keystroke history)
- **Git LFS** — detect LFS pointer files, batch-download missing objects, and smudge into the working tree (HTTPS remotes)

### Accounts & credentials
- **Multi-account** — multiple **GitHub**, **GitLab**, and **Gerrit** accounts at once; switch the active account per provider; remove accounts from the managed list
- **Credentials** — encrypted-at-rest (Android Keystore via `EncryptedSharedPreferences`) storage for per-host HTTPS tokens, SSH private key + passphrase, and optional OpenPGP secret key for commit signing; import account / import CSV
- **GPG signing** — sign commits with an imported armored secret key (Bouncy Castle / JGit); toggle in Settings
- **Commit identity** — name and email written on every commit (auto-filled from GitHub when connected)

### Forge features (when a remote is connected)
- **GitHub** — browse/search your repos and orgs; clone with one tap; **Issues** (list, create, comment, close/reopen); **Pull requests**; **Releases**; **Actions / Workflows**; remote file browse and file view
- **GitLab** — connect gitlab.com or self-hosted; MRs, issues, and remote browsing where supported
- **Gerrit** — connect a Gerrit host with username + HTTP password for clone and related flows
- **Profile** — own and other users’ profiles (GitHub / GitLab); switch provider when multiple are connected
- **User search** — find people on the connected forge

### App chrome & platform
- **Settings** — repos storage location, commit identity, layout mode, GPG toggle, app update check
- **Logs** (debug / logging flavor) — in-app log viewer and export
- **Large screens / Chromebooks / Googlebook OS** — resizeable multi-window and freeform; Material 3 window size classes; adaptive content width and padding; optional permanent left **NavigationRail** matching the Linux desktop app (Repos, Clone, Browse, Profile, Users, Logs, Creds, Settings). Toggle under **Settings → Layout**: Auto (by window width), Always, or Never. Touchscreen is **not** required (keyboard + trackpad / mouse)
- **Googlebook OS** — intended to run on Google’s Android-based laptop OS (Arm64 and **Intel x86_64** Googlebooks). The app is pure Kotlin/JVM + JGit with no ARM-only native libraries, so it is suitable for both Qualcomm and Intel Googlebook hardware. Use desktop layout (Auto or Always) for a laptop-style chrome

## Architecture

```
app/src/main/java/com/quickgit/app/
  data/
    RepoManager.kt           JGit: clone/status/stage/commit/push/pull/branches/diff/merge/files
    AccountManager.kt        Multi-account registry (GitHub / GitLab / Gerrit)
    CredentialStore.kt       Encrypted storage for tokens, SSH, GPG
    GitHubAccountManager.kt / GitLabAccountManager.kt / GerritAccountManager.kt
    IssueManager.kt / PullRequestManager.kt / ReleaseManager.kt / WorkflowManager.kt
    LfsSupport.kt            Git LFS pointer detect + batch fetch/smudge
    SshSupport.kt / GpgSupport.kt
    models/Models.kt
  viewmodel/                 StateFlow ViewModels per screen (IO on Dispatchers.IO)
  ui/screens/                Jetpack Compose (Material 3), including desktop-width variants
  navigation/                Compose Navigation + typed destinations
  MainActivity.kt / QuickGitApp.kt
```

Git operations are synchronous JGit calls wrapped in `withContext(Dispatchers.IO)`.
Long-running clone/push/pull can report progress via notifications where enabled.

## Building it

You'll need **Android Studio (Koala or newer)** with the Android SDK (compileSdk 34) and a
JDK 17.

1. Open this folder (`QuickGit/`) directly in Android Studio — it will detect the Gradle
   project and offer to generate the Gradle wrapper automatically. (The wrapper jar isn't
   checked in here since it's a binary; Android Studio's "Sync now" prompt handles it, or
   run `gradle wrapper` once if you have a system Gradle install.)
2. Let Gradle sync — it will pull JGit, the Apache MINA SSH transport, and AndroidX/Compose
   dependencies from Maven Central.
3. Run on a device or emulator running **API 26+** (phones, tablets, Chromebooks, Googlebook OS).

No Anthropic/Claude systems are involved at runtime — this is a plain offline Android app;
it just talks to whatever git remote you point it at.

## Known limitations / good next steps

- **SSH host key verification is disabled** (`SshSupport.kt` accepts any host key). Fine for
  personal use, not hardened — wire up a persisted known_hosts store before shipping this.
- **Single SSH identity** — only one key pair is stored app-wide, not per-host.
- **Diff viewer is read-only** — no inline “stage this hunk” (partial staging), only whole-file
  staging.
- **Conflict resolution UI** is a simple ours/theirs/manual-edit dialog, not a 3-way visual
  merge view.
- **Credentials lock** — phones/tablets: biometrics or device PIN when available; Chromebooks (and any device without system lock): in-app Credentials password (set on first open). Desktop: same in-app password gate. Only the Credentials screen is gated.
- **LFS** covers pointer detection and download/smudge for common HTTPS remotes; not a full
  `git-lfs` feature set (e.g. lock/unlock, custom transfer adapters).
- Googlebook OS Intel models use a translation layer for many Android apps; QuickGit’s
  JVM/JGit stack should run, but always verify on target hardware for large repos and LFS.

## Permissions

`INTERNET`, `ACCESS_NETWORK_STATE`, `POST_NOTIFICATIONS` (progress), and (on older API levels)
external storage so clones can live under **Documents/QuickGit** on shared storage. On newer
Android versions the app still tries `Documents/QuickGit` first and falls back to app-specific
external storage if the public folder is not writable. Uninstalling the app does **not** delete
`Documents/QuickGit` when the public location is used.

## Tested on
 - Samsung Galaxy S25 Ultra (Snapdragon)
 - Samsung Galaxy M55 (Snapdragon)
 - Samsung Galaxy A52s (Snapdragon)
 - Samsung Galaxy A34 (Mediatek)
 - Samsung Galaxy A25 (Exynos)
 - Samsung Galaxy A20e (Exynos)
 - Samsung Galaxy A13 (Mediatek)
 - Samsung Galaxy A12 Nacho (Exynos)
 - Google Pixel 10 (Tensor G5)
 - Google Pixel 10a (Tensor G4)
 - Google Pixel 9a (Tensor G4)
 - Amazon Fire 10 13th gen (Mediatek)
 - Samsung Chromebook 4 (Intel)
 - Acer Aspire 3 Running Waydroid on Ubuntu (Intel)
 - Acer Aspire Desktop deb app (Intel)
 - Honor Pad 10 (Snapdragon)
 - Xiaomi Mi A2 Lite (Snapdragon)

**Target platforms (supported by design, not all listed above as lab-tested):**
phones, tablets, Chromebooks, **Googlebook OS** (Arm64 and Intel x86_64 Googlebooks), and the native Linux desktop build.

---

## Linux / Desktop (native)

QuickGit also ships as a **native desktop application** for Linux (and Windows/macOS packaging)
built with Compose Multiplatform + JGit.

This is a full standalone app (not an Android module or Waydroid container).

### Features on desktop

- Repo list (local clones under `~/QuickGit` by default)
- Clone (HTTPS with PAT)
- Changes / staging / unstage / discard
- Commit with configurable author
- Push / Pull / Fetch
- History
- Branches (list, create, checkout)
- Encrypted credential store (`~/.config/quickgit/`)
- Settings (author, repos root, GPG sign toggle)
- Left NavigationRail (Repos, Clone, Browse, Profile, Users, Logs, Creds, Settings)

### Build & run the Linux app

Requirements: JDK 17+, the project Gradle wrapper (or system Gradle 8+).

```bash
# From the project root
./gradlew :desktop:run
```

### Package a distributable Linux app

```bash
./gradlew :desktop:packageDeb          # .deb
./gradlew :desktop:packageRpm          # .rpm
./gradlew :desktop:packageAppImage     # AppImage
./gradlew :desktop:packageDistributionForCurrentOS
```

Packages appear under `desktop/build/compose/binaries/main/`.

### Desktop data locations

| Item              | Path                          |
|-------------------|-------------------------------|
| Repos root        | `~/QuickGit` (configurable)   |
| Credentials       | `~/.config/quickgit/credentials.enc` |
| Preferences       | `~/.config/quickgit/prefs.properties` |

The Android app and the desktop app are independent; they do not share storage.

### Desktop auto-update

The native Linux builds check GitHub Releases on startup (and via **Settings → Check for updates**).
They prefer a `.deb` on Debian/Ubuntu and an `.AppImage` otherwise, download to
`~/Downloads/QuickGit-updates/`, and open the file for install.

### Desktop credentials

**Credentials** includes a **Username** field for GitHub and per-host HTTPS logins
(required for correct `UsernamePasswordCredentialsProvider` auth with JGit).
