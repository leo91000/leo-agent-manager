<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { api, notify } from '../api'
import CodexAccounts from '../components/CodexAccounts.vue'
import Icon from '../components/Icon.vue'
import UiAlert from '../components/UiAlert.vue'
import UiButton from '../components/UiButton.vue'
import { ArrowUpRight, CheckCircle2, Github, RefreshCw } from '../icons'
import { buttonBase, buttonSizes, buttonVariants } from '../ui'

const items = ref<any[]>([])
const flow = ref<any>()
const error = ref('')
const busy = ref(false)
async function load() {
  try {
    items.value = await api('/connections?refresh=true')
  }
  catch (e) {
    error.value = (e as Error).message
  }
}
async function connect(provider: string) {
  busy.value = true
  error.value = ''
  try {
    flow.value = await api('/connections/login', {
      method: 'POST',
      body: JSON.stringify({ provider }),
    })
  }
  catch (e) {
    error.value = (e as Error).message
  }
  finally {
    busy.value = false
  }
}
async function cancel() {
  await api('/connections/login', { method: 'DELETE' })
  flow.value = null
}
let timer: ReturnType<typeof setInterval>
onMounted(() => {
  load()
  timer = setInterval(async () => {
    if (flow.value?.state === 'pending') {
      try {
        flow.value = await api('/connections/login')
        if (flow.value?.state === 'complete') {
          notify('Account connected')
          await load()
        }
      }
      catch (e) {
        error.value = (e as Error).message
      }
    }
  }, 2000)
})
onBeforeUnmount(() => clearInterval(timer))
</script>

<template>
  <div class="page-heading flex items-center justify-between gap-5 mb-[27px] phone:gap-2.5 phone:flex-wrap phone:mb-[21px]">
    <div>
      <h1>Connections</h1>
    </div>
    <UiButton @click="load">
      <Icon :name="RefreshCw" :size="16" />Check GitHub
    </UiButton>
  </div>
  <UiAlert v-if="error">
    {{ error }}
  </UiAlert>
  <CodexAccounts />
  <div class="connection-grid grid grid-cols-2 gap-5.5 tablet:grid-cols-1">
    <article v-for="item in items.filter(item => item.provider === 'github')" :key="item.provider" class="connection-card border-t border-line py-6.5 phone:py-5.5">
      <div class="connection-brand flex items-center gap-[15px]">
        <span><Icon
          :name="Github"
          :size="26"
        /></span>
        <div>
          <h2>{{ item.provider === "codex" ? "Codex" : "GitHub" }}</h2>
          <p>
            {{
              item.provider === "codex"
                ? "Your coding agent, with your subscription."
                : "Repositories, pull requests, checks, and releases."
            }}
          </p>
        </div>
      </div>
      <div class="connection-state flex items-center gap-3 bg-surface border border-line text-warning rounded-lg p-[15px] mx-0 my-[25px]" :class="[{ connected: item.connected }]">
        <Icon v-if="item.connected" :name="CheckCircle2" :size="18" /><span
          v-else
          class="status-dot"
        />
        <div>
          <strong>{{
            item.connected
              ? "Connected"
              : item.installed
                ? "Not signed in"
                : "CLI not installed"
          }}</strong><small>{{ item.account }}</small>
        </div>
      </div>
      <footer>
        <code>{{ item.version || "Install the CLI on your worker" }}</code><UiButton
          size="small"
          :disabled="busy || !item.installed || flow?.state === 'pending'"
          @click="connect(item.provider)"
        >
          {{ item.connected ? "Reconnect" : "Connect account"
          }}<Icon :name="ArrowUpRight" :size="15" />
        </UiButton>
      </footer>
    </article>
  </div>
  <section v-if="flow" class="panel bg-surface border border-line rounded-card overflow-hidden device-flow mt-[25px] p-[27px]">
    <h2>
      {{
        flow.state === "complete"
          ? "You’re connected"
          : flow.state === "failed"
            ? "Sign-in needs another try"
            : "Finish signing in"
      }}
    </h2>
    <p v-if="flow.state === 'pending'">
      Open the verification page and enter this code. Your password stays with
      the provider.
    </p>
    <div v-if="flow.code" class="device-code font-mono [font-size:27px] tracking-[5px] text-muted mx-0 my-5.5">
      {{ flow.code }}
    </div>
    <a
      v-if="flow.url && flow.state === 'pending'"
      :class="[buttonBase, buttonVariants.primary, buttonSizes.default]"
      :href="flow.url"
      target="_blank"
      rel="noopener noreferrer"
    >Open verification page<Icon :name="ArrowUpRight" :size="16" /></a>
    <p v-if="!flow.code && flow.state === 'pending'" class="muted text-muted">
      Waiting for the CLI to generate a verification code…
    </p>
    <UiAlert v-if="flow.error">
      {{ flow.error }}
    </UiAlert>
    <UiButton v-if="flow.state === 'pending'" @click="cancel">
      Cancel sign-in
    </UiButton>
  </section>
</template>
