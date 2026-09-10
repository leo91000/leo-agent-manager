import { z } from 'zod'

export const name = z.string().trim().min(1).max(100)
export const id = z.string().uuid()
export { MAIN_AGENT_ID } from './constants'
export const accessPolicy = z.object({
  projects: z.array(id).max(100).nullable().default(null),
  skills: z.array(z.string().max(160)).max(100).nullable().default(null),
  mcps: z.array(id).max(100).nullable().default(null),
  mcpTools: z.record(id, z.array(z.string().min(1).max(200)).max(500)).default({}),
  github: z.boolean().default(true),
  sandbox: z.enum(['yolo', 'workspace-write', 'read-only']).default('yolo'),
})
export const agentInput = z.object({
  name,
  description: z.string().max(500).default(''),
  model: z.string().max(100).default(''),
  reasoning: z.enum(['low', 'medium', 'high', 'xhigh']).default('high'),
  instructions: z.string().max(20000).default(''),
  timeoutMinutes: z.number().int().min(1).max(720).default(120),
  access: accessPolicy.default(() => accessPolicy.parse({})),
})
export const projectInput = z.object({
  name,
  path: z.string().min(1).max(2000),
  description: z.string().max(500).default(''),
  baseBranch: z
    .string()
    .regex(/^[a-z0-9][\w/.-]*$/i)
    .default('main'),
})
export const taskInput = z.object({
  name,
  prompt: z.string().trim().min(1).max(50000),
  agentId: id,
  projectId: id.nullable().default(null),
  skills: z.array(z.string().max(160)).max(100).nullable().default(null),
  tags: z.array(z.string().max(30)).max(10).default([]),
  cron: z.string().max(100).nullable().default(null),
  timezone: z.string().max(100).default('Europe/Paris'),
  enabled: z.boolean().default(true),
  archived: z.boolean().default(false),
  worktree: z.boolean().default(true),
})
export type Agent = z.infer<typeof agentInput> & {
  id: string
  createdAt: number
}
export type Project = z.infer<typeof projectInput> & {
  origin?: string
  id: string
  createdAt: number
}
export type Task = z.infer<typeof taskInput> & {
  id: string
  createdAt: number
  nextRun: number | null
}
export type RunStatus
  = 'queued' | 'running' | 'succeeded' | 'failed' | 'cancelled' | 'interrupted'
export interface Run {
  id: string
  taskId: string
  projectId: string | null
  status: RunStatus
  trigger: string
  createdAt: number
  startedAt: number | null
  finishedAt: number | null
  summary: string
  sessionId: string | null
  workspace: string | null
  workspaces?: { projectId: string, path: string, kind: 'worktree' | 'clone' | 'direct' }[]
  isolated?: boolean
  workspaceCleanedAt?: number
  snapshot: {
    task: Task
    agent: Agent
    project: Project | null
    projects?: Project[]
    skills: { name: string, path: string, content: string }[]
  }
  usage: Record<string, number> | null
}
export interface Skill {
  name: string
  description: string
  scope: string
  content: string
  path: string
  valid: boolean
  error?: string
}
export interface RunEvent {
  id: number
  runId: string
  createdAt: number
  type: string
  text: string
  payload?: Record<string, unknown>
}

export type RunListItem = Omit<Run, 'snapshot' | 'summary'> & {
  taskName: string
  agentName: string
}
