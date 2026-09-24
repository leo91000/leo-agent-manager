<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { api, notify } from '../api'
import { ArrowUpRight, CheckCircle2, LoaderCircle, LogIn, RefreshCw } from '../icons'
import Icon from './Icon.vue'
import UiAlert from './UiAlert.vue'
import UiButton from './UiButton.vue'

interface Login { id: string, state: string, url: string | null, error: string | null, expiresAt: number }
interface UsageWindow { id: string, label: string, usedPercent: number, resetsAt: number | null }
interface Usage { windows: UsageWindow[], checkedAt: number | null, stale: boolean, error: string | null }
interface Connection { usage?: Usage | null, connected: boolean, busy: boolean, email?: string | null, subscriptionType?: string | null, error?: string, login: Login | null }
const connection = ref<Connection>()
const busy = ref(false)
const error = ref('')
const code = ref('')
const now = ref(Date.now())
const remaining = (window: UsageWindow) => Math.max(0, Math.min(100, 100 - window.usedPercent))
const resetDate = (window: UsageWindow) => window.resetsAt ? new Date(window.resetsAt * 1000).toLocaleString() : ''
const submitted = ref(false)
const pending = computed(() => connection.value?.login?.state === 'pending')
let timer: ReturnType<typeof setTimeout> | undefined
let disposed = false
let generation = 0
async function load() {
  try {
    const next = await api<Connection>('/claude/connection')
    if (disposed)
      return
    if (pending.value && next.login?.state === 'completed') {
      notify('Claude Code connected')
      code.value = ''
    }
    connection.value = next
    now.value = Date.now()
  }
  catch (e) {
    if (!disposed)
      error.value = (e as Error).message
  }
}
async function poll() {
  const current = ++generation
  await load()
  if (!disposed && current === generation)
    timer = setTimeout(poll, pending.value ? 2000 : 10000)
}
async function action(path: string, method = 'POST', body?: object) {
  busy.value = true
  error.value = ''
  try {
    await api(`/claude/${path}`, { method, ...(body ? { body: JSON.stringify(body) } : {}) })
    if (path === 'login/code') {
      code.value = ''
      submitted.value = true
    }
    else {
      submitted.value = false
    }
    await load()
    ++generation
    clearTimeout(timer)
    if (!disposed)
      timer = setTimeout(poll, pending.value ? 500 : 10000)
  }
  catch (e) { error.value = (e as Error).message }
  finally { busy.value = false }
}
onMounted(poll)
onBeforeUnmount(() => {
  disposed = true
  clearTimeout(timer)
  code.value = ''
})
</script>

