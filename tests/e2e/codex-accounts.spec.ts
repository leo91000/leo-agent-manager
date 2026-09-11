import { mkdir, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { accountFixture, limits } from '../codex-account-fixture'
import { expect, expectSingleScroll, test } from './fixtures'

test('structured sign-in supports copying, mobile layouts, cancellation, retry and automatic completion', async ({ page, workspace, context }, testInfo) => {
  test.setTimeout(90000)
  const data = await accountFixture({ service: workspace.service })
  const account = data.seed('Second account', limits(20, 30))
  const home = path.join(workspace.service.config.dataDir, 'codex-login', account.id, '.codex')
  async function prepare(mode: string) {
    await mkdir(home, { recursive: true })
    await writeFile(path.join(home, 'fixture-login.json'), JSON.stringify({ mode }))
  }
  await prepare('hold')
  await workspace.api('/api/codex/accounts/login', 'POST', { name: account.name, id: account.id })
  await page.goto('/connections')
  await page.getByLabel('Password', { exact: true }).fill('browser-password-long-enough')
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  const panel = page.getByRole('region', { name: 'Connect your ChatGPT account' })
  await expect(panel.getByLabel('Verification code', { exact: true })).toHaveText('ABCD-12345')
  await page.reload()
  await expect(panel).toBeVisible()
  for (const theme of ['light', 'dark'] as const) {
    await page.emulateMedia({ colorScheme: theme })
    for (const width of [1440, 390, 320]) {
      await page.setViewportSize({ width, height: 844 })
      await expectSingleScroll(page)
      await panel.screenshot({ path: testInfo.outputPath(`sign-in-${theme}-${width}.png`), animations: 'disabled' })
      await page.screenshot({ path: testInfo.outputPath(`connections-${theme}-${width}.png`), animations: 'disabled' })
    }
  }
  // Keep the external provider fully synthetic, including clipboard permissions.
  await context.route('https://auth.openai.com/**', route => route.fulfill({ contentType: 'text/html', body: '<h1>Fixture verification page</h1>' }))
  await page.evaluate(() => {
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: async (text: string) => {
      document.documentElement.dataset.copiedCode = text
    } } })
  })
  const popupPromise = page.waitForEvent('popup')
  await panel.getByRole('link', { name: 'Copy code & open sign-in' }).click()
  const popup = await popupPromise
  await expect(popup.getByRole('heading')).toHaveText('Fixture verification page')
  await popup.close()
  await expect(page.locator('html')).toHaveAttribute('data-copied-code', 'ABCD-12345')
  await expect(panel.getByRole('button', { name: 'Code copied' })).toBeVisible()
  await page.evaluate(() => {
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: async () => {
      throw new Error('Permission denied')
    } } })
  })
  await panel.getByRole('button', { name: 'Code copied' }).click()
  await expect(panel.getByRole('alert')).toContainText('copy it manually')
  await panel.getByRole('button', { name: 'Cancel sign-in' }).click()
  await expect(panel).toHaveCount(0)
  await expect.poll(() => workspace.api('/api/codex/accounts/login')).toBeNull()

  await prepare('failure')
  await page.getByRole('article', { name: account.name, exact: true }).getByRole('button', { name: 'Reconnect' }).click()
  const failed = page.getByRole('region', { name: 'Let’s try that again' })
  await expect(failed).toBeVisible()
  await expect(failed.getByRole('alert')).not.toContainText('synthetic secret')
  const count = (await workspace.api('/api/codex/accounts')).length
  await prepare('hold')
  await failed.getByRole('button', { name: 'Try again' }).click()
  await expect(panel).toBeVisible()
  expect((await workspace.api('/api/codex/accounts')).length).toBe(count)
  await writeFile(path.join(home, 'fixture-login-approve'), '')
  await expect(panel).toHaveCount(0)
  await expect.poll(async () => (await workspace.api('/api/codex/accounts/login')).state).toBe('complete')
  await expect(page.getByRole('article', { name: account.name, exact: true }).getByText('Finish sign-in', { exact: true })).toHaveCount(0)
  await workspace.api(`/api/codex/accounts/${account.id}`, 'DELETE')
})

