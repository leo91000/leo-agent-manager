# Android 0.5.1 validation

## Cache and backwards history

- The cache keeps up to 200 recent events within a 3 MiB payload budget instead of
  deleting an oversized conversation. Its live cursor and backwards boundary are
  stored independently. Session isolation, expiry and logout clearing remain.
- Compose/Robolectric regressions reopen small and oversized conversations while
  the stream deliberately sends no response. Recent messages remain visible.
  A backwards page preserves the visible message's screen position. Disk tests
  restore the retained suffix and invalidate offsets that refer to removed data.
- Backwards server pages skip superseded assistant snapshots. A test with 80 large
  revisions reaches the older question on the next page, while preserving an
  older turn that reused the same item ID. Normal forward SSE/REST replay remains
  compatible. Pagination checks cover gaps, revisions, pending-message metadata,
  invalid cursors, authorization and concurrent live writes.
- Chromium and WebKit journeys cover paging/scroll anchors, cache persistence
  before a delayed stream, logout, offline recovery and server restarts.
- Android debug and optimized release builds and lint completed locally. The
  final development APK is version 0.5.1 / code 8. See the commit's GitHub Actions
  for the complete checks against the published revision.

Commands:

```sh
mise exec java@temurin-21 -- android/gradlew -p android --no-daemon testDebugUnitTest lintDebug assembleDebug assembleRelease
env -u LEO_AUTH_SOCKET pnpm check
env -u LEO_AUTH_SOCKET cargo clippy --locked --workspace --all-targets -- -D warnings
env -u LEO_AUTH_SOCKET cargo test --workspace
env -u LEO_AUTH_SOCKET pnpm exec playwright test --project=journeys-live --project=layout-webkit-live --workers=1
```

Remove `LEO_AUTH_SOCKET` for fixture tests so they cannot use the coding session's
live token broker. On a 4 GiB machine, run compilation and timing-sensitive browser
journeys separately. Concurrent compilation caused a login deadline to expire in
one local browser run; the isolated run passed.

## Long-response streaming diagnostic

`StreamingCostTest` measures 15 samples after three warmup updates for each input.
These are component timings in a 2-CPU Linux VM under Robolectric native graphics,
not phone frame-rate measurements. The cache timing includes JSON and disk I/O but
excludes hardware Keystore encryption. All input is synthetic Markdown.

Local medians, milliseconds per update:

| Text length | Prior events | Cache | History merge | Timeline | Markdown | Text layout |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 2,000 | 80 | 1.35 | 0.14 | 13.36 | 10.28 | 45.86 |
| 20,000 | 80 | 2.07 | 0.18 | 6.10 | 42.67 | 111.94 |
| 100,000 | 80 | 7.23 | 0.18 | 7.02 | 391.29 | 770.28 |
| 100,000 | 2,000 | 7.59 | 1.13 | 122.48 | 351.75 | 812.10 |

The message receives 15 different timeline keys for 15 accepted updates. The key
uses the latest event ID, so Compose replaces the message subtree, including its
remembered renderer and Android TextView. `Markdown` then reparses the whole text
and lays it out again on the UI thread. The diagnostic recreates that view and
renderer for each update; their construction costs another 6–8 ms median.
Large groups of prior events also increase timeline work substantially.

The server diagnostic writes complete snapshots but transfers suffixes: with
2,000–100,000 initial characters, each measured update batch remained 478 bytes.
Local write-to-client delivery medians were approximately 33–53 ms. These tests
cover the application server, not the provider or production reverse proxy.

The render bottleneck is diagnosed, not fixed by this cache change. Follow-up
work should stabilize message identity and avoid rebuilding/layouting a complete
long Markdown response for every fragment; frame-level validation on an Android
device is still required.

## APK and limits

Package `dev.leo.manager`; minSdk 26; targetSdk 37; version 0.5.1 / code 8.
The development certificate matches the previously supplied APKs, allowing an
in-place update. Certificate SHA-256:
`b56038aa26883922efdf917ff45300b9d5a6f59cc7901c5e4097664a2abbbf99`.

Robolectric tests are not physical-device or emulator tests. Samsung keyboard
transitions, TalkBack, rotation and hardware-backed encryption remain device
checks. No test signs into production or starts a real agent run.

Android encrypts snapshots using Android Keystore AES-GCM and excludes them from
backups. Web uses session-scoped IndexedDB. Both caches retain at most 12 histories,
4 MiB per encoded record, 20 MiB total and seven days. Recent event payloads use a
3 MiB budget; an individual event exceeding it stays accessible from the server.
Messages, tools and artifact metadata are cached; files are fetched on demand.
Reading offsets are retained only for the cached window. Initial authentication
still requires the session check. There is no offline mutation queue.

Notifications remain opt-in periodic WorkManager checks without Firebase.
Distribution uses the development APK, not Google Play.
