# Prepared VM delivery — 2026-09-12

Version 0.17.0 implements the anonymous prepared-VM strategy investigated in
[the comparative benchmark](STARTUP-STRATEGIES-BENCHMARK.md) and
[the authenticated follow-up](AUTHENTICATED-STARTUP-BENCHMARK.md).

One spare is prepared and paused within the existing four-slot budget. Its disk
is assigned exactly once. Every attempt starts a fresh Codex process with current
credentials and MCP grants. Used VMs are destroyed; existing conversations retain
their disks and boot cleanly. Retaining used memory would preserve background
processes and sticky guest permissions, so it is outside this release.

The manager wakes on committed work instead of waiting for the next one-second
poll. Valid token reads avoid the quota-monitor lock, while refresh and account
selection retain their serialization. Fresh account-specific model capabilities
supply consistent defaults; missing or stale capabilities use existing discovery.
Binary archive imports from the initial investigation are included.

## Review and validation

Two independent peers reviewed manager concurrency and VM lifecycle in successive
passes. Follow-up fixes covered abandoned reservations, preserving a spare while
reopening another disk, preparation cancellation/backoff, dead-spare fallback,
clock synchronization, deployment wake ordering, and keeping cancellation/health
responsive during reservation waits. VM-to-attempt metadata preserves boot-log
traceability. Existing startup cleanup already fences stale jails before deleting
unassigned disks.

Local validation on the final implementation:

- 61 Rust tests, workspace `cargo check`, `cargo clippy -D warnings`, and rustfmt.
- 180 application tests, ESLint, TypeScript checking and frontend production build.
- Nine real Firecracker smoke scenarios: initial execution after a 31-second
  pause, disk resume, cancellation, controller crash, recovery, simultaneous
  read-only/workspace-write policies, read-only resume, and pool saturation with
  cancellation/refill. The last scenario also kills the spare and verifies cold
  fallback, rejects a duplicate disk owner and rejects a fifth active execution.
- Existing smoke coverage also exercises on-demand project imports/reopening,
  account token relay, live inbox steering, Docker, Compose and cached containers.

The controller test image embeds the same compiled Rust binary in both host and
guest. It uses a disposable controller with independent storage and networking;
production services are not restarted by these local tests.

## Performance interpretation

The earlier measurements established that anonymous VM preparation can remove
boot and toolkit initialization from the request path. Those microbenchmarks are
not the latency of this integrated release. A full 500 ms startup is not promised:
account selection may wait for due usage checks, configured MCPs and connected
apps still initialize, and first generated text also includes provider inference.
The release pipeline and deployed runtime must be verified independently.
