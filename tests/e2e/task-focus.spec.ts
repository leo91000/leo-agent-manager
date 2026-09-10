import { expect, test } from './fixtures'

test('keeps task selection across reloads and discards a previous run response', async ({ page }) => {
  await page.goto('/')
  await page.getByLabel('Password', { exact: true }).fill('browser-password-long-enough')
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  await expect(page.locator('.task-focus-detail')).toBeVisible()
  const session = await page.request.get('/api/session').then(response => response.json())
  const existing = await page.request.get('/api/tasks').then(response => response.json())
  const activity = await page.request.get('/api/tasks/activity').then(response => response.json())
  const created = await page.request.post('/api/tasks', { headers: { 'X-CSRF-Token': session.csrf }, data: { name: 'Fresh task without a run', prompt: 'This is the new task brief.', agentId: existing[0].agentId, enabled: false, worktree: false } })
  expect(created.ok()).toBe(true)
  const task = await created.json()
  let release!: () => void
  const gate = new Promise<void>(resolve => release = resolve)
  await page.route(`**/api/runs/${activity[0].id}/events?*`, async (route) => {
    await gate
    await route.fulfill({ json: [{ id: 9999, runId: activity[0].id, type: 'item.completed', createdAt: Date.now(), text: 'STALE RUN MESSAGE', payload: { item: { type: 'agent_message', text: 'STALE RUN MESSAGE' } } }] })
  })
  try {
    await page.goto(`/tasks?task=${existing[0].id}`)
    await expect(page.locator('.run-title-meta')).toBeVisible()
    await page.getByRole('button', { name: /Fresh task without a run/ }).click()
    await expect(page.locator('.task-focus-detail')).toContainText('This is the new task brief.')
    release()
    await expect(page.locator('.task-focus-detail')).not.toContainText('STALE RUN MESSAGE')
    await expect(page).toHaveURL(new RegExp(`task=${task.id}`))
    await page.reload()
    await expect(page.locator('.task-focus-detail')).toContainText('Fresh task without a run')
    await expect(page.locator('.task-focus-detail .activity-feed')).toHaveCount(0)
    await page.setViewportSize({ width: 390, height: 844 })
    await page.getByRole('button', { name: 'Choose task' }).click()
    await page.getByRole('button', { name: /Weekly dependency review/ }).click()
    await expect(page.locator('.task-inbox-row.selected')).toContainText('Weekly dependency review')
  }
  finally {
    release()
  }
})
