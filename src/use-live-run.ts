import type { RunEvent } from '../shared/contracts'
import type { LiveState } from '../shared/live'
import type { ReadingPosition } from './history-cache'
import type { LiveStatus } from './live-connection'
import { computed, onScopeDispose, ref, watch } from 'vue'
import { api, ApiError, state } from './api'
import { cacheScope, clearHistoryCache, readHistory, removeHistory, writeHistory } from './history-cache'
import { liveConnection } from './live-connection'
import { LiveEvents } from './live-events'

export function useLiveRun(path: () => string) {
  const snapshot = ref<LiveState>()
  const events = ref<RunEvent[]>([])
  const status = ref<LiveStatus>('connecting')
  const catchingUp = ref(true)
  const error = ref('')
  const position = ref<ReadingPosition>()
  let connection: ReturnType<typeof liveConnection> | undefined
  let disposed = false
  let generation = 0
  let persist: (() => void) | undefined
  let saveTimer: ReturnType<typeof setTimeout> | undefined
  function scheduleSave() {
    if (saveTimer !== undefined)
      return
    saveTimer = setTimeout(() => {
      saveTimer = undefined
      persist?.()
    }, 500)
  }
  function savePosition(value: ReadingPosition, key?: string) {
    if (key && key !== path())
      return
    position.value = value
    scheduleSave()
  }
  watch([path, () => state.authenticated && !state.signingOut, () => state.csrf], async ([value, enabled, csrf]) => {
    persist?.()
    const current = ++generation
    connection?.close()
    clearTimeout(saveTimer)
    saveTimer = undefined
    persist = undefined
    snapshot.value = undefined
    events.value = []
    position.value = undefined
    catchingUp.value = true
    status.value = 'connecting'
    error.value = ''
    if (!enabled) {
      void clearHistoryCache()
      return
    }
    const scope = await cacheScope(csrf).catch(() => undefined)
    const cached = scope ? await readHistory(scope, value) : undefined
    if (disposed || current !== generation)
      return
    let accumulator = new LiveEvents()
    let rows: RunEvent[] = cached?.events.slice() ?? []
    let detail = cached?.state
    let cursor = cached?.cursor ?? 0
    let history = cached?.history
    let complete = !!cached
    let storedPosition = cached?.position
    position.value = storedPosition
    accumulator.restore(rows, cursor)
    if (cached) {
      snapshot.value = cached.state
      events.value = cached.events
      catchingUp.value = false
    }
    persist = () => {
      if (!scope || !complete || !detail || !history || current !== generation || !state.authenticated || state.signingOut)
        return
      storedPosition = position.value
      void writeHistory(scope, value, { version: 1, cursor, history, state: detail, events: rows.slice(), savedAt: Date.now(), position: storedPosition })
    }
    let checking = false
    connection = liveConnection(value, (batch, accepted) => {
      const reset = batch.reset || (history && batch.history !== history)
        || (batch.state && detail?.run?.id !== batch.state.run?.id)
      if (reset) {
        rows = []
        accumulator = new LiveEvents()
        complete = false
        position.value = undefined
        if (scope)
          void removeHistory(scope, value)
      }
      // Persist a complete snapshot only. An interrupted catch-up cannot corrupt it.
      const next = rows.slice()
      const candidate = accumulator.copy()
      candidate.append(next, batch.events)
      accumulator = candidate
      rows = next
      if (batch.state)
        detail = batch.state
      cursor = accepted
      history = batch.history
      complete = !batch.more
      if (complete) {
        snapshot.value = detail
        events.value = rows.slice()
        catchingUp.value = false
        scheduleSave()
      }
      error.value = ''
    }, (connectionStatus) => {
      status.value = connectionStatus
      if (connectionStatus !== 'reconnecting' || checking)
        return
      checking = true
      void api(value.replace(/\/stream$/, '')).catch((e) => {
        if (!disposed && current === generation && e instanceof ApiError && [401, 403, 404].includes(e.status)) {
          complete = false
          clearTimeout(saveTimer)
          error.value = e.message
          connection?.close()
          snapshot.value = undefined
          events.value = []
          if (scope)
            void removeHistory(scope, value)
        }
      }).finally(() => { checking = false })
    }, cached ? { cursor: cached.cursor, history: cached.history } : undefined)
  }, { immediate: true, flush: 'sync' })
  const leaving = () => persist?.()
  window.addEventListener('pagehide', leaving)
  onScopeDispose(() => {
    window.removeEventListener('pagehide', leaving)
    persist?.()
    clearTimeout(saveTimer)
    disposed = true
    generation++
    connection?.close()
  })
  return {
    snapshot,
    events,
    catchingUp,
    error,
    position,
    savePosition,
    connectionNotice: computed(() => error.value ? '' : status.value === 'offline' ? (snapshot.value ? 'Offline · showing saved conversation' : 'Offline') : status.value === 'reconnecting' ? 'Reconnecting…' : status.value === 'connecting' && snapshot.value ? 'Updating…' : ''),
    reconnect: () => connection?.reconnect(),
  }
}
