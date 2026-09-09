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
    .fill('Review dependencies and report the checks you ran.')
  await page.getByLabel(/^When/).selectOption('weekly')
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
  await page.getByRole('button', { name: /navigation|menu/i }).click()
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
  await page.getByLabel('Skill file').selectOption('references/checks.md')
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
