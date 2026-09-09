import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: './tests/e2e',
  fullyParallel: false,
  workers: 1,
  timeout: 30000,
  expect: { timeout: 7000 },
  reporter: [['list'], ['html', { open: 'never' }]],
  use: { baseURL: 'http://127.0.0.1:4322', trace: 'retain-on-failure', screenshot: 'only-on-failure', ...devices['Desktop Chrome'] },
  webServer: {
    command: 'pnpm exec tsx tests/serve.ts',
    env: { TEST_PORT: '4322' },
    url: 'http://127.0.0.1:4322/health',
    reuseExistingServer: false,
  },
})
