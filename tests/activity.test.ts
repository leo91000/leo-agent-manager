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
    expect(groups[0].artifacts.map(item => item.kind)).toEqual(['plan', 'search', 'search', 'read', 'read', 'browse', 'command'])
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

describe('artifact identities', () => {
  it('keeps historical JSON output with its step instead of losing the pairing', () => {
    const entries = activityEntries([
      { id: 1, runId: 'old', createdAt: 10, type: 'item.started', text: '' },
      { id: 2, runId: 'old', createdAt: 20, type: 'item.completed', text: '{"files":["src/a.ts"]}' },
    ])
    expect(entries).toHaveLength(1)
    if (entries[0].kind !== 'group')
      throw new Error('Expected an artifact')
    expect(entries[0].artifacts).toHaveLength(1)
    expect(entries[0].artifacts[0]).toMatchObject({ title: 'Recorded output', status: 'info', blocks: [{ label: 'Output', code: '{"files":["src/a.ts"]}', language: 'plaintext' }] })
  })
  it('labels historical output honestly instead of leaving a completed Working card', () => {
    const entries = activityEntries([
      { id: 1, runId: 'old', createdAt: 10, type: 'item.started', text: '' },
      { id: 2, runId: 'old', createdAt: 20, type: 'item.completed', text: 'Already up to date.\ncompatibility/css-feature-target.json' },
    ])
    if (entries[0].kind !== 'group')
      throw new Error('Expected an artifact')
    expect(entries[0].artifacts[0]).toMatchObject({ kind: 'output', title: 'Recorded output', historical: true, status: 'info' })
  })
})

describe('operation cards', () => {
  function command(command: string, output = '') {
    const entries = activityEntries([{ id: 1, runId: 'test', createdAt: 10, type: 'item.completed', text: output, payload: { item: { id: 'a', type: 'command_execution', command, aggregated_output: output, exit_code: 0, status: 'completed' } } }])
    if (entries[0].kind !== 'group')
      throw new Error('Expected an artifact')
    return entries[0].artifacts[0]
  }
  it('recognizes simple shell file reads and highlights the file language', () => {
    const artifact = command('/bin/zsh -lc \'sed -n "1,80p" src/activity.ts\'', 'export const value = 1')
    expect(artifact).toMatchObject({ kind: 'read', title: 'Read activity.ts', subtitle: 'src/activity.ts' })
    expect(artifact.blocks.find(block => block.label === 'File content')?.language).toBe('typescript')
  })
  it('distinguishes repository searches and directory listings from commands', () => {
    expect(command('rg -n "sibling-count" src tests')).toMatchObject({ kind: 'search', title: 'Search files' })
    expect(command('rg --files src')).toMatchObject({ kind: 'browse', title: 'Browse files' })
    expect(command('pnpm test')).toMatchObject({ kind: 'command', title: 'Run tests' })
  })
  it('does not call compound or mutating commands a file read', () => {
    for (const value of ['cat src/a.ts && rm temp', 'cat src/a.ts > copy.ts', 'sed -i s/old/new/ src/a.ts', 'cat $(get-path)', 'cat src/a.ts | sh', 'cat src/[ab].ts', 'cat src/a.ts # comment'])
      expect(command(value).kind).toBe('command')
  })
  it('keeps operation identity and duration across command lifecycle updates', () => {
    const entries = activityEntries([
      { id: 1, runId: 'test', createdAt: 10, type: 'item.started', text: '', payload: { item: { id: 'read', type: 'command_execution', command: 'cat src/a.ts', status: 'in_progress' } } },
      { id: 2, runId: 'test', createdAt: 250, type: 'item.completed', text: '', payload: { item: { id: 'read', type: 'command_execution', command: 'cat src/a.ts', aggregated_output: 'const a = 1', exit_code: 0, status: 'completed' } } },
    ])
    if (entries[0].kind !== 'group')
      throw new Error('Expected an artifact')
    expect(entries[0].artifacts).toHaveLength(1)
    expect(entries[0].artifacts[0]).toMatchObject({ kind: 'read', durationMs: 240, exitCode: 0 })
  })
})
