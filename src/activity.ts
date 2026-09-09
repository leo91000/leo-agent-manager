import type { RunEvent } from '../shared/contracts'

export interface ActivityCode {
  label: string
  code: string
  language: string
}
export interface ActivityArtifact {
  kind: 'command' | 'files' | 'search' | 'tool' | 'plan' | 'thinking' | 'notice'
  id: string
  time: number
  title: string
  subtitle: string
  status: 'running' | 'done' | 'error' | 'info'
  blocks: ActivityCode[]
  files: { path: string, kind: string }[]
  tasks: { text: string, completed: boolean }[]
  raw: string
}
export type ActivityEntry
  = | { kind: 'message', id: string, time: number, text: string }
    | { kind: 'group', id: string, artifacts: ActivityArtifact[] }

function record(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {}
}
function text(value: unknown) {
  return typeof value === 'string' ? value : ''
}
function pretty(value: unknown) {
  return typeof value === 'string' ? value : JSON.stringify(value, null, 2) ?? ''
}
function payload(event: RunEvent) {
  if (event.payload)
    return event.payload
  try {
    return record(JSON.parse(event.text))
  }
  catch {
    return {}
  }
}
function readable(value: string) {
  return value.replaceAll(/[._]/g, ' ').replace(/^./, letter => letter.toUpperCase())
}

/** Keep tool updates in their original position and fold actions between messages. */
export function activityEntries(events: RunEvent[]): ActivityEntry[] {
  const entries: ActivityEntry[] = []
  const artifacts = new Map<string, ActivityArtifact>()
  const messages = new Map<string, Extract<ActivityEntry, { kind: 'message' }>>()
  let turn = 0
  let legacyTool: ActivityArtifact | undefined
  for (const event of events) {
    const data = payload(event)
    const item = record(data.item)
    const type = text(item.type)
    if (event.type === 'turn.started')
      turn++
    const itemId = text(item.id)
    const id = itemId ? `${turn}:${itemId}` : `event:${event.id}`
    const legacy = !event.payload && !Object.keys(data).length
    const legacyMessage = legacy && event.type === 'item.completed' && !legacyTool && event.text.trim()
    if (type === 'agent_message' || legacyMessage) {
      const content = text(item.text) || event.text
      const existing = messages.get(id)
      if (existing) {
        existing.text = content
      }
      else {
        const message = { kind: 'message' as const, id, time: event.createdAt, text: content }
        entries.push(message)
        messages.set(id, message)
      }
      continue
    }
    if (legacyTool && legacy && event.type === 'item.completed') {
      legacyTool.status = 'done'
      legacyTool.blocks = [{ label: 'Output', code: event.text, language: 'plaintext' }]
      legacyTool.raw = event.text
      legacyTool = undefined
      continue
    }
    const status = text(item.status)
    const failed = status === 'failed' || event.type.includes('failed') || event.type === 'error' || type === 'error' || (typeof item.exit_code === 'number' && item.exit_code !== 0)
    const running = !failed && (status === 'in_progress' || event.type.endsWith('.started')) && event.type.startsWith('item.')
    const artifact: ActivityArtifact = {
      id,
      time: event.createdAt,
      kind: 'notice',
      title: 'Worker update',
      subtitle: '',
      status: failed ? 'error' : running ? 'running' : 'done',
      blocks: [],
      files: [],
      tasks: [],
      raw: Object.keys(data).length ? pretty(data) : event.text,
    }
    const block = (label: string, value: unknown, language = 'plaintext') => {
      if (value !== undefined && value !== null && value !== '')
        artifact.blocks.push({ label, code: pretty(value), language })
    }
    if (type === 'command_execution') {
      artifact.kind = 'command'
      artifact.title = 'Terminal command'
      artifact.subtitle = text(item.command)
      block('Command', item.command, 'bash')
      block('Output', item.aggregated_output)
      if (typeof item.exit_code === 'number')
        artifact.title = item.exit_code === 0 ? 'Command completed' : `Command exited with code ${item.exit_code}`
    }
    else if (type === 'file_change') {
      artifact.kind = 'files'
      artifact.title = 'File changes'
      for (const change of Array.isArray(item.changes) ? item.changes : []) {
        const file = record(change)
        artifact.files.push({ path: text(file.path), kind: text(file.kind) || 'update' })
        block(text(file.path) || 'Changes', file.diff ?? file.patch, 'diff')
      }
      artifact.subtitle = `${artifact.files.length} ${artifact.files.length === 1 ? 'file' : 'files'}`
    }
    else if (type === 'mcp_tool_call' || type === 'web_search') {
      artifact.kind = type === 'web_search' ? 'search' : 'tool'
      artifact.title = type === 'web_search' ? 'Searched the web' : readable(text(item.tool) || 'Tool call')
      artifact.subtitle = text(item.query) || text(record(item.action).query) || text(item.server)
      block('Arguments', item.arguments, 'json')
      const result = record(item.result)
      for (const content of Array.isArray(result.content) ? result.content : []) {
        const part = record(content)
        block('Result', part.text)
      }
      if (!artifact.blocks.some(entry => entry.label === 'Result'))
        block('Result', item.result, 'json')
      block('Error', item.error, 'json')
      if (item.error || result.isError)
        artifact.status = 'error'
    }
    else if (type === 'todo_list') {
      artifact.kind = 'plan'
      artifact.title = 'Updated the plan'
      artifact.tasks = (Array.isArray(item.items) ? item.items : []).map(value => ({ text: text(record(value).text), completed: record(value).completed === true }))
      artifact.subtitle = `${artifact.tasks.filter(task => task.completed).length} of ${artifact.tasks.length} complete`
    }
    else if (type === 'reasoning') {
      artifact.kind = 'thinking'
      artifact.title = 'Thinking'
      block('Reasoning summary', item.text)
    }
    else {
      const labels: Record<string, string> = {
        'thread.started': 'Session connected',
        'turn.started': 'Started working',
        'turn.completed': 'Finished this turn',
        'diagnostic': 'Worker diagnostic',
        'output': 'Worker output',
        'error': 'Worker reported an error',
        'item.started': 'Working',
        'item.completed': 'Work completed',
        'turn.failed': 'Turn failed',
      }
      artifact.title = event.type === 'status' ? readable(event.text) : labels[event.type] || readable(type || event.type)
      const message = text(item.message) || text(data.message) || text(record(data.error).message)
      if (message)
        block('Details', message)
      else if (!['status', 'thread.started', 'turn.started', 'turn.completed'].includes(event.type))
        block('Output', event.text, Object.keys(data).length ? 'json' : 'plaintext')
      if (event.type === 'diagnostic')
        artifact.status = 'info'
      const usage = record(data.usage)
      if (typeof usage.input_tokens === 'number' && typeof usage.output_tokens === 'number')
        artifact.subtitle = `${usage.input_tokens.toLocaleString()} input · ${usage.output_tokens.toLocaleString()} output tokens`
    }
    const previous = artifacts.get(id)
    if (previous) {
      Object.assign(previous, artifact, { time: previous.time })
      continue
    }
    artifacts.set(id, artifact)
    const last = entries.at(-1)
    if (last?.kind === 'group')
      last.artifacts.push(artifact)
    else
      entries.push({ kind: 'group', id: `group:${id}`, artifacts: [artifact] })
    if (legacy && event.type === 'item.started')
      legacyTool = artifact
  }
  return entries
}
