import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: './tests/e2e',
  fullyParallel: false,
  workers: 5,
  timeout: 30000,
  expect: { timeout: 7000 },
  reporter: [['list'], ['html', { open: 'never' }]],
  use: { trace: 'retain-on-failure', screenshot: 'only-on-failure', ...devices['Desktop Chrome'] },
  projects: [
    { name: 'journeys', testMatch: 'workspace.spec.ts' },
    { name: 'journeys-agent-access', testMatch: 'agent-access.spec.ts' },
    ...(['chromium', 'webkit'] as const).flatMap(browserName =>
      (['light', 'dark'] as const).map(colorScheme => ({
        name: `layout-${browserName}-${colorScheme}`,
        testMatch: 'layouts.spec.ts',
        use: { browserName, colorScheme, ...(browserName === 'webkit' && colorScheme === 'light' ? { isMobile: true, hasTouch: true, viewport: { width: 390, height: 664 } } : {}) },
      }))),
  ],
})
