import type { Agent, Project, Run, Task } from '../shared/contracts.ts'
import type { Config } from './config.ts'
import type { Store } from './store.ts'
import { execFile } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import { promisify } from 'node:util'
import { CronExpressionParser } from 'cron-parser'
import { agentInput, projectInput, taskInput } from '../shared/contracts.ts'
import { AppError, requireValue } from './errors.ts'
import { workspaceDirectory } from './paths.ts'
import { Skills } from './skills.ts'

const exec = promisify(execFile)
async function projectOrigin(directory: string) {
  try {
    const { stdout } = await exec('git', ['-C', directory, 'config', '--get', 'remote.origin.url'], { timeout: 3000, maxBuffer: 8000 })
    const remote = stdout.trim()
    const scp = remote.match(/^(?:[\w.-]+@)?([a-z0-9.-]+):([\w./-]+)$/i)
    if (scp)
      return `${scp[1]}:${scp[2]}`
    const url = new URL(remote)
    if (!['https:', 'http:', 'ssh:', 'git:'].includes(url.protocol))
      return ''
    return `${url.protocol}//${url.host}${url.pathname}`
  }
  catch {
    return ''
  }
}

export function nextOccurrences(
  cron: string,
  timezone: string,
  now = Date.now(),
  count = 3,
) {
  try {
    if (cron.trim().split(/\s+/).length !== 5)
      throw new Error('Cron requires five fields')
    new Intl.DateTimeFormat('en', { timeZone: timezone }).resolvedOptions()
    const expression = CronExpressionParser.parse(cron, {
      tz: timezone,
      currentDate: new Date(now),
    })
    return Array.from({ length: count }, () => expression.next().getTime())
  }
  catch {
    throw new AppError(
      400,
      'Enter a valid five-field cron expression and IANA timezone.',
    )
  }
}
export class Service {
  skills: Skills
  constructor(
    public store: Store,
    public config: Config,
  ) {
    this.skills = new Skills(config.home, config.workspaceRoots)
  }

  agent(value: unknown, existingId?: string) {
    const input = agentInput.parse(value)
    if (existingId)
      requireValue(this.store.get('agents', existingId))
    const item: Agent = {
      ...input,
      id: existingId ?? randomUUID(),
      createdAt:
        this.store.get('agents', existingId ?? '')?.createdAt ?? Date.now(),
    }
    this.store.put('agents', item)
    this.store.audit('agent.saved', { id: item.id })
    return item
  }

  async project(value: unknown, existingId?: string) {
    const input = projectInput.parse(value)
    const actual = await workspaceDirectory(
      input.path,
      this.config.workspaceRoots,
    )
    if (existingId)
      requireValue(this.store.get('projects', existingId))
    const item: Project = {
      ...input,
      path: actual,
      origin: await projectOrigin(actual),
      id: existingId ?? randomUUID(),
      createdAt:
        this.store.get('projects', existingId ?? '')?.createdAt ?? Date.now(),
    }
    this.store.put('projects', item)
    this.store.audit('project.saved', { id: item.id })
    return item
  }

  task(value: unknown, existingId?: string) {
    const input = taskInput.parse(value)
    if (input.cron)
      nextOccurrences(input.cron, input.timezone)
    requireValue(this.store.get('agents', input.agentId), 'Agent not found')
    requireValue(
      this.store.get('projects', input.projectId),
      'Project not found',
    )
    if (existingId)
      requireValue(this.store.get('tasks', existingId))
    const item: Task = {
      ...input,
      id: existingId ?? randomUUID(),
      createdAt:
        this.store.get('tasks', existingId ?? '')?.createdAt ?? Date.now(),
      nextRun:
        input.cron && input.enabled && !input.archived
          ? nextOccurrences(input.cron, input.timezone)[0]
          : null,
    }
    this.store.put('tasks', item)
    this.store.audit('task.saved', { id: item.id })
    return item
  }

  remove(kind: 'agents' | 'projects' | 'tasks', id: string) {
    requireValue(this.store.get(kind, id))
    if (
      kind !== 'tasks'
      && this.store
        .list('tasks')
        .some(t =>
          kind === 'agents' ? t.agentId === id : t.projectId === id,
        )
    ) {
      throw new AppError(
        409,
        'This item is used by a task. Update or remove that task first.',
      )
    }
    if (
      this.store
        .active()
        .some(r =>
          kind === 'tasks'
            ? r.taskId === id
            : kind === 'projects'
              ? r.projectId === id
              : r.snapshot.agent.id === id,
        )
    ) {
      throw new AppError(
        409,
        'This item has active work. Cancel or wait for the run first.',
      )
    }
    this.store.remove(kind, id)
    this.store.audit(`${kind}.deleted`, { id })
  }

  async enqueue(
    taskId: string,
    trigger = 'manual',
    dedupe: string | null = null,
  ): Promise<Run> {
    const task = requireValue(
      this.store.get('tasks', taskId),
      'Task not found',
    )
    if (task.archived)
      throw new AppError(409, 'Restore this archived task before running it.')
    const agent = requireValue(this.store.get('agents', task.agentId))
    const project = requireValue(this.store.get('projects', task.projectId))
    const available = [
      ...(await this.skills.list()),
      ...(await this.skills.list(project.id, project.path)),
    ]
    const skills = task.skills.map((key) => {
      const selected = available.find(s => `${s.scope}/${s.name}` === key)
      if (!selected?.valid)
        throw new AppError(400, `Skill unavailable or invalid: ${key}`)
      return {
        name: selected.name,
        path: selected.path,
        content: selected.content,
      }
    })
    const run: Run = {
      id: randomUUID(),
      taskId,
      projectId: project.id,
      status: 'queued',
      trigger,
      createdAt: Date.now(),
      startedAt: null,
      finishedAt: null,
      summary: '',
      sessionId: null,
      workspace: null,
      usage: null,
      snapshot: { task, agent, project, skills },
    }
    try {
      this.store.addRun(run, dedupe)
    }
    catch (e) {
      if ((e as Error).message.includes('UNIQUE constraint')) {
        throw new AppError(
          409,
          'This task already has active work or this occurrence was already queued.',
        )
      }
      throw e
    }
    this.store.event(run.id, 'status', 'Queued')
    this.store.audit('run.queued', { id: run.id, taskId, trigger })
    return run
  }

  async schedule(now = Date.now()) {
    for (const task of this.store.list('tasks')) {
      if (
        !task.enabled
        || task.archived
        || !task.cron
        || task.nextRun === null
        || task.nextRun > now
      ) {
        continue
      }
      try {
        await this.enqueue(task.id, 'schedule', `${task.id}:${task.nextRun}`)
      }
      catch (e) {
        if (!(e instanceof AppError && e.statusCode === 409)) {
          this.store.audit('schedule.failed', {
            taskId: task.id,
            error: (e as Error).message,
          })
        }
      }
      const current = this.store.get('tasks', task.id)
      if (current?.nextRun === task.nextRun) {
        this.store.put('tasks', {
          ...current,
          nextRun: nextOccurrences(task.cron, task.timezone, now)[0],
        })
      }
    }
  }
}
