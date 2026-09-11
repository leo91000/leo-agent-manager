# Agents, task scope, and execution

Tasks choose an agent. **Main agent** is created automatically and includes all
registered projects, available skills, and shared connections, including future
additions. Its model, instructions, timeout, and execution mode are editable;
its resource access cannot be reduced and it cannot be deleted.

Other agents can select projects, skills, MCP connections and tools, and disable the shared GitHub
connection. New agents default to full access and YOLO. A task inherits its
agent's resources, or can narrow its project context and skills under **Customize
task scope**. Task overrides cannot expand agent access. A task needs no project;
it can work in a scratch workspace. Runs snapshot the effective resources when
queued. Changing an agent's access prevents its queued runs from executing under
an outdated policy; enqueue again to use the new policy. Running work keeps its
snapshot: cancel it before changing permissions when immediate revocation matters.

Existing tasks retain their project, explicit skills, schedules, and history.
Existing agents retain unrestricted YOLO behavior. Database schema 3 is required;
back up before upgrading from 0.1.x. Restore the matching schema-2 backup as well as
the old image if rolling back.

## Execution modes

Every production run, including Main, executes in a Firecracker microVM. Resource
scope and Codex sandbox policy are independent of VM isolation.

| Mode | Project writes | Guest privileges |
| --- | --- | --- |
| YOLO (default) | Allowed in private copies | User 1000 with passwordless sudo; Docker available |
| Workspace write | Codex writable roots | User 1000 without sudo; Codex sandbox |
| Read only | Read-only workspace mounts | User 1000 without sudo; Codex sandbox |

The runner fails closed if KVM, the guest image, or isolation setup is unavailable.
Local development without RUNNER_URL retains subprocess execution; production
workers require RUNNER_URL.

Projects are independent Git clones inside the run's private disk. Agents publish
changes through Git rather than editing the manager's checkout. Existing
worktree=false tasks also use private copies in production. The same disk is
retained for subsequent chat turns, account handoffs and restart recovery.
Historical conversations import their saved files and session history once.

Only selected skill resources are copied; symbolic links in skills are rejected.
Project Codex configuration and skill discovery directories are masked. Configured
MCPs use the existing scoped gateway. Project contents may themselves contain
secrets; project selection does not remove credentials already stored there.

Account refresh tokens stay in the manager. A run-specific vsock relay exposes
only the selected account's access-token exchange. Chat steering, answers and
attachments are delivered to the guest while the agent runs. They do not require
host filesystem mounts or expose the manager's Docker socket.

## GitHub connections

The shared GitHub connection requires access to all projects. Agents restricted
to selected projects can save a dedicated token in their editor. Create a
fine-grained token on GitHub with only the intended repositories and permissions;
remote authorization is determined by that token. Selecting projects here does
not reduce the token's GitHub permissions. Tokens are stored in the private
application database, never returned by the read API, and copied only to that
agent's run home. Removing the agent removes its stored token.

## Runner deployment

Use both services in compose.yaml with the same immutable LEO_IMAGE. The runner
needs Linux x86-64, hardware virtualization, /dev/kvm and /dev/net/tun. It owns
persistent runner-state storage and launches Firecracker through its jailer.
Its infrastructure capabilities stay outside the guest. The agent never receives
the runner credential, host devices or a host Docker socket.

Do not publish runner port 4311. The manager authenticates requests with the
private runner-secret file. Guest Internet access permits TCP 80/443 and DNS;
private networks, metadata endpoints, neighboring VMs and runner services are
blocked. Remote Git operations therefore use HTTPS.

See [MicroVM architecture and operations](MICROVMS.md) for storage, upgrades,
recovery, host prerequisites and validation.
