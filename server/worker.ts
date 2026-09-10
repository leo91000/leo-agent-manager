import type { ChildProcess } from 'node:child_process'
import type { Run } from '../shared/contracts.ts'
import type { Service } from './service.ts'
import { Buffer } from 'node:buffer'
import { execFile, spawn } from 'node:child_process'
import { constants } from 'node:fs'
import { mkdir, open, rm, writeFile } from 'node:fs/promises'
import path from 'node:path'
import process from 'node:process'
import { promisify } from 'node:util'
import { AppError, requireValue } from './errors.ts'
import { prepareExecution, runnerSecret } from './execution.ts'
import { maintenanceActive } from './maintenance.ts'
import { policy, runProjects } from './policy.ts'
import { toolkitEnvironment } from './toolkit.ts'

const exec = promisify(execFile)
async function readSummary(file: string) {
  const handle = await open(file, constants.O_RDONLY | constants.O_NOFOLLOW).catch(() => undefined)
  if (!handle)
    return ''
  try {
    const buffer = Buffer.alloc(100000)
    const { bytesRead } = await handle.read(buffer, 0, buffer.length, 0)
    return buffer.subarray(0, bytesRead).toString('utf8')
  }
  finally {
    await handle.close()
  }
}
export function redact(text: string) {
  return text
    .replace(
      /\b(?:gh[pousr]_\w{15,}|github_pat_\w{15,}|sk-[\w-]{12,})\b/g,
      '[redacted]',
    )
    .replace(/(Bearer\s+)[\w.~-]+/gi, '$1[redacted]')
    .replace(
      /("?(?:access_token|refresh_token|id_token|OPENAI_API_KEY|CODEX_API_KEY)"?\s*[:=]\s*"?)[^"\s,}]+/gi,
      '$1[redacted]',
    )
}
export function redactPayload(value: unknown): unknown {
  if (typeof value === 'string')
    return redact(value)
  if (Array.isArray(value))
    return value.map(redactPayload)
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).map(([key, item]) => [
      key,
      /^(?:access_token|refresh_token|id_token|OPENAI_API_KEY|CODEX_API_KEY)$/i.test(key) ? '[redacted]' : redactPayload(item),
    ]))
  }
  return value
}
export function codexArgs(run: Run, output: string) {
  const a = run.snapshot.agent
  return [
    ...(policy(a).sandbox === 'yolo' ? ['--dangerously-bypass-approvals-and-sandbox'] : ['--sandbox', policy(a).sandbox, '-a', 'never']),
    '-c',
    'forced_login_method="chatgpt"',
    'exec',
    '--json',
    '--skip-git-repo-check',
    '--color',
    'never',
    '-c',
    `model_reasoning_effort=${JSON.stringify(a.reasoning)}`,
    ...(a.model ? ['--model', a.model] : []),
    ...(policy(a).sandbox === 'workspace-write' ? ['-c', 'sandbox_workspace_write.network_access=true'] : []),
    ...(policy(a).sandbox === 'workspace-write' ? [path.dirname(output), ...run.workspaces?.map(workspace => workspace.path) ?? []].flatMap(directory => ['--add-dir', directory]) : []),
    '--output-last-message',
    output,
    '-',
  ]
}
export function runPrompt(run: Run) {
  const projects = runProjects(run).map(project => `- ${project.name}: ${run.workspaces?.find(workspace => workspace.projectId === project.id)?.path ?? project.path}`).join('\n')
  return `${run.snapshot.agent.instructions}\n\n${run.snapshot.task.prompt}\n\nAvailable project workspaces (choose the relevant projects for this task):\n${projects || 'No projects assigned; use the task workspace.'}\n\nTooling: mise manages project runtimes and global tools. Prefer rg and fd for search. Respect mise.toml, .tool-versions, .nvmrc, .node-version, .python-version, rust-toolchain.toml, and package.json packageManager pins. Use mise exec -- <command> when project environment variables are needed; use uv for Python environments. Do not upgrade project pins unless the task requests it.\n\nSelected skills (use their supporting resources from the supplied paths):\n${run.snapshot.skills.map(s => `\n${s.path}\n${s.content}`).join('\n')}\n\nRun this task to completion within its stated scope. Preserve unrelated files. Do not expose credentials. This unattended task cannot answer clarification questions; report a concrete blocker if required information is missing. All task-authorized effects such as creating PRs or releasing must follow their checks. Use .agents/skills for skills. Summarize actual changes, validation, external links and remaining blockers at the end.`
}
export class Worker {
  executions = new Set<Promise<void>>()
  active = new Map<
    string,
    { child: ChildProcess | null, cancelled: boolean, timedOut: boolean }
  >()

