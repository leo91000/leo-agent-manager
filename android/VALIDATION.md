# Android 0.6.0 — native web parity

The port targets web v0.21.12. The action-by-action feature map and deliberate
platform equivalents are in [WEB-PARITY.md](docs/WEB-PARITY.md).

The implementation passed the complete 87-test JVM/Robolectric suite, lint,
debug and optimized release builds in
[Android CI](https://github.com/leo91000/leo-agent-manager/actions/runs/35584279965).
Lint reports zero errors and eight warnings. The same run passed 11 actual
Android 16 instrumentation tests, followed by the separate one-test pinned-scroll
capture. These include native chat switching, preserved drafts, evidence,
keyboard/enlarged text, fullscreen, task management and adaptive layout alongside
the existing history and gesture checks. The repository's
[quality, browser and container checks](https://github.com/leo91000/leo-agent-manager/actions/runs/35584279943)
also passed.

Two additional native-preview tests cover phone and tablet layouts, long URLs,
completion evidence, task selection, the tablet chooser and selecting the main
agent by default when another agent appears first. They bring the complete suite
to 89 tests. The final commit's PR checks are the authoritative validation of
these final additions.

Preview images generated with `-PleoScreenshotsDir=...` use Robolectric native
graphics and synthetic data. They are not emulator or physical-phone screenshots.
Local software emulators did not finish booting reliably; the successful device
evidence above comes from the accelerated Android 16 CI emulator. Physical-phone
feel, screen-reader review and frame-time measurement have not been certified.

A race discovered by the device suite is fixed: the initial asynchronous Markdown
render can finish only once, including when its view is disposed during layout.
Two dedicated tests prevent the pending-render count from going negative.

Package `dev.leo.manager`; minSdk 26; targetSdk 37; version **0.6.0 / code 15**.
The downloadable APK is development-signed; the optimized release build is
validated separately. Background notifications retain Android's existing
WorkManager scheduling (roughly 15-minute checks, subject to OS delays), which
has different delivery timing from browser Web Push. Foreground updates use SSE.

## Previous validation: Android 0.5.6 — cascading history loads and held gestures

Two regression cases reproduce the 0.5.5 failure with the older-history control
visible and more pages available: with an idle reader and with a finger still
held in a drag. Both originally requested two pages instead of one. The corrected
cases assert one request and an unchanged visible text offset; the held-finger
case also continues the same gesture after the page arrives.

The tests run under Robolectric and are included in the Android 16 CI
instrumentation selection through `HistoryFollowDeviceTest`. Physical-phone
validation remains separate from these automated checks.

## Previous validation: Android 0.5.5 — older-history reading position

The local JVM/Robolectric suite passes 73 tests. Six new paging cases use the
production lazy list, asynchronous Markdown renderer and paging hook: stable text
position, movement during loading, removal of the visible loading control, long
first messages, consecutive pages, and returning to the latest message while a
page is pending. The existing cache pagination test also covers a fast response
through a simulated HTTP server. Before/after captures of the stable-position
case are byte-identical. Lint reports zero errors and seven warnings.

These paging checks simulate list movement through its scroll APIs; they are not
physical-device gesture evidence. The tactile feel of this correction on a phone
remains to be confirmed.

## Previous validation: Android 0.5.4 — message links

Targeted checks pass for a first tap opening exactly once, long-press selection,
dragging without opening a link, and authenticated metadata/file loading for an
artifact absent from the current conversation. The gesture case is shared with
the Android 16 instrumentation suite. Robolectric stubs only the selection
magnifier surface, which it cannot render; the device test uses the real magnifier.

The web deliverables journey passes on Chromium and WebKit: relative and absolute
artifact links, Markdown/image previews, external browser navigation, a missing
file, and links in both chats and run activity. Shared origin validation tests pass.
No backend change or database migration is required.

## Previous validation: Android 0.5.3

## Touch and follow behavior

Chat and run activity use `HistoryFollowGesture` and `FollowHistoryTail`. The list's
normal touch slop determines when a movement starts; no extra unpin threshold is
added. Pointer contact pauses following before the first scroll delta. Only real
user scrolling toward older content disables following. Reaching the end through
user scrolling enables it; layout growth and programmatic scrolling cannot.

The shared `HistoryFollowCases` runs with Robolectric and Android instrumentation.
It checks actual Compose pointer input with native Markdown, not just calls into
the gesture state machine: bottom padding, downward overscroll, a held finger while
text grows, small upward-history movement during growth, manual return with a fling,
direction reversal, stationary contact, viewport resizing and an oversized final
message. The local suite passed all 63 tests (including the five gesture cases); lint
reported zero errors, and debug, test and optimized release APKs built successfully.
The local Android 16 software emulator exceeded its ten-minute boot timeout before
application installation. The same gesture cases can be run on a connected Android device with
`connectedDebugAndroidTest`; the instrumentation test also saves a screenshot in
`files/scroll-validation/pinned-stream.png` within the app's private test data.

## Retained streaming optimization (0.5.2)

- Assistant presentation IDs remain stable across delta updates, cache serialization
  and reconnects. New turns isolate reused provider item IDs. Wire IDs/cursors still
  advance normally.
- The native Markdown views reuse unchanged groups of eight top-level AST nodes.
  Full-document parsing and changed-group rendering run on a background dispatcher.
  Reference definitions added or edited later invalidate earlier affected links.
  No raw Markdown is split at arbitrary character or newline boundaries.
- Completed source snapshots coalesce while rendering; every wire delta is applied.
  A deliberately stalled display test receives all 100 fragments in the final answer.
- Native Compose tests check stable TextView/text identities during a burst, final
  text delivery, corrected/empty documents, asynchronous bottom following, pausing
  follow to read earlier text, and restoring a 400-pixel reading offset after initial
  Markdown measurement. Existing cache/pagination and foreground-resume regressions
  are included in the Android suite.
- Plain tool output bypasses JSON parsing. Activity groups accumulate linearly
  instead of repeatedly copying their entire prefix.

## Measurements

`StreamingCostTest` compares the previous full-text view/renderer recreation path
with the new reusable-block renderer on the same synthetic inputs. It takes 15
samples after three warmup updates. These are component timings on a 2-CPU Linux VM
under Robolectric native graphics, **not phone frame rates or end-to-end latency**.
Both modes use the updated accumulator/timeline; only rendering uses the old path
in the baseline. There are no timing thresholds in the test.

Local medians in milliseconds per update:

| Initial characters | Prior events | Old Markdown/bind | Old text layout | New parse/render (background) | New bind + text layout |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 2,000 | 80 | 8.30 | 60.60 | 1.17 | 2.49 |
| 20,000 | 80 | 29.69 | 110.88 | 7.43 | 5.74 |
| 100,000 | 80 | 336.78 | 1000.78 | 16.86 | 5.83 |
| 100,000 | 2,000 | 322.80 | 878.69 | 16.05 | 3.37 |

For the 100,000-character / 80-event case, the old text-layout median is about
1,001 ms versus 5.83 ms for the new binding-plus-layout measurement. Background
parse/render takes 16.86 ms. These columns do not measure identical components:
the new UI column includes binding as well as layout, while the old binding is
included in its Markdown column. Medians must not be summed into an asserted
end-to-end median. Renderer/view construction is also recorded separately.

Each benchmark update calls bind/measure on all retained views, including unchanged
ones. The new view binding skips setText when its block object is unchanged. The
benchmark excludes initial cold rendering, Compose frame overhead, the network,
provider and production proxy. It retains the old cache timing fixture, whose JSON
and disk I/O are real but whose codec excludes hardware Keystore encryption.

An old diagnostic measured roughly 122 ms to build a timeline with 2,000 plain
output events; the optimized implementation measured 0.45 ms in this run. This
cross-run observation is indicative rather than a controlled old/new comparison.

## Commands and delivery

```sh
mise exec java@temurin-21 -- android/gradlew -p android --no-daemon testDebugUnitTest lintDebug assembleDebug
mise exec java@temurin-21 -- android/gradlew -p android --no-daemon assembleRelease
```

The GitHub Android workflow runs the complete suite, lint, debug and optimized
release builds on the published commit. See that commit's checks for final CI
results. Local screenshots, when enabled with `-PleoScreenshotsDir=...`, use
Robolectric native graphics. They are not emulator or physical-device evidence.

Package `dev.leo.manager`; minSdk 26; targetSdk 37; version **0.5.3 / code 10**.
The development certificate is the same as the preceding APKs, permitting an
in-place update. Certificate SHA-256:
`b56038aa26883922efdf917ff45300b9d5a6f59cc7901c5e4097664a2abbbf99`.

## Limits and preserved cache behavior

A single very large paragraph, fenced code block or table remains one AST node and
can still take longer to lay out. Initial opening lays out the visible message's
groups; the benchmark concerns subsequent appends. Selection is native within a
group, with **Copier tout le texte** in the selection toolbar for the full Markdown
source. Device-level frame timing, keyboard transitions and TalkBack remain to be
checked on an Android device.

The 0.5.1 cache fix is retained: up to 200 recent events within a 3 MiB payload
budget, backwards pages for older events, and separate backwards/live cursors.
Server history is preserved. Android encrypts its snapshots; cache isolation,
logout invalidation and generation checks remain. Limits are 12 histories,
4 MiB per encoded record, 20 MiB total and seven days. Artifact files load on demand.

This change is Android-only. It requires no new backend API or web-client change.
The Markdown integration uses Markwon's separate parse/render/setParsedMarkdown
stages, as described in its [plugin lifecycle documentation](https://noties.io/Markwon/docs/v4/core/plugins.html).
