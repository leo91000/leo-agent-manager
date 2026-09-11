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
    { name: 'journeys-models', testMatch: 'models.spec.ts' },
    { name: 'layout-webkit-models', testMatch: 'models.spec.ts', use: { browserName: 'webkit', hasTouch: true } },
    { name: 'journeys-chats', testMatch: 'chats.spec.ts' },
    { name: 'journeys-chat-attachments', testMatch: 'chat-attachments.spec.ts' },
    { name: 'layout-webkit-chat-attachments', testMatch: 'chat-attachments.spec.ts', use: { browserName: 'webkit', hasTouch: true } },
    { name: 'layout-webkit-chats', testMatch: 'chats.spec.ts', use: { browserName: 'webkit', hasTouch: true } },
    { name: 'layout-chromium-scrolling', testMatch: 'scrolling.spec.ts' },
    { name: 'layout-webkit-scrolling', testMatch: 'scrolling.spec.ts', use: { browserName: 'webkit' } },
    { name: 'journeys', testMatch: 'workspace.spec.ts', grepInvert: /appearance follows|dark appearance settings/ },
    { name: 'journeys-appearance', testMatch: 'workspace.spec.ts', grep: /appearance follows|dark appearance settings/ },
    { name: 'journeys-codex-accounts', testMatch: 'codex-accounts.spec.ts' },
    { name: 'layout-webkit-codex-accounts', testMatch: 'codex-accounts.spec.ts', use: { browserName: 'webkit', isMobile: true, hasTouch: true } },
    { name: 'journeys-mcps', testMatch: 'mcps.spec.ts' },
    { name: 'layout-webkit-mcps', testMatch: 'mcps.spec.ts', use: { browserName: 'webkit', isMobile: true, hasTouch: true } },
    { name: 'journeys-task-focus', testMatch: 'task-focus.spec.ts' },
    { name: 'journeys-agent-access', testMatch: 'agent-access.spec.ts' },
    ...(['chromium', 'webkit'] as const).flatMap(browserName =>
      (['light', 'dark'] as const).map(colorScheme => ({
        name: `layout-${browserName}-${colorScheme}`,
        testMatch: 'layouts.spec.ts',
        use: { browserName, colorScheme, ...(browserName === 'webkit' && colorScheme === 'light' ? { isMobile: true, hasTouch: true, viewport: { width: 390, height: 664 } } : {}) },
      }))),
  ],
})
