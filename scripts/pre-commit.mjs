import { execFileSync, spawnSync } from 'node:child_process'
import process from 'node:process'

const diff = () => execFileSync('git', ['diff', '--no-ext-diff', '--binary'], { maxBuffer: 64 * 1024 * 1024 })
const before = diff()
const result = spawnSync('pnpm', ['lint:fix'], { stdio: 'inherit' })

if (result.error) {
  console.error(result.error.message)
  process.exit(1)
}

if (result.status !== 0)
  process.exit(result.status ?? 1)

if (!before.equals(diff())) {
  console.error('Lint fixes changed tracked files. Review and stage the fixes, then retry your commit.')
  process.exit(1)
}
