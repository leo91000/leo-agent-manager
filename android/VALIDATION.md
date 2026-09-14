# Android 0.5.0 validation

Date: 2026-09-14. Version code: 7. Based on `bed13bf`.
This revision adds durable, bounded conversation/run caches to Android and web,
with server-side history revision validation and incremental stream resume.

## Checks performed

- **43 Android tests pass**, zero failures/errors/skips, using Robolectric,
  native Compose, MockWebServer and the WorkManager test environment.
- The 60-message lifecycle journey verifies atomic initial loading, foreground
  resume and reopening the conversation. Reopening sends `after=60` plus the
  saved history revision and restores the reader's exact scroll offset.
- Cache tests restore disk records through a new cache instance, preserve reading
  position, isolate sessions, reject invalid cursors/corruption/expired or oversized
  records, bound retained histories, and prevent writes after logout invalidation.
  Disk serialization tests inject a codec; they do not validate hardware Keystore.
- Existing chat/run, activity, artifact, permissions, notifications and workspace
  journeys pass. The delta reducer accepts further text after a cached baseline.
- Android lint: **0 errors, 6 existing warnings**. The development APK builds.
- **199 web unit tests and 84 Rust tests pass**. Web lint, typecheck/build and
  generated backend schema consistency pass.
- **8 browser journeys pass on Chromium and WebKit**. A deliberately held stream
  proves that IndexedDB supplies a reloaded chat before the network responds.
  A cached run restores its reading offset while connecting. Logout clears the
  persisted records. Streaming, offline recovery and server restart still pass.
- Backend tests validate unchanged history, mismatched revision and prefix pruning.
  Revision lookups use the existing `(run_id,id)` index without hashing all events.
- The commit hook runs ESLint/rustfmt, Cargo check and Clippy with warnings denied.
  Dependency/runtime pins were not upgraded.

Commands:

```sh
mise exec java@temurin-21 -- android/gradlew -p android --no-daemon testDebugUnitTest lintDebug assembleDebug
env -u LEO_AUTH_SOCKET pnpm check
env -u LEO_AUTH_SOCKET cargo test --workspace
env -u LEO_AUTH_SOCKET pnpm exec playwright test --project=journeys-live --project=layout-webkit-live --workers=1
node --import tsx scripts/backend-schemas.mjs
```

Remove `LEO_AUTH_SOCKET` for fixture tests so they cannot use the coding session's
live token broker. On a 4 GiB machine, run heavy builds and browser journeys
sequentially; simultaneous compilation exhausted memory during the initial run.

## APK and limits

Package `dev.leo.manager`; minSdk 26; targetSdk 37; version 0.5.0 / code 7.
The development certificate matches the previously supplied APKs, allowing an
in-place update. Certificate SHA-256:
`b56038aa26883922efdf917ff45300b9d5a6f59cc7901c5e4097664a2abbbf99`.

Robolectric lifecycle tests are not physical-device or emulator tests. Samsung
keyboard transitions, TalkBack, rotation and hardware-backed encryption remain
real-device checks. No test signs into production or starts a real agent run.

Android encrypts snapshots using Android Keystore AES-GCM and excludes them from
backups. Web uses session-scoped IndexedDB. Both caches are bounded to 12 histories,
4 MiB per record, 20 MiB total and seven days; oversized histories use the stream.
Messages, tools and artifact metadata are cached; files are fetched on demand.
The server remains authoritative and a changed history revision resets the cache.
Initial authentication still requires the existing session check. This revision
does not introduce offline mutations or a permanent background stream.

Notifications remain opt-in periodic WorkManager checks without Firebase.
The Android workflow also builds the optimized unsigned release; distribution
uses the development APK, not Google Play. See GitHub Actions for commit-specific
CI results and the release tag's deployment verification.
