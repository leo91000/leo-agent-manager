import type { FastifyReply } from 'fastify'
import type { Config } from './config.ts'
import { existsSync } from 'node:fs'
import path from 'node:path'
import process from 'node:process'
import cookie from '@fastify/cookie'
import formbody from '@fastify/formbody'
import rateLimit from '@fastify/rate-limit'
import staticFiles from '@fastify/static'
import Fastify from 'fastify'
import { z, ZodError } from 'zod'
import { Auth, OAuthError, safeEqual } from './auth.ts'
import { config as loadConfig } from './config.ts'
import { Connections } from './connections.ts'
import { AppError, requireValue } from './errors.ts'
import { mountMcp } from './mcp.ts'
import { nextOccurrences, Service } from './service.ts'
import { Store } from './store.ts'
import { Worker } from './worker.ts'

export async function buildApp(overrides: Partial<Config> = {}) {
  const config = loadConfig(overrides)
  const store = new Store(config.dataDir)
  const service = new Service(store, config)
  const worker = new Worker(service)
  const auth = new Auth(store, config.publicUrl)
  const connections = new Connections(config)
  const app = Fastify({
    logger: config.logger
      ? {
          redact: [
            'req.headers.authorization',
            'req.headers.cookie',
            'body.password',
            'body.setupToken',
          ],
        }
      : false,
    bodyLimit: 150000,
    trustProxy: false,
  })
  await app.register(cookie)
  await app.register(formbody)
  await app.register(rateLimit, { max: 300, timeWindow: '1 minute' })
  app.setErrorHandler((error, request, reply) => {
    if (error instanceof OAuthError) {
      return reply
        .code(400)
        .send({ error: error.code, error_description: error.message })
    }
    if (error instanceof ZodError) {
      return reply.code(400).send({
        error: error.issues
          .map(x => `${x.path.join('.')}: ${x.message}`)
          .join('; '),
      })
    }
    const status = (error as { statusCode?: number }).statusCode ?? 500
    if (status >= 500)
      app.log.error(error)
    reply.code(status).send({
      error:
        status >= 500
          ? 'An internal error occurred. Check the server logs.'
          : (error as Error).message,
    })
  })
  const origin = new URL(config.publicUrl)
  const origins = new Set([
    origin.origin,
    ...(process.env.NODE_ENV !== 'production'
      ? ['http://localhost:5178', 'http://127.0.0.1:5178']
      : []),
  ])
  app.addHook('onRequest', async (request, reply) => {
    if (
      ![origin.hostname, 'localhost', '127.0.0.1', '[::1]'].includes(
        request.hostname,
      )
    ) {
      throw new AppError(403, 'Unexpected host.')
    }
    if (request.headers.origin && !origins.has(request.headers.origin))
      throw new AppError(403, 'Unexpected origin.')
    const url = request.url.split('?')[0]
    if (
      url.startsWith('/api/')
      && !['/api/session', '/api/setup', '/api/login'].includes(url)
    ) {
      const session = auth.read(request.cookies.leo_session)
      if (!session)
        throw new AppError(401, 'Please sign in.')
      if (
        !['GET', 'HEAD', 'OPTIONS'].includes(request.method)
        && request.headers['x-csrf-token'] !== session.csrf
      ) {
        throw new AppError(
          403,
          'Invalid CSRF token. Refresh the page and try again.',
        )
      }
    }
    reply
      .header('X-Content-Type-Options', 'nosniff')
      .header('Referrer-Policy', 'same-origin')
      .header('X-Frame-Options', 'DENY')
      .header(
        'Content-Security-Policy',
        'default-src \'self\'; script-src \'self\'; style-src \'self\' \'unsafe-inline\'; font-src \'self\' data:; img-src \'self\' data:; connect-src \'self\'; object-src \'none\'; base-uri \'none\'; frame-ancestors \'none\'; form-action \'self\'',
      )
    if (url.startsWith('/api/') || url.startsWith('/oauth/'))
      reply.header('Cache-Control', 'no-store')
  })
  const setSession = (
    reply: FastifyReply,
    session: ReturnType<Auth['session']>,
  ) => {
    reply.setCookie('leo_session', session.value, {
      httpOnly: true,
      sameSite: 'lax',
      secure: origin.protocol === 'https:',
      path: '/',
      maxAge: 7 * 86400,
    })
    return { authenticated: true, csrf: session.csrf }
  }
  app.get('/health', () => ({ status: 'ok' }))
  app.get('/api/session', (request) => {
    const session = auth.read(request.cookies.leo_session)
    return {
      authenticated: !!session,
      csrf: session?.csrf,
      setupRequired: !store.kv('admin'),
    }
  })
  app.post(
    '/api/setup',
    { config: { rateLimit: { max: 5, timeWindow: '1 minute' } } },
    async (request, reply) => {
      const input = z
        .object({
          setupToken: z.string().max(200),
          password: z.string().min(12).max(200),
        })
        .parse(request.body)
      if (!config.setupToken || !safeEqual(input.setupToken, config.setupToken))
        throw new AppError(403, 'Incorrect setup token.')
      await auth.setup(input.password)
      store.audit('admin.setup', {})
      return setSession(reply, auth.session())
    },
  )
  app.post(
    '/api/login',
    { config: { rateLimit: { max: 10, timeWindow: '1 minute' } } },
    async (request, reply) => {
      const input = z
        .object({ password: z.string().max(200) })
        .parse(request.body)
      return setSession(reply, await auth.login(input.password))
    },
  )
  app.post('/api/logout', (request, reply) => {
    if (request.cookies.leo_session)
      auth.logout(request.cookies.leo_session)
    reply.clearCookie('leo_session', { path: '/' })
    return { ok: true }
  })
  app.get('/api/overview', () => ({
    counts: store.stats(),
    agents: store.list('agents').length,
    projects: store.list('projects').length,
    tasks: store.list('tasks'),
    runs: store.listRuns({ limit: 8 }),
    concurrency: config.concurrency,
  }))
  for (const kind of ['agents', 'projects', 'tasks'] as const) {
    app.get(`/api/${kind}`, () => store.list(kind))
    app.post(`/api/${kind}`, request =>
      kind === 'agents'
        ? service.agent(request.body)
        : kind === 'projects'
          ? service.project(request.body)
          : service.task(request.body))
    app.put<{ Params: { id: string } }>(`/api/${kind}/:id`, request =>
      kind === 'agents'
        ? service.agent(request.body, request.params.id)
        : kind === 'projects'
          ? service.project(request.body, request.params.id)
          : service.task(request.body, request.params.id))
    app.delete<{ Params: { id: string } }>(`/api/${kind}/:id`, (request) => {
      service.remove(kind, request.params.id)
      return { deleted: true }
    })
  }
  app.post<{ Params: { id: string } }>('/api/tasks/:id/run', request =>
    service.enqueue(request.params.id))
  app.post('/api/schedule/preview', (request) => {
    const input = z
      .object({ cron: z.string(), timezone: z.string() })
      .parse(request.body)
    return { occurrences: nextOccurrences(input.cron, input.timezone) }
  })
  app.get('/api/runs', (request) => {
    const query = z
      .object({
        limit: z.coerce.number().int().min(1).max(100).default(40),
        offset: z.coerce.number().int().min(0).default(0),
        status: z.string().optional(),
        taskId: z.string().optional(),
      })
      .parse(request.query)
    return store.listRuns(query)
  })
  app.get<{ Params: { id: string } }>('/api/runs/:id', request =>
    requireValue(store.run(request.params.id)))
  app.get<{ Params: { id: string } }>('/api/runs/:id/events', (request) => {
    requireValue(store.run(request.params.id))
    const query = z
      .object({ after: z.coerce.number().int().min(0).default(0) })
      .parse(request.query)
    return store.events(request.params.id, query.after)
  })
  app.post<{ Params: { id: string } }>('/api/runs/:id/cancel', (request) => {
    worker.cancel(request.params.id)
    return { ok: true }
  })
  app.post<{ Params: { id: string } }>('/api/runs/:id/retry', request =>
    service.enqueue(requireValue(store.run(request.params.id)).taskId, 'retry'))
  app.post<{ Params: { id: string } }>('/api/runs/:id/cleanup', request =>
    worker.cleanup(request.params.id))
  const skillProject = (scope: string) =>
    scope === 'global'
      ? undefined
      : requireValue(store.get('projects', scope), 'Project not found').path
  app.get('/api/skills', async () => {
    const items = await service.skills.list()
    for (const project of store.list('projects'))
      items.push(...(await service.skills.list(project.id, project.path)))
    return items
  })
  app.put<{ Params: { scope: string, name: string } }>(
    '/api/skills/:scope/:name',
    (request) => {
      const { content } = z
        .object({ content: z.string().max(100000) })
        .parse(request.body)
      store.audit('skill.saved', {
        scope: request.params.scope,
        name: request.params.name,
      })
      return service.skills.save(
        request.params.name,
        content,
        skillProject(request.params.scope),
      )
    },
  )
  app.delete<{ Params: { scope: string, name: string } }>(
    '/api/skills/:scope/:name',
    async (request) => {
      const key = `${request.params.scope}/${request.params.name}`
      if (store.list('tasks').some(task => task.skills.includes(key))) {
        throw new AppError(
          409,
          'This skill is selected by a task. Update that task first.',
        )
      }
      await service.skills.remove(
        request.params.name,
        skillProject(request.params.scope),
      )
      store.audit('skill.deleted', request.params)
      return { deleted: true }
    },
  )
  app.get<{ Params: { scope: string, name: string } }>(
    '/api/skills/:scope/:name/files',
    request =>
      service.skills.files(
        request.params.name,
        skillProject(request.params.scope),
      ),
  )
  app.get<{
    Params: { scope: string, name: string }
    Querystring: { path: string }
  }>('/api/skills/:scope/:name/file', async request => ({
    content: await service.skills.file(
      request.params.name,
      request.query.path,
      undefined,
      skillProject(request.params.scope),
    ),
  }))
  app.put<{ Params: { scope: string, name: string } }>(
    '/api/skills/:scope/:name/file',
    (request) => {
      const input = z
        .object({ path: z.string(), content: z.string().max(100000) })
        .parse(request.body)
      return service.skills.file(
        request.params.name,
        input.path,
        input.content,
        skillProject(request.params.scope),
      )
    },
  )
  app.get('/api/connections', request =>
    connections.status(
      (request.query as { refresh?: string }).refresh === 'true',
    ))
  app.get('/api/connections/login', () => connections.flow ?? null)
  app.post('/api/connections/login', request =>
    connections.start(
      z.object({ provider: z.enum(['codex', 'github']) }).parse(request.body)
        .provider,
    ))
  app.delete('/api/connections/login', () => {
    connections.cancel()
    return { cancelled: true }
  })
  app.get('/api/settings', () => ({
    publicUrl: config.publicUrl,
    workspaceRoots: config.workspaceRoots,
    home: config.home,
    concurrency: config.concurrency,
    mcpUrl: `${config.publicUrl}/mcp`,
    version: '0.1.0',
    commit: process.env.APP_COMMIT || 'development',
    protocol: '2026-07-28',
  }))
  app.get('/api/audit', () =>
    store.db.prepare('SELECT * FROM audit ORDER BY id DESC LIMIT 100').all())
  app.get('/api/tokens', () =>
    store
      .keys('grant:')
      .map(entry => ({ id: entry.data.family, ...entry.data })))
  app.post('/api/tokens', (request) => {
    const input = z
      .object({
        label: z.string().min(1).max(100),
        scopes: z.array(z.enum(['read', 'run', 'manage'])).min(1),
      })
      .parse(request.body)
    return auth.personal(input.label, input.scopes)
  })
  app.delete<{ Params: { id: string } }>('/api/tokens/:id', (request) => {
    auth.revoke(request.params.id)
    return { revoked: true }
  })
  const resourceMetadata = () => ({
    resource: `${config.publicUrl}/mcp`,
    authorization_servers: [config.publicUrl],
    scopes_supported: ['read', 'run', 'manage'],
    bearer_methods_supported: ['header'],
    resource_name: 'Leo Agent Manager',
  })
  app.get('/.well-known/oauth-protected-resource/mcp', resourceMetadata)
  app.get('/.well-known/oauth-protected-resource', resourceMetadata)
  app.get('/.well-known/oauth-authorization-server', () => ({
    issuer: config.publicUrl,
    authorization_endpoint: `${config.publicUrl}/oauth/authorize`,
    token_endpoint: `${config.publicUrl}/oauth/token`,
    registration_endpoint: `${config.publicUrl}/oauth/register`,
    revocation_endpoint: `${config.publicUrl}/oauth/revoke`,
    response_types_supported: ['code'],
    grant_types_supported: ['authorization_code', 'refresh_token'],
    code_challenge_methods_supported: ['S256'],
    token_endpoint_auth_methods_supported: ['none'],
    scopes_supported: ['read', 'run', 'manage'],
  }))
  app.post(
    '/oauth/register',
    { config: { rateLimit: { max: 10, timeWindow: '1 hour' } } },
    (request, reply) =>
      reply
        .code(201)
        .send(
          auth.register(z.record(z.string(), z.unknown()).parse(request.body)),
        ),
  )
  app.get('/oauth/authorize', (request, reply) => {
    const parameters = z.record(z.string(), z.string()).parse(request.query)
    auth.authorization(parameters)
    return reply.redirect(`/authorize?${new URLSearchParams(parameters)}`)
  })
  app.post('/api/oauth/preview', request =>
    auth.authorization(z.record(z.string(), z.string()).parse(request.body)))
  app.post('/api/oauth/consent', (request) => {
    const input = z
      .object({
        parameters: z.record(z.string(), z.string()),
        approved: z.boolean(),
      })
      .parse(request.body)
    return { redirect: auth.consent(input.parameters, input.approved) }
  })
  app.post('/oauth/token', request =>
    auth.exchange(z.record(z.string(), z.string()).parse(request.body)))
  app.post('/oauth/revoke', (request) => {
    const input = z
      .object({ token: z.string(), client_id: z.string() })
      .parse(request.body)
    auth.revokeToken(input.token, input.client_id)
    return {}
  })
  mountMcp(app, service, worker, auth)
  const dist = path.resolve('dist')
  if (existsSync(path.join(dist, 'index.html'))) {
    await app.register(staticFiles, { root: dist, prefix: '/', maxAge: '1h' })
    app.setNotFoundHandler((request, reply) => {
      if (
        request.method === 'GET'
        && !request.url.startsWith('/api/')
        && !request.url.startsWith('/oauth/')
        && !request.url.startsWith('/.well-known/')
      ) {
        return reply.header('Cache-Control', 'no-cache').sendFile('index.html')
      }
      return reply.code(404).send({ error: 'Not found' })
    })
  }
  app.addHook('onClose', async () => {
    connections.cancel()
    await worker.close()
    store.close()
  })
  if (config.workerEnabled)
    worker.start()
  return { app, service, worker, auth, connections }
}
