<script setup lang="ts">
import type { CodexAccountView, CodexLoginFlow, UsageWindow } from '../../shared/codex-accounts'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { usageBlocked } from '../../shared/codex-accounts'
import { api, date, notify } from '../api'
import { ArrowUpRight, BrandOpenAI, Plus, RefreshCw } from '../icons'
import CodexSignIn from './CodexSignIn.vue'
import Icon from './Icon.vue'
import Modal from './Modal.vue'
import UiAlert from './UiAlert.vue'
import UiButton from './UiButton.vue'

const accounts = ref<CodexAccountView[]>([])
const flow = ref<CodexLoginFlow | null>(null)
const busy = ref(false)
const loading = ref(true)
const error = ref('')
const pollError = ref('')
const editing = ref<CodexAccountView>()
const removing = ref<CodexAccountView>()
const open = ref(false)
const name = ref('')
const maxConcurrentRuns = ref(4)
const now = ref(Date.now())
let timer: ReturnType<typeof setInterval>
let polling = false
const stale = (account: CodexAccountView) => !account.checkedAt || now.value - account.checkedAt > 90000
const activeRuns = (account: CodexAccountView) => account.activeRunIds ?? (account.activeRunId ? [account.activeRunId] : [])
const eligible = (account: CodexAccountView) => account.enabled && account.state === 'ready' && !stale(account) && !account.exhausted && activeRuns(account).length < (account.maxConcurrentRuns ?? 4) && account.limits && !usageBlocked(account.limits) && (account.remainingPercent ?? 0) > 0
const next = computed(() => accounts.value.filter(eligible).sort((a, b) => b.remainingPercent! - a.remainingPercent! || (a.lastUsedAt ?? 0) - (b.lastUsedAt ?? 0) || a.id.localeCompare(b.id))[0]?.id)
function status(account: CodexAccountView) {
  if (!account.enabled)
    return 'Paused'
  if (account.state === 'pending')
    return 'Finish sign-in'
  if (account.state === 'error')
    return 'Reconnect'
  if (stale(account))
    return 'Usage unavailable'
  if (account.exhausted || (account.limits && usageBlocked(account.limits)) || account.remainingPercent === 0)
    return 'Waiting for reset'
  if (account.remainingPercent === null)
    return 'Usage unavailable'
  if (activeRuns(account).length >= (account.maxConcurrentRuns ?? 4))
    return 'At capacity'
  if (account.id === next.value)
    return 'Next run'
  return account.remainingPercent < 5 ? 'Low usage' : 'Ready'
}
function windows(account: CodexAccountView) {
  return [account.limits?.rateLimits.primary, account.limits?.rateLimits.secondary].filter((window): window is UsageWindow => !!window)
}
function windowLabel(window: UsageWindow) {
  const minutes = window.windowDurationMins
  if (!minutes)
    return 'Usage window'
  if (minutes === 10080)
    return 'Weekly'
  if (minutes % 1440 === 0)
    return `${minutes / 1440}-day window`
  return minutes % 60 === 0 ? `${minutes / 60}-hour window` : `${minutes}-minute window`
}
const remaining = (window: UsageWindow) => Math.max(0, Math.min(100, 100 - window.usedPercent))
function resetLabel(window: UsageWindow) {
  if (!window.resetsAt)
    return 'Reset time unavailable'
  const minutes = Math.ceil((window.resetsAt * 1000 - now.value) / 60000)
  if (minutes <= 0)
    return 'Reset due · checking usage'
  if (minutes < 60)
    return `Resets in ${minutes}m`
  if (minutes < 1440)
    return `Resets in ${Math.floor(minutes / 60)}h ${minutes % 60}m`
  return `Resets in ${Math.floor(minutes / 1440)}d ${Math.floor(minutes % 1440 / 60)}h`
}
async function load(refresh = false) {
  accounts.value = await api(`/codex/accounts${refresh ? '/refresh' : ''}`, refresh ? { method: 'POST' } : {})
  now.value = Date.now()
}
async function action(operation: () => Promise<void>) {
  busy.value = true
  error.value = ''
  try {
    await operation()
  }
  catch (e) {
    error.value = (e as Error).message
  }
  finally {
    busy.value = false
  }
}
function edit(account?: CodexAccountView) {
  editing.value = account
  name.value = account?.name ?? ''
  maxConcurrentRuns.value = account?.maxConcurrentRuns ?? 4
  open.value = true
}
async function save() {
  await action(async () => {
    if (editing.value) {
      await api(`/codex/accounts/${editing.value.id}`, { method: 'PUT', body: JSON.stringify({ name: name.value, enabled: editing.value.enabled, maxConcurrentRuns: maxConcurrentRuns.value }) })
    }
    else {
      flow.value = await api('/codex/accounts/login', { method: 'POST', body: JSON.stringify({ name: name.value }) })
    }
    open.value = false
    await load()
  })
}
async function reconnect(account: CodexAccountView) {
  await action(async () => {
    flow.value = await api('/codex/accounts/login', { method: 'POST', body: JSON.stringify({ name: account.name, id: account.id }) })
  })
}
async function retry() {
  const account = accounts.value.find(account => account.id === flow.value?.accountId)
  if (account)
    await reconnect(account)
}
async function toggle(account: CodexAccountView) {
  await action(async () => {
    await api(`/codex/accounts/${account.id}`, { method: 'PUT', body: JSON.stringify({ name: account.name, enabled: !account.enabled }) })
    await load()
  })
}
async function cancel() {
  await action(async () => {
    await api('/codex/accounts/login', { method: 'DELETE' })
    flow.value = null
    await load()
  })
}
async function remove() {
  if (!removing.value)
    return
  const id = removing.value.id
  await action(async () => {
    await api(`/codex/accounts/${id}`, { method: 'DELETE' })
    removing.value = undefined
    await load()
  })
}
async function poll() {
  if (polling || busy.value)
    return
  polling = true
  try {
    const previous = flow.value?.state
    const [items, login] = await Promise.all([api<CodexAccountView[]>('/codex/accounts'), api<typeof flow.value>('/codex/accounts/login')])
    accounts.value = items
    flow.value = login
    now.value = Date.now()
    pollError.value = ''
    if (login?.state === 'complete' && previous === 'pending')
      notify('Codex account connected')
  }
  catch (e) {
    pollError.value = (e as Error).message
  }
  finally {
    polling = false
    loading.value = false
  }
}
onMounted(() => {
  void poll()
  window.addEventListener('focus', poll)
  timer = setInterval(() => {
    now.value = Date.now()
    void poll()
  }, 2000)
})
onBeforeUnmount(() => {
  clearInterval(timer)
  window.removeEventListener('focus', poll)
})
</script>

