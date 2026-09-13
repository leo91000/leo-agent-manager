# Run preparation and delivery outcomes

New Git workspaces default to the configured **remote branch**. The manager
clones directly from that remote into private storage and records the starting
commit in `workspaces[].revision`. It does not fetch, reset or modify the
registered checkout. A failed remote fetch fails preparation; it never silently
falls back to an old local branch.

Projects can explicitly choose **Local branch snapshot** in the project editor.
This uses committed files from the local base branch, preserving local edits in
the registered checkout. Repositories without an origin use this local behavior.
Partial clones retain their real promisor remote before checkout so missing blobs
can be fetched into the private clone. Direct execution without a worktree keeps
its existing behavior. Reopened projects and resumed conversations reuse their
private files; freshness only applies when materializing a new project seed.

## GitHub permissions

GitHub sign-in requests `workflow` in addition to the normal CLI scopes. The
Connections page checks the response's OAuth scopes and offers **Enable workflow
updates** when that scope is missing. An absent scopes header is **unknown**, not
proof that a fine-grained token can modify workflows. Repository rules and branch
protection still apply.

Changing the shared GitHub connection requires idle workers. The existing worker
lease fences scheduling while sign-in runs and is released on completion,
cancellation or CLI failure. New work can queue during sign-in. GitHub itself must
approve the expanded OAuth scope; updating the image alone cannot grant it.

## Android in an agent VM

The base includes Temurin JDK 21 through mise. Automatic toolkit refreshes update
stable patch/build releases without downgrading; project runtime pins take
precedence. The SDK and emulator are installed **only on demand**:

```sh
leo-android setup --accept-licenses 'platforms;android-34' 'build-tools;34.0.0'
./gradlew test
leo-android emulator start 34 --accept-licenses
adb -s emulator-5580 install --no-incremental -r app/build/outputs/apk/debug/app-debug.apk
./gradlew connectedAndroidTest
leo-android emulator stop
```

Select API/build-tools versions from the project. `--accept-licenses` explicitly
accepts Android SDK package licenses; without it the CLI can prompt interactively.
The command-line tools download is pinned and SHA-256 checked. Concurrent bootstrap
calls use a kernel lock; downloads stage on persistent storage and failed downloads
are removed. Existing SDK installations are reused.

Persistent defaults inside each run:

| Data | Location |
| --- | --- |
| SDK (`ANDROID_HOME`) | `$HOME/.local/share/android/sdk` |
| Device state (`ANDROID_USER_HOME`) | `$HOME/.android` |
| Gradle (`GRADLE_USER_HOME`) | `$HOME/.gradle` |

`leo-android status` describes SDK/KVM availability; `leo-android emulator status`
reports device readiness. Emulator metadata uses both Linux boot ID and process
start time so a stale PID from an earlier VM cannot be treated as a live device.
One managed emulator runs at a time in each VM; other runs have separate disks,
ADB servers and devices. Device state persists, but emulator processes stop with
the VM and must be started again on a later turn.
For deterministic software-emulator tests, use `adb install --no-incremental`,
disable UI animations, unlock the device and use bounded readiness retries.
Software cold boots may also display a System UI ANR dialog. The fixture waits
at most twice for that exact Android system dialog; an application ANR still
fails validation. This environment is suitable for functional checks, not Android
performance measurements.

Firecracker guests currently do **not** expose KVM. The emulator uses software
CPU emulation there, and KVM when the same command is run on a suitable development
host. No host Docker socket, host ADB server, nested KVM or extra guest privilege
is granted. Software device startup takes minutes and consumes the run's existing
CPU/RAM budget. Stop the emulator before memory-heavy builds when necessary.
JVM/Robolectric tests remain useful, but are not device tests.

Managed devices use a 720 × 1280 display at 320 dpi, 1.5 GiB of Android RAM and
software graphics with Vulkan disabled. The initial Pixel 6 graphics defaults
exhausted the standard 4 GiB guest during validation; the smaller display keeps
the existing VM resource allocation. This does not reserve memory for a concurrent
large Gradle build, and applications requiring Vulkan need a different device.

