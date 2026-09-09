<script setup lang="ts">
import { Copy, ExternalLink, KeyRound, Plus } from '@lucide/vue'
import { onMounted, ref } from 'vue'
import { api, date, notify } from '../api'
import Modal from '../components/Modal.vue'

const settings = ref<any>()
const grants = ref<any[]>([])
const audit = ref<any[]>([])
const open = ref(false)
const label = ref('')
const scopes = ref(['read'])
const created = ref('')
const error = ref('')
async function load() {
  try {
    [settings.value, grants.value, audit.value] = await Promise.all([
      api('/settings'),
      api('/tokens'),
      api('/audit'),
    ])
  }
  catch (e) {
    error.value = (e as Error).message
  }
}
onMounted(load)
async function create() {
  try {
    created.value = (
      await api('/tokens', {
        method: 'POST',
        body: JSON.stringify({ label: label.value, scopes: scopes.value }),
      })
    ).token
    await load()
  }
  catch (e) {
    error.value = (e as Error).message
  }
}
async function revoke(id: string) {
  try {
    await api(`/tokens/${id}`, { method: 'DELETE' })
    await load()
    notify('Access revoked')
  }
  catch (e) {
    error.value = (e as Error).message
  }
}
async function copy(value: string) {
  try {
    await navigator.clipboard.writeText(value)
    notify('Copied to clipboard')
  }
  catch {
    error.value = 'Clipboard unavailable. Select and copy the value manually.'
  }
}
</script>

<template>
  <div class="page-heading">
    <div>
      <span class="eyebrow">YOUR WORKSPACE, YOUR RULES</span>
      <h1>Settings</h1>
      <p>
        Connections for other assistants, access controls, and workspace
        details.
      </p>
    </div>
  </div>
  <p v-if="error" class="error" role="alert">
    {{ error }}
  </p>
  <template v-if="settings">
    <section class="panel settings-section">
      <div class="section-intro">
        <span class="resource-avatar"><ExternalLink :size="22" /></span>
        <div>
          <h2>Connect from ChatGPT or Claude</h2>
          <p>
            Add Leo as a remote MCP server in your assistant’s connector
            settings.
          </p>
        </div>
      </div>
      <label>MCP server URL
        <div class="copy-field">
          <input
            :value="settings.mcpUrl"
            readonly
            aria-label="MCP server URL"
          ><button
            class="icon-button"
            aria-label="Copy MCP URL"
            @click="copy(settings.mcpUrl)"
          >
            <Copy :size="17" />
          </button></div></label>
      <div class="inline-note">
        Choose OAuth authentication. You’ll sign in here and approve exactly
        what the assistant can access. This endpoint uses stateless MCP
        {{ settings.protocol }}; the client must support that protocol.
      </div>
    </section>
    <section class="panel settings-section">
      <header class="panel-heading">
        <div>
          <h2>Connected clients & access tokens</h2>
          <p>
            Review and revoke access without disconnecting your agent accounts.
          </p>
        </div>
        <button
          class="button small"
          @click="
            open = true;
            created = '';
            label = '';
            scopes = ['read'];
          "
        >
          <Plus :size="15" />New token
        </button>
      </header>
      <div v-if="grants.length" class="grants-list">
        <div v-for="grant in grants" :key="grant.id">
          <KeyRound :size="19" />
          <div class="grow">
            <strong>{{ grant.label }}</strong><small>{{ grant.scopes.join(" · ") }} ·
              {{
                grant.clientId === "personal"
                  ? "Personal token"
                  : "OAuth client"
              }}</small>
          </div>
          <button class="button small danger-outline" @click="revoke(grant.id)">
            Revoke
          </button>
        </div>
      </div>
      <p v-else class="muted">
        No external clients have access yet.
      </p>
    </section>
    <section class="panel settings-section">
      <h2>Worker environment</h2>
      <dl class="settings-facts">
        <dt>Version</dt>
        <dd>{{ settings.version }}</dd>
        <dt>Concurrent runs</dt>
        <dd>{{ settings.concurrency }}</dd>
        <dt>Workspace roots</dt>
        <dd>
          <code v-for="root in settings.workspaceRoots" :key="root">{{
            root
          }}</code>
        </dd>
        <dt>Global skills</dt>
        <dd>
          <code>{{ settings.home }}/.agents/skills</code>
        </dd>
      </dl>
      <p class="muted">
        Infrastructure settings are configured through your deployment
        environment.
      </p>
    </section>
    <section class="panel settings-section">
      <h2>Recent changes</h2>
      <div class="audit-list">
        <div v-for="item in audit.slice(0, 20)" :key="item.id">
          <span>{{ item.action.replaceAll(".", " · ") }}</span><time>{{ date(item.created_at) }}</time>
        </div>
        <p v-if="!audit.length" class="muted">
          Workspace changes will be recorded here.
        </p>
      </div>
    </section>
  </template><Modal
    v-if="open"
    :title="created ? 'Save your access token' : 'Create an access token'"
    @close="
      open = false;
      created = '';
    "
  >
    <div class="modal-body">
      <template v-if="created">
        <p>
          This token is shown once. Store it securely and use it as a Bearer
          token in your MCP client.
        </p>
        <div class="token-display">
          {{ created }}
        </div>
        <button class="button" @click="copy(created)">
          <Copy :size="16" />Copy token
        </button>
      </template>
      <form v-else @submit.prevent="create">
        <label>Name<input
          v-model="label"
          required
          placeholder="e.g. My terminal assistant"
          autofocus
        ></label>
        <fieldset>
          <legend>Permissions</legend>
          <label
            v-for="scope in [
              {
                id: 'read',
                label: 'Read tasks, agents, skills, and run results',
              },
              { id: 'run', label: 'Start and cancel configured tasks' },
              {
                id: 'manage',
                label: 'Create and change tasks, profiles, and skills',
              },
            ]"
            :key="scope.id"
            class="checkbox"
          ><input v-model="scopes" type="checkbox" :value="scope.id">{{
            scope.label
          }}</label>
        </fieldset>
        <p v-if="error" class="error">
          {{ error }}
        </p>
        <button class="button primary" :disabled="!scopes.length">
          Create token
        </button>
      </form>
    </div>
  </Modal>
</template>
