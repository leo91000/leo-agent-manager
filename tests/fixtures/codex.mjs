#!/usr/bin/env node
import { writeFileSync } from 'node:fs'
import process from 'node:process'
import activityEvents from './activity-events.json' with { type: 'json' }

async function main() {
  const args = process.argv.slice(2)
  if (args.includes('--version')) {
    process.stdout.write('fixture-codex 1.0\n')
    process.exit(0)
  }
  if (args[0] === 'login' && args[1] === 'status') {
    process.stderr.write('Logged in using ChatGPT\n')
    process.exit(0)
  }
  if (args[0] === 'login') {
    process.stdout.write(
      'Open https://auth.openai.com/codex/device and enter ABCD-12345\n',
    )
    setTimeout(() => process.exit(0), 120)
  }
  else {
    let prompt = ''
    for await (const chunk of process.stdin) prompt += chunk
    const emit = value => process.stdout.write(`${JSON.stringify(value)}\n`)
    emit({ type: 'thread.started', thread_id: 'fixture-session' })
    process.stdout.write('non-JSON diagnostic\n')
    emit({ type: 'item.completed', item: { text: 'Checking the project' } })
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