<template>
  <section class="border-t border-line py-6.5 phone:py-5.5" aria-labelledby="claude-connection-heading">
    <div class="flex items-start justify-between gap-4 phone:flex-wrap">
      <div>
        <div class="flex items-center gap-2.5">
          <h2 id="claude-connection-heading" class="m-0!">
            Claude Code
          </h2>
          <span v-if="connection?.connected" class="inline-flex items-center gap-1 rounded-full bg-accent/10 px-2 py-1 text-[11px] text-accent"><Icon :name="CheckCircle2" :size="12" />Connected</span>
        </div>
        <p class="mb-0! text-sm text-muted">
          {{ connection?.email || 'Connect your Claude account to run Claude Code agents.' }}
        </p>
        <p v-if="connection?.subscriptionType" class="mb-0! text-xs text-muted">
          {{ connection.subscriptionType }} plan
        </p>
      </div>
      <div v-if="!pending" class="flex gap-2">
        <UiButton v-if="connection?.connected" :disabled="busy || connection.busy" @click="action('connection', 'DELETE')">
          Disconnect
        </UiButton>
        <UiButton :variant="connection?.connected ? 'default' : 'primary'" :disabled="busy || connection?.busy" @click="action('login')">
          <Icon :name="connection?.connected ? RefreshCw : LogIn" :size="16" />{{ connection?.connected ? 'Reconnect' : 'Connect Claude Code' }}
        </UiButton>
      </div>
    </div>
    <div v-if="connection?.connected" class="mt-5 max-w-2xl" aria-label="Claude Code usage">
      <div v-if="connection.usage?.windows.length" class="grid grid-cols-2 gap-5 phone:grid-cols-1">
        <div v-for="window in connection.usage.windows" :key="window.id">
          <div class="mb-2 flex justify-between gap-2 text-xs">
            <span>{{ window.label }}</span><span class="font-semibold">{{ Math.round(remaining(window)) }}% left</span>
          </div>
          <div role="progressbar" :aria-label="`Claude Code ${window.label} remaining`" :aria-valuenow="remaining(window)" :aria-valuemin="0" :aria-valuemax="100" class="h-2 overflow-hidden rounded-full bg-hover">
            <div class="h-full rounded-full transition-[width] motion-reduce:transition-none" :class="connection.usage.stale ? 'bg-muted' : remaining(window) < 5 ? 'bg-warning' : 'bg-accent'" :style="{ width: `${remaining(window)}%` }" />
          </div>
          <p class="mt-1.5 text-2xs text-muted">
            {{ !window.resetsAt ? 'Reset time unavailable' : window.resetsAt * 1000 <= now ? 'Reset due · checking usage' : `Resets ${resetDate(window)}` }}
          </p>
        </div>
      </div>
      <p v-else class="text-xs text-muted" role="status">
        Usage limits unavailable{{ connection.busy || pending ? ' · checked after the current session finishes' : '' }}.
      </p>
      <p v-if="connection.usage?.checkedAt" class="mt-3 text-2xs text-muted">
        {{ connection.usage.stale ? 'Last known usage' : 'Usage checked' }} · {{ new Date(connection.usage.checkedAt).toLocaleString() }}
      </p>
      <p v-if="connection.usage?.error" class="text-xs text-muted" role="status">
        {{ connection.usage.error }}
      </p>
    </div>
    <p v-if="connection?.busy" class="text-xs text-muted" role="status">
      Claude Code is running. Reconnect or disconnect when the run finishes.
    </p>
    <div v-if="pending" class="mt-5 max-w-xl rounded-xl border border-accent/25 bg-accent/5 p-5 phone:p-4">
      <div class="flex items-center gap-2 text-sm font-semibold" role="status">
        <Icon :name="LoaderCircle" :size="16" class="animate-spin" />Waiting for you to sign in
      </div>
      <p class="text-sm text-muted">
        Sign in on Anthropic’s page using your Claude subscription. Your password stays with Anthropic.
      </p>
      <a v-if="connection?.login?.url" :href="connection.login.url" target="_blank" rel="noopener noreferrer" class="inline-flex items-center gap-2 rounded-lg bg-accent px-4 py-2.5 text-sm font-semibold text-white">Open Claude sign-in<Icon :name="ArrowUpRight" :size="16" /></a>
      <p v-else class="text-xs text-muted">
        Preparing your secure sign-in link…
      </p>
      <form class="mt-4" @submit.prevent="action('login/code', 'POST', { id: connection?.login?.id, code })">
        <label class="text-xs">Authorization code<input v-model="code" type="password" autocomplete="off" placeholder="Paste the code from Anthropic" :disabled="busy" aria-label="Claude authorization code"></label>
        <p class="text-xs text-muted">
          If Anthropic shows a code, paste it here to finish. This link expires after 15 minutes.
        </p>
        <p v-if="submitted" role="status" class="text-xs text-muted">
          Checking your code…
        </p>
        <div class="flex gap-2">
          <UiButton type="submit" variant="primary" :disabled="busy || !code.trim()">
            Finish sign-in
          </UiButton><UiButton :disabled="busy" @click="action('login', 'DELETE')">
            Cancel
          </UiButton>
        </div>
      </form>
    </div>
    <UiAlert v-if="error || connection?.error || connection?.login?.error" class="mt-4">
      {{ error || connection?.login?.error || connection?.error }}
    </UiAlert>
    <p class="mt-4! mb-0! max-w-2xl text-xs text-muted">
      Claude Code uses the official CLI and your account’s usage limits. Select Claude Code in an agent’s settings after connecting.
    </p>
  </section>
</template>