  timer: NodeJS.Timeout | undefined
  busy = false
  closing = false
  lastMaintenance = 0
  constructor(public service: Service) {}
  start() {
    for (const run of this.service.store.active()) {
      if (run.status === 'running') {
        this.service.store.updateRun(run.id, {
          status: 'interrupted',
          finishedAt: Date.now(),
          summary:
            'The worker restarted. Review external effects before retrying.',
        })
        this.service.store.event(
          run.id,
          'status',
          'Interrupted by worker restart',
        )
      }
    }
    this.timer = setInterval(() => void this.tick(), 1000)
    this.timer.unref()
    void this.tick()
  }

  async tick() {
    if (this.busy || this.closing)
      return
    this.busy = true
    try {
      if (Date.now() - this.lastMaintenance > 3600000) {
        this.service.store.maintain()
        this.lastMaintenance = Date.now()
      }
      await this.service.schedule()
      if (this.closing || maintenanceActive(this.service.store))
        return
      const projects = new Set(
        [...this.active.keys()].map(
          id => runProjects(this.service.store.run(id)!).map(project => project.id),
        ).flat(),
      )
      for (const run of this.service.store.active()) {
        if (this.active.size >= this.service.config.concurrency)
          break
        if (run.status !== 'queued' || runProjects(run).some(project => projects.has(project.id)))
          continue
        for (const project of runProjects(run))
          projects.add(project.id)
        this.active.set(run.id, {
          child: null,
          cancelled: false,
          timedOut: false,
        })
        const execution = this.execute(run)
        this.executions.add(execution)
        void execution.finally(() => this.executions.delete(execution))
      }
    }
    catch (e) {
      this.service.store.audit('worker.error', {
        message: (e as Error).message,
      })
    }
    finally {
      this.busy = false
    }
  }