<template>
  <section aria-labelledby="codex-accounts-title" class="mb-7">
    <header class="mb-5 flex flex-wrap items-center justify-between gap-4">
      <div class="flex items-center gap-3">
        <span class="grid size-11 place-items-center rounded-xl bg-accent-soft text-accent"><Icon :name="BrandOpenAI" :size="25" /></span>
        <div>
          <h2 id="codex-accounts-title">
            Codex accounts <span class="ml-1.5 text-muted text-sm">{{ accounts.length }}</span>
          </h2><p class="text-xs text-muted">
            Usage checked automatically
          </p>
        </div>
      </div>
      <div class="flex flex-wrap gap-2">
        <UiButton :disabled="busy" size="small" aria-label="Refresh Codex usage" @click="action(() => load(true))">
          <Icon :name="RefreshCw" :size="15" />Refresh
        </UiButton>
        <UiButton variant="primary" size="small" :disabled="busy || flow?.state === 'pending' || accounts.length >= 10" @click="edit()">
          <Icon :name="Plus" :size="15" />Add account
        </UiButton>
      </div>
    </header>
    <UiAlert v-if="error || pollError">
      {{ error || pollError }}
    </UiAlert>
    <CodexSignIn v-if="flow?.state === 'pending' || flow?.state === 'failed'" :flow="flow" :busy="busy" @cancel="cancel" @retry="retry" />
    <p v-if="loading" class="text-muted py-6" role="status">
      Loading accounts…
    </p>
    <div v-else-if="!accounts.length" class="rounded-card border border-dashed border-line p-7 text-center">
      <h3>More accounts. More room to build.</h3><p class="mt-2 text-muted text-sm">
        Connect your ChatGPT accounts to share their available capacity.
      </p>
    </div>
    <div class="grid grid-cols-2 gap-4 tablet:grid-cols-1">
      <article v-for="account in accounts" :key="account.id" class="codex-account-card min-w-0 rounded-card border bg-surface p-5 phone:p-4" :class="account.id === next ? 'border-accent' : 'border-line'" :aria-label="account.name">
        <header class="flex items-start justify-between gap-3">
          <div class="min-w-0">
            <h3 class="break-words">
              {{ account.name }}
            </h3><p class="mt-1 break-all text-xs text-muted">
              {{ account.email || 'Awaiting sign-in' }}<span v-if="account.plan"> · {{ account.plan }}</span>
            </p>
          </div>
          <span class="shrink-0 rounded-md bg-hover px-2 py-1 text-2xs font-semibold" :class="account.id === next || account.activeRunId ? 'text-accent' : 'text-muted'">{{ status(account) }}</span>
        </header>
        <div class="my-5 flex items-baseline gap-2" :class="stale(account) || !account.enabled ? 'text-muted' : 'text-ink'">
          <strong class="text-4xl tracking-tight">{{ account.remainingPercent === null ? '—' : `${Math.round(account.remainingPercent)}%` }}</strong><span class="text-xs text-muted">{{ stale(account) ? 'last known remaining' : 'remaining' }}</span>
        </div>
        <div class="grid gap-4">
          <div v-for="(window, index) in windows(account)" :key="index">
            <div class="mb-2 flex justify-between gap-2 text-xs">
              <span>{{ windowLabel(window) }}</span><span class="font-semibold">{{ Math.round(remaining(window)) }}% left</span>
            </div>
            <div role="progressbar" :aria-label="`${account.name} ${windowLabel(window)} remaining`" :aria-valuenow="remaining(window)" :aria-valuemin="0" :aria-valuemax="100" class="h-2 overflow-hidden rounded-full bg-hover">
              <div class="h-full rounded-full transition-[width] motion-reduce:transition-none" :class="stale(account) || !account.enabled ? 'bg-muted' : remaining(window) < 5 ? 'bg-warning' : 'bg-accent'" :style="{ width: `${remaining(window)}%` }" />
            </div>
            <p class="mt-1.5 text-2xs text-muted" :title="window.resetsAt ? date(window.resetsAt * 1000) : undefined">
              {{ resetLabel(window) }}
            </p>
          </div>
        </div>
        <p v-if="account.error" class="mt-4 text-xs text-danger" role="status">
          {{ account.error }}
        </p>
        <p v-if="account.limits?.rateLimitResetCredits" class="mt-4 text-xs text-muted">
          Banked resets: {{ account.limits.rateLimitResetCredits.availableCount }} · {{ account.enabled ? 'Automatic at 2% remaining' : 'Automatic use paused' }}
        </p>
        <p v-if="account.resetError" class="mt-2 text-xs text-warning" role="status">
          {{ account.resetError }}
        </p>
        <p class="mt-3 text-xs text-muted">
          {{ activeRuns(account).length }} / {{ account.maxConcurrentRuns ?? 4 }} parallel runs
        </p>
        <p v-if="account.checkedAt" class="mt-4 text-2xs text-muted" :title="date(account.checkedAt)">
          Updated {{ Math.max(0, Math.floor((now - account.checkedAt) / 1000)) }}s ago
        </p>
        <RouterLink v-for="(runId, index) in activeRuns(account)" :key="runId" :to="`/runs/${runId}`" class="mt-3 inline-flex items-center gap-1 text-xs text-accent">
          View run {{ index + 1 }}<Icon :name="ArrowUpRight" :size="13" />
        </RouterLink>
        <footer class="mt-4 flex flex-wrap items-center gap-2 border-t border-line pt-4">
          <UiButton size="small" :disabled="busy || flow?.state === 'pending'" :aria-label="`${account.enabled ? 'Pause' : 'Enable'} ${account.name}`" @click="toggle(account)">
            {{ account.enabled ? 'Pause' : 'Enable' }}
          </UiButton>
          <UiButton size="small" :disabled="busy || flow?.state === 'pending'" :aria-label="`Edit ${account.name}`" @click="edit(account)">
            Edit
          </UiButton>
          <UiButton size="small" :disabled="busy || !!account.activeRunId || flow?.state === 'pending'" @click="reconnect(account)">
            Reconnect
          </UiButton>
          <UiButton size="small" variant="danger-outline" :disabled="busy || !!account.activeRunId || flow?.state === 'pending'" :aria-label="`Remove ${account.name}`" @click="removing = account">
            Remove
          </UiButton>
        </footer>
      </article>
    </div>
    <p class="mt-4 text-xs text-muted">
      New runs use the available account with the most capacity. Enabled accounts use banked resets automatically at 2% remaining, even while idle. Exhausted runs resume when capacity returns or another account is available. Accounts can run tasks in parallel, including on the account with the most remaining usage.
    </p>
  </section>
  <Modal v-if="open" :title="editing ? 'Edit account' : 'Add Codex account'" @close="open = false">
    <form @submit.prevent="save">
      <div class="grid gap-4 p-6">
        <UiAlert v-if="error">
          {{ error }}
        </UiAlert><label v-if="editing">Parallel runs<input v-model.number="maxConcurrentRuns" type="number" min="1" max="4" required><span class="text-xs text-muted">The server’s overall limit also applies. Lowering this lets current runs finish.</span></label><label>Account name<input v-model="name" required maxlength="100" placeholder="e.g. Personal" autocomplete="off"></label><p v-if="!editing" class="text-sm text-muted">
          Sign in with ChatGPT using a one-time device code.
        </p>
      </div><footer class="flex justify-end gap-3 border-t border-line p-4">
        <UiButton @click="open = false">
          Cancel
        </UiButton><UiButton type="submit" variant="primary" :disabled="busy">
          {{ editing ? 'Save account' : 'Continue to sign in' }}
        </UiButton>
      </footer>
    </form>
  </Modal>
  <Modal v-if="removing" title="Remove Codex account?" @close="removing = undefined">
    <div class="p-6">
      <UiAlert v-if="error">
        {{ error }}
      </UiAlert><p>Remove {{ removing.name }} and its saved credentials from this worker?</p>
    </div><footer class="flex justify-end gap-3 border-t border-line p-4">
      <UiButton @click="removing = undefined">
        Cancel
      </UiButton><UiButton variant="danger" :disabled="busy" @click="remove">
        Remove account
      </UiButton>
    </footer>
  </Modal>
</template>
