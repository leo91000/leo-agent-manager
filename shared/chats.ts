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
  questionId?: string
  answers?: Record<string, string[]>
  id: string
  chatId: string
  text: string
  model: string
  mode: 'queue' | 'steer'
  status: 'queued' | 'sending' | 'delivered'
  createdAt: number
}
export interface ChatView extends Chat {
  pendingQuestions: number
  agentName: string
  projectName: string | null
  status: Run['status'] | 'idle'
}
export interface ChatDetail extends ChatView {
  questions: ChatQuestion[]
  run: Run | null
  messages: ChatMessage[]
}
export const questionFields = z.array(z.object({
  id: z.string().min(1).max(200),
  title: z.string().min(1).max(10000),
  secret: z.boolean().default(false),
  options: z.array(z.object({ label: z.string().max(2000), description: z.string().max(4000).default('') })).max(30).default([]),
})).min(1).max(20).refine(fields => new Set(fields.map(field => field.id)).size === fields.length, 'Question identifiers must be unique')
export interface ChatQuestion {
  id: string
  chatId: string
  runId: string
  blocking: boolean
  fields: z.infer<typeof questionFields>
  status: 'pending' | 'answering' | 'answered'
  createdAt: number
  messageId?: string
}
export const questionAnswerInput = z.object({
  id: z.string().uuid(),
  answers: z.record(z.string(), z.array(z.string().trim().min(1).max(10000)).min(1).max(1)),
})
export interface ChatExecution {
  messageId: string
  text: string
  recovery: boolean
}
