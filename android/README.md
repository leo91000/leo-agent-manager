# Leo for Android

Native Kotlin / Jetpack Compose client for the Leo Agent Manager API in this repository.
Version 0.4.1 resumes conversations without replaying historical scroll.
Tool activity has native views: commands, reads, searches, diffs,
plans, MCP results and session notices. Structured results and JSON artifacts are
readable without opening the raw JSON. Chats retain the compact 0.3 composer.
See [release notes](RELEASE-NOTES.md). The inventory below distinguishes implemented
paths from deployment and device validation.

Open **this `android/` directory** in Android Studio. The web application and server
keep their existing build and dependencies. The native MCP OAuth bridge is included
in the backend; older deployments use the browser fallback. No database migration is required.

## Build and connect

Requirements: JDK 21 (including Android 16 UI tests), Android SDK platform 37.0, build-tools 36.0.0. The checked-in
Gradle wrapper pins Gradle 9.4.1. AGP 9.2.1 supplies Kotlin 2.3.10; Compose compiler
and serialization plugins match it. Compose libraries use BOM 2026.09.00.
Minimum Android version: Android 8 (API 26). Compile/target API: 37.

```sh
# From android/, with ANDROID_HOME pointing to your Android SDK (or local.properties):
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
# Install the development APK on a connected phone:
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Enter your server's HTTPS origin (for example `https://leo.example.com`) and the
same owner password used by the web application. First-time server setup also
supports the bootstrap token and creation of the owner password. No production
address or credentials are embedded in the app.

For local development, use `adb reverse tcp:4310 tcp:4310` and
`http://127.0.0.1:4310` in a **debug** build. HTTP is limited to localhost,
127.0.0.1 and the emulator host alias 10.0.2.2; the server still validates Host
against PUBLIC_URL. Prefer adb reverse so the existing localhost server config
works unchanged. Release builds require HTTPS and normal certificate validation.

The APK produced by `assembleDebug` is installable and development-signed. The
release APK is deliberately unsigned: configure your own signing key before any
store distribution. Never commit a signing key or local SDK paths.

## Product and architecture

- Compose Material 3 components, system back navigation, edge-to-edge insets,
  keyboard handling, bottom navigation in the main phone sections and a rail from 700 dp.
  Chat/run details use their own compact header and system back navigation. Dragging
  the activity stops automatic following; the down arrow returns to the latest item.
- Semantic colors match `src/styles/theme.css` at `207ca4f`: blue-violet accent
  `#4545ef`, ink `#28283c`, canvas `#fdfcfe`; dark accent `#b8b2ff`, canvas `#1b1b20`,
  surface `#222228`. Material roles also map borders, muted text and error colors.
  Settings → Appearance offers System, Light and Dark, saved on the device.
  Status/navigation bar contrast follows the selected appearance. Native typography,
  controls and touch targets retain Android behavior. UI text is French.
- An AndroidViewModel exposes immutable workspace state through StateFlow;
  Compose observes it with lifecycle awareness. Forms save drafts through
  recreation. Passwords, setup tokens and newly issued access tokens are never
  placed in saved instance state.
- Kotlin serialization models mirror `shared/contracts.ts` and the API response
  shapes. OkHttp requests support cancellation, enforce a 30-second timeout,
  refuse redirects, and send the existing cookie plus CSRF token.
- The server origin and appearance preference are stored in DataStore. The session cookie is encrypted
  with an AES-GCM Android Keystore key, bound to the origin with authenticated
  associated data, and written atomically outside Android backups. Cookies are
  checked against scheme, host, port, path and expiry. No password is persisted.
- Chats and run details consume the server’s SSE batches only while STARTED.
  Reconnection resumes from the accepted cursor, folds compressed assistant deltas,
  resets on run changes and replays malformed batches without skipping events.
  Logout cancels open streams before revoking the session. Other lists poll while visible.
- Documents use the Android picker, bounded streaming uploads, authenticated file
  downloads and scoped FileProvider sharing. Image, PDF, Markdown, code and media
  previews use native Android components; no arbitrary artifact URL is fetched.
- Optional WorkManager checks notify about pending questions approximately every
  15 minutes with a network constraint. No Firebase service or keys are required.
  Android battery management can delay checks; force-stopping the app prevents
  background work until it is opened again. The preference and permission are
  managed in Settings, and notification taps open the corresponding conversation.
- Markdown uses native Android text rendering, including tables and
  strikethrough. There is no WebView. Provider device login and OAuth callbacks
  open a browser Custom Tab, as these pages belong to the external provider.
- Android is a client: scheduled work and agent processes keep running on the
  existing server when the phone is offline. There is no offline mutation queue.

## Coverage

This inventory was rechecked against web/backend commit `207ca4f` (2026-09-13).
The initial checkout, `1e0d71b`, had an obsolete green theme and fewer features.
The palette and API contracts follow this baseline. “Implemented” describes the
client path, not certification against a production deployment.
See [validation](VALIDATION.md).

