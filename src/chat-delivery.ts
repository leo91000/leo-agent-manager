import type { ChatDetail, ChatMessage } from '../shared/chats'
import type { Run, RunEvent } from '../shared/contracts'

export interface SendingMessage {
  message: ChatMessage
  label: string
}

export function chatWaitNotice(run: Run | null | undefined) {
  const reason = run?.status === 'queued' ? run.accountWaitReason?.trim() : ''
  if (!reason)
    return null
  const reconnectClaude = reason === 'Reconnect Claude Code after an interrupted credential synchronization.'
    || reason === 'Connect Claude Code in Connections before running this agent.'
  return {
    reconnectClaude,
    message: reconnectClaude
      ? 'Reconnect Claude Code in Connections to continue. Your message is saved and will be sent when the connection is restored.'
      : reason,
  }
}

/** The dispatch queue is durable storage; only waiting follow-ups belong in the UI queue. */
export function chatDelivery(chat: ChatDetail | null, events: RunEvent[], outgoing: ChatMessage | null = null) {
  const acknowledged = new Set(events.filter(event => event.type === 'chat.user').map(event => event.payload?.messageId))
  const messages = (chat?.messages ?? []).filter(message => message.status === 'queued' || message.status === 'sending')
  if (outgoing && !messages.some(message => message.id === outgoing.id))
    messages.push(outgoing)
  const active = !!chat?.run && ['queued', 'running'].includes(chat.run.status)
  const canStart = !chat?.paused && (!chat?.run || chat.run.status === 'succeeded')
  const first = messages[0]
  const sending: SendingMessage[] = []
  const queued: ChatMessage[] = []
  for (const message of messages) {
    if (acknowledged.has(message.id))
      continue
    const local = message.id === outgoing?.id && !chat?.messages.some(saved => saved.id === message.id)
    const starting = active && chat?.run?.chatExecution?.messageId === message.id
    const steering = active && message.mode === 'steer' && (!chat?.paused || !!message.questionId)
    if (local || starting || steering || (canStart && message === first)) {
      // Delivered question answers get their transcript text (including privacy
      // redaction) from the server; preserve genuinely waiting answers below.
      if (message.questionId)
        continue
      const waiting = chat?.run?.status === 'queued'
      sending.push({ message, label: waiting ? (chatWaitNotice(chat.run)?.reconnectClaude ? 'Waiting for Claude Code sign-in' : 'Waiting for the agent…') : starting ? 'Starting agent…' : steering ? 'Sending to agent…' : 'Sending…' })
    }
    else {
      queued.push(message)
    }
  }

  return { sending, queued }
}
