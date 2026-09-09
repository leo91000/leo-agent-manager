import type { Page, TestInfo } from '@playwright/test'
import { expect, test } from '@playwright/test'

test.describe.configure({ mode: 'serial' })

// One fresh application exercises persistence between screens and real child execution.
test('set up, author skills, schedule work, inspect results, and sign out', async ({
  page,
}) => {
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  page.on('console', (message) => {
    if (
      message.type() === 'error'
      && !message
        .text()
        .startsWith(
          'Failed to load resource: the server responded with a status of 400',
        )
    ) {
      errors.push(message.text())
    }
  })
  await page.goto('/')
  await page.emulateMedia({ colorScheme: 'dark' })
  await page.screenshot({ path: 'test-results/theme-setup-desktop.png', fullPage: true, animations: 'disabled' })
  await page.setViewportSize({ width: 320, height: 568 })
  await page.screenshot({ path: 'test-results/theme-setup-mobile.png', fullPage: true, animations: 'disabled' })
  await page.setViewportSize({ width: 1280, height: 720 })
  await page.emulateMedia({ colorScheme: 'light' })
  await page.getByLabel('Setup token').fill('browser-test-setup')
  await page
    .getByLabel('Password', { exact: true })
    .fill('browser-password-long-enough')
  await page.getByRole('button', { name: 'Create workspace' }).click()
  await page.getByRole('link', { name: 'Agents', exact: true }).click()
  await page.getByRole('button', { name: 'New agent', exact: true }).click()
  await page
    .getByRole('dialog')
    .getByLabel('Name', { exact: true })
    .fill('Release engineer')
  await page.getByRole('button', { name: 'Save agent' }).click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await page.getByRole('link', { name: 'Projects', exact: true }).click()
  await page.getByRole('button', { name: 'Add project', exact: true }).click()
  await page
    .getByRole('dialog')
    .getByLabel('Name', { exact: true })
    .fill('Design system')
  await page.getByLabel('Project directory').fill('/does-not-exist')
  await page.getByRole('button', { name: 'Save project' }).click()
  await expect(page.getByRole('alert')).toContainText('does not exist')
  await page.emulateMedia({ colorScheme: 'dark' })
  await page.screenshot({ path: 'test-results/theme-validation-error.png', animations: 'disabled' })
  await page.emulateMedia({ colorScheme: 'light' })
  await page
    .getByLabel('Project directory')
    .fill('/tmp/leo-manager-browser-4322/project')
  await page.getByRole('button', { name: 'Save project' }).click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await page.getByRole('link', { name: 'Skills', exact: true }).click()
  await page.getByRole('button', { name: 'New skill', exact: true }).click()
  await page.getByLabel('Skill name').fill('review')
  await page
    .getByLabel('Skill content')
    .fill(
      '---\nname: review\ndescription: Review the project carefully\n---\nInspect the project and report checks.',
    )
  await page.getByRole('button', { name: 'Save skill' }).click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await page.getByRole('link', { name: 'Tasks', exact: true }).click()
  await page.getByRole('button', { name: 'New task', exact: true }).click()
  await page.getByLabel('Task name').fill('Weekly dependency review')
  await page
    .getByLabel('What should happen?')
    .fill('Review dependencies and report the checks you ran. fixture:activity')
  await page.getByRole('combobox', { name: 'When', exact: true }).click()
  await page.getByRole('option', { name: 'Every Monday', exact: true }).click()
  await page.getByLabel('Timezone').fill('Europe/Paris')
  await page.getByRole('button', { name: 'Preview next runs' }).click()
  await expect(page.locator('.schedule-preview span')).toHaveCount(3)
  await page.getByLabel('Timezone').fill('Invalid/Zone')
  await expect(page.locator('.schedule-preview span')).toHaveCount(0)
  await page.getByRole('button', { name: 'Preview next runs' }).click()
  await expect(page.getByRole('alert')).toContainText('IANA timezone')
  await page.getByLabel('Timezone').fill('Europe/Paris')
  await page.getByLabel('review', { exact: true }).check()
  await page.getByLabel(/Use an isolated Git worktree/).uncheck()
  await page.getByRole('button', { name: 'Create task', exact: true }).click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await expect(
    page.getByRole('heading', { name: 'Weekly dependency review' }),
  ).toBeVisible()
  await page.getByRole('button', { name: 'Run now', exact: true }).click()
  await expect(page).toHaveURL(/\/runs\//)
  await expect(page.locator('.run-title-meta .status')).toContainText(
    'succeeded',
  )
  await page.getByRole('button', { name: 'Result', exact: true }).click()
  await expect(page.getByText('The fixture task passed.')).toBeVisible()
  await page.reload()
  await expect(page.getByText('The fixture task passed.')).toBeVisible()
  await page.getByRole('link', { name: 'Tasks', exact: true }).click()
  await page
    .getByRole('button', { name: 'Pause schedule', exact: true })
    .click()
  await expect(page.getByText('Paused', { exact: true }).last()).toBeVisible()
  await page.getByRole('link', { name: 'Overview', exact: true }).click()
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.mouse.move(0, 0)
  await expect(page.locator('.toast')).toHaveCount(0)
  await page.screenshot({
    path: 'docs/screenshots/overview-desktop.png',
    fullPage: true,
    animations: 'disabled',
  })
  await page.setViewportSize({ width: 390, height: 844 })
  await expect
    .poll(() =>
      page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
    )
    .toBe(true)
  await page.screenshot({
    path: 'docs/screenshots/overview-mobile.png',
    fullPage: true,
    animations: 'disabled',
  })
  await page.getByRole('button', { name: 'Open navigation', exact: true }).click()
  await page.getByRole('button', { name: 'Sign out', exact: true }).click()
  await expect(
    page.getByRole('button', { name: 'Sign in', exact: true }),
  ).toBeVisible()
  expect(errors).toEqual([])
})

test('edits supporting files, cancels work, and archives without losing history', async ({
  page,
}) => {
  await page.goto('/')
  await page
    .getByLabel('Password', { exact: true })
    .fill('browser-password-long-enough')
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  await page.getByRole('link', { name: 'Skills', exact: true }).click()
  await page.getByRole('button', { name: 'Edit review', exact: true }).click()
  await page.getByLabel('New supporting file').fill('references/checks.md')
  await page.getByRole('button', { name: 'Add file', exact: true }).click()
  await page
    .getByLabel('Supporting file content')
    .fill('# Checks\nRun the test suite and inspect the diff.')
  await page.getByRole('button', { name: 'Save skill' }).click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await page.getByRole('button', { name: 'Edit review', exact: true }).click()
  await page.getByRole('combobox', { name: 'Skill file', exact: true }).click()
  await page.getByRole('option', { name: 'references/checks.md', exact: true }).click()
  await expect(page.getByLabel('Supporting file content')).toHaveValue(
    '# Checks\nRun the test suite and inspect the diff.',
  )
  await page.getByRole('button', { name: 'Preview', exact: true }).click()
  await expect(
    page.getByRole('heading', { name: 'Checks', exact: true }),
  ).toBeVisible()
  await page.keyboard.press('Escape')
  await page.getByRole('link', { name: 'Tasks', exact: true }).click()
  await page
    .getByRole('button', { name: 'Edit Weekly dependency review', exact: true })
    .click()
  await page.getByLabel('What should happen?').fill('fixture:hang')
  await page.getByRole('button', { name: 'Save changes' }).click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await page.getByRole('button', { name: 'Run now', exact: true }).click()
  await expect(page.locator('.run-title-meta .status')).toHaveText('running')
  await page.getByRole('button', { name: 'Stop run', exact: true }).click()
  await page
    .getByRole('dialog')
    .getByRole('button', { name: 'Stop run', exact: true })
    .click()
  await expect(page.locator('.run-title-meta .status')).toHaveText('cancelled')
  await page.getByRole('link', { name: 'Tasks', exact: true }).click()
  await page
    .getByRole('button', { name: 'Archive Weekly dependency review' })
    .click()
  await expect(
    page.getByRole('heading', { name: 'Weekly dependency review' }),
  ).toHaveCount(0)
  await page.getByRole('button', { name: 'Archived', exact: true }).click()
  await expect(
    page.getByRole('heading', { name: 'Weekly dependency review' }),
  ).toBeVisible()
  await expect(
    page.getByRole('button', { name: 'Run now', exact: true }),
  ).toBeDisabled()
  await page
    .getByRole('button', { name: 'Restore Weekly dependency review' })
    .click()
  await page.getByRole('button', { name: /^All tasks/ }).click()
  await expect(
    page.getByRole('heading', { name: 'Weekly dependency review' }),
  ).toBeVisible()
  await page.getByRole('link', { name: 'Runs', exact: true }).click()
  await expect(page.locator('tbody tr')).toHaveCount(2)
})

test('approves a scoped OAuth connector and revokes its grant', async ({
  page,
  request,
}) => {
  const { createHash } = await import('node:crypto')
  const verifier = 'test-verifier-'.repeat(5)
  const registration = await request.post('/oauth/register', {
    data: {
      client_name: 'Browser QA connector',
      redirect_uris: ['http://127.0.0.1:4322/oauth-test-callback'],
    },
  })
  const client = await registration.json()
  const params = new URLSearchParams({
    client_id: client.client_id,
    redirect_uri: client.redirect_uris[0],
    response_type: 'code',
    code_challenge: createHash('sha256').update(verifier).digest('base64url'),
    code_challenge_method: 'S256',
    scope: 'read run',
    resource: 'http://127.0.0.1:4322/mcp',
    state: 'qa-state',
  })
  await page.goto(`/oauth/authorize?${params}`)
  await page
    .getByLabel('Password', { exact: true })
    .fill('browser-password-long-enough')
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  await expect(
    page.getByText('Browser QA connector', { exact: true }),
  ).toBeVisible()
  await expect(
    page.getByText(
      'Start and cancel tasks in YOLO mode with full container access',
    ),
  ).toBeVisible()
  await page.route('**/oauth-test-callback?*', route =>
    route.fulfill({
      contentType: 'text/plain',
      body: 'Connector callback received',
    }))
  await page.emulateMedia({ colorScheme: 'dark' })
  await page.screenshot({ path: 'test-results/theme-consent-desktop.png', fullPage: true, animations: 'disabled' })
  await page.setViewportSize({ width: 320, height: 568 })
  await expect(page.locator('.sidebar')).toHaveAttribute('inert', '')
  await page.screenshot({ path: 'test-results/theme-consent-mobile.png', fullPage: true, animations: 'disabled' })
  await page.setViewportSize({ width: 1280, height: 720 })
  await page.emulateMedia({ colorScheme: 'light' })
  await page.getByRole('button', { name: 'Allow access' }).click()
  await expect(page).toHaveURL(/oauth-test-callback\?state=qa-state&code=/)
  const code = new URL(page.url()).searchParams.get('code')!
  const token = await request.post('/oauth/token', {
    form: {
      grant_type: 'authorization_code',
      code,
      client_id: client.client_id,
      redirect_uri: client.redirect_uris[0],
      code_verifier: verifier,
    },
  })
  expect(token.status()).toBe(200)
  const credentials = await token.json()
  expect(credentials.scope).toBe('read run')
  await page.goto('/settings')
  await expect(
    page.getByText('Browser QA connector', { exact: true }),
  ).toBeVisible()
  await page.getByRole('button', { name: /Revoke/ }).click()
  await expect(
    page.getByText('Browser QA connector', { exact: true }),
  ).toHaveCount(0)
  const denied = await request.post('/mcp', {
    headers: { authorization: `Bearer ${credentials.access_token}` },
    data: { jsonrpc: '2.0', id: 1, method: 'tools/list' },
  })
  expect(denied.status()).toBe(401)
})

async function checkMobileLayouts(page: Page, testInfo: TestInfo, colorScheme: 'light' | 'dark' = 'light') {
  await page.emulateMedia({ colorScheme })
  test.setTimeout(180000)
  // The screenshot matrix is a burst of real requests against the production
  // limiter. Begin with a fresh budget instead of weakening it in the fixture.
  const health = await page.request.get('/health')
  const headers = health.headers()
  if (Number(headers['x-ratelimit-remaining']) < 250)
    await new Promise(resolve => setTimeout(resolve, (Number(headers['x-ratelimit-reset']) + 1) * 1000))
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
  ] }))
  await page.goto(`/runs/${run.id}`)
  await page.getByRole('button', { name: /^Activity/ }).click()
  await page.locator('.activity-group-toggle').click()
  const historical = page.locator('.operation-card')
  await expect(historical.getByText('Recorded output', { exact: true })).toBeVisible()
  await expect(historical.getByText('Recorded', { exact: true })).toBeVisible()
  await historical.locator('.artifact-toggle').click()
  await expect(historical.getByText(/Its command and exit code weren’t recorded/)).toBeVisible()
  await screenshot('historical-operation')
  await page.unroute(legacyRoute)
  expect(errors).toEqual([])
}

