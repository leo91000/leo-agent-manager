import process from 'node:process'
import { McpServer } from '@modelcontextprotocol/server'
import { StdioServerTransport } from '@modelcontextprotocol/server/stdio'
import { z } from 'zod'

const server = new McpServer({ name: 'fixture-command', version: '1.0.0' })
server.registerTool('fixture_echo', { description: 'Echo with a configured prefix.', inputSchema: z.object({ message: z.string() }) }, async ({ message }) => ({ content: [{ type: 'text', text: `${process.env.TEST_PREFIX || ''}${message}` }] }))
server.connect(new StdioServerTransport()).catch(() => {
  process.exitCode = 1
})
