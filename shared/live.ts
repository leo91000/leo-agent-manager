import type { Deliverable } from './artifacts'
import type { ChatDetail, ChatView } from './chats'
import type { Run, RunEvent } from './contracts'

export interface LiveState {
  run: Run | null
  chat: ChatDetail | null
  chats?: ChatView[]
  artifacts: Deliverable[]
}
export interface LiveBatch {
  events: RunEvent[]
  state?: LiveState
  reset: boolean
  more: boolean
}
