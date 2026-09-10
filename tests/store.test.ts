import { mkdtemp, rm } from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { DatabaseSync } from 'node:sqlite'
import { describe, expect, it } from 'vitest'
import { Store } from '../server/store.ts'
import { fixture } from './helpers.ts'

describe('durable storage and API boundaries', () => {
  it('upgrades version 1 event history without losing records and persists structured payloads', async () => {
    const ctx = await fixture()
    try {
      const run = await ctx.service.enqueue(ctx.task.id)
      ctx.service.store.event(run.id, 'output', 'Historical output')
      ctx.service.store.db.exec('ALTER TABLE events DROP COLUMN payload; PRAGMA user_version=1')
      await ctx.app.close()
      const upgraded = new Store(path.join(ctx.directory, 'data'))
      try {
        expect(upgraded.events(run.id).at(-1)?.text).toBe('Historical output')
        expect(upgraded.events(run.id).at(-1)?.payload).toBeUndefined()
        upgraded.event(run.id, 'item.completed', 'Done', { item: { type: 'agent_message', text: 'Done' } })
        expect(upgraded.events(run.id).at(-1)?.payload).toEqual({ item: { type: 'agent_message', text: 'Done' } })
        expect(upgraded.db.prepare('PRAGMA user_version').get()!.user_version).toBe(3)
      }
      finally {
        upgraded.close()
      }
      const reopened = new Store(path.join(ctx.directory, 'data'))
      expect(reopened.events(run.id).at(-1)?.payload?.item).toEqual({ type: 'agent_message', text: 'Done' })
      reopened.close()
    }
    finally {
      await ctx.dispose()
    }
  })
  it('persists sessions, rolls back transactions, expires credentials, and refuses database downgrade', async () => {
    const dir = await mkdtemp(path.join(os.tmpdir(), 'leo-store-'))
    const store = new Store(dir)
    store.set('persistent', { value: 42 })
    expect(() =>
      store.transaction(() => {
        store.set('rolled-back', true)
        throw new Error('rollback')
      }),
    ).toThrow('rollback')
    expect(store.kv('rolled-back')).toBeUndefined()
    store.set('expired', true, Date.now() - 1)
    store.maintain()
    expect(
      store.db.prepare('SELECT * FROM kv WHERE key=?').get('expired'),
    ).toBeUndefined()
    store.close()
    const reopened = new Store(dir)
    expect(reopened.kv('persistent')).toEqual({ value: 42 })
    reopened.db.exec('PRAGMA user_version=99')
    reopened.close()
    expect(() => new Store(dir)).toThrow(/newer application/)
    const check = new DatabaseSync(path.join(dir, 'manager.db'))
    expect(check.prepare('PRAGMA user_version').get()!.user_version).toBe(99)
    check.close()
    await rm(dir, { recursive: true, force: true })
  })

  it('returns compact pages while preserving full run details and rejects invalid pagination', async () => {
    const ctx = await fixture()
    try {
      const headers = await ctx.login()
      const run = await ctx.service.enqueue(ctx.task.id)
      ctx.service.store.updateRun(run.id, {
        status: 'succeeded',
        summary: 'Full result kept here',
      })
      const response = await ctx.app.inject({
        url: '/api/runs?limit=1',
        headers,
      })
      expect(response.statusCode).toBe(200)
      expect(response.json()[0]).toMatchObject({
        id: run.id,
        taskName: ctx.task.name,
      })
      expect(response.json()[0]).not.toHaveProperty('snapshot')
      expect(response.json()[0]).not.toHaveProperty('summary')
      expect(
        (await ctx.app.inject({ url: `/api/runs/${run.id}`, headers })).json()
          .summary,
      ).toBe('Full result kept here')
      for (const query of ['limit=0', 'limit=101', 'offset=-1', 'limit=abc']) {
        expect(
          (await ctx.app.inject({ url: `/api/runs?${query}`, headers }))
            .statusCode,
        ).toBe(400)
      }
      const archived = ctx.service.task(
        { ...ctx.task, archived: true, cron: '* * * * *' },
        ctx.task.id,
      )
      expect(archived.nextRun).toBeNull()
      await expect(ctx.service.enqueue(ctx.task.id)).rejects.toThrow(
        /archived/,
      )
      expect(
        (
          await ctx.app.inject({
            method: 'POST',
            url: `/api/runs/${run.id}/cleanup`,
            headers,
          })
        ).statusCode,
      ).toBe(409)
    }
    finally {
      await ctx.dispose()
    }
  })
})
