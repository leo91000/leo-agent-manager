import type { FastifyInstance } from 'fastify'
import type { Auth } from './auth.ts'
import type { Service } from './service.ts'
import type { Worker } from './worker.ts'
import { toNodeHandler } from '@modelcontextprotocol/node'
import { createMcpHandler, McpServer } from '@modelcontextprotocol/server'
import { z } from 'zod'
import { agentInput, projectInput, taskInput } from '../shared/contracts.ts'
import { version } from '../shared/version.ts'
import { AppError, requireValue } from './errors.ts'

export function mountMcp(
  app: FastifyInstance,
  service: Service,
  worker: Worker,
  auth: Auth,
) {
  app.all('/mcp', async (request, reply) => {
    const bearer = request.headers.authorization?.match(/^Bearer (.+)$/i)?.[1]
    try {
      auth.verify(bearer)
    }
    catch {
      reply.header(
        'WWW-Authenticate',
        `Bearer resource_metadata="${service.config.publicUrl}/.well-known/oauth-protected-resource/mcp"`,
      )
      return reply.code(401).send({ error: 'unauthorized' })
    }
    const handler = createMcpHandler(
      () => {
        const server = new McpServer({
          name: 'leo-agent-manager',
          version,
        })
        const tool = <Shape extends z.ZodRawShape>(
          name: string,
          description: string,
          schema: z.ZodObject<Shape>,
          scope: string,
          fn: (args: z.output<z.ZodObject<Shape>>) => unknown,
          destructive = false,
        ) => {
          server.registerTool(
            name,
            {
              description,
              title: name.replaceAll('_', ' '),
              inputSchema: schema,
              outputSchema: z.object({ result: z.unknown() }),
              _meta: { securitySchemes: [{ type: 'oauth2', scopes: [scope] }] },
              annotations: {
                readOnlyHint: scope === 'read',
                destructiveHint:
                  destructive
                  || scope === 'run'
                  || name.startsWith('update_')
                  || name === 'save_skill',
                openWorldHint: scope !== 'read',
              },
            },
            async (args) => {
              try {
                auth.verify(bearer, scope)
                const result = await fn(schema.parse(args))
                return {
                  structuredContent: { result },
                  content: [
                    { type: 'text' as const, text: JSON.stringify(result) },
                  ],
                }
              }
              catch (e) {
                return {
                  isError: true,
                  ...(e instanceof AppError && [401, 403].includes(e.statusCode)
                    ? {
                        _meta: {
                          'mcp/www_authenticate': `Bearer error="insufficient_scope", error_description="The ${scope} scope is required", scope="${scope}", resource_metadata="${service.config.publicUrl}/.well-known/oauth-protected-resource/mcp"`,
                        },
                      }
                    : {}),
                  content: [
                    { type: 'text' as const, text: (e as Error).message },
                  ],
                }
              }
            },
          )
        }
        tool(
          'list_agents',
          'List configured Codex agent profiles.',
          z.object({}),
          'read',
          () => service.store.list('agents'),
        )
        tool(
          'save_agent',
          'Create an agent profile. Execution access is explicit.',
          agentInput,
          'manage',
          args => service.agent(args),
        )
        tool(
          'list_projects',
          'List registered project workspaces.',
          z.object({}),
          'read',
          () => service.store.list('projects'),
        )
        tool(
          'save_project',
          'Register an existing directory inside an allowed workspace root.',
          projectInput,
          'manage',
          args => service.project(args),
        )
        tool(
          'list_tasks',
          'List task instructions and schedules.',
          z.object({}),
          'read',
          () => service.store.list('tasks'),
        )
        tool(
          'create_task',
          'Create a one-off or scheduled task. This does not execute it immediately.',
          taskInput,
          'manage',
          args => service.task(args),
        )
        tool(
          'update_task',
          'Update an existing task, including pausing its schedule.',
          z.object({ id: z.string().uuid(), task: taskInput }),
          'manage',
          args => service.task(args.task, args.id),
        )
        tool(
          'run_task',
          'Queue a task for YOLO execution with full container access and no approval prompts. May modify code and external services as instructed.',
          z.object({ taskId: z.string().uuid() }),
          'run',
          args => service.enqueue(args.taskId, 'mcp'),
        )
        tool(
          'cancel_run',
          'Cancel queued or running work. Already completed external effects are preserved.',
          z.object({ runId: z.string().uuid() }),
          'run',
          (args) => {
            worker.cancel(args.runId)
            return { cancelled: true }
          },
          true,
        )
        tool(
          'list_runs',
          'List recent runs with pagination, without bulky prompts or logs.',
          z.object({
            limit: z.number().int().min(1).max(100).default(20),
            offset: z.number().int().min(0).default(0),
            status: z.string().optional(),
          }),
          'read',
          args => service.store.listRuns(args),
        )
        tool(
          'get_run',
          'Get a run summary and an incremental page of its event log.',
          z.object({
            runId: z.string().uuid(),
            after: z.number().int().min(0).default(0),
          }),
          'read',
          args => ({
            run: requireValue(service.store.run(args.runId)),
            events: service.store.events(args.runId, args.after),
          }),
        )
        tool(
          'list_skills',
          'List global .agents skills or the skills for one registered project.',
          z.object({ projectId: z.string().uuid().optional() }),
          'read',
          args =>
            service.skills.list(
              args.projectId ?? 'global',
              args.projectId
                ? requireValue(service.store.get('projects', args.projectId))
                  .path
                : undefined,
            ),
        )
        tool(
          'save_skill',
          'Create or update SKILL.md with validated frontmatter in .agents/skills.',
          z.object({
            name: z.string(),
            content: z.string().max(100000),
            projectId: z.string().uuid().optional(),
          }),
          'manage',
          args =>
            service.skills.save(
              args.name,
              args.content,
              args.projectId
                ? requireValue(service.store.get('projects', args.projectId))
                  .path
                : undefined,
            ),
        )
        return server
      },
      { legacy: 'stateless' },
    )
    reply.hijack()
    try {
      await toNodeHandler(handler)(request.raw, reply.raw, request.body)
    }
    finally {
      await handler.close()
    }
  })
}