## Execution versus outcome

`Run.status` remains the technical lifecycle used by scheduling and recovery.
A zero exit code is displayed as **Execution finished**. It does not certify that
a requested push, merge or release happened.

The built-in, run-scoped MCP exposes `report_outcome`:

```json
{
  "status": "blocked",
  "reason": "GitHub refused the workflow update.",
  "evidence": ["42 tests passed", "Push refused: workflow scope missing"]
}
```

Statuses are `completed`, `blocked`, and `needs_input`. This is an explicit agent
report, not an inference from logs or independently verified delivery. Historical
runs without a report remain unreported. Chats clear the previous outcome when a
new message starts; explicit resume clears it too. Expired grants and grants for
an earlier message cannot change the new turn. A blocked/input-required outcome
places a technically successful run under **Needs attention**.

## Validation

- Rust integration tests cover stale/dirty sources, actual missing promisor blobs,
  unavailable remotes, scoped outcomes, earlier-message rejection, workflow scope
  parsing and release of the sign-in fence.
- Browser tests cover saving/reloading source selection, workflow permission UX,
  blocked outcomes, and mobile/light/dark layouts.
- `node tests/container-smoke.mjs IMAGE` verifies the actual runtime/toolkit.
- `node tests/android-runner-smoke.mjs IMAGE` is an explicit opt-in integration
  test: a real APK is compiled, installed and tapped on a software Android emulator
  inside Firecracker, then its device/SDK state is checked after VM restart.
  It uses disposable state under `/var/tmp` (or `VM_TEST_ROOT`); do not use a small
  tmpfs for VM disks. It downloads several GiB and is kept out of the fast CI path.

Local validation on 2026-09-13: 193 JavaScript tests, 80 Rust tests, lint, TypeScript
and production build, Clippy, the focused browser review, container/toolkit tests
and Firecracker lifecycle/isolation tests passed. Browser fixtures and captures
are in [screenshots/run-readiness](screenshots/run-readiness/); they use test data.
No production deployment or GitHub scope grant is implied by these checks.

Android API 34 measurements on a local 2-vCPU/4-GiB Firecracker guest:

| Measurement | Observed time |
| --- | --- |
| First SDK/system-image installation plus Android boot | 7 min 36 s |
| Subsequent setup/boot with installed SDK and device data | 2 min 11–22 s |
| Successful compile/install/tap/capture/stop pass, SDK already installed | 6 min 25 s |
| VM restart, unchanged SDK, saved app state, capture and emulator stop | 3 min 21 s |

The functional pass used one bounded System UI wait and retried UI hierarchy
reads. [The resulting screenshot](screenshots/run-readiness/android-first.png)
shows the fixture's confirmed state after the ADB tap.
[The restart screenshot](screenshots/run-readiness/android-resume.png) confirms
the same state without reinstalling the APK; the probe also checked that the
Linux boot ID changed and the SDK installation timestamp did not. Both passes
completed successfully. These are local
measurements, not VPS timings or guarantees for other apps/API levels.

One separate diagnostic was observed at fixture teardown: the existing 6.12.109
guest kernel can print `Missing ENDBR` in the forced-reboot path after the guest
has synced its disk. The VM controller still terminates it, and lifecycle tests
pass; this is not the Android assertion failure. Kernel shutdown deserves a
separate investigation. Replacing reboot with poweroff blindly is inappropriate:
on x86, [Firecracker documents that poweroff leaves the VMM process alive](https://github.com/firecracker-microvm/firecracker/blob/main/FAQ.md#how-can-i-gracefully-reboot-the-guest-how-can-i-gracefully-poweroff-the-guest).

References: [GitHub CLI login](https://cli.github.com/manual/gh_auth_login),
[mise Java](https://mise.jdx.dev/lang/java.html),
[Android tools](https://developer.android.com/studio),
[Android environment variables](https://developer.android.com/tools/variables),
[ADB](https://developer.android.com/tools/adb).
