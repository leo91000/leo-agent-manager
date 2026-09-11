<script setup lang="ts">
import type { CodexLoginFlow } from '../../shared/codex-accounts'
import { computed, ref, watch } from 'vue'
import { ArrowUpRight, Check, Copy, LoaderCircle, RefreshCw } from '../icons'
import { buttonBase, buttonSizes, buttonVariants } from '../ui'
import Icon from './Icon.vue'
import UiButton from './UiButton.vue'

const props = defineProps<{ flow: CodexLoginFlow, busy: boolean }>()
defineEmits<{ cancel: [], retry: [] }>()
const copied = ref(false)
const copyError = ref('')
const waiting = computed(() => props.flow.state === 'pending')
const ready = computed(() => waiting.value && !!props.flow.code && !!props.flow.url)
const title = computed(() => {
  if (props.flow.state === 'failed')
    return 'Let’s try that again'
  if (props.flow.phase === 'verifying')
    return 'Verifying your account'
  return ready.value ? 'Connect your ChatGPT account' : 'Preparing sign-in'
})
watch(() => props.flow.code, () => {
  copied.value = false
  copyError.value = ''
})
async function copyCode() {
  const code = props.flow.code
  if (!code)
    return
  try {
    // Called directly from the click, without delaying the link's navigation.
    await navigator.clipboard.writeText(code)
    if (props.flow.code !== code)
      return
    copied.value = true
    copyError.value = ''
  }
  catch {
    if (props.flow.code !== code)
      return
    copied.value = false
    copyError.value = 'Couldn’t copy automatically. Select the code and copy it manually.'
  }
}
</script>

<template>
  <section aria-labelledby="codex-sign-in-title" class="my-5 overflow-hidden rounded-card border border-accent bg-surface">
    <div class="p-5 phone:p-4">
      <div class="flex items-center gap-3">
        <span class="grid size-9 shrink-0 place-items-center rounded-xl bg-soft text-accent">
          <Icon :name="ready ? Copy : waiting ? LoaderCircle : RefreshCw" :size="18" :class="waiting && !ready ? 'animate-spin motion-reduce:animate-none' : ''" />
        </span>
        <h3 id="codex-sign-in-title" aria-live="polite">
          {{ title }}
        </h3>
      </div>
      <template v-if="ready">
        <p class="mt-3 text-sm text-muted">
          Enter this code on OpenAI, using the account you want to add.
        </p>
        <div class="my-4 flex w-fit max-w-full items-center gap-3 rounded-xl border border-line bg-inset px-4 py-3 phone:px-3">
          <code aria-label="Verification code" class="min-w-0 select-all break-all font-mono text-2xl font-semibold tracking-wider phone:text-xl">{{ flow.code }}</code>
          <UiButton size="small" :aria-label="copied ? 'Code copied' : 'Copy verification code'" @click="copyCode">
            <Icon :name="copied ? Check : Copy" :size="16" />
          </UiButton>
        </div>
        <p v-if="copyError" role="alert" class="mb-3 text-sm text-warning">
          {{ copyError }}
        </p>
        <p v-else-if="copied" role="status" class="mb-3 text-xs text-accent">
          Code copied. Paste it on the verification page.
        </p>
      </template>
      <p v-else-if="waiting" class="mt-3 text-sm text-muted">
        {{ flow.phase === 'verifying' ? 'Sign-in approved. Checking your account and usage…' : 'Getting a secure code from Codex…' }}
      </p>
      <p v-if="flow.error" role="alert" class="mt-3 text-sm text-danger">
        {{ flow.error }}
      </p>
      <div class="mt-4 flex flex-wrap gap-2">
        <a v-if="ready" :href="flow.url" target="_blank" rel="noopener noreferrer" :class="[buttonBase, buttonVariants.primary, buttonSizes.small]" @click="copyCode">Copy code &amp; open sign-in<Icon :name="ArrowUpRight" :size="15" /></a>
        <UiButton v-if="flow.state === 'failed'" variant="primary" size="small" :disabled="busy" @click="$emit('retry')">
          <Icon :name="RefreshCw" :size="15" />Try again
        </UiButton>
        <UiButton size="small" :disabled="busy || flow.phase === 'verifying' && waiting" @click="$emit('cancel')">
          {{ waiting ? 'Cancel sign-in' : 'Dismiss' }}
        </UiButton>
      </div>
    </div>
    <p v-if="ready" class="border-t border-line bg-soft px-5 py-3 text-xs text-muted phone:px-4">
      This page connects automatically after you approve. The code is temporary.
    </p>
  </section>
</template>
