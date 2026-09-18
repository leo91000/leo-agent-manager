import { randomUUID } from 'node:crypto'
import { expect, expectSingleScroll, initializeRepository, test } from './fixtures'

test('task outcomes stay with the reply and expose evidence without crowding the composer', async ({ page, workspace }) => {
  test.setTimeout(60000)
  const chat = await workspace.api('/api/chats', 'POST', {})
  const messageId = randomUUID()
  await workspace.api(`/api/chats/${chat.id}/messages`, 'POST', { id: messageId, text: 'Review the Android fix and report the checks.' })
  await expect.poll(() => workspace.service.chats.detail(chat.id).run?.status).toBe('succeeded')
  const run = workspace.service.chats.detail(chat.id).run!
  const artifactPath = `/api/runs/${run.id}/artifacts/${randomUUID()}`
  const outcome = {
    status: 'completed' as const,
    reason: 'Android fix pushed to main. Signed APK published and checks passed.',
    evidence: [
      'Changes pushed to **main** · https://github.com/leo91000/leo-agent-manager/commit/2b62b45',
      '**Local validation passed**\n\n73 tests · lint · debug & release builds',
      '**Android CI passed** · https://github.com/leo91000/leo-agent-manager/actions/runs/35315999841',
      `Published APK: ${artifactPath}`,
      '[Review notes](https://example.test/review) · [Unsafe link](javascript:alert(1))',
    ],
    reportedAt: Date.now(),
    messageId,
  }
  workspace.service.store.updateRun(run.id, { outcome })
  await page.goto(`/chats/${chat.id}`)
  await page.getByLabel('Password', { exact: true }).fill('browser-password-long-enough')
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  const panel = page.getByRole('region', { name: 'Task outcome', exact: true })
  await expect(panel.getByText('Task completed', { exact: true })).toBeVisible()
  expect(await panel.evaluate(element => !!element.closest('.activity-scroll'))).toBe(true)
  expect(await panel.evaluate(element => element.previousElementSibling?.classList.contains('activity-message'))).toBe(true)
  await expect(page.getByRole('button', { name: /Session updates/ })).toHaveCount(0)
  expect(await panel.evaluate(element => element.parentElement?.lastElementChild === element)).toBe(true)
  const toggle = panel.getByRole('button', { name: 'View evidence', exact: true })
  await expect(toggle).toHaveAttribute('aria-expanded', 'false')
  await expect(panel.getByText(outcome.reason, { exact: true })).toHaveCount(0)
  await toggle.focus()
  await page.keyboard.press('Enter')
  await expect(panel.getByRole('button', { name: 'Hide evidence' })).toHaveAttribute('aria-expanded', 'true')
  await expect(panel.getByText('73 tests · lint · debug & release builds')).toBeVisible()
  await expect(panel.getByRole('link', { name: 'View commit' })).toHaveAttribute('href', 'https://github.com/leo91000/leo-agent-manager/commit/2b62b45')
  await expect(panel.getByRole('link', { name: 'View workflow' })).toHaveAttribute('target', '_blank')
  await expect(panel.getByRole('link', { name: 'View workflow' })).toHaveAttribute('rel', 'noopener noreferrer')
  await expect(panel.getByRole('link', { name: 'Review notes' })).toHaveAttribute('href', 'https://example.test/review')
  await expect(panel.getByRole('link', { name: 'View file' })).toHaveAttribute('href', artifactPath)
  await expect(panel.locator('a[href^="javascript:"]')).toHaveCount(0)
  await panel.getByRole('button', { name: 'Hide evidence' }).blur()
  for (const colorScheme of ['dark', 'light'] as const) {
    await page.emulateMedia({ colorScheme })
    for (const width of [1440, 390]) {
      await page.setViewportSize({ width, height: 1000 })
      await panel.scrollIntoViewIfNeeded()
      await expectSingleScroll(page)
      await expect(page.getByRole('textbox', { name: 'Message', exact: true })).toBeInViewport()
      await page.screenshot({ path: test.info().outputPath(`outcome-expanded-${width}-${colorScheme}.png`), animations: 'disabled' })
    }
  }
  await panel.getByRole('button', { name: 'Hide evidence' }).click()
  await expect(panel.getByRole('link', { name: 'View commit' })).toHaveCount(0)
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.emulateMedia({ colorScheme: 'dark' })
  await page.screenshot({ path: test.info().outputPath('outcome-collapsed-desktop-dark.png'), animations: 'disabled' })
  // The next turn must never inherit the previous reply's completion report.
  await workspace.api(`/api/chats/${chat.id}/messages`, 'POST', { id: randomUUID(), text: 'Another reply without a completion report' })
  await expect.poll(() => workspace.service.chats.detail(chat.id).run?.status).toBe('succeeded')
  await page.reload()
  await expect(panel).toHaveCount(0)
  // Outcomes without evidence still expose their reason; attention stays visible.
  for (const status of ['completed', 'blocked', 'needs_input'] as const) {
    workspace.service.store.updateRun(run.id, { outcome: { ...outcome, status, reason: 'A concrete explanation of this result.', evidence: [], reportedAt: Date.now() } })
    await page.reload()
    await expect(panel.getByRole('button', { name: 'View details' })).toBeVisible()
    if (status === 'completed') {
      await expect(panel.getByText('A concrete explanation of this result.')).toHaveCount(0)
      await panel.getByRole('button', { name: 'View details' }).click()
    }
    await expect(panel.getByText('A concrete explanation of this result.')).toBeVisible()
  }
  workspace.service.store.updateRun(run.id, { status: 'failed' })
  await page.reload()
  await expect(panel).toHaveCount(0)
})

