#!/usr/bin/env node
// Synthetic official-CLI protocol fixture. Never connects to a model provider.
import { appendFileSync, existsSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import process from 'node:process'
import { createInterface } from 'node:readline'

const home = process.env.CLAUDE_CONFIG_DIR
if (!home)
  throw new Error('Missing isolated fixture home')
mkdirSync(home, { recursive: true })
const auth = path.join(home, '.credentials.json')
const args = process.argv.slice(2)
const out = value => process.stdout.write(`${JSON.stringify(value)}\n`)
const lines = createInterface({ input: process.stdin })
if (args[0] === 'auth') {
  if (args[1] === 'status') {
    out({ loggedIn: existsSync(auth), email: 'claude-fixture@example.test', subscriptionType: 'max', authMethod: 'oauth_token', accessToken: 'never-return-this-secret' })
    process.exit(existsSync(auth) ? 0 : 1)
  }
  if (args[1] === 'logout') {
    rmSync(auth, { force: true })
    process.exit(0)
  }
  process.stdout.write('Open https://claude.com/oauth/authorize?fixture=1\nPaste code here if prompted > ')
  lines.on('line', (code) => {
    if (code !== 'fixture-code') {
      process.stderr.write('never-return-this-secret')
      process.exit(1)
    }
    writeFileSync(auth, 'connected')
    writeFileSync(path.join(home, '.credentials.json'), '{"fixture":true}')
    process.exit(0)
  })
}
else {
  if (!existsSync(auth) && !args.includes('--no-session-persistence')) {
    out({ type: 'result', subtype: 'error_during_execution', is_error: true, result: 'Not logged in' })
    process.exit(1)
  }
  const session = '70f5e7a1-8d65-4f5f-a545-af6ee8c0e1ab'
  const log = path.join(home, 'invocations.jsonl')
  appendFileSync(log, `${JSON.stringify({ args })}\n`)
  out({ type: 'system', subtype: 'init', session_id: session })
  let question
  let serial = 0
  const complete = (text = 'Claude fixture completed') => {
    const id = `assistant-${process.pid}-${++serial}`
    out({ type: 'stream_event', event: { type: 'message_start', message: { id } } })
    out({ type: 'stream_event', event: { type: 'content_block_start', index: 0, content_block: { type: 'text', text: '' } } })
    out({ type: 'stream_event', event: { type: 'content_block_delta', index: 0, delta: { type: 'text_delta', text } } })
    out({ type: 'assistant', message: { id, content: [{ type: 'text', text }] } })
    out({ type: 'result', subtype: 'success', is_error: false, result: text, session_id: session })
  }
  lines.on('line', (line) => {
    const value = JSON.parse(line)
    if (value.type === 'control_request') {
      out({ type: 'control_response', response: { subtype: 'success', request_id: value.request_id, response: { models: [{ value: 'sonnet', displayName: 'Sonnet', description: 'Balanced Claude model', supportedEffortLevels: ['low', 'medium', 'high'] }, { value: 'opus', displayName: 'Opus', description: 'Deep reasoning', supportedEffortLevels: ['low', 'medium', 'high', 'max'] }] } } })
      return
    }
    if (value.type === 'control_response') {
      if (question) {
        writeFileSync(path.join(home, 'question-response.json'), JSON.stringify(value))
        complete('Question answered')
        question = false
      }
      return
    }
    if (value.type !== 'user')
      return
    appendFileSync(path.join(home, 'inputs.jsonl'), `${JSON.stringify(value)}\n`)
    out(value)
    const prompt = value.message.content.filter(b => b.type === 'text').map(b => b.text).join('\n')
    if (prompt.includes('fixture:hang'))
      return
    if (prompt.includes('fixture:fail')) {
      out({ type: 'result', subtype: 'error_during_execution', is_error: true, result: 'Fixture usage limit reached' })
      return
    }
    if (prompt.includes('fixture:question')) {
      question = true
      out({ type: 'control_request', request_id: 'question-1', request: { subtype: 'can_use_tool', tool_name: 'AskUserQuestion', input: { questions: [{ question: 'Which approach?', options: [{ label: 'Small change', description: 'Keep scope focused' }, { label: 'Larger change', description: 'Expand scope' }] }] } } })
      return
    }
    out({ type: 'assistant', message: { id: 'tool-message', content: [{ type: 'tool_use', id: `tool-${process.pid}`, name: 'Bash', input: { command: 'printf fixture' } }] } })
    out({ type: 'user', message: { role: 'user', content: [{ type: 'tool_result', tool_use_id: `tool-${process.pid}`, content: 'fixture' }] } })
    if (prompt.includes('fixture:slow'))
      setTimeout(complete, 1500)
    else complete()
  })
}