  async execute(run: Run) {
    const { store, config } = this.service
    const control = this.active.get(run.id)!
    let timeout: NodeJS.Timeout | undefined
    try {
      store.updateRun(run.id, { status: 'running', startedAt: Date.now() })
      store.event(run.id, 'status', 'Preparing workspace')
      const directory = path.join(config.dataDir, 'runs', run.id)
      await mkdir(directory, { recursive: true, mode: 0o700 })
      const currentAgent = requireValue(store.get('agents', run.snapshot.agent.id))
      if (JSON.stringify(policy(currentAgent)) !== JSON.stringify(policy(run.snapshot.agent)))
        throw new AppError(409, 'Agent access changed after this run was queued. Run the task again with the current policy.')
      const prepared = await prepareExecution(run, config, store.kv<string>(`agent-github:${run.snapshot.agent.id}`))
      Object.assign(run, { workspace: prepared.cwd, workspaces: prepared.workspaces, isolated: prepared.isolated })
      store.updateRun(run.id, { workspace: run.workspace, workspaces: run.workspaces, isolated: run.isolated })
      if (control.cancelled)
        throw new Error('Cancelled before execution')
      const output = prepared.output
      const env = prepared.isolated ? { ...process.env } : await toolkitEnvironment(config.home)
      env.HOME = config.home
      env.CODEX_HOME = path.join(config.home, '.codex')
      delete env.OPENAI_API_KEY
      delete env.CODEX_API_KEY
      delete env.CODEX_THREAD_ID
      let binary = config.codexBin
      let args = codexArgs(run, output)
      if (prepared.isolated) {
        const plans = path.join(config.dataDir, 'runner-plans')
        await mkdir(plans, { recursive: true, mode: 0o700 })
        await writeFile(path.join(plans, `${run.id}.json`), JSON.stringify({ id: run.id, args, cwd: prepared.cwd, prompt: runPrompt({ ...run, snapshot: { ...run.snapshot, skills: prepared.skills } }), mounts: prepared.mounts, expires: Date.now() + run.snapshot.agent.timeoutMinutes * 60000, sandbox: policy(run.snapshot.agent).sandbox }), { mode: 0o600 })
        env.RUNNER_URL = config.runnerUrl
        env.RUNNER_TOKEN = await runnerSecret(config.dataDir)
        binary = process.execPath
        args = ['--import', import.meta.resolve('tsx'), path.join(import.meta.dirname, 'runner-client.ts'), run.id]
      }
      const child = spawn(binary, args, {
        cwd: prepared.cwd,
        env,
        stdio: ['pipe', 'pipe', 'pipe'],
        detached: process.platform !== 'win32',
      })
      control.child = child
      timeout = setTimeout(() => {
        control.timedOut = true
        this.kill(child)
      }, run.snapshot.agent.timeoutMinutes * 60000)
      let buffer = ''
      let total = 0
      const max = 5_000_000
      const line = (raw: string) => {
        if (total >= max)
          return
        total += raw.length
        try {
          const event = JSON.parse(raw)
          if (
            event.type === 'thread.started'
            && typeof event.thread_id === 'string'
          ) {
            store.updateRun(run.id, { sessionId: event.thread_id })
          }
          if (event.type === 'turn.completed' && event.usage)
            store.updateRun(run.id, { usage: event.usage })
          const text
            = event.item?.text
              ?? event.item?.aggregated_output
              ?? event.error?.message
              ?? raw
          store.event(
            run.id,
            event.type ?? 'output',
            redact(typeof text === 'string' ? text : JSON.stringify(text)),
            redactPayload(event) as Record<string, unknown>,
          )
        }
        catch {
          store.event(run.id, 'output', redact(raw))
        }
      }
      child.stdout?.on('data', (chunk) => {
        buffer += chunk.toString()
        let split = buffer.indexOf('\n')
        while (split >= 0) {
          line(buffer.slice(0, Math.min(split, 64000)))
          buffer = buffer.slice(split + 1)
          split = buffer.indexOf('\n')
        }
        if (buffer.length > 64000) {
          line(buffer.slice(0, 64000))
          buffer = ''
        }
      })
      child.stderr?.on('data', (chunk) => {
        if (total >= max)
          return
        total += chunk.length
        store.event(run.id, 'diagnostic', redact(chunk.toString()))
      })
      child.stdin?.on('error', () => {})
      child.stdin?.end(runPrompt(run))
      const code = await new Promise<number | null>((resolve, reject) => {
        child.once('error', reject)
        child.once('close', resolve)
      })
      if (buffer)
        line(buffer)
      const summary = redact(await readSummary(output))
      const status = control.cancelled
        ? 'cancelled'
        : control.timedOut
          ? 'failed'
          : code === 0
            ? 'succeeded'
            : 'failed'
      store.updateRun(run.id, {
        status,
        finishedAt: Date.now(),
        summary:
          summary.slice(0, 100000)
          || (control.timedOut
            ? 'Run exceeded its time limit.'
            : `Process exited with code ${code}.`),
      })
      store.event(run.id, 'status', status)
    }
    catch (e) {
      store.updateRun(run.id, {
        status: control.cancelled ? 'cancelled' : 'failed',
        finishedAt: Date.now(),
        summary: redact((e as Error).message),
      })
      store.event(run.id, 'error', redact((e as Error).message))
    }
    finally {
      if (timeout)
        clearTimeout(timeout)
      // Per-run login copies are disposable; keep results and working files only.
      await rm(path.join(config.dataDir, 'runs', run.id, 'home'), { recursive: true, force: true }).catch(() => {})
      await rm(path.join(config.dataDir, 'runner-plans', `${run.id}.json`), { force: true }).catch(() => {})
      this.active.delete(run.id)
    }
  }

