import type { Page, TestInfo } from '@playwright/test'
import { expect, test } from './fixtures'

async function checkMobileLayouts(page: Page, testInfo: TestInfo, colorScheme: 'light' | 'dark' = 'light') {
  await page.emulateMedia({ colorScheme })
  test.setTimeout(180000)
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  await page.goto('/')
  await page.getByLabel('Password', { exact: true }).fill('browser-password-long-enough')
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  await expect(page.locator('.shell')).toBeVisible()
  const runs = await page.request.get('/api/runs').then(response => response.json())
  const run = runs.find((item: { status: string }) => item.status === 'succeeded')

  async function navigate(url: string) {
    const destination = url.startsWith('/runs/') ? '/runs' : url
    const menu = page.getByRole('button', { name: 'Open navigation' })
    if (await menu.isVisible())
      await menu.click()
    await page.locator(`.sidebar a[href="${destination}"]`).last().click()
    const headings: Record<string, string> = { '/tasks': 'Tasks', '/runs': 'Run history', '/agents': 'Agents', '/projects': 'Projects', '/skills': 'Skills library', '/connections': 'Connections', '/settings': 'Settings' }
    if (headings[destination])
      await expect(page.getByRole('heading', { name: headings[destination], exact: true })).toBeVisible()
    if (destination !== url) {
      await page.locator(`.run-table a[href="${url}"]`).first().click()
      await expect(page.locator('.run-title-meta')).toBeVisible()
    }
  }
  async function fits() {
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(1)
  }
  async function screenshot(name: string) {
    await page.evaluate(() => document.fonts.ready)
    await fits()
    if (colorScheme === 'dark') {
      const brightSurfaces = await page.locator('.run-facts, .settings-section, .resource-card, .task-card, .skill-card, .connection-card, dialog[open], .vs-popup:popover-open').evaluateAll(elements => elements.filter((el) => {
        const color = getComputedStyle(el).backgroundColor.match(/[\d.]+/g)?.map(Number)
        return color && color.length >= 3 && (color[3] ?? 1) > 0.5 && Math.min(...color.slice(0, 3)) > 180
      }).map(el => el.className))
      expect(brightSurfaces).toEqual([])
    }
    const overlay = await page.locator('dialog[open], .sidebar.open').count()
    await page.screenshot({ path: testInfo.outputPath(`${name}.png`), fullPage: !overlay, animations: 'disabled' })
  }
  const screens = [
    ['overview', '/', '.stat-card'],
    ['tasks', '/tasks', '.task-card'],
    ['runs', '/runs', 'tbody tr'],
    ['agents', '/agents', '.resource-card'],
    ['projects', '/projects', '.resource-card'],
    ['skills', '/skills', '.skill-card'],
    ['connections', '/connections', '.connection-card'],
    ['settings', '/settings', '.settings-section'],
    ['result', `/runs/${run.id}`, '.run-panel'],
  ]
  for (const viewport of [...(colorScheme === 'dark' ? [{ width: 1440, height: 1000 }] : []), { width: 320, height: 568 }, { width: 390, height: 664 }, { width: 430, height: 932 }, { width: 844, height: 390 }]) {
    await page.setViewportSize(viewport)
    for (const [name, url, ready] of screens) {
      await navigate(url)
      await expect(page.locator(ready).first()).toBeVisible()
      await screenshot(`${viewport.width}-${name}`)
      if (name === 'tasks' || name === 'skills') {
        const geometry = await page.locator('.search-field').evaluate((field) => {
          const icon = field.querySelector('svg')!.getBoundingClientRect()
          const input = field.querySelector('input')!.getBoundingClientRect()
          return { aligned: Math.abs(icon.y + icon.height / 2 - input.y - input.height / 2) < 2, separated: icon.right <= input.left }
        })
        expect(geometry).toEqual({ aligned: true, separated: true })
      }
    }
    await page.getByRole('button', { name: /^Activity/ }).click()
    await expect(page.locator('.activity-group').first()).toBeAttached()
    const head = await page.locator('.run-panel-head').boundingBox()
    const actions = await page.locator('.activity-toolbar').boundingBox()
    expect(actions!.y).toBeGreaterThanOrEqual(head!.y + head!.height)
    await page.getByLabel('Follow output').uncheck()
    await expect(page.getByLabel('Follow output')).not.toBeChecked()
    await screenshot(`${viewport.width}-activity`)
    const work = page.locator('.activity-group').filter({ hasText: 'Files · Terminal · Tools' })
    await work.locator('.activity-group-toggle').click()
    await work.getByRole('button', { name: /File changes/ }).click()
    await expect(work.locator('.hljs-addition').first()).toBeVisible()
    await work.getByRole('button', { name: /Run command/ }).click()
    await expect(work.getByText('TypeScript: no errors found.', { exact: false })).toBeVisible()
    await screenshot(`${viewport.width}-activity-details`)
    await page.getByRole('button', { name: 'Open activity fullscreen' }).click()
    const viewer = page.getByRole('dialog', { name: 'Fullscreen activity' })
    await expect(viewer).toBeVisible()
    await expect(page.getByRole('button', { name: 'Exit fullscreen' })).toBeFocused()
    const fullBox = await viewer.boundingBox()
    expect(fullBox!.height).toBe(viewport.height)
    expect(fullBox!.width).toBe(viewport.width)
    await screenshot(`${viewport.width}-activity-fullscreen`)
    const research = viewer.locator('.activity-group').filter({ hasText: 'Plan · Research · Reading · Workspace · Terminal' })
    await research.locator('.activity-group-toggle').click()
    const read = research.locator('[data-kind="read"]').filter({ hasText: 'Read functions.ts' })
    await read.locator('.artifact-toggle').click()
    await expect(read.locator('.hljs-keyword').first()).toBeVisible()
    await read.getByRole('button', { name: 'Show command' }).click()
    await expect(read.getByRole('button', { name: 'Copy Command' })).toBeVisible()
    const skillRead = research.locator('[data-kind="read"]').filter({ hasText: 'Read SKILL.md' })
    await skillRead.locator('.artifact-toggle').click()
    await expect(skillRead.getByRole('heading', { name: 'CSS Baseline update and release' })).toBeVisible()
    await screenshot(`${viewport.width}-file-read-preview`)
    await skillRead.getByRole('button', { name: 'View source' }).click()
    await expect(skillRead.getByRole('button', { name: 'Copy File content' })).toBeVisible()
    const failed = research.locator('[data-status="error"]')
    await expect(failed.getByText('Failed', { exact: true })).toBeVisible()
    await expect(failed.getByText('Exit 1', { exact: true })).toBeVisible()
    await failed.scrollIntoViewIfNeeded()
    await screenshot(`${viewport.width}-operation-cards`)
    const checks = viewer.getByRole('region', { name: 'Workflow checks', exact: true })
    await checks.scrollIntoViewIfNeeded()
    await expect(checks.getByText('11 passed', { exact: true })).toBeVisible()
    await checks.getByRole('button', { name: /Show more/ }).click()
    await expect(checks.getByText('Skipped', { exact: true })).toBeVisible()
    await checks.locator('.data-content').evaluate(el => el.scrollTo(0, 0))
    await screenshot(`${viewport.width}-structured-checks`)
    await checks.locator('.data-source > summary').click()
    await expect(checks.locator('.hljs-attr').first()).toBeVisible()
    await expect(checks.getByRole('button', { name: 'Copy JSON' })).toBeVisible()
    await checks.locator('.data-source > summary').click()
    const pullRequest = viewer.getByRole('region', { name: 'Pull request details', exact: true })
    await pullRequest.scrollIntoViewIfNeeded()
    await expect(pullRequest.getByText('.changeset/september-baseline-authoring.md', { exact: true })).toBeVisible()
    await pullRequest.getByRole('button', { name: /Show more/ }).click()
    await expect(pullRequest.getByText('scripts/generate-css-feature-target.ts', { exact: true })).toBeVisible()
    await pullRequest.locator('.data-content').evaluate(el => el.scrollTo(0, 0))
    await screenshot(`${viewport.width}-structured-files`)
    await page.keyboard.press('Escape')
    await expect(viewer).not.toBeVisible()
    await expect(page.getByRole('button', { name: 'Open activity fullscreen' })).toBeFocused()
    await expect(work.getByText('TypeScript: no errors found.', { exact: false })).toBeVisible()
    await page.getByRole('button', { name: 'Task brief', exact: true }).click()
    await screenshot(`${viewport.width}-brief`)

    for (const [name, url, button] of [
      ['task-editor', '/tasks', 'New task'],
      ['agent-editor', '/agents', 'New agent'],
      ['project-editor', '/projects', 'Add project'],
      ['skill-editor', '/skills', 'New skill'],
      ['skill-files', '/skills', 'Edit review'],
      ['token-editor', '/settings', 'New token'],
    ]) {
      await navigate(url)
      await page.getByRole('button', { name: button, exact: true }).click()
      const dialog = page.getByRole('dialog')
      await expect(dialog).toBeVisible()
      const box = await dialog.boundingBox()
      expect(box!.y).toBeGreaterThanOrEqual(0)
      expect(box!.y + box!.height).toBeLessThanOrEqual(viewport.height)
      await screenshot(`${viewport.width}-${name}`)
      for (const select of await dialog.getByRole('combobox').all()) {
        if (await select.isDisabled())
          continue
        expect((await select.boundingBox())!.width).toBeGreaterThan(80)
        await select.click()
        const list = page.getByRole('listbox')
        await expect(list).toBeVisible()
        const popup = page.locator('.vs-popup:popover-open')
        const bounds = await popup.boundingBox()
        expect(bounds!.x).toBeGreaterThanOrEqual(0)
        expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(viewport.width)
        expect(bounds!.y).toBeGreaterThanOrEqual(0)
        expect(bounds!.y + bounds!.height).toBeLessThanOrEqual(viewport.height)
        await screenshot(`${viewport.width}-${name}-${await select.getAttribute('aria-label')}-select`)
        await page.keyboard.press('Escape')
        await expect(list).not.toBeVisible()
        await expect(dialog).toBeVisible()
      }
      await dialog.getByRole('button').last().scrollIntoViewIfNeeded()
      await expect(dialog.getByRole('button').last()).toBeInViewport()
      await page.keyboard.press('Escape')
      await expect(dialog).toHaveCount(0)
    }
    await page.getByRole('button', { name: 'Search workspace' }).click()
    await page.getByRole('dialog').getByLabel('Search', { exact: true }).fill('review')
    await screenshot(`${viewport.width}-workspace-search`)
    await page.keyboard.press('Escape')
  }

  await page.setViewportSize({ width: 390, height: 664 })
  await page.getByRole('button', { name: 'Open navigation' }).click()
  await expect(page.getByRole('button', { name: 'Close navigation' })).toBeFocused()
  await expect(page.locator('.main-area')).toHaveAttribute('inert', '')
  for (const height of [360, 568, 844]) {
    await page.setViewportSize({ width: 390, height })
    await expect.poll(() => page.locator('.sidebar').evaluate(el => el.clientHeight)).toBe(height)
    await page.locator('.sidebar').evaluate(el => el.scrollTo({ top: el.scrollHeight }))
    await expect(page.getByRole('button', { name: 'Sign out' })).toBeInViewport()
    await screenshot(`drawer-${height}-bottom`)
    await page.locator('.sidebar').evaluate(el => el.scrollTo({ top: 0 }))
    await screenshot(`drawer-${height}-top`)
  }
  await page.locator('.sidebar a').first().focus()
  await page.keyboard.press('Shift+Tab')
  await expect(page.getByRole('button', { name: 'Sign out' })).toBeFocused()
  await page.keyboard.press('Tab')
  await expect(page.locator('.sidebar a').first()).toBeFocused()
  await page.keyboard.press('Escape')
  await expect(page.getByRole('button', { name: 'Open navigation' })).toBeFocused()
  await expect(page.locator('.sidebar')).toHaveAttribute('inert', '')
  await expect(page.locator('.main-area')).not.toHaveAttribute('inert', '')
  await page.getByRole('button', { name: 'Open navigation' }).click()
  await page.getByRole('link', { name: 'Tasks', exact: true }).click()
  await expect(page.locator('.sidebar')).not.toHaveClass(/open/)
  await expect(page.locator('.task-card').first()).toBeVisible()
  const manyAgents = Array.from({ length: 10000 }, (_, index) => ({
    id: `virtual-${index}`,
    name: index === 4999 ? 'Équipe sécurité' : `Agent ${index.toString().padStart(5, '0')}`,
    description: `Maintains project ${index}`,
    model: '',
    reasoning: 'high',
  }))
  await page.route('**/api/agents', route => route.fulfill({ json: manyAgents }))
  await page.goto('/tasks')
  await page.getByRole('button', { name: 'New task', exact: true }).click()
  const agentSelect = page.getByRole('combobox', { name: 'Agent', exact: true })
  await agentSelect.click()
  await expect(page.getByRole('option').first()).toBeVisible()
  expect(await page.getByRole('option').count()).toBeLessThan(20)
  await agentSelect.press('End')
  await expect(page.getByRole('option', { name: 'Agent 09999', exact: true })).toBeVisible()
  expect(await page.getByRole('option').count()).toBeLessThan(20)
  await screenshot('select-10000-options')
  await agentSelect.press('Enter')
  await expect(agentSelect).toHaveValue('Agent 09999')
  await expect(page.getByRole('dialog')).toBeVisible()
  await agentSelect.click()
  await agentSelect.fill('equipe')
  await expect(page.getByRole('option')).toHaveCount(1)
  await agentSelect.press('Enter')
  await expect(agentSelect).toHaveValue('Équipe sécurité')
  await agentSelect.click()
  await agentSelect.fill('does-not-exist')
  await expect(page.getByText('No matches found', { exact: true })).toBeVisible()
  await screenshot('select-no-results')
  await agentSelect.press('Escape')
  await expect(agentSelect).toHaveValue('Équipe sécurité')
  await agentSelect.click()
  await agentSelect.press('Tab')
  await expect(page.getByRole('listbox')).not.toBeVisible()
  await expect(page.getByRole('combobox', { name: 'Project', exact: true })).toBeFocused()
  await page.keyboard.press('Escape')
  await page.unroute('**/api/agents')
  const legacyRoute = `**/api/runs/${run.id}/events?*`
  await page.route(legacyRoute, route => route.fulfill({ json: [
    { id: 1, runId: run.id, createdAt: 1000, type: 'item.started', text: '' },
    { id: 2, runId: run.id, createdAt: 1800, type: 'item.completed', text: 'Already up to date.\ncompatibility/css-feature-target.json' },
    { id: 3, runId: run.id, createdAt: 2000, type: 'item.started', text: '' },
    { id: 4, runId: run.id, createdAt: 2500, type: 'item.completed', text: '[{"id":123,"jobs":[{"name":"quality","conclusion":"success"}]}]' },
    { id: 5, runId: run.id, createdAt: 3000, type: 'item.started', text: '' },
    { id: 6, runId: run.id, createdAt: 3500, type: 'item.completed', text: 'implementation-pr {"files":["src/activity.ts"' },
  ] }))
  await page.goto(`/runs/${run.id}`)
  await page.getByRole('button', { name: /^Activity/ }).click()
  await page.locator('.activity-group-toggle').click()
  const historical = page.locator('.operation-card').first()
  await expect(historical.getByText('Recorded output', { exact: true })).toBeVisible()
  await expect(historical.getByText('Recorded', { exact: true })).toBeVisible()
  await historical.locator('.artifact-toggle').click()
  await expect(historical.getByText(/Its command and exit code weren’t recorded/)).toBeVisible()
  await screenshot('historical-operation')
  const checksCard = page.locator('.operation-card').nth(1)
  await expect(checksCard.locator('.artifact-heading')).toContainText('Workflow checks')
  await expect(checksCard.locator('.artifact-heading')).not.toContainText('"jobs"')
  await checksCard.locator('.artifact-toggle').click()
  await expect(checksCard.getByRole('region', { name: 'Workflow checks' })).toBeVisible()
  await expect(checksCard.locator('pre')).toHaveCount(0)
  const incompleteCard = page.locator('.operation-card').nth(2)
  await expect(incompleteCard.locator('.artifact-heading')).toContainText('Incomplete result')
  await expect(incompleteCard.locator('.artifact-heading')).not.toContainText('"files"')
  await incompleteCard.locator('.artifact-toggle').click()
  const incomplete = incompleteCard.getByRole('region', { name: 'Incomplete result' })
  await expect(incomplete).toBeVisible()
  await expect(incomplete.locator('pre')).toHaveCount(0)
  await incomplete.scrollIntoViewIfNeeded()
  await screenshot('historical-json-results')
  await incomplete.locator('summary').click()
  await expect(incomplete.getByRole('button', { name: 'Copy Saved source' })).toBeVisible()
  await expect(incomplete.locator('pre')).toContainText('{"files":["src/activity.ts"')
  await page.unroute(legacyRoute)
  await page.route(legacyRoute, route => route.fulfill({ json: [
    { id: 1, runId: run.id, createdAt: 1000, type: 'error', text: '', payload: { message: 'WebSocket connection failed: 503 Service Unavailable' } },
    { id: 2, runId: run.id, createdAt: 2000, type: 'item.completed', text: '', payload: { item: { type: 'command_execution', command: 'rg needle src', exit_code: 1, status: 'failed' } } },
    { id: 3, runId: run.id, createdAt: 3000, type: 'item.completed', text: '', payload: { item: { type: 'command_execution', command: 'diff before after', exit_code: 1, status: 'failed' } } },
    { id: 4, runId: run.id, createdAt: 4000, type: 'item.completed', text: '', payload: { item: { type: 'command_execution', command: 'pnpm test', exit_code: 1, status: 'failed' } } },
    { id: 5, runId: run.id, createdAt: 5000, type: 'turn.completed', text: '', payload: {} },
  ] }))
  await page.goto(`/runs/${run.id}`)
  await page.getByRole('button', { name: /^Activity/ }).click()
  await page.locator('.activity-group-toggle').click()
  for (const label of ['Recovered', 'No matches', 'Differences found']) {
    const card = page.locator('.operation-card').filter({ has: page.getByText(label, { exact: true }) })
    await expect(card).toHaveAttribute('data-status', 'info')
    await expect(card.locator('.operation-error')).toHaveCount(0)
  }
  await expect(page.locator('.operation-card[data-status="error"]')).toHaveCount(1)
  await screenshot('command-outcomes')
  await page.unroute(legacyRoute)
  expect(errors).toEqual([])
}

test('pages, dialogs, navigation and activity', async ({ page }, testInfo) => {
  await checkMobileLayouts(page, testInfo, testInfo.project.use.colorScheme === 'dark' ? 'dark' : 'light')
})
