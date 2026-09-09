import type { RunEvent } from '../shared/contracts'
import { describe, expect, it } from 'vitest'
import { redactPayload } from '../server/worker.ts'
import { activityEntries } from '../src/activity'
import sample from './fixtures/activity-events.json'

const events: RunEvent[] = sample.map((payload, index) => ({ id: index + 1, runId: 'test', createdAt: index, type: payload.type, text: '', payload }))
describe('conversation activity', () => {
  it('groups actions between assistant messages and updates commands in place', () => {
    const entries = activityEntries(events)
    expect(entries.map(entry => entry.kind)).toEqual(['message', 'group', 'message', 'group', 'message'])
    const groups = entries.filter(entry => entry.kind === 'group')
    expect(groups[0].artifacts.map(item => item.kind)).toEqual(['plan', 'command', 'search'])
    expect(groups[1].artifacts.map(item => item.kind)).toEqual(['files', 'command', 'tool'])
    expect(groups[1].artifacts[1]).toMatchObject({ status: 'done', subtitle: 'pnpm test -- sibling-functions && pnpm typecheck' })
    expect(groups[1].artifacts[0].blocks[0].language).toBe('diff')
    expect(groups[1].artifacts[2].blocks.map(block => block.label)).toEqual(['Arguments', 'Result'])
  })
  it('shows pending, failed and unknown events without losing details', () => {
    const input = (id: number, item: Record<string, unknown>, type = 'item.completed'): RunEvent => ({ id, runId: 'test', createdAt: id, type, text: '', payload: { type, item } })
    const entries = activityEntries([
      input(1, { id: 'a', type: 'command_execution', command: 'false', status: 'in_progress' }, 'item.started'),
      input(2, { id: 'a', type: 'command_execution', command: 'false', exit_code: 1 }),
      input(3, { id: 'b', type: 'future_tool', answer: 'preserved' }),
      input(4, { id: 'c', type: 'command_execution', command: 'sleep 5', status: 'in_progress' }, 'item.started'),
    ])
    if (entries[0].kind !== 'group')
      throw new Error('Expected a group')
    expect(entries[0].artifacts.map(item => item.status)).toEqual(['error', 'done', 'running'])
    expect(entries[0].artifacts[1].raw).toContain('preserved')
  })
  it('keeps historical plain output readable and pairs legacy command events', () => {
    const old = (id: number, type: string, text: string): RunEvent => ({ id, type, text, runId: 'old', createdAt: id })
    const entries = activityEntries([old(1, 'item.completed', 'I will check this.'), old(2, 'item.started', ''), old(3, 'item.completed', '8 tests passed'), old(4, 'item.completed', 'The checks passed.')])
    expect(entries.map(entry => entry.kind)).toEqual(['message', 'group', 'message'])
    if (entries[1].kind !== 'group')
      throw new Error('Expected a group')
    expect(entries[1].artifacts[0].blocks[0].code).toBe('8 tests passed')
  })
  it('redacts structured tool arguments and nested text before persistence', () => {
    expect(redactPayload({ arguments: { access_token: 'private-value' }, result: { text: 'Bearer private-token' } })).toEqual({ arguments: { access_token: '[redacted]' }, result: { text: 'Bearer [redacted]' } })
  })
})
