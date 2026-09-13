import type { BrowserContext, Page } from '@playwright/test'
import { randomUUID } from 'node:crypto'
import { expect, expectSingleScroll, initializeRepository, test } from './fixtures'

test('signing out closes live subscriptions before the session is revoked', async ({ page, workspace }) => {
  await page.goto(workspace.url)
  await page.getByLabel('Password', { exact: true }).fill('browser-password-long-enough')
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  await page.getByRole('link', { name: 'Chats', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'What are we building?' })).toBeVisible()
  const unauthorized: string[] = []
  page.on('response', (response) => {
    if (response.status() === 401)
      unauthorized.push(response.url())
  })
  await page.route('**/api/logout', async (route) => {
    const response = await route.fetch()
    // Leave the UI mounted after revocation: an open stream would immediately
    // close and trigger its access check before the logout response arrives.
    await new Promise(resolve => setTimeout(resolve, 800))
    await route.fulfill({ response })
  })
  await page.getByRole('button', { name: 'Sign out', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Sign in', exact: true })).toBeVisible()
  expect(unauthorized).toEqual([])
})

test('two independent clients follow deltas, recover offline, refresh mid-answer and survive a server restart', async ({ page, browser, workspace }) => {
  test.setTimeout(90000)
  initializeRepository(workspace.projectPath)
  const chat = await workspace.api('/api/chats', 'POST', {})
  const contexts: BrowserContext[] = []
  const signIn = async (target: Page) => {
    await target.goto(workspace.url)
    await target.getByLabel('Password', { exact: true }).fill('browser-password-long-enough')
    await target.getByRole('button', { name: 'Sign in', exact: true }).click()
    await expect(target.getByRole('link', { name: 'Chats', exact: true })).toBeVisible()
    await target.goto(`${workspace.url}/chats/${chat.id}`)
  }
  const message = (target: Page) => target.locator('.activity-message').filter({ hasText: 'Streaming proof:' })
  const requests: string[] = []
  page.on('request', (request) => {
    if (request.url().includes('/api/'))
      requests.push(request.url())
  })
  try {
    const second = await browser.newContext({ baseURL: workspace.url })
    contexts.push(second)
    const other = await second.newPage()
    await Promise.all([signIn(page), signIn(other)])
    await workspace.api(`/api/chats/${chat.id}/messages`, 'POST', { id: randomUUID(), text: 'fixture:stream' })
    await expect(message(page)).toContainText('005')
    await expect(message(other)).toContainText('005')
    await second.setOffline(true)
    await expect(message(page)).toContainText('020')
    await expect(other.getByRole('status').filter({ hasText: /Offline|Reconnecting/ })).toBeVisible()
    await second.setOffline(false)
    await expect(message(other)).toContainText('025')
    await page.reload()
    await expect(message(page)).toContainText('035')
    await expect(message(page)).not.toContainText('100')
    await page.setViewportSize({ width: 390, height: 844 })
    await page.emulateMedia({ colorScheme: 'dark' })
    await expectSingleScroll(page)
    await page.screenshot({ path: test.info().outputPath('stream-mobile-dark.png'), animations: 'disabled' })
    await expect(message(page)).toContainText('100')
    await expect(message(other)).toContainText('100')
    await expect(message(page)).toHaveCount(1)
    await expect(message(other)).toHaveCount(1)
    expect(await message(page).textContent()).toBe(await message(other).textContent())
    await expect(page.getByText('Ready', { exact: true })).toBeVisible()
    // No event/metadata polling is required while watching the conversation.
    expect(requests.some(url => /\/events\?|\/artifacts$/.test(url))).toBe(false)
    await workspace.restart()
    await workspace.api(`/api/chats/${chat.id}/messages`, 'POST', { id: randomUUID(), text: 'After server restart' })
    await expect(page.locator('.activity-message').filter({ hasText: 'After server restart' })).toHaveCount(2)
    await expect(other.locator('.activity-message').filter({ hasText: 'After server restart' })).toHaveCount(2)
    await expect(message(page)).toHaveCount(1)
    await expect(message(other)).toHaveCount(1)
    const third = await browser.newContext({ baseURL: workspace.url })
    contexts.push(third)
    const late = await third.newPage()
    await signIn(late)
    await expect(message(late)).toHaveCount(1)
    await expect(message(late)).toContainText('100')
    await expect(late.locator('.activity-message').filter({ hasText: 'After server restart' })).toHaveCount(2)
    // Route changes dispose old streams and cannot mix conversations.
    const empty = await workspace.api('/api/chats', 'POST', {})
    await page.goto(`${workspace.url}/chats/${empty.id}`)
    await expect(message(page)).toHaveCount(0)
    await page.goto(`${workspace.url}/chats/${chat.id}`)
    await expect(message(page)).toHaveCount(1)
  }
  finally {
    for (const context of contexts) await context.close()
  }
})