  kill(child: ChildProcess) {
    if (!child.pid)
      return
    const signal = (value: NodeJS.Signals) => {
      try {
        if (process.platform === 'win32')
          child.kill(value)
        else process.kill(-child.pid!, value)
      }
      catch {
        /* already stopped */
      }
    }
    signal('SIGTERM')
    const timer = setTimeout(signal, 3000, 'SIGKILL')
    timer.unref()
    child.once('close', () => clearTimeout(timer))
  }

  cancel(id: string) {
    const run = requireValue(this.service.store.run(id))
    if (!['queued', 'running'].includes(run.status))
      throw new AppError(409, 'This run has already finished.')
    const active = this.active.get(id)
    if (active) {
      active.cancelled = true
      if (active.child)
        this.kill(active.child)
    }
    else {
      this.service.store.updateRun(id, {
        status: 'cancelled',
        finishedAt: Date.now(),
        summary: 'Cancelled before execution.',
      })
    }
    this.service.store.audit('run.cancelled', { id })
  }

  async cleanup(id: string) {
    const run = requireValue(this.service.store.run(id))
    if (['queued', 'running'].includes(run.status)) {
      throw new AppError(
        409,
        'Wait for this run to finish before cleaning up.',
      )
    }
    if (run.isolated)
      throw new AppError(409, 'Isolated clones are retained for review. After preserving your work, remove their directory through the server terminal.')
    const workspaces = run.workspaces ?? (run.workspace && run.snapshot.project ? [{ projectId: run.snapshot.project.id, path: run.workspace, kind: 'worktree' as const }] : [])
    const managed = workspaces.filter(workspace => workspace.kind !== 'direct')
    if (!run.snapshot.task.worktree || !run.workspace || !managed.length)
      throw new AppError(409, 'This run has no managed worktree to clean up.')
    const root = path.join(this.service.config.dataDir, 'runs', id, 'workspace')
    for (const workspace of managed) {
      if (workspace.path !== root && !workspace.path.startsWith(root + path.sep))
        throw new AppError(409, 'Workspace is outside this run’s managed directory.')
      const status = await exec('git', ['-C', workspace.path, 'status', '--porcelain', '--ignored'], { timeout: 10000, maxBuffer: 100000 })
      if (status.stdout.trim())
        throw new AppError(409, 'This worktree contains changes or untracked files. Commit or move them before cleanup.')
    }
    for (const workspace of managed) {
      if (workspace.kind === 'clone') {
        await rm(workspace.path, { recursive: true })
        continue
      }
      const project = requireValue(runProjects(run).find(project => project.id === workspace.projectId))
      await exec('git', ['-C', project.path, 'worktree', 'remove', workspace.path], { timeout: 10000, maxBuffer: 100000 })
    }
    this.service.store.updateRun(id, {
      workspace: null,
      workspaceCleanedAt: Date.now(),
    })
    this.service.store.audit('run.workspace.cleaned', { id })
    return { cleaned: true }
  }

  async close() {
    this.closing = true
    if (this.timer)
      clearInterval(this.timer)
    for (const value of this.active.values()) {
      value.cancelled = true
      if (value.child)
        this.kill(value.child)
    }
    while (this.busy) await new Promise(r => setTimeout(r, 30))
    await Promise.allSettled(this.executions)
  }
}
