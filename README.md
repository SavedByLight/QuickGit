# QuickGit — a GitHub Desktop-style git client for Android

A native Android app (Kotlin + Jetpack Compose) for cloning, browsing, staging,
committing, pushing/pulling, branching, and resolving merge conflicts on git
repositories — the mobile equivalent of GitHub Desktop. Git operations run
locally on-device via [JGit](https://www.eclipse.org/jgit/); nothing is sent
to any third-party server other than the git remote you configure.

## What's implemented

- **Issues** — list, create, comment, close/reopen GitHub issues for github.com remotes

- **Repo list** — shows locally cloned repos, current branch, and a dirty-state dot
- **GitHub account** — connect with a personal access token; browse and search your repositories and clone with one tap
- **Clone** — clone over HTTPS (with PAT) or SSH (with an imported private key), with live progress; or pick from your connected GitHub account
- **Changes / staging** — see staged, unstaged, and untracked files; tap to stage/unstage
  individually or stage all; discard unstaged changes; write a commit message and commit
- **Push / Pull** — with auth-required detection that routes you to the credentials screen
- **Diff viewer** — colorized unified diff for a file, for working-tree changes, staged
  changes, or a specific historical commit
- **History** — scrollable commit log (message, author, short SHA, date)
- **Branches** — list local + remote branches, create, checkout, delete
- **Merge conflicts** — lists conflicting files after a failed pull; per-file "keep ours /
  keep theirs / hand-edit" resolution, then commit the merge or abort it
- **Credentials** — encrypted-at-rest (Android Keystore via `EncryptedSharedPreferences`)
  storage for per-host HTTPS personal access tokens, a single SSH private key + passphrase,
  and an optional OpenPGP secret key for commit signing
- **GPG signing** — sign commits with an imported armored secret key (Bouncy Castle / JGit); toggle in Settings
- **Large screens / Chromebooks** — resizeable multi-window and freeform support; Material3 window size classes; adaptive content width and padding; on medium/expanded widths a permanent NavigationRail matching the Linux desktop app layout (Repos / Clone / Browse / Profile / Users / Logs / Settings) so the Chromebook experience is identical in structure to the native Linux version (touchscreen not required)

## Architecture

```
app/src/main/java/com/quickgit/app/
  data/
    RepoManager.kt       All JGit calls: clone/status/stage/commit/push/pull/branches/diff/merge
    CredentialStore.kt   Encrypted storage for PAT + SSH key
    SshSupport.kt        JGit SSH transport wired to the stored key
    models/Models.kt     Plain data classes shared by ViewModels + UI
  viewmodel/              One StateFlow-based ViewModel per screen, IO on Dispatchers.IO
  ui/screens/             Jetpack Compose screens (Material 3)
  navigation/             Compose Navigation graph + typed destinations
  MainActivity.kt / QuickGitApp.kt
```

Git operations are all synchronous JGit calls wrapped in `withContext(Dispatchers.IO)` —
no background service or WorkManager is used yet, so a killed app mid-clone will need to
restart the clone (see Known limitations).

## Building it

You'll need **Android Studio (Koala or newer)** with the Android SDK (compileSdk 34) and a
JDK 17.

1. Open this folder (`QuickGit/`) directly in Android Studio — it will detect the Gradle
   project and offer to generate the Gradle wrapper automatically. (The wrapper jar isn't
   checked in here since it's a binary; Android Studio's "Sync now" prompt handles it, or
   run `gradle wrapper` once if you have a system Gradle install.)
2. Let Gradle sync — it will pull JGit, the Apache MINA SSH transport, and AndroxX/Compose
   dependencies from Maven Central.
3. Run on a device or emulator running **API 26+**.

No Anthropic/Claude systems are involved at runtime — this is a plain offline Android app;
it just talks to whatever git remote you point it at.

## Known limitations / good next steps

- **SSH host key verification is disabled** (`SshSupport.kt` accepts any host key). Fine for
  personal use, not hardened — wire up a persisted known_hosts store before shipping this.
- **Single SSH identity** — only one key pair is stored app-wide, not per-host.
- **No background/foreground service** — large clones or pushes are tied to the screen's
  lifecycle; consider a `WorkManager` job with a persistent notification for big repos.
- **Diff viewer is read-only** — no inline "stage this hunk" (partial staging), only whole-file
  staging.
- **Conflict resolution UI** is a simple ours/theirs/manual-edit dialog, not a 3-way visual
  merge view.
- **No biometric lock** on the credentials screen — worth adding given it stores tokens/keys.
- Large repos / large binary files aren't specially handled (no LFS support).

## Permissions

`INTERNET`, `ACCESS_NETWORK_STATE`, and (on API ≤ 28) external storage so clones can live
under **Documents/QuickGit** on shared storage. On newer Android versions the app still
tries `Documents/QuickGit` first and falls back to app-specific external storage if the
public folder is not writable. Uninstalling the app does **not** delete `Documents/QuickGit`
when the public location is used.

## Tested on
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

---

## Linux / Desktop (native)

QuickGit now also ships as a **native desktop application** for Linux (and Windows/macOS) built with Compose Multiplatform + JGit.

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