test('failed replies show the current error instead of an old answer and can be dismissed', async ({ page, workspace }) => {
  const chat = await workspace.api('/api/chats', 'POST', {})
  await workspace.api(`/api/chats/${chat.id}/messages`, 'POST', { id: randomUUID(), text: 'A successful first reply' })
  await expect.poll(() => workspace.service.chats.detail(chat.id).run?.status).toBe('succeeded')
  const firstRun = workspace.service.chats.detail(chat.id).run!
  workspace.service.store.event(firstRun.id, 'error', 'Validation needs attention.', { message: 'Validation needs attention.' })
  workspace.service.store.event(firstRun.id, 'status', 'interrupted')
  await page.goto(`/chats/${chat.id}`)
  await page.getByLabel('Password', { exact: true }).fill('browser-password-long-enough')
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  await expect(page.getByRole('note').filter({ hasText: 'Validation needs attention.' })).toBeVisible()
  await expect(page.getByRole('note').filter({ hasText: 'Interrupted' })).toBeVisible()
  await expect(page.getByRole('button', { name: /Session updates/ })).toHaveCount(0)
  for (const width of [1440, 390]) {
    await page.setViewportSize({ width, height: 844 })
    await expectSingleScroll(page)
    await expect(page.getByRole('textbox', { name: 'Message', exact: true })).toBeInViewport()
  }
  // Diagnostic run activity still has the complete lifecycle records.
  await page.goto(`/runs/${firstRun.id}`)
  await page.getByRole('button', { name: /^Activity/ }).click()
  await expect(page.getByRole('button', { name: /Session updates/ }).first()).toBeVisible()
  await workspace.api(`/api/chats/${chat.id}/messages`, 'POST', { id: randomUUID(), text: 'fixture:disconnect' })
  await expect.poll(() => workspace.service.chats.detail(chat.id).run?.status).toBe('failed')
  await page.goto(`/chats/${chat.id}`)
  await expect(page.getByRole('alert')).toContainText('Codex')
  await expect(page.getByRole('alert')).not.toContainText('A successful first reply')
  for (const colorScheme of ['dark', 'light'] as const) {
    await page.emulateMedia({ colorScheme })
    await page.setViewportSize({ width: 390, height: 844 })
    await expectSingleScroll(page)
    await page.screenshot({ path: test.info().outputPath(`chat-failure-${colorScheme}.png`), animations: 'disabled' })
  }
  await page.getByRole('button', { name: 'Dismiss error' }).click()
  await expect(page.getByRole('alert')).toHaveCount(0)
  await page.reload()
  await expect(page.getByRole('alert')).toContainText('Codex')
  const run = workspace.service.chats.detail(chat.id).run!
  workspace.service.store.updateRun(run.id, { error: null, summary: 'An old **successful** reply' })
  await page.reload()
  await expect(page.getByRole('alert')).toContainText('The response stopped before finishing')
  await expect(page.getByRole('alert')).not.toContainText('successful')
})

