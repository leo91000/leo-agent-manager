import { expect, expectSingleScroll, test } from './fixtures'

test('keeps activity scrolling inside the workspace and gives tabs breathing room', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.route('**/api/runs/*/events?*', route => route.fulfill({ json: Array.from({ length: 100 }, (_, index) => ({ id: index + 1, runId: 'fixture', type: 'item.completed', createdAt: Date.now(), text: `Review step ${index + 1}: the checks passed and the next change is ready to inspect.`, payload: { item: { type: 'agent_message', text: `Review step ${index + 1}: the checks passed and the next change is ready to inspect.` } } })) }))
  await page.goto('/tasks')
  await page.getByLabel('Password', { exact: true }).fill('browser-password-long-enough')
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  await expect(page.locator('.activity-message').first()).toBeAttached()
  await page.getByLabel('Follow output').uncheck()
  const runs = await page.request.get('/api/runs').then(response => response.json())
  for (const theme of ['light', 'dark'] as const) {
    await page.emulateMedia({ colorScheme: theme })
    for (const viewport of [{ width: 1440, height: 900 }, { width: 390, height: 844 }, { width: 390, height: 664 }, { width: 320, height: 568 }, { width: 844, height: 390 }]) {
      await page.setViewportSize(viewport)
      for (const route of ['/tasks', `/runs/${runs[0].id}`]) {
        if (route === '/tasks' && new URL(page.url()).pathname !== route) {
          const menu = page.getByRole('button', { name: 'Open navigation' })
          if (await menu.isVisible())
            await menu.click()
          await page.locator('.sidebar a[href="/tasks"]').click()
        }
        else if (route !== '/tasks') {
          await page.getByRole('link', { name: 'Open run', exact: true }).click()
        }
        await page.getByRole('button', { name: /^Activity/ }).click()
        await expect(page.locator('.activity-message').first()).toBeAttached()
        await page.getByLabel('Follow output').uncheck()
        if (route === '/tasks') {
          await page.getByLabel('Task actions', { exact: true }).click()
          await expect(page.locator('.task-action-menu button').last()).toBeInViewport({ ratio: 1 })
          await page.getByLabel('Task actions', { exact: true }).click()
        }
        await expectSingleScroll(page)
        const scroller = page.getByRole('region', { name: 'Activity output' })
        const box = await scroller.boundingBox()
        expect(box!.height).toBeGreaterThan(65)
        const geometry = await scroller.evaluate((element) => {
          const chain = []
          for (let parent: Element | null = element; parent; parent = parent.parentElement) {
            const style = getComputedStyle(parent)
            chain.push({ name: parent.className, height: parent.clientHeight, min: style.minHeight, flex: style.flex, display: style.display })
          }
          return chain
        })
        expect(box!.y + box!.height, JSON.stringify({ route, geometry })).toBeLessThanOrEqual(viewport.height)
        await scroller.evaluate(element => element.scrollTop = 0)
        await scroller.focus()
        await page.keyboard.press('PageDown')
        await expect.poll(() => scroller.evaluate(element => element.scrollTop)).toBeGreaterThan(0)
        await expectSingleScroll(page)
        const tab = page.getByRole('button', { name: /^Activity/ })
        const spacing = await tab.evaluate(element => ({ left: Number.parseFloat(getComputedStyle(element).paddingLeft), right: Number.parseFloat(getComputedStyle(element).paddingRight) }))
        expect(spacing.left).toBeGreaterThanOrEqual(10)
        expect(spacing.right).toBeGreaterThanOrEqual(10)
        await scroller.evaluate((element) => {
          element.scrollTop = 0
          if (element instanceof HTMLElement)
            element.blur()
        })
        await page.screenshot({ path: testInfo.outputPath(`${theme}-${viewport.width}${route === '/tasks' ? '-tasks.png' : '-run.png'}`), animations: 'disabled' })
        await page.getByRole('button', { name: 'Task brief', exact: true }).click()
        await expectSingleScroll(page)
        await page.getByRole('button', { name: 'Result', exact: true }).click()
        await expectSingleScroll(page)
      }
    }
  }
})
