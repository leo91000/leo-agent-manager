# MicroVM execution

The manager schedules work and owns account refresh credentials. The runner owns
execution attempts. Every production agent, including Main, executes in a private
Firecracker VM. YOLO remains the default inside the guest.

## Interface and implementation

The authenticated runner interface is start, output stream, wait and stop, keyed
by an attempt UUID. A separate run UUID identifies persistent storage. Chats reuse
that run across turns. The manager saves the attempt before starting it and fences
the previous attempt before reusing the disk or account lease.

`runner.rs` implements that interface and durable attempt outcomes. `microvm/host.rs`
hides disks, jailer, network rules, vsock, imports and teardown. `microvm/guest.rs`
bridges the existing Rust chat/task process inside the VM. `microvm/wire.rs` bounds
protocol messages and rejects truncated frames. Output reads retain partial frames
while live inbox updates are delivered.

Each VM boots a pinned kernel and a read-only root image. A sparse 32 GiB ext4
private disk supplies the writable overlay, repositories, home, sessions, installed
tools and Docker cache. Docker data is mounted directly from that disk. The daemon
starts only when the `docker` command first needs it. VM recreation retains the disk;
RAM snapshots are not part of correctness or recovery.

The controller never mounts a guest-modified filesystem. Initial archives travel
into the guest over vsock; only a bounded result file comes back to a predetermined
manager path. Guest edits never overwrite the host checkout. The UI retains the
conversation/run needed to resume and inspect saved work. Disks are retained,
including on failure or cancellation; back them up and account for their storage.
Never remove a retained disk if its uncommitted work is still needed.

## Authentication and live chat

The guest has a local Unix authentication socket backed by a per-VM vsock relay.
That relay connects only to the manager socket for the current run/account lease.
The manager serializes refresh rotation across concurrent uses of an account.
Account IDs, refresh tokens, the runner credential and the host Docker socket are
not supplied through this relay. Managed authentication files are excluded from
home imports. GitHub credentials remain scoped according to the agent policy.

The manager remains authoritative for queued messages, answers and attachments.
Changed inbox contents are transferred while output continues streaming. The guest
inbox is readable by the agent and written by the guest supervisor. Codex session
IDs are persisted in manager events; `thread/resume` verifies the retained session
inside the replacement guest.

## Host and resource constraints

Production currently supports Linux x86-64 with working KVM. The Compose controller
has explicit infrastructure capabilities, access to KVM/TUN and no Docker socket.
Firecracker runs through jailer as an unprivileged UID, with its default seccomp
filters. The controller's AppArmor/seccomp allowances are needed for jailer mount
namespaces and privilege setup; they are not granted to agents on the host.

There are four execution slots, with 2 vCPUs and 4 GiB guest RAM each. The controller
container is capped at 8 CPUs, 20 GiB RAM and 256 host processes. Guest process counts
are not host process counts. Disk ownership is protected by an exclusive file lock;
one writable disk cannot be opened by two attempts. Console and execution output
are bounded independently of the guest disk.

Per-guest firewall rules permit outgoing web traffic (TCP 80/443) and DNS (UDP 53).
They reject private/reserved destinations and access to the controller itself;
new inbound connections are not forwarded into guests. This allows the public
manager MCP gateway and Git HTTPS. Additional outbound protocols require an
explicit network-policy change. Firecracker does not remove the need to patch host
kernel, firmware, guest kernel and the VMM; follow upstream production guidance.

## Builds, upgrades and recovery

The Dockerfile builds Linux 6.12.109 from its checksum-pinned kernel.org source with
`deploy/microvm/kernel.config`. Firecracker and jailer 1.17.0 are checksum pinned.
The guest root uses the same Rust binary and development tools as the manager.
CLI-update images rebuild the embedded guest root as well. Tag deployment migrates
Coolify's stored Compose configuration, preserving generated network names and
labels. `/health` only succeeds once the Firecracker runner is ready.

Before the first upgrade, back up application volumes and saved workspaces and
wait for active work to finish. Retain the previous Compose document with its image
for rollback: an old container-runner image needs its old Docker-socket setup.
After agents have made changes in VM disks, a rollback must preserve those disks;
old releases cannot interpret their guest storage as host worktrees.

Controller restarts fence its container PID namespace. Interrupted attempts are
recorded as exited; the manager recreates VMs against their retained disks. Graceful
stop requests guest shutdown and sync; forced stops rely on ext4 journal recovery.
No task is allowed to silently switch to host execution when VM setup fails.

## Validation

Run `cargo test --workspace`, `cargo clippy --workspace --all-targets -- -D warnings`
and `pnpm check`. The real KVM test runs with `node tests/runner-smoke.mjs IMAGE` on
the Docker host. It uses synthetic credentials and temporary storage to exercise
kernel isolation, the authentication relay, live steering, Docker and disk reuse.
Use `VM_TEST_ROOT=/var/tmp` when `/tmp` is a small memory filesystem.

The September 11 hardware check on the production VPS completed the first probe,
including downloading and running BusyBox, in 9.8 seconds. Recreating the VM with
its saved disk and Docker image took 4.0 seconds. Cancellation, forced controller
restart and recovery also passed. These are complete probe durations, not bare
Firecracker boot times. Concurrent read-only and workspace-write guests are also
covered by the smoke test; neither restricted mode receives sudo access.

Sources: [Firecracker production setup](https://github.com/firecracker-microvm/firecracker/blob/main/docs/prod-host-setup.md),
[jailer](https://github.com/firecracker-microvm/firecracker/blob/main/docs/jailer.md),
[vsock transport](https://github.com/firecracker-microvm/firecracker/blob/main/docs/vsock.md),
[Linux source](https://cdn.kernel.org/pub/linux/kernel/v6.x/linux-6.12.109.tar.xz).
