// The oracle predates accounts shared by Codex and Claude Code: its runs still name
// their Codex account with these fields.
import type { Run as ApplicationRun } from '../../../shared/contracts.ts'

export type * from '../../../shared/contracts.ts'
export interface Run extends ApplicationRun {
  codexAccountId?: string | null
  codexAccountName?: string | null
}
