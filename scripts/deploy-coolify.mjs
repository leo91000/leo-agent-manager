import { appendFileSync } from 'node:fs'
import process from 'node:process'
import { setTimeout } from 'node:timers/promises'

export async function deploy(config, { timeoutMs = 600000, intervalMs = 5000 } = {}) {
  const { coolifyUrl, serviceUuid, token, image, commit, publicUrl } = config
  const servicePath = `/api/v1/services/${encodeURIComponent(serviceUuid)}`
  async function api(path, method, body) {
    const response = await fetch(new URL(path, coolifyUrl), {
      method,
      headers: { 'authorization': `Bearer ${token}`, 'content-type': 'application/json' },
      body: body ? JSON.stringify(body) : undefined,
      redirect: 'error',
      signal: AbortSignal.timeout(15000),
    })
    // API responses can contain environment values: never log their bodies.
    await response.body?.cancel()
    if (!response.ok)
      throw new Error(`Coolify ${method} ${path} failed (HTTP ${response.status})`)
  }

  await api(`${servicePath}/envs`, 'PATCH', {
    key: 'LEO_IMAGE',
    value: image,
    is_literal: true,
  })
  await api(`${servicePath}/restart`, 'POST')

  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    try {
      const response = await fetch(new URL('/health', publicUrl), {
        cache: 'no-store',
        redirect: 'error',
        signal: AbortSignal.timeout(10000),
      })
      if (response.ok) {
        const health = await response.json()
        if (health.status === 'ok' && health.commit === commit)
          return
      }
      else {
        await response.body?.cancel()
      }
    }
    catch {
      // The reverse proxy can briefly return errors during replacement.
    }
    await setTimeout(intervalMs)
  }
  throw new Error(`Deployment did not serve commit ${commit} before the timeout`)
}

function configuration() {
  const names = ['COOLIFY_URL', 'COOLIFY_SERVICE_UUID', 'COOLIFY_TOKEN', 'LEO_PUBLIC_URL', 'DEPLOY_IMAGE', 'DEPLOY_COMMIT']
  for (const name of names) {
    if (!process.env[name])
      throw new Error(`Missing ${name}`)
  }
  for (const name of ['COOLIFY_URL', 'LEO_PUBLIC_URL']) {
    const url = new URL(process.env[name])
    if (url.protocol !== 'https:' || url.username || url.password || url.pathname !== '/' || url.search || url.hash)
      throw new Error(`${name} must be an HTTPS origin`)
  }
  if (!/^ghcr\.io\/[a-z0-9_.\-/]+@sha256:[a-f0-9]{64}$/.test(process.env.DEPLOY_IMAGE))
    throw new Error('DEPLOY_IMAGE must be an immutable GHCR digest')
  if (!/^[a-f0-9]{40}$/.test(process.env.DEPLOY_COMMIT))
    throw new Error('DEPLOY_COMMIT must be a full Git commit SHA')
  return {
    coolifyUrl: process.env.COOLIFY_URL,
    serviceUuid: process.env.COOLIFY_SERVICE_UUID,
    token: process.env.COOLIFY_TOKEN,
    image: process.env.DEPLOY_IMAGE,
    commit: process.env.DEPLOY_COMMIT,
    publicUrl: process.env.LEO_PUBLIC_URL,
  }
}

if (import.meta.main) {
  try {
    const config = configuration()
    await deploy(config)
    const result = `Deployed ${config.image}\nVerified commit ${config.commit} at ${config.publicUrl}\n`
    console.log(result)
    if (process.env.GITHUB_STEP_SUMMARY)
      appendFileSync(process.env.GITHUB_STEP_SUMMARY, result)
  }
  catch (error) {
    console.error(error.message)
    process.exitCode = 1
  }
}
