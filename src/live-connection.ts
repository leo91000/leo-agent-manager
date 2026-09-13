import type { LiveBatch } from '../shared/live'

export type LiveStatus = 'connecting' | 'live' | 'reconnecting' | 'offline'

// The cursor advances only after the consumer accepts a complete batch. Every
// connection (including another tab) owns its cursor; nothing is shared in storage.
export function liveConnection(path: string, accept: (batch: LiveBatch) => void, status: (value: LiveStatus) => void) {
  let cursor = '0'
  let source: EventSource | undefined
  let timer: ReturnType<typeof setTimeout> | undefined
  let watchdog: ReturnType<typeof setTimeout> | undefined
  let stopped = false
  let failures = 0
  function disconnect() {
    source?.close()
    source = undefined
    clearTimeout(timer)
    clearTimeout(watchdog)
  }
  function retry() {
    disconnect()
    if (stopped)
      return
    status(navigator.onLine ? 'reconnecting' : 'offline')
    if (navigator.onLine)
      timer = setTimeout(connect, Math.min(15000, 500 * 2 ** Math.min(failures++, 5)))
  }
  function alive() {
    clearTimeout(watchdog)
    watchdog = setTimeout(retry, 45000)
  }
  function connect() {
    disconnect()
    if (stopped)
      return
    if (!navigator.onLine) {
      status('offline')
      return
    }
    status(failures ? 'reconnecting' : 'connecting')
    const current = new EventSource(`/api${path}?after=${cursor}`)
    source = current
    alive()
    current.addEventListener('ping', () => {
      if (source === current && !stopped)
        alive()
    })
    current.addEventListener('batch', (event) => {
      if (source !== current || stopped)
        return
      try {
        if (!/^\d+$/.test(event.lastEventId))
          throw new Error('Invalid stream cursor')
        const batch = JSON.parse(event.data) as LiveBatch
        if (!Array.isArray(batch.events) || typeof batch.reset !== 'boolean' || typeof batch.more !== 'boolean')
          throw new Error('Invalid stream batch')
        accept(batch)
        cursor = event.lastEventId
        failures = 0
        status('live')
        alive()
      }
      catch { retry() }
    })
    current.onerror = () => {
      if (source === current)
        retry()
    }
  }
  function visible() {
    if (!document.hidden)
      connect()
  }
  function offline() {
    disconnect()
    status('offline')
  }
  window.addEventListener('online', connect)
  window.addEventListener('offline', offline)
  window.addEventListener('pageshow', visible)
  document.addEventListener('visibilitychange', visible)
  connect()
  return {
    reconnect: connect,
    close() {
      stopped = true
      disconnect()
      window.removeEventListener('online', connect)
      window.removeEventListener('offline', offline)
      window.removeEventListener('pageshow', visible)
      document.removeEventListener('visibilitychange', visible)
    },
  }
}