test('Chromium mobile pages, dialogs and navigation', async ({ page }, testInfo) => {
  await checkMobileLayouts(page, testInfo)
})

test('WebKit mobile pages, dialogs and navigation', async ({ playwright }, testInfo) => {
  const browser = await playwright.webkit.launch()
  try {
    const context = await browser.newContext({
      baseURL: 'http://127.0.0.1:4322',
      viewport: { width: 390, height: 664 },
      isMobile: true,
      hasTouch: true,
    })
    await checkMobileLayouts(await context.newPage(), testInfo)
  }
  finally {
    await browser.close()
  }
})

for (const engine of ['chromium', 'webkit'] as const) {
  test(`${engine} dark desktop and mobile pages, dialogs and navigation`, async ({ playwright }, testInfo) => {
    const browser = await playwright[engine].launch()
    try {
      const context = await browser.newContext({ baseURL: 'http://127.0.0.1:4322', colorScheme: 'dark' })
      await checkMobileLayouts(await context.newPage(), testInfo, 'dark')
    }
    finally {
      await browser.close()
    }
  })
}

test('appearance follows the device, persists overrides, syncs tabs and paints before the app', async ({ page, context }, testInfo) => {
  await page.emulateMedia({ colorScheme: 'dark' })
  await page.goto('/')
  const root = page.locator('html')
  await expect(root).toHaveAttribute('data-theme', 'dark')
  await expect(root).toHaveAttribute('data-theme-preference', 'system')
  await page.screenshot({ path: testInfo.outputPath('login-dark-desktop.png'), fullPage: true, animations: 'disabled' })
  await page.setViewportSize({ width: 320, height: 568 })
  await page.screenshot({ path: testInfo.outputPath('login-dark-mobile.png'), fullPage: true, animations: 'disabled' })
  await page.getByRole('button', { name: 'Appearance: system', exact: true }).click()
  await page.screenshot({ path: testInfo.outputPath('theme-menu-mobile.png'), fullPage: false, animations: 'disabled' })
  await expect(page.getByRole('radio', { name: /System/ })).toBeFocused()
  const menu = await page.locator('.theme-popover').boundingBox()
  expect(menu!.y + menu!.height).toBeLessThanOrEqual(568)
  await page.locator('.theme-popover label').filter({ hasText: 'Light' }).click()
  await expect(root).toHaveAttribute('data-theme', 'light')
  await expect(page.getByRole('button', { name: 'Appearance: light', exact: true })).toBeFocused()
  await page.reload()
  await expect(root).toHaveAttribute('data-theme', 'light')
  await page.emulateMedia({ colorScheme: 'light' })
  await page.emulateMedia({ colorScheme: 'dark' })
  await expect(root).toHaveAttribute('data-theme', 'light')
  const other = await context.newPage()
  await other.goto('/')
  await other.getByRole('button', { name: 'Appearance: light', exact: true }).click()
  await other.locator('.theme-popover label').filter({ hasText: 'Dark' }).click()
  await expect(root).toHaveAttribute('data-theme', 'dark')
  await other.close()
  await page.getByRole('button', { name: 'Appearance: dark', exact: true }).click()
  await page.locator('.theme-popover label').filter({ hasText: 'System' }).click()
  await page.emulateMedia({ colorScheme: 'light' })
  await expect(root).toHaveAttribute('data-theme', 'light')
  await page.emulateMedia({ colorScheme: 'dark' })
  await expect(root).toHaveAttribute('data-theme', 'dark')
  await page.getByRole('button', { name: 'Appearance: system', exact: true }).click()
  await page.locator('.theme-popover label').filter({ hasText: 'Dark' }).click()
  await page.emulateMedia({ colorScheme: 'light' })
  // With the application module blocked, the saved override must already be painted.
  await page.route('**/assets/*.js', route => route.abort())
  await page.reload()
  await expect(page.locator('#app')).toBeEmpty()
  await expect(root).toHaveAttribute('data-theme', 'dark')
  expect(await root.evaluate(el => getComputedStyle(el).backgroundColor)).toBe('rgb(18, 26, 22)')
})