| Web section | Android coverage |
| --- | --- |
| Setup / login | Server selection, bootstrap setup, password login, encrypted session restore, expiry handling, logout |
| Overview (under Espace) | Running/completed/queued counts, agent and schedule counts, recent activity, upcoming tasks |
| Tasks | Create/edit/delete, name and tag search, scheduled/paused/one-off/archived filters, latest execution and status, run now, pause/resume, archive/restore, duplicate disabled |
| Task editor | Agent selection, optional project focus (all authorized projects), instructions, isolated worktree, tags, all authorized or individually selected skills, once/daily/weekly/custom cron, timezone, server schedule preview |
| Runs | Status and task filters, 30-item pagination, current status, results, sharing/copy through Android selection, duration and metadata |
| Run detail | SSE activity with follow/reconnection, original brief and snapshot, skills, usage, cancellation, retry, resume, recovery/account waiting states, multiple workspaces and guarded cleanup; chat runs link back to their conversation |
| Agents | List/search, create/edit/delete, server model/reasoning catalog, instructions, timeout, project/skill/GitHub access, dedicated GitHub token, sandbox, MCP server and per-tool restrictions |
| Projects | List/search, register/edit/delete, server path, description, base branch and origin |
| Skills | Search, global/project filters, create/edit/delete, validation errors, Markdown preview, supporting file list/read/create/edit |
| Connections | GitHub status/version and device login; Codex multi-account creation, renaming, reconnect, removal, pause, concurrency, quota windows, refresh, reset-credit status and active run links |
| Settings | Saved light/dark/system appearance, server/MCP details, grant list, scoped personal token creation, one-time token copying, revocation, 100 most recent audit entries |
| OAuth authorization | Share/paste an authorization link, verify its origin, preview client/scopes, explicit allow/deny, return to client |
| Chats | Native list/search, creation from agent/project, model/effort selection, streamed messages, queue editing/removal, steering, pause/resume/stop, blocking and optional questions, choices/custom/private answers |
| Attachments | Android file/photo picker, up to 8 files, 10 MiB per file / 40 MiB per message, stable upload/message identifiers for explicit retries |
| Artifacts | Authenticated file previews, downloads through Android’s save picker, sharing/open-with, native opening of Markdown artifact links, latest/all versions, groups and search |
| MCP servers | HTTP/stdio configuration, test/discovery, enabled tool selection, bearer/client secrets, environment variables, OAuth, removal/disconnect |
| Notifications | Opt-in Android permission, periodic pending-question checks, deduplication, cancellation of resolved alerts and opening the related chat; no Firebase |

Destructive actions use native confirmation dialogs and remain subject to the
same server checks as the web. No live task is launched as part of validation.

Self-hosted domains cannot be automatically verified as Android App Links by a
generic APK. OAuth requests can be shared from the browser to Leo, or pasted in
**Espace → Autoriser un assistant**. Domain-specific verified links can be added
when the deployment domain and signing certificate are fixed.

## Verification and distribution

The Android workflow runs JVM/network tests, Compose UI tests with Robolectric,
Android lint and both build variants. It uploads the debug APK and reports to the
workflow run. It does not deploy the server or publish to Google Play.

Optional native UI test captures (fixture data only):

```sh
./gradlew testDebugUnitTest -PleoScreenshotsDir=/tmp/leo-android-captures
```

## MCP OAuth and deployment

Provider browser tabs do not share the Android session cookie. The accompanying
Rust patch advertises `nativeMcpOauth` in `/api/settings` and supports:

1. Authenticated `POST /api/mcps/:id/connect` with `{ "native": true }`.
2. The provider returns to the existing public HTTPS callback. The server captures
   only the allowlisted response fields in the original ten-minute pending record.
   This browser step does not exchange credentials or reveal them in HTML.
3. The initiating Android session finishes through CSRF-protected
   `POST /api/mcps/:id/callback`. Session binding, issuer, PKCE, revision and replay
   checks remain enforced by the existing exchange. Other sessions cannot finish it.

The normal web OAuth flow remains supported. On a deployment without this patch,
Android opens the existing web MCP page to complete provider authentication there.
No deployment is performed by the Android build or CI.

The current web API has no conversation rename/delete endpoints; those actions
are not invented by the native client. Scheduled jobs continue on the server;
there is no offline mutation queue. Notifications deliberately use periodic
checks rather than the browser’s Web Push subscriptions, following the chosen
Firebase-free approach.

Release gates: physical-device validation against the intended HTTPS server,
deployment of the optional native MCP OAuth bridge, release signing identity and
distribution channel. See [validation](VALIDATION.md) for actual checks and limits.

Reference documentation used to select compatible Android versions:
[AGP 9.2](https://developer.android.com/build/releases/agp-9-2-0-release-notes),
[Compose BOM](https://developer.android.com/develop/ui/compose/bom),
[built-in Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin),
[Activity](https://developer.android.com/jetpack/androidx/releases/activity),
[Lifecycle](https://developer.android.com/jetpack/androidx/releases/lifecycle),
[WorkManager](https://developer.android.com/jetpack/androidx/releases/work),
[periodic work](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work).

## Conversation cache (0.5.0)

Chats and run activity restore a bounded local snapshot before reconnecting the
SSE stream. The snapshot includes the accepted cursor, decoded messages, tool
activity, artifact metadata and reading position. Files are loaded on demand.
Android encrypts disk records with an Android Keystore AES-GCM key, excludes them
from backups, and scopes them to the server and authenticated session. Signing
out or forgetting the server clears the cache. Storage failure falls back to the
normal stream. Limits: 12 histories, 4 MiB per history, 20 MiB total, seven days.
Oversized histories use the normal live stream without a persisted snapshot.

The web client follows the same protocol using session-scoped IndexedDB records.
The backend emits a `history` revision (run identity + earliest retained event)
and validates it with `after`. Unchanged histories transfer only new events;
changed or pruned histories send a reset. Existing clients without a revision
remain supported. This does not add an offline mutation queue or eliminate the
initial session check; it lets previously read content appear while the stream
reconnects.
