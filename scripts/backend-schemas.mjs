import { writeFile } from 'node:fs/promises'
import { z } from 'zod'
import { chatInput, chatMessageInput, questionAnswerInput, questionFields } from '../shared/chats.ts'
import { agentInput, projectInput, taskInput } from '../shared/contracts.ts'
import { mcpInput } from '../shared/mcp.ts'

const schemas = { agent: agentInput, project: projectInput, task: taskInput, chat: chatInput, message: chatMessageInput, answer: questionAnswerInput, questions: questionFields, mcp: mcpInput }
await writeFile('backend/schemas/inputs.json', `${JSON.stringify(Object.fromEntries(Object.entries(schemas).map(([name, schema]) => [name, z.toJSONSchema(schema, { unrepresentable: 'any' })])), null, 2)}\n`)