test('dark appearance settings, empty states and connection sign-in feedback', async ({ page }, testInfo) => {
  await page.emulateMedia({ colorScheme: 'dark' })
  await page.goto('/')
  await page.getByLabel('Password', { exact: true }).fill('browser-password-long-enough')
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  await expect(page.locator('.shell')).toBeVisible()
  await page.goto('/settings')
  await page.locator('.theme-control:not(.theme-compact) label').filter({ hasText: 'Light' }).click()
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'light')
  await page.locator('.theme-control:not(.theme-compact) label').filter({ hasText: 'Dark' }).click()
  await expect(page.getByRole('button', { name: 'Appearance: dark', exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Appearance: dark', exact: true }).click()
  await page.keyboard.press('Escape')
  await expect(page.locator('.theme-popover')).not.toBeVisible()
  await expect(page.getByRole('button', { name: 'Appearance: dark', exact: true })).toBeFocused()
  await page.route('**/api/tasks', route => route.fulfill({ json: [] }))
  await page.goto('/tasks')
  await expect(page.getByRole('heading', { name: 'What would you like to get done?', exact: true })).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('empty-tasks.png'), fullPage: true, animations: 'disabled' })
  await page.route('**/api/connections?*', route => route.fulfill({ json: [
    { provider: 'codex', installed: true, connected: false, version: 'Test CLI' },
    { provider: 'github', installed: false, connected: false },
  ] }))
  // Synthetic feedback only: never initiate a real device login or capture a real code.
  await page.route('**/api/connections/login', route => route.fulfill({ json: { state: 'pending', code: 'DEMO-CODE', url: 'https://example.com' } }))
  await page.goto('/connections')
  await page.getByRole('button', { name: /Connect account/ }).first().click()
  await expect(page.getByText('DEMO-CODE', { exact: true })).toBeVisible()
  for (const width of [1440, 320]) {
    await page.setViewportSize({ width, height: width === 320 ? 568 : 1000 })
    await page.screenshot({ path: testInfo.outputPath(`${width}-connection-pending.png`), fullPage: true, animations: 'disabled' })
  }
  await page.unroute('**/api/connections/login')
  await page.route('**/api/connections/login', route => route.fulfill({ json: { state: 'failed', error: 'The verification code expired. Please try again.' } }))
  await expect(page.getByText('Sign-in needs another try', { exact: true })).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('connection-failed.png'), fullPage: true, animations: 'disabled' })
})
