<script setup lang="ts">
import type { Deliverable } from '../../shared/artifacts'
import DOMPurify from 'dompurify'
import { marked } from 'marked'
import { computed, defineAsyncComponent, onBeforeUnmount, ref } from 'vue'
import { artifactLink } from '../../shared/artifacts'
import { api } from '../api'
import { highlight } from '../highlight'

const props = defineProps<{ content: string }>()
const ArtifactViewer = defineAsyncComponent(() => import('./ArtifactViewer.vue'))
const opening = ref<{ items: Deliverable[], id: string }>()
const loading = ref(false)
const error = ref('')
let request: AbortController | undefined
onBeforeUnmount(() => request?.abort())
async function openLink(event: MouseEvent) {
  if (event.defaultPrevented || event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey)
    return
  const anchor = event.target instanceof Element ? event.target.closest('a[href]') : null
  const target = anchor && artifactLink(anchor.getAttribute('href')!, window.location.origin)
  if (!target)
    return
  event.preventDefault()
  request?.abort()
  const controller = new AbortController()
  request = controller
  loading.value = true
  error.value = ''
  try {
    const items = await api<Deliverable[]>(`/runs/${target.runId}/artifacts`, { signal: controller.signal })
    if (!items.some(item => item.id === target.id && item.runId === target.runId))
      throw new Error('This file is no longer available.')
    if (!controller.signal.aborted)
      opening.value = { items, id: target.id }
  }
  catch (e) {
    if (!controller.signal.aborted)
      error.value = (e as Error).message
  }
  finally {
    if (!controller.signal.aborted)
      loading.value = false
  }
}
const html = computed(() => {
  const container = document.createElement('div')
  container.innerHTML = DOMPurify.sanitize(marked.parse(props.content, { async: false }) as string)
  for (const code of container.querySelectorAll('pre code')) {
    const language = [...code.classList].find(name => name.startsWith('language-'))?.slice(9)
    code.innerHTML = highlight(code.textContent || '', language)
  }
  for (const link of container.querySelectorAll('a[href]')) {
    link.setAttribute('target', '_blank')
    link.setAttribute('rel', 'noopener noreferrer')
  }
  return container.innerHTML
})
</script>

<template>
  <div class="markdown text-sm leading-[1.8] wrap-anywhere" @click="openLink" v-html="html" />
  <p v-if="loading" role="status" class="text-sm text-muted">
    Opening file…
  </p>
  <p v-if="error" role="alert" class="text-sm text-danger">
    {{ error }}
  </p>
  <ArtifactViewer v-if="opening" :key="opening.id" :items="opening.items" :initial="opening.id" @close="opening = undefined" />
</template>