test('starts project chats, steers, edits the queue and preserves a compact mobile composer', async ({ page, workspace }) => {
  initializeRepository(workspace.projectPath)
  await page.goto('/')
  await page.getByLabel('Password', { exact: true }).fill('browser-password-long-enough')
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  await expect(page.locator('.task-focus-detail')).toBeVisible()
  await page.goto('/projects')
  await page.getByRole('link', { name: 'Start chat' }).first().click()
  await expect(page.getByRole('combobox', { name: 'Chat agent' })).toHaveValue('Main agent')
  await expect(page.getByRole('combobox', { name: 'Chat project' })).toHaveValue('Design system')
  await page.setViewportSize({ width: 1440, height: 1000 })
  await expectSingleScroll(page)
  await page.screenshot({ path: test.info().outputPath('chat-new-desktop-light.png'), animations: 'disabled' })
  await page.getByRole('textbox', { name: 'Message', exact: true }).fill('Review our component architecture. fixture:chat-hang')
  await page.getByRole('button', { name: 'Send', exact: true }).click()
  await expect(page).toHaveURL(/\/chats\/[a-f0-9-]+$/)
  await expect(page.getByText('Working', { exact: true }).first()).toBeVisible()
  await expect(page.getByRole('button', { name: /Session updates/ })).toHaveCount(0)
  await expect(page.locator('.activity-message').filter({ hasText: 'Review our component architecture' })).toBeVisible()
  await page.getByRole('textbox', { name: 'Message', exact: true }).fill('Add a regression test for the composer')
  await page.getByRole('button', { name: 'Queue', exact: true }).click()
  await expect(page.getByText('1 queued', { exact: false })).toBeVisible()
  await page.getByRole('button', { name: 'Edit queued message' }).click()
  await page.getByRole('textbox', { name: 'Message', exact: true }).fill('Add a regression test for keyboard navigation')
  await page.getByRole('button', { name: 'Save', exact: true }).click()
  await page.getByRole('textbox', { name: 'Message', exact: true }).fill('Focus on accessibility first.')
  await page.getByRole('button', { name: 'Steer now' }).click()
  await expect(page.locator('.activity-message').filter({ hasText: 'Focus on accessibility first.' }).first()).toBeVisible()
  await page.getByRole('textbox', { name: 'Message', exact: true }).fill('Consider the mobile experience too')
  await page.setViewportSize({ width: 390, height: 844 })
  await page.emulateMedia({ colorScheme: 'dark' })
  await expect(page.locator('.sidebar')).not.toBeInViewport()
  await expectSingleScroll(page)
  await expect(page.getByRole('button', { name: 'Queue', exact: true })).toBeInViewport()
  await page.screenshot({ path: test.info().outputPath('chat-mobile-dark.png'), animations: 'disabled' })
  await page.emulateMedia({ colorScheme: 'light' })
  await page.screenshot({ path: test.info().outputPath('chat-mobile-light.png'), animations: 'disabled' })
  await page.setViewportSize({ width: 320, height: 600 })
  await expect(page.getByRole('button', { name: 'Queue', exact: true })).toBeInViewport()
  await page.emulateMedia({ colorScheme: 'dark' })
  await expectSingleScroll(page)
  await expect(page.getByRole('button', { name: 'Steer now' })).toBeInViewport()
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.screenshot({ path: test.info().outputPath('chat-desktop-dark.png'), animations: 'disabled' })
  await page.emulateMedia({ colorScheme: 'light' })
  await page.screenshot({ path: test.info().outputPath('chat-desktop-light.png'), animations: 'disabled' })
  await page.reload()
  await expect(page.getByRole('textbox', { name: 'Message', exact: true })).toHaveValue('Consider the mobile experience too')
  await page.getByRole('textbox', { name: 'Message', exact: true }).fill('finish now')
  await page.getByRole('button', { name: 'Steer now' }).click()
  await expect.poll(() => workspace.service.chats.detail(workspace.service.store.list('chats')[0].id).messages.filter(message => message.status !== 'delivered').length).toBe(0)
  await expect(page.getByText('Ready', { exact: true })).toBeVisible()
  await expect(page.locator('.activity-message').filter({ hasText: 'Add a regression test for keyboard navigation' }).last()).toBeVisible()
  await page.goto('/agents')
  await page.locator('article').filter({ has: page.getByRole('heading', { name: 'Release engineer', exact: true }) }).getByRole('link', { name: 'Start chat' }).click()
  await expect(page.getByRole('combobox', { name: 'Chat agent' })).toHaveValue('Release engineer')
})

