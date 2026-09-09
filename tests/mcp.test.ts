import {
  Client,
  StreamableHTTPClientTransport,
} from '@modelcontextprotocol/client'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { fixture } from './helpers.ts'

describe('stateless MCP over real HTTP', () => {
  let ctx: Awaited<ReturnType<typeof fixture>>
  let address: string
  const clients: Client[] = []
  beforeEach(async () => {
    ctx = await fixture()
    address = await ctx.app.listen({ host: '127.0.0.1', port: 0 })
  })
  afterEach(async () => {
    await Promise.all(clients.splice(0).map(client => client.close()))
    await ctx.dispose()
  })
  async function connect(scopes: string[], protocolVersion = '2026-07-28') {
    const { token } = ctx.auth.personal('SDK test', scopes)
    const client = new Client(
      { name: 'test-client', version: '1.0.0' },
      protocolVersion === '2026-07-28'
        ? { versionNegotiation: { mode: { pin: '2026-07-28' } } }
        : { supportedProtocolVersions: [protocolVersion] },
    )
    clients.push(client)
    const transport = new StreamableHTTPClientTransport(
      new URL(`${address}/mcp`),
      { authProvider: { token: async () => token } },
    )
    await client.connect(transport)
    return { client, transport, token }
  }

  it('discovers tools and uses independent requests without a session ID', async () => {
    const { client, transport } = await connect(['read', 'run', 'manage'])
    const tools = await client.listTools()
    expect(tools.tools.map(tool => tool.name)).toEqual(
      expect.arrayContaining([
        'list_agents',
        'create_task',
        'run_task',
        'list_skills',
      ]),
    )
    expect(transport.sessionId).toBeUndefined()
    const result = await client.callTool({
      name: 'list_agents',
      arguments: {},
    })
    expect(result.isError).not.toBe(true)
    expect(JSON.stringify(result.content)).toContain('Test agent')
    const other = await connect(['read'])
    expect((await other.client.listTools()).tools.length).toBe(
      tools.tools.length,
    )
    const queued = await client.callTool({
      name: 'run_task',
      arguments: { taskId: ctx.task.id },
    })
    expect(queued.isError).not.toBe(true)
    expect(ctx.service.store.active()).toHaveLength(1)
  })

  it.each(['2026-07-28', '2025-06-18'])('enforces scopes and revocation over %s', async (protocolVersion) => {
    const { client, token } = await connect(['read'], protocolVersion)
    const denied = await client.callTool({
      name: 'run_task',
      arguments: { taskId: ctx.task.id },
    })
    expect(denied.isError).toBe(true)
    expect(ctx.service.store.active()).toHaveLength(0)
    const unauthorized = await fetch(`${address}/mcp`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'tools/list' }),
    })
    expect(unauthorized.status).toBe(401)
    expect(unauthorized.headers.get('www-authenticate')).toContain(
      'oauth-protected-resource/mcp',
    )
    ctx.auth.revoke(ctx.auth.verify(token).family)
    await expect(
      client.callTool({ name: 'list_agents', arguments: {} }),
    ).rejects.toThrow()
  })

  it.each(['2025-06-18', '2025-11-25'])('supports %s clients without session state', async (protocolVersion) => {
    const { client, transport, token } = await connect(['read'], protocolVersion)
    expect(transport.sessionId).toBeUndefined()
    expect((await client.listTools()).tools.map(tool => tool.name)).toContain('list_agents')
    const other = await connect(['read'], protocolVersion)
    expect(other.transport.sessionId).toBeUndefined()
    const result = await other.client.callTool({ name: 'list_agents', arguments: {} })
    expect(result.isError).not.toBe(true)
    expect(JSON.stringify(result.content)).toContain('Test agent')

    const initialized = await fetch(`${address}/mcp`, {
      method: 'POST',
      headers: {
        'content-type': 'application/json',
        'accept': 'application/json, text/event-stream',
        'authorization': `Bearer ${token}`,
      },
      body: JSON.stringify({
        jsonrpc: '2.0',
        id: 1,
        method: 'initialize',
        params: {
          protocolVersion,
          capabilities: {},
          clientInfo: { name: 'old', version: '1' },
        },
      }),
    })
    expect(initialized.status).toBe(200)
    expect(initialized.headers.get('mcp-session-id')).toBeNull()
    await initialized.text()
    for (const method of ['GET', 'DELETE']) {
      const response = await fetch(`${address}/mcp`, {
        method,
        headers: {
          'authorization': `Bearer ${token}`,
          'accept': 'application/json, text/event-stream',
          'mcp-protocol-version': protocolVersion,
        },
      })
      expect(response.status).toBe(405)
      await response.text()
    }
  })
})
