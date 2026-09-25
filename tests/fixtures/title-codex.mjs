#!/usr/bin/env node
import assert from 'node:assert/strict'
import { existsSync } from 'node:fs'
import path from 'node:path'
import process from 'node:process'
import { createInterface } from 'node:readline'

const emit = value => process.stdout.write(`${JSON.stringify(value)}\n`)
let account = 'fixture'
let thread
for await (const line of createInterface({ input: process.stdin })) {
  const { id, method, params } = JSON.parse(line)
  if (id === undefined)
    continue
  let result = {}
  if (method === 'account/read')
    result = { account: { type: 'chatgpt', email: 'fixture@example.test', planType: 'plus' } }
  if (method === 'account/rateLimits/read')
    result = { rateLimits: { primary: { usedPercent: 5 }, secondary: { usedPercent: 10 } } }
  if (method === 'account/login/start') {
    assert.equal(params.type, 'chatgptAuthTokens')
    assert.equal(existsSync(path.join(process.env.CODEX_HOME, 'auth.json')), false)
    account = params.chatgptAccountId
    result = { type: 'chatgptAuthTokens' }
  }
  if (method === 'model/list')
    result = { data: [{ model: 'gpt-6-luna', supportedReasoningEfforts: [{ reasoningEffort: account === 'unsupported' ? 'low' : 'xhigh' }] }], nextCursor: null }
  if (method === 'thread/start') {
    assert.equal(params.model, 'gpt-6-luna')
    assert.equal(params.ephemeral, true)
    assert.equal(params.sandbox, 'read-only')
    assert.equal(params.approvalPolicy, 'never')
    assert.equal(params.config['features.shell_tool'], false)
    assert.equal(params.config['features.apps'], false)
    assert.equal(params.config['features.plugins'], false)
    assert.equal(params.cwd, process.cwd())
    thread = 'title-thread'
    result = { thread: { id: thread } }
  }
  if (method === 'turn/start') {
    assert.equal(params.threadId, thread)
    assert.equal(params.model, 'gpt-6-luna')
    assert.equal(params.effort, 'xhigh')
    assert.deepEqual(params.outputSchema.required, ['title'])
    if (account === 'hang') {
      emit({ id, result: { turn: { id: 'title-turn' } } })
      continue
    }
    const input = JSON.parse(params.input[0].text)
    const text = account === 'malformed' ? 'not JSON' : JSON.stringify({ title: input.recentMessages.some(m => m.text.includes('Android')) ? 'Mises à jour Android' : input.currentTitle })
    // Notifications deliberately precede the response to catch lost fast results.
    emit({ method: 'item/completed', params: { threadId: thread, item: { type: 'agentMessage', text } } })
    emit({ method: 'turn/completed', params: { threadId: thread, turn: { status: account === 'failed' ? 'failed' : 'completed' } } })
    result = { turn: { id: 'title-turn' } }
  }
  emit({ id, result })
}
