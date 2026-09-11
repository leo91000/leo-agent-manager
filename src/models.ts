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
