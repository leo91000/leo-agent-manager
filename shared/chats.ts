import type { Run } from './contracts'
import { z } from 'zod'
import { MAIN_AGENT_ID } from './constants'

export const chatInput = z.object({
  agentId: z.string().uuid().default(MAIN_AGENT_ID),
  projectId: z.string().uuid().nullable().default(null),
})
export const chatMessageInput = z.object({
  id: z.string().uuid(),
  text: z.string().trim().min(1).max(50000),
  mode: z.enum(['queue', 'steer']).default('queue'),
  model: z.string().trim().max(120).regex(/^[\w./:-]*$/).default(''),
})
export interface Chat {
  id: string
  title: string
  agentId: string
  projectId: string | null
  runId: string | null
  paused: boolean
  createdAt: number
  updatedAt: number
}
export interface ChatMessage {
  id: string
  chatId: string
  text: string
  model: string
  mode: 'queue' | 'steer'
  status: 'queued' | 'sending' | 'delivered'
  createdAt: number
}
export interface ChatView extends Chat {
  agentName: string
  projectName: string | null
  status: Run['status'] | 'idle'
}
export interface ChatDetail extends ChatView {
  run: Run | null
  messages: ChatMessage[]
}
export interface ChatExecution {
  messageId: string
  text: string
  recovery: boolean
}
