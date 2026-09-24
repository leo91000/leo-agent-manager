import { expect, test } from './fixtures'

test('GitHub picker handles retry, pagination, selection and import on a phone', async ({ page }) => {
  test.setTimeout(90000)
  await page.goto('/projects')
  await page.getByLabel('Password', { exact: true }).fill('browser-password-long-enough')
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  let failed = true
  await page.route('**/api/github/repositories?*', async (route) => {
    if (failed) {
      failed = false
      return route.fulfill({ status: 502, json: { error: 'Check the GitHub connection and retry.' } })
    }
    const second = route.request().url().endsWith('page=2')
    await route.fulfill({ json: { repositories: [{ fullName: second ? 'team/selected' : 'team/existing', name: second ? 'selected' : 'existing', description: 'A private repository', defaultBranch: 'develop', private: true, archived: false, imported: !second }], nextPage: second ? null : 2 } })
  })
  let payload: any
  await page.route('**/api/projects/github', async (route) => {
    payload = route.request().postDataJSON()
    await route.fulfill({ json: { id: 'imported-project' } })
  })
  await page.setViewportSize({ width: 390, height: 844 })
  await page.getByRole('button', { name: 'Add project', exact: true }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByRole('combobox', { name: 'Add from' }).click()
  await page.getByRole('option', { name: 'GitHub', exact: true }).click()
  await expect(dialog.getByText('Check the GitHub connection and retry.')).toBeVisible()
  await dialog.getByRole('button', { name: 'Retry', exact: true }).click()
  await expect(dialog.getByRole('button', { name: /team\/existing/ })).toBeDisabled()
  await dialog.getByRole('button', { name: 'Load more repositories' }).click()
  await dialog.getByLabel('Filter loaded repositories').fill('selected')
  await dialog.getByRole('button', { name: /team\/selected/ }).click()
  await expect(dialog.getByLabel('Name', { exact: true })).toHaveValue('selected')
  await expect(dialog.getByLabel('Base branch', { exact: false })).toHaveValue('develop')
  await expect(dialog.getByLabel('Project directory', { exact: false })).toHaveCount(0)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await dialog.getByRole('button', { name: 'Save project', exact: true }).click()
  await expect(dialog).toHaveCount(0)
  expect(payload).toEqual({ repository: 'team/selected', name: 'selected', description: 'A private repository', baseBranch: 'develop' })
})
