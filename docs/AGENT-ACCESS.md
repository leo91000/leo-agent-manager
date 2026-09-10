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

| Mode | Project writes | Codex approvals | Runtime |
| --- | --- | --- | --- |
| YOLO (default) | Allowed | Bypassed | Shared manager when unrestricted; disposable container when restricted |
| Workspace write | Allowed in task workspaces | Never escalated | Disposable container plus Codex sandbox |
| Read only | Denied by read-only project mounts | Never escalated | Disposable container plus Codex sandbox |

Restricted execution requires the runner. Missing or failing isolation is an error;
it never falls back to shared execution. Each restricted container runs as UID 1000,
with a read-only image filesystem, all capabilities dropped, no privileged mode,
and no Docker socket. It mounts only its project workspaces, scratch/output folders,
a fresh home, and its own execution plan. Network access remains available; this is
not network egress filtering. The Docker host and manager are trusted administration
components, not a multi-tenant service.

When worktrees are enabled, restricted runs get independent Git clones with no
shared worktree metadata. Unrestricted runs retain Git worktrees. Multiple projects
get separate directories, listed by name and path in the task instructions. The
scheduler locks every effective project for the duration of the run. Isolated
clones are retained for manual review and removal through the server terminal;
the manager does not run cleanup Git commands against their untrusted Git config. Without
worktrees, an allowed project's mounted files are shared with its checkout.

Only selected skill directories and supporting files are copied. Symbolic links
in skill resources are rejected. Project Codex configuration and skill discovery
directories are masked; shared home configuration, unmanaged MCP connections, SSH keys, and
unselected global skills are not copied. Skill selection controls supplied
instructions, not the ability to write equivalent code. Secrets already committed
or stored inside an allowed project remain accessible with that project.

MCP connections configured in the UI are supplied separately according to the
agent's allowlist. Remote credentials stay in the manager; isolated runs receive
a temporary gateway credential. See [MCP connections](MCP-CONNECTIONS.md) for
tool permissions and revocation behavior.

A temporary copy of the shared ChatGPT subscription login authenticates Codex.
Per-run home and plan files are removed when execution finishes; results and project
changes remain available. Other tools installed only in the shared home are not
available to restricted agents; install required toolchains into a derived image.

## GitHub connections

The shared GitHub connection requires access to all projects. Agents restricted
to selected projects can save a dedicated token in their editor. Create a
fine-grained token on GitHub with only the intended repositories and permissions;
remote authorization is determined by that token. Selecting projects here does
not reduce the token's GitHub permissions. Tokens are stored in the private
application database, never returned by the read API, and copied only to that
agent's run home. Removing the agent removes its stored token.

## Runner deployment

Use the two services in `compose.yaml`. Both must use the same `LEO_IMAGE`. Set
`RUNNER_URL=http://runner:4311` on the manager and set
`RUNNER_MANAGER_CONTAINER` on the runner to the actual manager container name
(`leo-manager` in local Compose; the generated manager name in Coolify).
The runner needs the same data volume mounted read-only and the Docker socket.
Do not publish runner port 4311 or give agent containers access to the socket.
The manager creates a private shared authentication file in the data volume.

On AppArmor hosts (including Ubuntu), install the included profile on the host:

```sh
sudo install -m 644 deploy/leo-runner.apparmor /etc/apparmor.d/leo-agent-sandbox
sudo apparmor_parser -r /etc/apparmor.d/leo-agent-sandbox
```

Set `RUNNER_APPARMOR_PROFILE=leo-agent-sandbox` on the runner. The profile permits
unprivileged user namespaces and the mount operations needed by Codex's inner
sandbox while retaining Docker's sensitive `/proc` and `/sys` restrictions.
Non-YOLO containers use `server/runner-seccomp.json`, derived from the Moby default
profile at commit `61eaf32614c7c71b60bd8927d3e6a4ffc8ff1f31`, with the namespace
syscalls needed by that sandbox. Its Apache-2.0 license is included alongside it.
No host-wide sandbox restrictions are disabled. YOLO containers use Docker's
default security profiles.

Containers are removed after success, failure, or cancellation. Broker lease expiry
also removes orphaned containers after the configured task timeout. Run the real
container checks after changing the image, host security policy, or runner:

```sh
node tests/container-smoke.mjs IMAGE
node tests/runner-smoke.mjs IMAGE
```

These checks cover broker authentication, filesystem/credential isolation,
read-only mounts, real Codex sandbox write denial, and cancellation. The runner
smoke installs the AppArmor profile when the Docker host reports AppArmor support.
