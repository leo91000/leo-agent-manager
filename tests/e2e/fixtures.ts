import type { Page } from '@playwright/test'
import { mkdir, mkdtemp, rm } from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { test as base, expect } from '@playwright/test'
import { buildApp } from '../../server/app'

interface Workspace {
  url: string
  projectPath: string
}

// Each Playwright project owns its application, database, worker, and limiter.
// Journeys retain their deliberately ordered persistence checks within one project.
export const test = base.extend<object, { workspace: Workspace }>({
  // Playwright requires a destructuring pattern even with no fixture dependencies.
  // eslint-disable-next-line no-empty-pattern
  workspace: [async ({}, use, workerInfo) => {
    const directory = await mkdtemp(path.join(os.tmpdir(), 'leo-browser-'))
    const home = path.join(directory, 'home')
    const projectPath = path.join(directory, 'project')
    const port = 4322 + workerInfo.parallelIndex
    const url = `http://127.0.0.1:${port}`
    await Promise.all([mkdir(home), mkdir(projectPath)])
    const { app, service, worker } = await buildApp({
      dataDir: path.join(directory, 'data'),
      home,
      workspaceRoots: [projectPath],
      setupToken: 'browser-test-setup',
      codexBin: path.resolve('tests/fixtures/codex.mjs'),
      ghBin: '/nonexistent/fixture-gh',
      publicUrl: url,
      logger: false,
      workerEnabled: !workerInfo.project.name.startsWith('layout-'),
    })
    try {
      await app.listen({ host: '127.0.0.1', port })
      if (workerInfo.project.name !== 'journeys') {
        const setup = await app.inject({ method: 'POST', url: '/api/setup', payload: { setupToken: 'browser-test-setup', password: 'browser-password-long-enough' } })
        expect(setup.statusCode).toBe(200)
        const agent = service.agent({ name: 'Release engineer' })
        const project = await service.project({ name: 'Design system', path: projectPath })
        await service.skills.save('review', '---\nname: review\ndescription: Review the project carefully\n---\nInspect the project and report checks.')
        const task = service.task({ name: 'Weekly dependency review', prompt: 'Review dependencies and report the checks you ran. fixture:activity', agentId: agent.id, projectId: project.id, skills: ['global/review'], worktree: false, enabled: false, cron: '0 9 * * 1' })
        const run = await service.enqueue(task.id)
        await worker.tick()
        await expect.poll(() => service.store.run(run.id)?.status, { timeout: 10000 }).toBe('succeeded')
        service.task({ ...task, prompt: 'fixture:hang' }, task.id)
        const cancelled = await service.enqueue(task.id)
        worker.cancel(cancelled.id)
      }
      await use({ url, projectPath })
    }
    finally {
      await app.close()
      await rm(directory, { recursive: true, force: true })
    }
  }, { scope: 'worker' }],
  baseURL: async ({ workspace }, use) => use(workspace.url),
})
export { expect } from '@playwright/test'

export async function expectSingleScroll(page: Page) {
  const report = await page.evaluate(() => {
    const scrolls = (element: Element) => element.clientHeight > 0 && element.scrollHeight > element.clientHeight + 1 && /auto|scroll/.test(getComputedStyle(element).overflowY)
    const nested: string[] = []
    for (const element of document.querySelectorAll('*')) {
      if (element.matches('textarea') || element.closest('.vs-popup, .theme-popover') || !scrolls(element))
        continue
      for (let parent = element.parentElement; parent; parent = parent.parentElement) {
        if (scrolls(parent))
          nested.push(`${element.className} inside ${parent.className}`)
      }
    }
    return { root: document.documentElement.scrollHeight - innerHeight, horizontal: document.documentElement.scrollWidth - innerWidth, nested }
  })
  expect(report).toEqual({ root: 0, horizontal: 0, nested: [] })
}
