<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, notify } from '../api'
import Modal from './Modal.vue'
import UiAlert from './UiAlert.vue'
import UiButton from './UiButton.vue'

interface Account { id: string, name: string, enabled: boolean, agentIds: string[] }
const accounts = ref<Account[]>([])
const agents = ref<{ id: string, name: string }[]>([])
const loaded = ref(false)
const busy = ref(false)
const error = ref('')
const open = ref(false)
const removing = ref<Account>()
const editing = ref('')
const name = ref('')
const token = ref('')
const enabled = ref(true)
const agentIds = ref<string[]>([])
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
async function load() {
  [accounts.value, agents.value] = await Promise.all([api('/onepassword'), api('/agents')])
  loaded.value = true
}
function edit(account?: Account) {
  editing.value = account?.id ?? ''
  name.value = account?.name ?? ''
  token.value = ''
  enabled.value = account?.enabled ?? true
  agentIds.value = (account?.agentIds ?? []).filter(id => agents.value.some(agent => agent.id === id))
  error.value = ''
  open.value = true
}
function close() {
  if (busy.value)
    return
  token.value = ''
  open.value = false
}
async function save() {
  await action(async () => {
    await api(`/onepassword${editing.value ? `/${editing.value}` : ''}`, {
      method: editing.value ? 'PUT' : 'POST',
      body: JSON.stringify({ name: name.value, enabled: enabled.value, agentIds: agentIds.value, ...(token.value.trim() ? { token: token.value.trim() } : {}) }),
    })
    token.value = ''
    open.value = false
    notify('1Password account saved')
    await load()
  })
}
async function test(account: Account) {
  await action(async () => {
    await api(`/onepassword/${account.id}/test`, { method: 'POST' })
    notify(`1Password: ${account.name} is connected`)
  })
}
onMounted(() => action(load))
</script>

<template>
  <section aria-label="1Password accounts" class="border-t border-line py-6 space-y-4">
    <div class="flex items-center justify-between gap-3 flex-wrap">
      <h2>1Password</h2>
      <UiButton :disabled="busy || !loaded" @click="edit()">
        Add service account
      </UiButton>
    </div>
    <p class="text-muted">
      Store service account tokens and choose which agents can read their secrets. Access is off by default, including for Main agent.
    </p>
    <UiAlert v-if="error && !open && !removing">
      {{ error }}
    </UiAlert>
    <UiButton v-if="!loaded" :disabled="busy" @click="action(load)">
      {{ busy ? 'Loading…' : 'Retry loading 1Password' }}
    </UiButton>
    <p v-else-if="!accounts.length" class="text-muted">
      No 1Password service accounts yet.
    </p>
    <article v-for="account in accounts" :key="account.id" class="rounded-lg border border-line p-4 space-y-3">
      <h3>{{ account.name }}</h3>
      <p class="text-muted">
        {{ account.enabled ? 'Enabled' : 'Disabled' }} · {{ account.agentIds.filter(id => agents.some(agent => agent.id === id)).length }} authorized agents
      </p>
      <div class="flex gap-2 flex-wrap">
        <UiButton :disabled="busy" :aria-label="`Edit ${account.name}`" @click="edit(account)">
          Edit access / token
        </UiButton>
        <UiButton :disabled="busy" :aria-label="`Test ${account.name}`" @click="test(account)">
          Test connection
        </UiButton>
        <UiButton :disabled="busy" :aria-label="`Delete ${account.name}`" @click="removing = account; error = ''">
          Delete
        </UiButton>
      </div>
    </article>
    <Modal v-if="open" :title="editing ? 'Edit 1Password account' : 'Add 1Password account'" @close="close">
      <form class="p-6 space-y-4" @submit.prevent="save">
        <UiAlert v-if="error">
          {{ error }}
        </UiAlert>
        <fieldset :disabled="busy" class="space-y-4">
          <label>Name<input v-model="name" required maxlength="100" autocomplete="off"></label>
          <label>Service account token<input v-model="token" type="password" :required="!editing" maxlength="16384" autocomplete="new-password" spellcheck="false" :placeholder="editing ? 'Leave blank to keep saved token' : 'ops_…'"></label>
          <p class="text-muted text-sm">
            Tokens are encrypted on the server and never shown again. Only read access is exposed to agents; choose the vault permissions in 1Password.
          </p>
          <label class="checkbox flex flex-row items-center gap-2"><input v-model="enabled" type="checkbox">Enable this account</label>
          <h3>Authorized agents</h3>
          <p class="text-muted text-sm">
            Uncheck an agent to revoke future reads immediately. Secrets already retrieved cannot be recalled.
          </p>
          <label v-for="agent in agents" :key="agent.id" class="checkbox flex flex-row items-center gap-2"><input v-model="agentIds" type="checkbox" :value="agent.id">{{ agent.name }}</label>
        </fieldset>
        <div class="flex justify-end gap-2">
          <UiButton :disabled="busy" @click="close">
            Cancel
          </UiButton>
          <UiButton type="submit" variant="primary" :disabled="busy || !name.trim() || (!editing && !token.trim())">
            Save account
          </UiButton>
        </div>
      </form>
    </Modal>
    <Modal v-if="removing" title="Delete 1Password account" @close="!busy && (removing = undefined)">
      <div class="p-6 space-y-4">
        <p>Delete {{ removing.name }} and its stored token? All agents will lose access.</p>
        <UiAlert v-if="error">
          {{ error }}
        </UiAlert>
        <UiButton :disabled="busy" @click="action(async () => { await api(`/onepassword/${removing!.id}`, { method: 'DELETE' }); removing = undefined; await load() })">
          Delete account
        </UiButton>
      </div>
    </Modal>
  </section>
</template>