test('answers in-flight questions with choices or free text on desktop and mobile', async ({ page, workspace }) => {
  initializeRepository(workspace.projectPath)
  await page.goto('/')
  await page.getByLabel('Password', { exact: true }).fill('browser-password-long-enough')
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  await expect(page.locator('.task-focus-detail')).toBeVisible()
  await page.goto('/chats')
  await page.getByRole('textbox', { name: 'Message', exact: true }).fill('Plan the navigation refresh. fixture:question')
  await page.getByRole('button', { name: 'Send', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Answer pending questions' })).toBeVisible()
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.emulateMedia({ colorScheme: 'light' })
  await expectSingleScroll(page)
  await page.screenshot({ path: test.info().outputPath('questions-chat-desktop.png'), animations: 'disabled' })
  await page.getByRole('button', { name: 'Answer', exact: true }).click()
  await expect(page.getByText('Agent keeps working', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Send answer', exact: true })).toBeDisabled()
  await expect(page.getByRole('radio').first()).not.toBeChecked()
  await expectSingleScroll(page)
  await page.screenshot({ path: test.info().outputPath('questions-desktop-light.png'), animations: 'disabled' })
  await page.emulateMedia({ colorScheme: 'dark' })
  await page.screenshot({ path: test.info().outputPath('questions-desktop-dark.png'), animations: 'disabled' })
  await page.getByRole('radio', { name: /Gradual rollout/ }).check()
  await expect(page.getByRole('button', { name: 'Send answer', exact: true })).toBeEnabled()
  await page.getByRole('button', { name: 'Answer later' }).click()
  await page.reload()
  await expect(page.getByRole('button', { name: 'Answer pending questions' })).toBeVisible()
  await page.setViewportSize({ width: 390, height: 844 })
  await page.getByRole('button', { name: 'Answer', exact: true }).click()
  await expect(page.locator('.sidebar')).not.toBeInViewport()
  await expectSingleScroll(page)
  await page.screenshot({ path: test.info().outputPath('questions-mobile-dark.png'), animations: 'disabled' })
  await page.emulateMedia({ colorScheme: 'light' })
  await page.getByRole('textbox', { name: 'Or write your own answer' }).fill('Start with the chat, keeping keyboard navigation intact.')
  await page.screenshot({ path: test.info().outputPath('questions-mobile-light.png'), animations: 'disabled' })
  await page.setViewportSize({ width: 320, height: 600 })
  await expectSingleScroll(page)
  await page.getByRole('button', { name: 'Send answer', exact: true }).click()
  await expect(page.getByRole('dialog')).not.toBeVisible()
  await expect(page.getByRole('button', { name: 'Answer pending questions' })).not.toBeVisible()
  await expect(page.getByText('Ready', { exact: true })).toBeVisible()
  const chatId = page.url().split('/').at(-1)!
  expect(workspace.service.questions.list(chatId)[0].status).toBe('answered')
  await page.setViewportSize({ width: 390, height: 844 })
  await page.getByRole('button', { name: 'Question notifications' }).click()
  await expect(page.getByRole('heading', { name: 'Question notifications' })).toBeVisible()
  await page.screenshot({ path: test.info().outputPath('notifications-mobile.png'), animations: 'disabled' })
})

test('opts into device notifications and can revoke that device', async ({ page, context, browserName, workspace }) => {
  test.skip(browserName !== 'chromium', 'Permission automation uses Chromium; WebKit renders the same settings in the chat journey.')
  // This independent journey must not inherit the chat tests' request budget.
  await workspace.restart()
  const { createECDH, randomBytes } = await import('node:crypto')
  const curve = createECDH('prime256v1')
  curve.generateKeys()
  const subscription = { endpoint: 'https://fcm.googleapis.com/fcm/send/browser-fixture', keys: { p256dh: curve.getPublicKey().toString('base64url'), auth: randomBytes(16).toString('base64url') } }
  await context.grantPermissions(['notifications'], { origin: workspace.url })
  await page.addInitScript((data) => {
    // Keep native service worker registration, but replace the OS permission
    // prompt and push provider so this test does not contact an external service.
    Object.defineProperty(Notification, 'permission', { get: () => 'default' })
    Notification.requestPermission = async () => 'granted'
    let current: object | null = null
    PushManager.prototype.getSubscription = async () => current as PushSubscription | null
    PushManager.prototype.subscribe = async () => {
      current = { toJSON: () => data, unsubscribe: async () => {
        current = null
        return true
      } }
      return current as PushSubscription
    }
  }, subscription)
  await page.goto('/')
  await page.getByLabel('Password', { exact: true }).fill('browser-password-long-enough')
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  await expect(page.locator('.task-focus-detail')).toBeVisible()
  await page.goto('/chats')
  await page.getByRole('button', { name: 'Question notifications' }).click()
  await page.getByRole('button', { name: 'Enable on this device' }).click()
  await expect(page.getByText('Notifications on', { exact: true })).toBeVisible()
  expect(workspace.service.store.keys('push-device:')).toHaveLength(1)
  await page.getByRole('button', { name: 'Disable on this device' }).click()
  await expect(page.getByRole('button', { name: 'Enable on this device' })).toBeEnabled()
  expect(workspace.service.store.keys('push-device:')).toHaveLength(0)
})
