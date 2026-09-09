<script setup lang="ts">
import {
  ArrowUpRight,
  CheckCircle2,
  GitBranch,
  Link,
  RefreshCw,
  Terminal,
} from '@lucide/vue'
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { api, notify } from '../api'

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
  <div class="page-heading">
    <div>
      <span class="eyebrow">THE TOOLS YOUR AGENTS KNOW</span>
      <h1>Connections</h1>
      <p>Bring your accounts. Keep your existing tools and permissions.</p>
    </div>
    <button class="button" @click="load">
      <RefreshCw :size="16" />Check connections
    </button>
  </div>
  <p v-if="error" class="error" role="alert">
    {{ error }}
  </p>
  <div class="connection-grid">
    <article v-for="item in items" :key="item.provider" class="connection-card">
      <div class="connection-brand">
        <span><Terminal v-if="item.provider === 'codex'" :size="26" /><GitBranch
          v-else
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
      <div class="connection-state" :class="[{ connected: item.connected }]">
        <CheckCircle2 v-if="item.connected" :size="18" /><span
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
        <code>{{ item.version || "Install the CLI on your worker" }}</code><button
          class="button small"
          :disabled="busy || !item.installed || flow?.state === 'pending'"
          @click="connect(item.provider)"
        >
          {{ item.connected ? "Reconnect" : "Connect account"
          }}<ArrowUpRight :size="15" />
        </button>
      </footer>
    </article>
  </div>
  <section v-if="flow" class="panel device-flow">
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
    <div v-if="flow.code" class="device-code">
      {{ flow.code }}
    </div>
    <a
      v-if="flow.url && flow.state === 'pending'"
      class="button primary"
      :href="flow.url"
      target="_blank"
      rel="noopener noreferrer"
    >Open verification page<ArrowUpRight :size="16" /></a>
    <p v-if="!flow.code && flow.state === 'pending'" class="muted">
      Waiting for the CLI to generate a verification code…
    </p>
    <p v-if="flow.error" class="error">
      {{ flow.error }}
    </p>
    <button v-if="flow.state === 'pending'" class="button" @click="cancel">
      Cancel sign-in
    </button>
  </section>
  <section class="explanation-panel">
    <Link :size="24" />
    <div>
      <h2>Connected once. Available to your agents.</h2>
      <p>
        Accounts are stored in the worker’s persistent home directory. Agents
        use the official CLIs, and you can revoke access with the provider at
        any time. Moving to another server requires connecting that worker
        separately.
      </p>
    </div>
  </section>
</template>
