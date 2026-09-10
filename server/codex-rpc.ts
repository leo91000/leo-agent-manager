import type { ChildProcessWithoutNullStreams } from 'node:child_process'
import type { Config } from './config.ts'
import { spawn } from 'node:child_process'
import process from 'node:process'
import { createInterface } from 'node:readline'
import { AppError } from './errors.ts'

export interface CodexRpc {
  request: <T>(method: string, params?: unknown) => Promise<T>
}
export type CodexSession = <T>(home: string, operation: (rpc: CodexRpc) => Promise<T>) => Promise<T>

export function codexEnvironment(config: Config, home: string) {
  const env = { ...process.env, HOME: config.home, CODEX_HOME: home }
  for (const key of ['CODEX_API_KEY', 'OPENAI_API_KEY', 'CODEX_THREAD_ID'])
    delete (env as NodeJS.ProcessEnv)[key]
  return env
}

export function codexSession(config: Config): CodexSession {
  return async (home, operation) => {
    const child: ChildProcessWithoutNullStreams = spawn(config.codexBin, ['-c', 'cli_auth_credentials_store="file"', '-c', 'forced_login_method="chatgpt"', 'app-server', '--listen', 'stdio://'], { env: codexEnvironment(config, home), stdio: ['pipe', 'pipe', 'pipe'] })
    const pending = new Map<number, { resolve: (value: unknown) => void, reject: (error: Error) => void }>()
    let sequence = 0
    let closed = false
    const fail = () => {
      closed = true
      for (const request of pending.values()) request.reject(new AppError(503, 'Codex account service stopped. Check the CLI installation and reconnect the account.'))
      pending.clear()
    }
    child.on('error', fail)
    child.on('close', fail)
    child.stdin.on('error', fail)
    child.stderr.resume()
    const lines = createInterface({ input: child.stdout })
    lines.on('line', (line) => {
      if (line.length > 2_000_000)
        return fail()
      try {
        const message = JSON.parse(line)
        if (message.method && message.id !== undefined) {
          child.stdin.write(`${JSON.stringify({ id: message.id, error: { code: -32601, message: 'Interactive requests are not supported by the usage monitor.' } })}\n`)
          return
        }
        const request = pending.get(message.id)
        if (!request)
          return
        if (message.error)
          request.reject(new AppError(502, message.error.code === -32601 ? 'Update Codex to support this account operation.' : 'Codex could not read or update this account. Reconnect it and try again.'))
        else request.resolve(message.result)
        pending.delete(message.id)
      }
      catch {
        fail()
      }
    })
    const rpc: CodexRpc = {
      request: <T>(method: string, params: unknown = {}) => new Promise<T>((resolve, reject) => {
        if (closed)
          return reject(new AppError(503, 'Codex account service is unavailable.'))
        const id = ++sequence
        const timer = setTimeout(() => {
          pending.delete(id)
          reject(new AppError(504, 'Codex account request timed out.'))
        }, 20000)
        pending.set(id, { resolve: (value) => {
          clearTimeout(timer)
          resolve(value as T)
        }, reject: (error) => {
          clearTimeout(timer)
          reject(error)
        } })
        child.stdin.write(`${JSON.stringify({ id, method, params })}\n`)
      }),
    }
    try {
      await rpc.request('initialize', { clientInfo: { name: 'leo_agent_manager', version: '1.0.0' }, capabilities: { experimentalApi: true } })
      child.stdin.write(`${JSON.stringify({ method: 'initialized' })}\n`)
      return await operation(rpc)
    }
    finally {
      lines.close()
      fail()
      child.stdin.end()
      child.kill('SIGTERM')
      if (child.exitCode === null && child.signalCode === null) {
        await new Promise<void>((resolve) => {
          const timer = setTimeout(() => {
            child.kill('SIGKILL')
            resolve()
          }, 2000)
          child.once('close', () => {
            clearTimeout(timer)
            resolve()
          })
        })
      }
    }
  }
}
