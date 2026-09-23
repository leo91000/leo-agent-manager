import type { ModelCatalog } from '../shared/models'
import { reactive } from 'vue'
import { api } from './api'

export const modelCatalog = reactive({ models: [] as ModelCatalog['models'], checkedAt: null as number | null, stale: false, error: '', loading: false })
let pending: Promise<void> | undefined
export function loadModels() {
  if (pending)
    return pending
  modelCatalog.loading = true
  pending = api<ModelCatalog>('/codex/models')
    .then(catalog => Object.assign(modelCatalog, catalog))
    .catch((error: Error) => { modelCatalog.error = error.message })
    .finally(() => {
      modelCatalog.loading = false
      pending = undefined
    })
    .then(() => {})
  return pending
}

export const claudeCatalog = reactive({ models: [] as ModelCatalog['models'], checkedAt: null as number | null, stale: false, error: '', loading: false })
let claudePending: Promise<void> | undefined
export function loadClaudeModels() {
  if (claudePending)
    return claudePending
  claudeCatalog.loading = true
  claudePending = api<ModelCatalog>('/claude/models')
    .then(catalog => Object.assign(claudeCatalog, catalog))
    .catch((error: Error) => { claudeCatalog.error = error.message })
    .finally(() => {
      claudeCatalog.loading = false
      claudePending = undefined
    })
    .then(() => {})
  return claudePending
}
