<script setup lang="ts">
import DOMPurify from 'dompurify'
import { marked } from 'marked'
import { computed } from 'vue'
import { highlight } from '../highlight'

const props = defineProps<{ content: string }>()
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
  <div class="markdown" v-html="html" />
</template>
