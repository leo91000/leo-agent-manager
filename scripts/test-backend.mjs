import { spawnSync } from 'node:child_process'
import process from 'node:process'
import { isolateTestCredentials } from './test-environment.mjs'

// A coding-agent VM may expose its live auth broker to child processes. Tests
// must use their own temporary homes and synthetic brokers instead.
isolateTestCredentials()
const result = spawnSync('cargo', ['test', '--locked', '--workspace', ...process.argv.slice(2)], { stdio: 'inherit' })
if (result.error)
  throw result.error
process.exit(result.status ?? 1)
