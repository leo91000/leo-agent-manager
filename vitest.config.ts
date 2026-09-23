import { defineConfig } from 'vitest/config'
import { isolateTestCredentials } from './scripts/test-environment.mjs'

isolateTestCredentials()

export default defineConfig({
  test: { include: ['tests/**/*.test.{ts,mjs}'], testTimeout: 15000, pool: 'forks' },
})
