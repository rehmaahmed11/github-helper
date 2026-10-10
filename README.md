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

## Sign in (no rebuild needed)

The app always opens with sign-in enabled. Pick either option on the login screen:

1. **Personal access token (fastest).** At
   <https://github.com/settings/tokens> create a token with the `repo` scope,
   paste it into the app, and you're in. The token is verified with a `GET /user`
   call before it is stored, and it is kept encrypted via `EncryptedSharedPreferences`.
2. **Browser / OAuth.** If this build baked in OAuth credentials, just tap
   *Continue with GitHub*. Otherwise the app asks for your OAuth App's
   **Client ID and Client Secret once**, saves them encrypted on the device,
   and enables browser sign-in from then on.

### 1. Create an OAuth App (only for browser sign-in)

At <https://github.com/settings/developers> → **New OAuth App**:

| Field | Value |
| --- | --- |
| Homepage URL | anything, e.g. `https://github.com` |
| Authorization callback URL | `githubdesktop://callback` |

### 2. Configure both OAuth credentials

GitHub requires the OAuth App's **Client ID and Client Secret** at the code-to-token
exchange, even when PKCE is enabled. The browser approval can therefore succeed
and return to the app, but the token exchange will fail if the secret is missing
or belongs to a different OAuth App.

For a local build, put both values in `local.properties` at the repository root
(this file is git-ignored):

```properties
sdk.dir=/path/to/Android/sdk
github.clientId=YOUR_OAUTH_APP_CLIENT_ID
github.clientSecret=YOUR_OAUTH_APP_CLIENT_SECRET
```

Or provide both at build time:

```bash
./gradlew assembleDebug \
  -PGITHUB_CLIENT_ID=YOUR_OAUTH_APP_CLIENT_ID \
  -PGITHUB_CLIENT_SECRET=YOUR_OAUTH_APP_CLIENT_SECRET
```

Or through environment variables:

```bash
export GITHUB_CLIENT_ID=YOUR_OAUTH_APP_CLIENT_ID
export GITHUB_CLIENT_SECRET=YOUR_OAUTH_APP_CLIENT_SECRET
./gradlew assembleDebug
```

Each value is resolved independently in this order: `-P` Gradle property →
matching environment variable → `local.properties` (`github.clientId` and
`github.clientSecret`). If either value is missing, the app still builds but
sign-in is disabled with a setup message.

**Native-app security note:** a client secret compiled into an APK is not truly
secret and can be extracted. GitHub requires it for this OAuth App flow; PKCE
protects authorization codes but does not hide the secret. Do not commit the
secret or paste it into source code. For a flow that does not ship an OAuth App
secret, use GitHub's Device Flow (which requires enabling it in the OAuth App
settings) or exchange codes through a trusted backend. See [GitHub's OAuth App
authorization docs](https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps).

For the **Build APK** GitHub Actions artifact, add repository Actions secrets
named `GITHUB_CLIENT_ID` and `GITHUB_CLIENT_SECRET` to pre-enable browser
sign-in. Without these, CI still produces a fully usable APK — the user just
enters their own credentials in the app or uses a personal access token.

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
│   ├── build.gradle.kts            # OAuth credential resolution
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