test('accounts show live usage, reset windows and accessible controls across themes and mobile sizes', async ({ page, workspace }, testInfo) => {
  // This journey covers multiple viewports and screenshots; individual assertions retain their deadlines.
  test.setTimeout(90000)
  await workspace.service.accounts.close()
  const data = await accountFixture({ service: workspace.service })
  const personal = data.seed('Personal', limits(28, 37))
  const work = data.seed('Work', { ...limits(4, 18), rateLimitResetCredits: { availableCount: 3 } })
  const empty = data.seed('Almost empty', limits(97, 32))
  const paused = data.seed('Travel', limits(20, 50))
  data.pool.update(paused.id, { name: paused.name, enabled: false })
  for (const account of [personal, work, empty, paused]) await workspace.setAccountUsage(account.id, account.limits)
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  await page.goto('/connections')
  await page.getByLabel('Password', { exact: true }).fill('browser-password-long-enough')
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  const card = page.getByRole('article', { name: 'Work', exact: true })
  await expect(card.getByText('Next run', { exact: true })).toBeVisible()
  await expect(card.getByText('Banked resets: 3 · Automatic at 2% remaining')).toBeVisible()
  await expect(card.getByRole('progressbar', { name: 'Work Weekly remaining' })).toHaveAttribute('aria-valuenow', '82')
  for (const theme of ['light', 'dark'] as const) {
    await page.emulateMedia({ colorScheme: theme })
    for (const width of [1440, 390, 320]) {
      await page.setViewportSize({ width, height: width === 1440 ? 1050 : 844 })
      await expectSingleScroll(page)
      await page.screenshot({ path: testInfo.outputPath(`${theme}-${width}-accounts.png`), animations: 'disabled' })
      if (width === 390)
        await card.screenshot({ path: testInfo.outputPath(`${theme}-mobile-banked-resets.png`), animations: 'disabled' })
    }
  }
  await card.getByRole('button', { name: 'Pause Work', exact: true }).click()
  await expect(card.getByText('Paused', { exact: true })).toBeVisible()
  await expect(card.getByText('Banked resets: 3 · Automatic use paused')).toBeVisible()
  await expect(page.getByRole('article', { name: 'Personal', exact: true }).getByText('Next run', { exact: true })).toBeVisible()
  await workspace.setAccountUsage(personal.id, limits(100, 37))
  await page.getByRole('button', { name: 'Refresh Codex usage', exact: true }).click()
  await expect(page.getByRole('article', { name: 'Personal', exact: true }).getByText('Waiting for reset')).toBeVisible()
  await workspace.setAccountUsage(personal.id, limits(0, 37))
  await workspace.api('/api/codex/accounts/refresh', 'POST')
  await expect(page.getByRole('article', { name: 'Personal', exact: true }).getByText('Next run', { exact: true })).toBeVisible({ timeout: 8000 })
  await card.getByRole('button', { name: 'Edit Work', exact: true }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByLabel('Account name', { exact: true }).fill('Work subscription')
  await dialog.getByRole('spinbutton', { name: /Parallel runs/ }).fill('2')
  await page.screenshot({ path: testInfo.outputPath('dark-mobile-edit.png'), animations: 'disabled' })
  await dialog.getByRole('button', { name: 'Save account', exact: true }).click()
  await expect(dialog).not.toBeVisible()
  await expect(page.getByRole('article', { name: 'Work subscription', exact: true })).toBeVisible()
  expect(data.pool.get(work.id).name).toBe('Work subscription')
  await expect(page.getByRole('article', { name: 'Work subscription', exact: true }).getByText('0 / 2 parallel runs')).toBeVisible()
  await page.getByRole('button', { name: 'Add account', exact: true }).click()
  await dialog.getByLabel('Account name', { exact: true }).fill('New subscription')
  await dialog.getByRole('button', { name: 'Continue to sign in', exact: true }).click()
  await expect(page.getByRole('article', { name: 'New subscription', exact: true })).toBeVisible()
  await expect.poll(async () => (await workspace.api('/api/codex/accounts/login'))?.state).toBe('complete')
  await expect(page.getByRole('article', { name: 'New subscription', exact: true }).getByText('Ready', { exact: true })).toBeVisible({ timeout: 8000 })
  await page.getByRole('button', { name: 'Remove New subscription', exact: true }).click()
  await dialog.getByRole('button', { name: 'Remove account', exact: true }).click()
  await expect(page.getByRole('article', { name: 'New subscription', exact: true })).toHaveCount(0)
  expect(errors).toEqual([])
})
