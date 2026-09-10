import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import process from 'node:process'

export function chatFixture() {
  const file = path.join(process.env.CODEX_HOME, 'fixture-conversation.json')
  const emit = value => process.stdout.write(`${JSON.stringify(value)}\n`)
  const notify = (method, params) => emit({ method, params })
  let thread
  let active
  let counter = 0
  let hold = false
  const save = () => {
    mkdirSync(process.env.CODEX_HOME, { recursive: true })
    writeFileSync(file, JSON.stringify(thread))
  }
  const finish = () => {
    if (!active)
      return
    const text = `I reviewed the workspace. **The approach looks good.**\n\n${active.items.filter(item => item.type === 'userMessage').map(item => item.content[0].text).join('\n\n')}\n\nReady for the next step.`
    const item = { id: `reply-${active.id}`, type: 'agentMessage', text }
    active.items.push(item)
    active.status = 'completed'
    save()
    notify('item/completed', { threadId: thread.id, item })
    notify('turn/completed', { threadId: thread.id, turn: active })
    active = null
  }
  return (request) => {
    const { method, params } = request
    if (!['thread/start', 'thread/resume', 'turn/start', 'turn/steer', 'thread/turns/list'].includes(method))
      return false
    if (method === 'thread/start') {
      thread = { id: 'fixture-chat', cwd: params.cwd, historyMode: 'paginated', turns: [], parentThreadId: null }
      save()
      emit({ id: request.id, result: { thread } })
    }
    if (method === 'thread/resume') {
      if (!existsSync(file))
        throw new Error('No conversation')
      thread = JSON.parse(readFileSync(file, 'utf8'))
      counter = thread.turns.length
      emit({ id: request.id, result: { thread: { ...thread, turns: [] } } })
    }
    if (method === 'thread/turns/list')
      emit({ id: request.id, result: { data: [...thread.turns].reverse(), nextCursor: null } })
    if (method === 'turn/start' || method === 'turn/steer') {
      if (method === 'turn/steer' && (!active || active.id !== params.expectedTurnId)) {
        emit({ id: request.id, error: { code: -32000, message: 'Turn has finished' } })
        return true
      }
      if (method === 'turn/start') {
        hold = params.input[0].text.includes('fixture:chat-hang') && !params.input[0].text.startsWith('Continue the interrupted')
        active = { id: `turn-${++counter}`, status: 'inProgress', items: [], model: params.model }
        thread.turns.push(active)
        notify('turn/started', { threadId: thread.id, turn: active })
      }
      const item = { id: `user-${active.items.length}`, clientId: params.clientUserMessageId, type: 'userMessage', content: params.input }
      active.items.push(item)
      save()
      notify('item/completed', { threadId: thread.id, item })
      emit({ id: request.id, result: { turn: active, turnId: active.id } })
      if (method === 'turn/start') {
        const commentary = { id: `intro-${active.id}`, type: 'agentMessage', text: 'I’ll trace the component boundaries and keyboard handling, then propose a focused change.' }
        active.items.push(commentary)
        notify('item/completed', { threadId: thread.id, item: commentary })
        const command = { id: `command-${active.id}`, type: 'commandExecution', command: 'rg --files src/components', status: 'completed', exitCode: 0, aggregatedOutput: 'ChatComposer.vue\nActivityFeed.vue\nVirtualSelect.vue', durationMs: 18 }
        active.items.push(command)
        save()
        notify('item/completed', { threadId: thread.id, item: command })
      }
      const text = params.input[0].text
      if (text.includes('fixture:disconnect'))
        process.exit(1)
      if (text.includes('finish now'))
        finish()
      else if (!hold)
        setTimeout(finish, 250)
    }
    return true
  }
}
