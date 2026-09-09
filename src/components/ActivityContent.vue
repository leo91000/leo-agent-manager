<script setup lang="ts">
import { Braces, ChevronDown } from '@lucide/vue'
import { computed, ref } from 'vue'
import { contentParts, dataSummary, dataTitle } from '../activity-data'
import ActivityCode from './ActivityCode.vue'
import ActivityDataNode from './ActivityDataNode.vue'
import Markdown from './Markdown.vue'

const props = defineProps<{ content: string, label?: string, language?: string }>()
const sourceOpen = ref(new Set<number>())
function toggleSource(index: number, event: Event) {
  if ((event.target as HTMLDetailsElement).open)
    sourceOpen.value.add(index)
  else
    sourceOpen.value.delete(index)
}
const parts = computed(() => props.language && !['json', 'plaintext'].includes(props.language) ? [{ kind: 'text' as const, text: props.content }] : contentParts(props.content))
</script>

<template>
  <template v-for="(part, index) in parts" :key="index">
    <section v-if="part.kind === 'data'" class="activity-data" :aria-label="dataTitle(part.value)">
      <header class="data-header">
        <span class="data-header-icon"><Braces :size="18" /></span><div><strong>{{ dataTitle(part.value) }}</strong><small>{{ label || 'Agent output' }} · {{ dataSummary(part.value) }}</small></div>
      </header>
      <div class="data-content">
        <ActivityDataNode :value="part.value" />
      </div>
      <details class="data-source" @toggle="toggleSource(index, $event)">
        <summary><Braces :size="14" />View JSON<ChevronDown :size="14" /></summary><ActivityCode v-if="sourceOpen.has(index)" label="JSON" :code="JSON.stringify(part.value, null, 2)" :copy-code="part.source" language="json" />
      </details>
    </section>
    <template v-else-if="part.text.trim()">
      <ActivityCode v-if="label" :label="label" :code="part.text" :language="language" />
      <Markdown v-else :content="part.text" />
    </template>
  </template>
</template>
