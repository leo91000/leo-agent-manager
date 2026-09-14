# Android 0.4.1 validation

Date: 2026-09-14. Version code: 6. Integrated on web/backend baseline `71a82af`.
This revision includes the native Android client, its CI workflow, the native MCP
OAuth bridge and stable conversation resume.

## Checks performed

- **40 Android tests pass**, zero failures/errors/skips, with Robolectric / Android
  16, native Compose, MockWebServer and the WorkManager test environment.
- A 60-message conversation is delivered in three batches. Initial opening and
  reopening keep partial pages hidden. Foreground resume requests `after=60`
  instead of replaying the history from zero. The test scrolls upward manually
  and verifies that the exact reading offset survives another foreground resume.
- Retained stream tests verify compressed message continuation, route isolation
  and clearing data after the stream generation changes. Existing reset,
  authentication revocation and network reconnect tests pass.
- Existing chat/run, structured activity, artifact, permissions, notifications
  and workspace journeys pass.
- Android lint: **0 errors, 6 existing warnings**. The development APK builds.
  The Android GitHub workflow also builds the optimized unsigned release.
- Repository checks: **193 web tests** and **83 Rust tests** pass. Web lint,
  typecheck/build, generated schema consistency and actionlint pass. The commit
  hook runs ESLint/rustfmt, Cargo check and Clippy with warnings denied.
- Rust tests run with `LEO_AUTH_SOCKET` removed from their environment so token
  broker tests use their fixtures rather than the coding session's broker.
- `git diff --check` passes. Dependency/runtime pins were not upgraded.

Commands:

```sh
mise exec java@temurin-21 -- android/gradlew -p android --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
pnpm check
env -u LEO_AUTH_SOCKET cargo test --locked --workspace
node --import tsx scripts/backend-schemas.mjs
node scripts/pre-commit.mjs
actionlint .github/workflows/android.yaml
```

## APK

Package `dev.leo.manager`; minSdk 26; targetSdk 37; version 0.4.1 / code 6.
The development signature was verified with `apksigner` and matches the previously
supplied APKs, allowing installation as an update.

APK SHA-256: `72bb257c4dd753e54c87e4710b36acfbc9e254538b9b5b78bc3ab389858743f0`.
Certificate SHA-256: `b56038aa26883922efdf917ff45300b9d5a6f59cc7901c5e4097664a2abbbf99`.

## Limits

Robolectric lifecycle tests are not physical-device or emulator tests. Samsung
keyboard transitions, large text/TalkBack, system bars, device rotation and real
file sharing/media playback remain device checks. Tests do not sign in to the
production app or start real agent runs.

History/cursor retention is in memory for an open screen. A newly opened or
process-recreated screen reloads history atomically; it does not persist chat
content to disk. There is no new offline cache or background stream.

Notifications remain opt-in periodic WorkManager checks without Firebase. The
native MCP OAuth bridge is included in the backend source; older deployments
continue to use browser sign-in. No signing key is committed and no release tag
or store publication is part of this task. See GitHub Actions for commit-specific
CI and optimized-release build results.
