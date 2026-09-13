import type { RunEvent } from '../shared/contracts'
import type { LiveState } from '../shared/live'
import type { LiveStatus } from './live-connection'
import { computed, onScopeDispose, ref, watch } from 'vue'
import { api, ApiError } from './api'
import { liveConnection } from './live-connection'
import { LiveEvents } from './live-events'

export function useLiveRun(path: () => string) {
  const snapshot = ref<LiveState>()
  const events = ref<RunEvent[]>([])
  const status = ref<LiveStatus>('connecting')
  const catchingUp = ref(true)
  const error = ref('')
  let connection: ReturnType<typeof liveConnection> | undefined
  let accumulator = new LiveEvents()
  let disposed = false
  let generation = 0
  watch(path, (value) => {
    const current = ++generation
    let checking = false
    connection?.close()
    snapshot.value = undefined
    events.value = []
    accumulator = new LiveEvents()
    catchingUp.value = true
    error.value = ''
    connection = liveConnection(value, (batch) => {
      if (batch.reset || (batch.state && snapshot.value?.run?.id !== batch.state.run?.id)) {
        events.value = []
        accumulator = new LiveEvents()
      }
      accumulator.append(events.value, batch.events)
      if (batch.state)
        snapshot.value = batch.state
      catchingUp.value = batch.more
      error.value = ''
    }, (connectionStatus) => {
      status.value = connectionStatus
      if (connectionStatus !== 'reconnecting' || checking)
        return
      // EventSource deliberately hides HTTP status. Check access separately to
      // distinguish a transient disconnect from logout/deletion, then stop retrying.
      checking = true
      void api(value.replace(/\/stream$/, '')).catch((e) => {
        if (!disposed && current === generation && e instanceof ApiError && [401, 403, 404].includes(e.status)) {
          error.value = e.message
          connection?.close()
        }
      }).finally(() => { checking = false })
    })
  }, { immediate: true })
  onScopeDispose(() => {
    disposed = true
    connection?.close()
  })
  return {
    snapshot,
    events,
    catchingUp,
    error,
    connectionNotice: computed(() => error.value ? '' : status.value === 'offline' ? 'Offline · reconnecting when the network returns' : status.value === 'reconnecting' ? 'Reconnecting… The conversation will catch up automatically.' : ''),
    reconnect: () => connection?.reconnect(),
  }
}
