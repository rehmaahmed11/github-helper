# github-helper

A native Android GitHub client, built with Kotlin and Jetpack Compose — browse
your repositories, edit and upload files from the phone, and push through a
working branch you review before it lands.

## Features

- Sign in with GitHub via the system browser (Custom Tabs) using
  **OAuth 2.0 Authorization Code + PKCE (S256)**, or with a personal access token
- List, create and delete repositories; toggle public/private
- Browse files and folders, switch between branches
- Upload files or a whole folder as **one commit** (blobs -> tree -> commit -> ref)
- Create, edit and delete files; delete a file the same way
- "Clone" - download a repository snapshot into a folder you pick
- After every push, review and optionally merge the working branch and delete it
- **Access check** screen: what GitHub actually allows this credential to do,
  its scopes, the rate-limit budget, and a link that opens the token form with the
  right scopes already ticked

## 403 - the one thing to read before reporting a bug

A 403 from GitHub is not one problem, it is four, and they have different fixes:

| Granted scopes on the token | What GitHub allows | What the app can do |
| --- | --- | --- |
| `repo` (classic PAT or OAuth) | read + write private and public repos, create, delete with `delete_repo` | everything |
| `public_repo` only | public repos only | private repos look like `404 Not Found`; writes to them look like `403` |
| none | public reads only | browsing works, **every write is 403** |
| fine-grained PAT | exactly the permissions ticked, per repository | needs Contents: Write; cannot create repositories unless Administration: Write is granted |
| GitHub App / installation token | the app's permission manifest | `403 Resource not accessible by integration` - **app tokens cannot create repositories at all** |

A fifth cause is real but temporary: **a secondary rate limit** also answers
`403 You have exceeded a secondary rate limit`. Uploading a large folder as one
commit per file used to trip it; that is why a push is now a single commit, and
why the client waits and retries when GitHub sends `Retry-After`.

The app does not guess which of these it is: every failure keeps GitHub's own
`message`, the `X-OAuth-Scopes` the token has, the `X-Accepted-OAuth-Scopes` the
endpoint needs, the request id, and a one-line fix. Open the **Access check**
sheet (the ⓘ in either toolbar) to see them, and tap the button that opens
GitHub's token form pre-filled:

```text
https://github.com/settings/tokens/new?scopes=repo,delete_repo
```

## Sign in

Sign-in is always enabled; nothing has to be configured to use the app.

**Recommended: a classic personal access token.** Tap *Open the token form (repo +
delete_repo ticked)* on the login screen - GitHub opens with the boxes already
ticked - press *Generate token*, copy it, paste it in, done. The token is verified
with `GET /user` before it is stored, then measured with an access probe, and kept
in `EncryptedSharedPreferences`. A token that can read but not write is reported
immediately rather than at the first failed upload.

**Browser / OAuth** works too, and needs an **OAuth App** (Settings -> Developer
settings -> OAuth Apps), *not* a GitHub App: a GitHub App's user token cannot
create repositories, which is the source of most "403 on everything" reports.

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
│       │   ├── data/               # Retrofit API, models, repository, AccessReport probe
│       │   └── ui/                 # Compose screens, components, SAF + Git-data helpers
│       └── res/
├── .github/workflows/build.yml     # builds the debug APK on push/PR
├── build.gradle.kts
├── settings.gradle.kts
└── gradlew                        # Gradle wrapper (8.7)
```

## Notes on behaviour

- **Writes never touch your default branch.** Each push creates its own
  `mobile-edit-<timestamp>-<rand>` branch off the branch you are looking at, and you
  choose whether to merge it. An *empty* repository is the exception: there is no
  branch to fork and nothing to merge, so the first commit creates the default branch.
- **A folder is one commit.** File contents are uploaded as blobs (identical bytes
  only once), combined into a tree on top of the current head, committed, and the ref
  is moved. Nothing lands unless everything did, so a failure cannot leave a
  half-uploaded folder or an orphan branch behind. Payloads over 8 MB are split into
  stacked commits; a single file over 20 MB is refused with the reason (GitHub's own
  limit is 100 MB, but the payload is base64 in memory and an OOM looks like a hang).
- **Failures carry their reason.** GitHub answers most errors with a 4xx/5xx
  *response* rather than an exception, so every call checks the status and turns the
  body plus the scope / rate-limit headers into a `GitHubFailure`.
- **Throttling is retried, not reported.** `429`, `5xx` and a `403` that says
  "secondary rate limit" are re-sent after `Retry-After` (or jittered backoff), at
  most three times. A throttled request was rejected before GitHub acted on it, so
  repeating it cannot double-write. Transport failures are only retried for `GET`.
- **`main` is not assumed.** Branches come from each repository's `default_branch`,
  so `master` repositories work; an empty `default_branch` falls back to `main`.
- **Large files are not editable in-app.** GitHub only returns file contents inline
  below ~1 MB; opening a bigger file says so instead of risking an empty overwrite.
- **The token is stored encrypted** via `EncryptedSharedPreferences` against the
  Android Keystore. The PKCE `code_verifier` never leaves memory. Android backup is
  disabled on purpose: restoring keystore-backed preferences onto another device
  would crash the app at launch.
- **"Clone" extracts a snapshot**, not history - the zipball contents are written
  into the folder you pick. History-aware clone is planned for V2.

## License

A license has not been selected yet. Until one is added, all rights are reserved
by the repository owner.
