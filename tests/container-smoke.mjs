import assert from 'node:assert/strict'
import { execFile } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import process from 'node:process'
import { setTimeout } from 'node:timers/promises'
import { promisify } from 'node:util'

const exec = promisify(execFile)
const name = `leo-smoke-${randomUUID().slice(0, 8)}`
const volumes = ['data', 'home', 'workspaces'].map(
  suffix => `${name}-${suffix}`,
)
function docker(...args) {
  return exec('docker', ['--context', 'default', ...args], { timeout: 60000 })
}
async function main() {
  try {
    await docker(
      'run',
      '-d',
      '--name',
      name,
      '-p',
      '127.0.0.1::4310',
      '-e',
      'SETUP_TOKEN=container-smoke-bootstrap',
      '-v',
      `${volumes[0]}:/data`,
      '-v',
      `${volumes[1]}:/home/node`,
      '-v',
      `${volumes[2]}:/workspaces`,
      process.argv[2] || 'leo-agent-manager:test',
    )
    const { stdout } = await docker('port', name, '4310/tcp')
    let url = `http://${stdout.trim()}`
    async function ready() {
      for (let i = 0; i < 60; i++) {
        if (
          await fetch(`${url}/health`)
            .then(r => r.ok)
            .catch(() => false)
        ) {
          return
        }
        await setTimeout(200)
      }
      throw new Error('Container did not become healthy')
    }
    await ready()
    assert.equal((await fetch(`${url}/api/tasks`)).status, 401)
    const setup = await fetch(`${url}/api/setup`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        setupToken: 'container-smoke-bootstrap',
        password: 'container-test-password-long',
      }),
    })
    assert.equal(setup.status, 200)
    const session = await setup.json()
    const headers = {
      'cookie': setup.headers.get('set-cookie').split(';')[0],
      'x-csrf-token': session.csrf,
      'content-type': 'application/json',
    }
    const saved = await fetch(`${url}/api/agents`, {
      method: 'POST',
      headers,
      body: JSON.stringify({ name: 'Persistent profile' }),
    })
    assert.equal(saved.status, 200)
    assert.equal(
      (await docker('exec', name, 'id', '-u')).stdout.trim(),
      '1000',
    )
    assert.match(
      (await docker('exec', name, 'pnpm', '--version')).stdout,
      /^12\./,
    )
    assert.match(
      (await docker('exec', name, 'codex', '--version')).stdout,
      /codex-cli/,
    )
    assert.match(
      (await docker('exec', name, 'gh', '--version')).stdout,
      /gh version/,
    )
    await docker('restart', name)
    url = `http://${(await docker('port', name, '4310/tcp')).stdout.trim()}`
    await ready()
    const agents = await fetch(`${url}/api/agents`, { headers }).then(r =>
      r.json(),
    )
    assert.equal(agents[0].name, 'Persistent profile')
    assert.equal((await fetch(`${url}/api/session`)).status, 200)
    assert.match(await fetch(url).then(r => r.text()), /Leo/)
    process.stdout.write(
      'Container smoke passed: non-root, CLI tools, auth, Vue build, persistent session and data across restart.\n',
    )
  }
  catch (error) {
    const logs = await docker('logs', '--tail', '50', name).catch(() => null)
    if (logs)
      process.stderr.write(logs.stdout + logs.stderr)
    throw error
  }
  finally {
    await docker('rm', '-f', name).catch(() => {})
    await docker('volume', 'rm', ...volumes).catch(() => {})
  }
}
main().catch((error) => {
  console.error(error)
  process.exitCode = 1
})
