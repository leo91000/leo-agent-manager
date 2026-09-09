<script setup lang="ts">
import { Check, Copy } from '@lucide/vue'
import { computed, onBeforeUnmount, ref } from 'vue'
import { notify } from '../api'
import { highlight } from '../highlight'

const props = defineProps<{ code: string, label: string, language?: string }>()
const expanded = ref(false)
const copied = ref(false)
let timer: ReturnType<typeof setTimeout>
const shown = computed(() => expanded.value ? props.code : props.code.slice(0, 12000))
const html = computed(() => highlight(shown.value, props.language))
async function copy() {
  try {
    await navigator.clipboard.writeText(props.code)
    copied.value = true
    clearTimeout(timer)
    timer = setTimeout(() => copied.value = false, 2000)
  }
  catch {
    notify('Clipboard unavailable. Select the text to copy it.')
  }
}
onBeforeUnmount(() => clearTimeout(timer))
</script>

<template>
  <div class="activity-code">
    <header>
      <span>{{ label }}</span><button class="code-copy" :aria-label="`Copy ${label}`" @click="copy">
        <Check v-if="copied" :size="14" /><Copy v-else :size="14" />{{ copied ? 'Copied' : 'Copy' }}
      </button>
    </header>
    <pre tabindex="0" :aria-label="label"><code v-html="html" /></pre>
    <button v-if="code.length > 12000" class="code-expand" @click="expanded = !expanded">
      {{ expanded ? 'Show less' : `Show all ${code.length.toLocaleString()} characters` }}
    </button>
  </div>
</template>
