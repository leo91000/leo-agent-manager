import type { Agent, Project, Skill, Task } from '../shared/contracts'
import { reactive } from 'vue'

export const state = reactive({
  ready: false,
  authenticated: false,
  setupRequired: false,
  csrf: '',
  agents: [] as Agent[],
  projects: [] as Project[],
  tasks: [] as Task[],
  skills: [] as Skill[],
  toast: '',
  error: '',
})
let toastTimer: ReturnType<typeof setTimeout> | undefined
export function notify(message: string) {
  state.toast = message
  if (toastTimer)
    clearTimeout(toastTimer)
  toastTimer = setTimeout(() => (state.toast = ''), 4500)
}
export async function api<T = any>(
  url: string,
  options: RequestInit = {},
): Promise<T> {
  const response = await fetch(`/api${url}`, {
    ...options,
    headers: {
      ...(options.body === undefined
        ? {}
        : { 'Content-Type': 'application/json' }),
      'X-CSRF-Token': state.csrf,
      ...options.headers,
    },
  })
  const data = await response.json()
  if (!response.ok) {
    if (response.status === 401)
      state.authenticated = false
    throw new Error(data.error || 'Request failed. Please try again.')
  }
  return data
}
export async function session() {
  Object.assign(state, await api('/session'))
  state.ready = true
}
export async function refresh() {
  const [agents, projects, tasks, skills] = await Promise.all([
    api<Agent[]>('/agents'),
    api<Project[]>('/projects'),
    api<Task[]>('/tasks'),
    api<Skill[]>('/skills'),
  ])
  Object.assign(state, { agents, projects, tasks, skills })
}
export function date(value: number | null | undefined) {
  return value
    ? new Intl.DateTimeFormat(undefined, {
        dateStyle: 'medium',
        timeStyle: 'short',
      }).format(value)
    : '—'
}
export function relative(value: number) {
  const mins = Math.round((Date.now() - value) / 60000)
  if (mins < 1)
    return 'Just now'
  if (mins < 60)
    return `${mins}m ago`
  if (mins < 1440)
    return `${Math.floor(mins / 60)}h ago`
  return date(value)
}
export function duration(start: number | null, end: number | null) {
  if (!start)
    return '—'
  const seconds = Math.floor(((end ?? Date.now()) - start) / 1000)
  return seconds < 60
    ? `${seconds}s`
    : `${Math.floor(seconds / 60)}m ${seconds % 60}s`
}
