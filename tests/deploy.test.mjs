import { createServer } from 'node:http'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { deploy } from '../scripts/deploy-coolify.mjs'

describe('coolify deployment over HTTP', () => {
  let server
  let config
  let requests
  let patchStatus
  let healthResponses

  beforeEach(async () => {
    requests = []
    patchStatus = 201
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
    expect(requests.slice(0, 2)).toEqual([
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
    expect(requests).toHaveLength(1)
  })

  it('fails if a healthy service keeps serving the previous commit', async () => {
    healthResponses = [{ status: 'ok', commit: 'old-commit' }]
    await expect(deploy(config, { intervalMs: 0, timeoutMs: 25 })).rejects.toThrow('did not serve commit')
    expect(requests.some(request => request.path === '/health')).toBe(true)
  })
})
