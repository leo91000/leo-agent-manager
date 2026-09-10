import { Buffer } from 'node:buffer'
import { readFileSync } from 'node:fs'
import { createServer } from 'node:http'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { deploy } from '../scripts/deploy-coolify.mjs'
import { persistentRunnerCompose } from '../scripts/runner-compose.mjs'

describe('coolify deployment over HTTP', () => {
  let server
  let config
  let requests
  let patchStatus
  let healthResponses
  let compose
  let persistCompose

  beforeEach(async () => {
    requests = []
    patchStatus = 201
    compose = readFileSync(new URL('../compose.yaml', import.meta.url), 'utf8')
    persistCompose = true
    healthResponses = [{ status: 'ok', commit: 'new-commit' }]
    server = createServer(async (request, response) => {
      let body = ''
      for await (const chunk of request)
        body += chunk
      requests.push({
        method: request.method,
        path: request.url,
        authorization: request.headers.authorization,
        body: body ? JSON.parse(body) : undefined,
      })
      response.setHeader('content-type', 'application/json')
      if (request.url === '/api/v1/services/leo-service') {
        if (request.method === 'PATCH' && patchStatus < 300 && persistCompose)
          compose = Buffer.from(JSON.parse(body).docker_compose_raw, 'base64').toString()
        response.statusCode = request.method === 'PATCH' ? patchStatus : 200
        response.end(JSON.stringify({ docker_compose_raw: compose }))
        return
      }
      if (request.url === '/health') {
        const health = healthResponses.length > 1 ? healthResponses.shift() : healthResponses[0]
        response.statusCode = health ? 200 : 503
        response.end(JSON.stringify(health))
        return
      }
      response.statusCode = request.method === 'PATCH' ? patchStatus : 200
      response.end('{}')
    })
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
    const origin = `http://127.0.0.1:${server.address().port}`
    config = {
      coolifyUrl: origin,
      publicUrl: origin,
      serviceUuid: 'leo-service',
      token: 'test-token',
      image: `ghcr.io/owner/leo@sha256:${'a'.repeat(64)}`,
      commit: 'new-commit',
    }
  })

  afterEach(async () => {
    server.closeAllConnections()
    await new Promise(resolve => server.close(resolve))
  })

  it('pins the image, restarts and waits through stale health and proxy errors', async () => {
    healthResponses = [{ status: 'ok', commit: 'old-commit' }, null, { status: 'ok', commit: config.commit }]
    await deploy(config, { intervalMs: 0, timeoutMs: 1000 })
    expect(requests.filter(request => request.method !== 'GET')).toEqual([
      {
        method: 'PATCH',
        path: '/api/v1/services/leo-service/envs',
        authorization: 'Bearer test-token',
        body: { key: 'LEO_IMAGE', value: config.image, is_literal: true },
      },
      {
        method: 'POST',
        path: '/api/v1/services/leo-service/restart',
        authorization: 'Bearer test-token',
        body: undefined,
      },
    ])
    const healthRequests = requests.filter(request => request.path === '/health')
    expect(healthRequests).toHaveLength(3)
    expect(healthRequests.every(request => !request.authorization)).toBe(true)
  })

  it('does not restart when the image update fails', async () => {
    patchStatus = 401
    await expect(deploy(config)).rejects.toThrow('HTTP 401')
    expect(requests).toHaveLength(2)
    expect(requests.some(request => request.method === 'POST')).toBe(false)
  })

  it('fails if a healthy service keeps serving the previous commit', async () => {
    healthResponses = [{ status: 'ok', commit: 'old-commit' }]
    await expect(deploy(config, { intervalMs: 0, timeoutMs: 25 })).rejects.toThrow('did not serve commit')
    expect(requests.some(request => request.path === '/health')).toBe(true)
  })

  it('adds persistent runner storage and verifies it before updating the image', async () => {
    compose = compose.replace('      - runner-state:/runner-state\n', '').replace('  runner-state:\n', '')
    const original = compose
    await deploy(config, { intervalMs: 0, timeoutMs: 1000 })
    const migration = requests.find(request => request.method === 'PATCH' && request.path === '/api/v1/services/leo-service')
    expect(Buffer.from(migration.body.docker_compose_raw, 'base64').toString()).toBe(persistentRunnerCompose(original))
    expect(compose).toContain('data:/data:ro')
    expect(compose).toContain('      - runner-state:/runner-state')
    expect(compose).toContain('\nvolumes:\n  runner-state:')
    expect(requests.slice(0, 4).map(request => [request.method, request.path])).toEqual([
      ['GET', '/api/v1/services/leo-service'],
      ['PATCH', '/api/v1/services/leo-service'],
      ['GET', '/api/v1/services/leo-service'],
      ['PATCH', '/api/v1/services/leo-service/envs'],
    ])
  })

  it('does not deploy when the mount migration was not persisted', async () => {
    compose = compose.replace('      - runner-state:/runner-state\n', '').replace('  runner-state:\n', '')
    persistCompose = false
    await expect(deploy(config)).rejects.toThrow('did not persist')
    expect(requests.some(request => request.path.endsWith('/envs') || request.path.endsWith('/restart'))).toBe(false)
  })

  it('preserves quoted mount sources, variables and unrelated read-only mounts', () => {
    // eslint-disable-next-line no-template-curly-in-string -- Literal Compose interpolation must survive the migration.
    const input = 'services:\n  manager:\n    volumes:\n      - data:/runner-state:ro\n  runner:\n    environment:\n      - SETTING=example:/runner-state:ro\n    volumes:\n      - "${DATA_VOLUME}:/runner-state:ro" # persistent\n      - other:/other:ro\n'
    // eslint-disable-next-line no-template-curly-in-string -- These are literal Compose expressions.
    expect(persistentRunnerCompose(input)).toBe(input.replace('"${DATA_VOLUME}:/runner-state:ro"', '"${DATA_VOLUME}:/runner-state:rw"'))
  })

  it.each([undefined, 'services: {}', 'services:\n  runner:\n    environment:\n      - SETTING=example:/runner-state:ro\n', 'services:\n  runner:\n    volumes:\n      - type: volume\n        target: /runner-state\n'])('rejects unverified custom Compose layouts', (input) => {
    expect(() => persistentRunnerCompose(input)).toThrow('Cannot verify runner storage')
  })
})
