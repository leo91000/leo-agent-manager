#!/usr/bin/env node
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import process from 'node:process'
import { createInterface } from 'node:readline'
import activityEvents from './activity-events.json' with { type: 'json' }
import { chatFixture } from './chat-rpc.mjs'

async function main() {
  const args = process.argv.slice(2)
  if (args.includes('--version')) {
    process.stdout.write('fixture-codex 1.0\n')
    process.exit(0)
  }
  if (args.includes('app-server')) {
    const chat = chatFixture()
    const lines = createInterface({ input: process.stdin })
    for await (const line of lines) {
      const request = JSON.parse(line)
      if (request.id === undefined)
        continue
      if (chat(request))
        continue
      let result = {}
      const authPath = path.join(process.env.CODEX_HOME, 'auth.json')
      const auth = existsSync(authPath) ? JSON.parse(readFileSync(authPath, 'utf8')) : null
      const id = auth?.tokens.account_id ?? 'fixture'
      const conversation = path.join(process.env.CODEX_HOME, 'fixture-conversation.json')
      if (request.method === 'thread/read' || request.method === 'thread/list') {
        if (!existsSync(conversation)) {
          process.stdout.write(`${JSON.stringify({ id: request.id, error: { code: -32000, message: 'Session missing' } })}\n`)
          continue
        }
        const thread = JSON.parse(readFileSync(conversation, 'utf8'))
        result = request.method === 'thread/read' ? { thread } : { data: [thread] }
      }
      if (request.method === 'account/read')
        result = { account: auth ? { type: 'chatgpt', email: `${id}@example.test`, planType: 'plus' } : null }
      if (request.method === 'account/rateLimits/read')
        result = { ordinaryUsageAllowed: true, accountId: id, rateLimits: { limitId: 'codex', limitName: 'Codex', primary: { usedPercent: 25, windowDurationMins: 300, resetsAt: Math.floor(Date.now() / 1000) + 7200 }, secondary: { usedPercent: 40, windowDurationMins: 10080, resetsAt: Math.floor(Date.now() / 1000) + 172800 } } }
      process.stdout.write(`${JSON.stringify({ id: request.id, result })}\n`)
    }
    return
  }
  if (args[0] === 'login' && args[1] === 'status') {
    process.stderr.write('Logged in using ChatGPT\n')
    process.exit(0)
  }
  if (args[0] === 'login') {
    process.stdout.write(
      'Open https://auth.openai.com/codex/device and enter ABCD-12345\n',
    )
    setTimeout(() => {
      mkdirSync(process.env.CODEX_HOME, { recursive: true })
      const id = path.basename(path.dirname(process.env.CODEX_HOME))
      writeFileSync(path.join(process.env.CODEX_HOME, 'auth.json'), JSON.stringify({ tokens: { access_token: 'synthetic-access', refresh_token: 'synthetic-refresh', account_id: id } }), { mode: 0o600 })
      process.exit(0)
    }, 120)
  }
  else {
    let prompt = ''
    for await (const chunk of process.stdin) prompt += chunk
    const emit = value => process.stdout.write(`${JSON.stringify(value)}\n`)
    mkdirSync(process.env.CODEX_HOME, { recursive: true })
    const conversation = path.join(process.env.CODEX_HOME, 'fixture-conversation.json')
    if (!args.includes('resume'))
      writeFileSync(conversation, JSON.stringify({ id: 'fixture-session', cwd: process.cwd(), parentThreadId: null }))
    emit({ type: 'thread.started', thread_id: 'fixture-session' })
    const restartMarker = path.join(process.env.CODEX_HOME, 'fixture-restart.json')
    if (prompt.includes('fixture:restart') && !args.includes('resume')) {
      writeFileSync(restartMarker, JSON.stringify({ cwd: process.cwd() }))
      writeFileSync(path.join(process.cwd(), 'restart-work.txt'), 'preserved before restart')
      setInterval(emit, 100, { type: 'progress', item: { text: 'Waiting for restart' } })
      return
    }
    if (args.includes('resume') && existsSync(restartMarker)) {
      const previous = JSON.parse(readFileSync(restartMarker, 'utf8'))
      if (previous.cwd !== process.cwd() || readFileSync(path.join(process.cwd(), 'restart-work.txt'), 'utf8') !== 'preserved before restart')
        throw new Error('Restart lost workspace')
      writeFileSync(args[args.indexOf('--output-last-message') + 1], '# Resumed\nThe saved conversation and work survived the restart.')
      emit({ type: 'turn.completed', usage: { input_tokens: 1, output_tokens: 1 } })
      return
    }
    const marker = path.join(process.env.CODEX_HOME, 'fixture-resume.json')
    if (prompt.includes('fixture:exhaust')) {
      mkdirSync(process.env.CODEX_HOME, { recursive: true })
      const auth = JSON.parse(readFileSync(path.join(process.env.CODEX_HOME, 'auth.json'), 'utf8'))
      writeFileSync(marker, JSON.stringify({ account: auth.tokens.account_id, cwd: process.cwd(), allowSameAccount: prompt.includes('fixture:banked-reset') }))
      writeFileSync(path.join(process.cwd(), 'preserved-work.txt'), 'work before exhaustion')
      emit({ type: 'turn.failed', error: { message: 'You\'ve hit your usage limit. Try again later.' } })
      process.exitCode = 1
      return
    }
    if (args.includes('resume')) {
      const previous = JSON.parse(readFileSync(marker, 'utf8'))
      const auth = JSON.parse(readFileSync(path.join(process.env.CODEX_HOME, 'auth.json'), 'utf8'))
      if ((previous.account === auth.tokens.account_id && !previous.allowSameAccount) || previous.cwd !== process.cwd() || args[args.indexOf('resume') + 1] !== 'fixture-session' || readFileSync(path.join(process.cwd(), 'preserved-work.txt'), 'utf8') !== 'work before exhaustion')
        throw new Error('Resume did not preserve context or switch accounts')
      emit({ type: 'item.completed', item: { text: 'Resumed original session on another account with workspace intact' } })
    }
    process.stdout.write('non-JSON diagnostic\n')
    emit({ type: 'item.completed', item: { text: 'Checking the project' } })
    if (prompt.includes('fixture:mcp')) {
      const { Client, StreamableHTTPClientTransport } = await import('@modelcontextprotocol/client')
      const configuration = args.find(arg => arg.startsWith('mcp_servers.') && arg.includes('"url"='))
      const url = JSON.parse(configuration.match(/"url"=("[^"]+")/)[1])
      const client = new Client({ name: 'fixture-worker', version: '1' })
      try {
        await client.connect(new StreamableHTTPClientTransport(new URL(url), { requestInit: { headers: { Authorization: `Bearer ${process.env.LEO_MCP_RUN_TOKEN}` } } }))
        const result = await client.callTool({ name: 'echo', arguments: { message: `MCP subprocess passed: ${process.env.LEO_MCP_RUN_TOKEN}` } })
        emit({ type: 'item.completed', item: { text: result.content[0].text } })
        writeFileSync(args[args.indexOf('--output-last-message') + 1], result.content[0].text)
      }
      finally {
        await client.close()
      }
      return
    }
    if (prompt.includes('fixture:activity')) {
      for (const event of activityEvents)
        emit(event)
    }
    if (prompt.includes('fixture:hang')) {
      setInterval(emit, 100, {
        type: 'progress',
        item: { text: 'Still working' },
      })
    }
    else {
      await new Promise(resolve => setTimeout(resolve, 100))
      process.stderr.write('Bearer test-token-value\n')
      if (prompt.includes('fixture:fail'))
        process.exit(2)
      emit({
        type: 'turn.completed',
        usage: { input_tokens: 12, output_tokens: 8 },
      })
      const output = args[args.indexOf('--output-last-message') + 1]
      writeFileSync(output, '# Complete\nThe fixture task passed.')
    }
  }
}
void main()
