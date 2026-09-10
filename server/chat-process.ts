import type { ChatExecution, ChatMessage } from '../shared/chats.ts'
import type { Config } from './config.ts'
import { readFile, writeFile } from 'node:fs/promises'
import process from 'node:process'
import { codexSession } from './codex-rpc.ts'

export interface ChatPlan {
  execution: ChatExecution
  sessionId?: string
  instructions: string
  inputDirectory: string
  output: string
  cwd: string
  model: string
  reasoning: string
  sandbox: 'yolo' | 'workspace-write' | 'read-only'
  writableRoots: string[]
  args: string[]
}
const emit = (event: Record<string, unknown>) => process.stdout.write(`${JSON.stringify(event)}\n`)
const input = (text: string) => [{ type: 'text', text, text_elements: [] }]
// Keep the established Activity artifact contract independent of Codex's transport.
export function chatItem(item: Record<string, any>) {
  const types: Record<string, string> = { agentMessage: 'agent_message', commandExecution: 'command_execution', fileChange: 'file_change', mcpToolCall: 'mcp_tool_call', reasoning: 'reasoning', plan: 'reasoning', webSearch: 'web_search', collabAgentToolCall: 'collab_tool_call' }
  return { ...item, type: types[item.type] ?? item.type, aggregated_output: item.aggregatedOutput, exit_code: item.exitCode, duration_ms: item.durationMs, text: item.text ?? item.summary?.join('\n'), changes: item.changes?.map((change: any) => ({ ...change, kind: typeof change.kind === 'object' ? change.kind.type : change.kind })) }
}
export async function runChat(plan: ChatPlan, binary = 'codex') {
  let threadId = ''
  let turnId = ''
  let finished = false
  let lastMessage = ''
  let settle!: (turn: any) => void
  let rejectCompletion!: (error: Error) => void
  const completed = new Promise<any>((resolve, reject) => {
    settle = resolve
    rejectCompletion = reject
  })
  void completed.catch(() => {})
  const seen = new Set<string>()
  const attempted = new Set<string>()
  const texts = new Map<string, string>()
  const acknowledge = (id: string) => {
    if (seen.has(id))
      return
    seen.add(id)
    emit({ type: 'chat.delivered', messageId: id })
  }
  const onItem = (item: any, type = 'item.completed') => {
    if (item.type === 'userMessage') {
      if (item.clientId)
        acknowledge(item.clientId)
      return
    }
    if (item.type === 'agentMessage')
      lastMessage = item.text || lastMessage
    emit({ type, item: chatItem(item) })
  }
  const session = codexSession({ codexBin: binary, home: process.env.HOME } as Config, {
    args: plan.args,
    closed: () => rejectCompletion(new Error('Codex disconnected before finishing the response. Resume the conversation to continue.')),
    cwd: plan.cwd,
    notification(method, params) {
      if (params?.threadId && threadId && params.threadId !== threadId)
        return
      if (method === 'turn/started') {
        turnId = params.turn.id
        emit({ type: 'turn.started' })
      }
      if (method === 'item/started' || method === 'item/completed')
        onItem(params.item, method.replace('/', '.'))
      if (method === 'item/agentMessage/delta') {
        const text = (texts.get(params.itemId) ?? '') + params.delta
        texts.set(params.itemId, text)
        emit({ type: 'item.updated', item: { id: params.itemId, type: 'agent_message', text } })
      }
      if (method === 'turn/completed') {
        finished = true
        settle(params.turn)
      }
    },
  })
  await session(process.env.CODEX_HOME!, async (rpc) => {
    const settings = { cwd: plan.cwd, model: plan.model || undefined, approvalPolicy: 'never', sandbox: plan.sandbox === 'yolo' ? 'danger-full-access' : plan.sandbox, developerInstructions: plan.instructions, config: { model_reasoning_effort: plan.reasoning, sandbox_workspace_write: { network_access: true, writable_roots: plan.writableRoots } } }
    const result = await rpc.request<any>(plan.sessionId ? 'thread/resume' : 'thread/start', { ...settings, ...(plan.sessionId ? { threadId: plan.sessionId, excludeTurns: false } : {}) })
    threadId = result.thread.id
    emit({ type: 'thread.started', thread_id: threadId })
    // Client IDs survive in the Codex transcript: a crash after acceptance must
    // recover the existing turn, not submit the user's instruction twice.
    const turns = result.thread.turns ?? []
    if (plan.sessionId && result.thread.historyMode === 'paginated') {
      turns.length = 0
      let cursor: string | undefined
      do {
        const page = await rpc.request<any>('thread/turns/list', { threadId, cursor, limit: 100, itemsView: 'full', sortDirection: 'desc' })
        turns.push(...page.data)
        cursor = page.nextCursor ?? undefined
      } while (cursor)
    }
    for (const turn of turns) {
      for (const item of turn.items ?? []) {
        if (item.type === 'userMessage' && item.clientId)
          acknowledge(item.clientId)
      }
    }
    const acceptedTurn = turns.findLast((turn: any) => turn.items?.some((item: any) => item.clientId === plan.execution.messageId))
    const previous = acceptedTurn && plan.execution.recovery ? (result.thread.historyMode === 'paginated' ? turns[0] : turns.at(-1)) : acceptedTurn
    if (previous) {
      emit({ type: 'chat.delivered', messageId: plan.execution.messageId })
      if (previous.status === 'completed') {
        for (const item of previous.items) onItem(item)
        await writeFile(plan.output, lastMessage)
        emit({ type: 'turn.completed' })
        return
      }
    }
    const recovering = !!previous
    const start = await rpc.request<any>('turn/start', {
      threadId,
      clientUserMessageId: recovering ? undefined : plan.execution.messageId,
      input: input(recovering ? `Continue the interrupted conversation from its last completed step. Preserve completed work and verify external effects before repeating any action. The pending user request is:\n${plan.execution.text}` : plan.execution.text),
      model: plan.model || undefined,
      effort: plan.reasoning,
    })
    turnId = start.turn.id
    acknowledge(plan.execution.messageId)
    let polling = false
    const timer = setInterval(async () => {
      if (polling || finished || !turnId)
        return
      polling = true
      try {
        const messages: ChatMessage[] = JSON.parse(await readFile(`${plan.inputDirectory}/messages.json`, 'utf8'))
        for (const message of messages) {
          if (finished || seen.has(message.id) || attempted.has(message.id))
            continue
          attempted.add(message.id)
          try {
            await rpc.request('turn/steer', { threadId, expectedTurnId: turnId, clientUserMessageId: message.id, input: input(message.text) })
            acknowledge(message.id)
          }
          catch {
            // The turn may have ended between the click and the request. Leave
            // the message queued for the next turn; never discard it.
            break
          }
        }
      }
      catch { /* The worker has not published an inbox yet. */ }
      finally { polling = false }
    }, 250)
    try {
      const turn = await completed
      clearInterval(timer)
      // Polling is cleared by the in-flight steering request.
      // eslint-disable-next-line no-unmodified-loop-condition
      while (polling) await new Promise(resolve => setTimeout(resolve, 10))
      if (turn.status !== 'completed') {
        emit({ type: 'turn.failed', error: turn.error ?? { message: 'Conversation interrupted.' } })
        process.exitCode = 1
        return
      }
      await writeFile(plan.output, lastMessage)
      emit({ type: 'turn.completed' })
    }
    finally { clearInterval(timer) }
  })
}
if (process.argv[1] === import.meta.filename) {
  let data = ''
  for await (const chunk of process.stdin) data += chunk
  runChat(JSON.parse(data), process.argv[2]).catch((error) => {
    emit({ type: 'turn.failed', error: { message: error.message } })
    process.exitCode = 1
  })
}
