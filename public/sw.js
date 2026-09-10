/* Notifications only: never cache authenticated pages or API responses. */
globalThis.addEventListener('push', (event) => {
  let data
  try {
    data = event.data.json()
  }
  catch { return }
  if (!/^[\da-f-]{36}$/.test(data.chatId) || !/^[a-f0-9]{64}$/.test(data.questionId))
    return
  event.waitUntil(globalThis.registration.showNotification('Your agent has a question', {
    body: 'Open the chat to answer.',
    icon: '/icons/icon-192.png',
    badge: '/icons/icon-192.png',
    tag: `question-${data.questionId}`,
    data: { url: `/chats/${data.chatId}?question=${data.questionId}` },
  }))
})
globalThis.addEventListener('notificationclick', (event) => {
  event.notification.close()
  const url = new URL(event.notification.data?.url || '/chats', globalThis.location.origin)
  if (url.origin !== globalThis.location.origin)
    return
  event.waitUntil((async () => {
    for (const client of await globalThis.clients.matchAll({ type: 'window', includeUncontrolled: true })) {
      if (new URL(client.url).origin !== globalThis.location.origin)
        continue
      await client.navigate(url.href)
      await client.focus()
      return
    }
    await globalThis.clients.openWindow(url.href)
  })())
})
globalThis.addEventListener('message', (event) => {
  if (event.data?.type !== 'question-answered')
    return
  event.waitUntil((async () => {
    const notifications = await globalThis.registration.getNotifications({ tag: `question-${event.data.questionId}` })
    for (const notification of notifications) notification.close()
  })())
})
