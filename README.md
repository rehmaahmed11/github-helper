# github-helper

A native Android GitHub client, built with Kotlin and Jetpack Compose — browse
your repositories, edit and upload files from the phone, and push through a
working branch you review before it lands.

## Features

- Sign in with GitHub via the system browser (Custom Tabs) using
  **OAuth 2.0 Authorization Code + PKCE (S256)**
- List, create and delete repositories; toggle public/private
- Browse files and folders, switch between branches
- Upload individual files or a whole folder
- Create, edit and delete files
- "Clone" — download a repo's contents into a folder you pick
- After every push, review and optionally merge the working branch and delete it

## Setup

### 1. Create an OAuth App

At <https://github.com/settings/developers> → **New OAuth App**:

| Field | Value |
| --- | --- |
| Homepage URL | anything, e.g. `https://github.com` |
| Authorization callback URL | `githubdesktop://callback` |

There is **no client secret** — PKCE does not need one, and nothing secret is
shipped in the app.

### 2. Add your Client ID

The Client ID is a public identifier, but it identifies one specific OAuth App, so
it is kept out of version control. Create `local.properties` in the repo root
(this file is git-ignored):

```properties
sdk.dir=/path/to/Android/sdk
github.clientId=Ov23liYdoLYwgghXQVXQ
```

Or pass it at build time / through the environment:

```bash
./gradlew assembleDebug -PGITHUB_CLIENT_ID=Ov23liYdoLYwgghXQVXQ
export GITHUB_CLIENT_ID=Ov23liYdoLYwgghXQVXQ
```

Resolution order is `-P` property → `GITHUB_CLIENT_ID` env var →
`local.properties`. With none of them set the app still builds, and the sign-in
screen explains that it has no Client ID rather than failing mysteriously.

## Build

```bash
./gradlew assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew lintDebug
```

Requires JDK 17 and the Android SDK (compileSdk 34, minSdk 26).

Alternatively, push to GitHub → **Actions** → *Build APK* → download the
`app-debug-apk` artifact.

## Project structure

```text
github-helper/
├── app/
│   ├── build.gradle.kts            # GITHUB_CLIENT_ID resolution
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/example/githubdesktop/
│       │   ├── MainActivity.kt     # auth state + PKCE redirect handling
│       │   ├── auth/               # OAuth + PKCE + encrypted token storage
│       │   ├── data/               # Retrofit API, models, repository
│       │   └── ui/                 # Compose screens, components, SAF helpers
│       └── res/
├── .github/workflows/build.yml     # builds the debug APK on push/PR
├── build.gradle.kts
├── settings.gradle.kts
└── gradlew                        # Gradle wrapper (8.7)
```

## Notes on behaviour

- **Writes never touch your default branch directly.** Every upload, edit and
  delete is committed to a fresh `mobile-edit-<timestamp>-<rand>` branch, and
  you choose whether to merge it.
- **Failures are reported.** GitHub returns most errors as a 4xx/5xx *response*
  rather than an exception, so every call checks its HTTP status and surfaces
  GitHub's own error message in a snackbar.
- **`main` is not assumed.** Branches are resolved from each repository's
  `default_branch`, so `master` repos work too.
- **The token is stored encrypted** via `EncryptedSharedPreferences` against the
  Android Keystore. The PKCE `code_verifier` never leaves memory. Android backup
  is disabled, because restoring keystore-backed prefs onto a new device would
  otherwise crash the app on launch.
- **Large files are not editable in-app.** GitHub only returns file contents
  inline below ~1 MB; opening a bigger file shows a message instead of risking an
  empty overwrite.
- **"Clone" extracts a snapshot**, not history — the zipball contents are
  written into the folder you pick. History-aware clone is planned for V2.

## License

A license has not been selected yet. Until one is added, all rights are reserved
by the repository owner.