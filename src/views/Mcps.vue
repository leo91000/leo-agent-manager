<script setup lang="ts">
import type { McpInput, McpView } from '../../shared/mcp'
import { ArrowUpRight, Check, Copy, KeyRound, LoaderCircle, MoreHorizontal, Plug, Plus, Search, Server, Terminal, Trash2, Wrench, X } from '@lucide/vue'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, date, notify, state } from '../api'
import Empty from '../components/Empty.vue'
import Modal from '../components/Modal.vue'
import VirtualSelect from '../components/VirtualSelect.vue'
import '../mcp.css'

const callbackUrl = `${window.location.origin}/oauth/mcp/callback`
const route = useRoute()
const router = useRouter()
const query = ref('')
const error = ref('')
const formError = ref('')
const loading = ref(false)
const busy = ref<Record<string, string>>({})
const open = ref(false)
const editing = ref<McpView>()
const removing = ref<McpView>()
const disconnecting = ref<McpView>()
const inspecting = ref<McpView>()
const toolQuery = ref('')
const selectedTools = ref<string[] | null>(null)
const form = ref<McpInput>({} as McpInput)
const argsText = ref('')
const envRows = ref<{
  key: string
  value: string
  saved: boolean
}[]>([])
let disposed = false
const filtered = computed(() => state.mcps.filter(item => `${item.name} ${item.url} ${item.command}`.toLowerCase().includes(query.value.toLowerCase())))
const tools = computed(() => inspecting.value?.tools.filter(tool => `${tool.name} ${tool.description || ''}`.toLowerCase().includes(toolQuery.value.toLowerCase())) || [])
const labels = { 'untested': 'Not tested', 'connected': 'Connected', 'needs-auth': 'Sign in required', 'error': 'Connection error' }
async function load() {
  loading.value = true
  try {
    const items = await api<McpView[]>('/mcps')
    if (!disposed)
      state.mcps = items
  }
  catch (e) {
    error.value = (e as Error).message
  }
  finally {
    loading.value = false
  }
}
onMounted(() => {
  load()
  if (route.query.oauth) {
    const messages: Record<string, string> = { connected: 'MCP connected', denied: 'Authorization was cancelled', failed: 'Authorization failed. Review the connection and try again.', expired: 'Authorization expired. Connect again from this page.' }
    const message = messages[route.query.oauth as string]
    if (message)
      notify(message)
    router.replace({ query: {} })
  }
})
onBeforeUnmount(() => {
  disposed = true
})
function edit(item?: McpView) {
  editing.value = item
  formError.value = ''
  form.value = item ? { name: item.name, transport: item.transport, url: item.url, command: item.command, args: item.args, auth: item.auth, clientId: item.clientId, scopes: item.scopes, allowPrivateNetwork: item.allowPrivateNetwork, enabled: item.enabled, enabledTools: item.enabledTools } : { name: '', transport: 'http', url: '', command: '', args: [], auth: 'none', clientId: '', scopes: '', allowPrivateNetwork: false, enabled: true, enabledTools: null }
  argsText.value = item?.args.join('\n') || ''
  envRows.value = item?.envKeys.map(key => ({ key, value: '', saved: true })) || []
  open.value = true
}
async function save() {
  busy.value.form = 'Saving'
  formError.value = ''
  try {
    const entries = envRows.value.filter(row => row.key.trim())
    if (new Set(entries.map(row => row.key.trim())).size !== entries.length)
      throw new Error('Environment variable names must be unique.')
    const payload = { ...form.value, auth: form.value.transport === 'stdio' ? 'none' : form.value.auth, args: argsText.value ? argsText.value.split('\n') : [], env: Object.fromEntries(entries.filter(row => !row.saved || row.value).map(row => [row.key.trim(), row.value])), removeEnv: editing.value?.envKeys.filter(key => !entries.some(row => row.key === key)) || [] }
    if (!payload.token)
      delete payload.token
    if (!payload.clientSecret)
      delete payload.clientSecret
    await api(`/mcps${editing.value ? `/${editing.value.id}` : ''}`, { method: editing.value ? 'PUT' : 'POST', body: JSON.stringify(payload) })
    open.value = false
    form.value.token = ''
    form.value.clientSecret = ''
    envRows.value = []
    await load()
    notify('MCP saved')
  }
  catch (e) {
    formError.value = (e as Error).message
  }
  finally {
    delete busy.value.form
  }
}
async function action(item: McpView, kind: 'test' | 'connect') {
  busy.value[item.id] = kind
  error.value = ''
  try {
    const result = await api(`/mcps/${item.id}/${kind}`, { method: 'POST' })
    if (kind === 'connect') {
      window.location.assign(result.url)
      return
    }
    await load()
    notify(result.state === 'connected' ? `${result.tools.length} tools available` : result.error)
  }
  catch (e) {
    error.value = (e as Error).message
  }
  finally {
    delete busy.value[item.id]
  }
}
async function remove(item: McpView, disconnect = false) {
  busy.value[item.id] = 'Removing'
  try {
    await api(`/mcps/${item.id}${disconnect ? '/disconnect' : ''}`, { method: disconnect ? 'POST' : 'DELETE' })
    removing.value = undefined
    disconnecting.value = undefined
    await load()
    notify(disconnect ? 'Credentials removed' : 'MCP removed')
  }
  catch (e) {
    error.value = (e as Error).message
  }
  finally {
    delete busy.value[item.id]
  }
}
function inspect(item: McpView) {
  inspecting.value = item
  toolQuery.value = ''
  selectedTools.value = item.enabledTools === null ? null : [...item.enabledTools]
}
async function saveTools() {
  const item = inspecting.value!
  busy.value.tools = 'Saving'
  try {
    await api(`/mcps/${item.id}`, { method: 'PUT', body: JSON.stringify({ ...item, enabledTools: selectedTools.value }) })
    inspecting.value = undefined
    await load()
    notify('Tool access updated')
  }
  catch (e) {
    error.value = (e as Error).message
  }
  finally {
    delete busy.value.tools
  }
}
async function copyCallback() {
  try {
    await navigator.clipboard.writeText(editing.value?.callbackUrl || `${window.location.origin}/oauth/mcp/callback`)
    notify('Callback URL copied')
  }
  catch {
    formError.value = 'Clipboard unavailable. Select the callback URL to copy it.'
  }
}
</script>

<template>
  <div class="page-heading">
    <h1>MCPs</h1><button class="button primary" @click="edit()">
      <Plus :size="17" />Add MCP
    </button>
  </div>
  <p v-if="error" class="error" role="alert">
    {{ error }}
  </p>
  <div v-if="state.mcps.length" class="toolbar">
    <label class="search-field"><Search :size="17" /><input v-model="query" aria-label="Search MCPs" placeholder="Search connections"></label><span class="muted">{{ state.mcps.filter(item => item.state === 'connected' && item.enabled).length }} connected</span>
  </div>
  <div v-if="filtered.length" class="mcp-grid">
    <article v-for="item in filtered" :key="item.id" class="mcp-card" :class="{ disabled: !item.enabled }">
      <header>
        <span class="mcp-mark"><Server v-if="item.transport === 'http'" :size="23" /><Terminal v-else :size="23" /></span><div><h2>{{ item.name }}</h2><span class="mcp-transport">{{ item.transport === 'http' ? 'Remote server' : 'Command server' }}</span></div><details class="task-action-menu" @click="($event.target as HTMLElement).closest('button') && (($event.currentTarget as HTMLDetailsElement).open = false)">
          <summary class="icon-button" :aria-label="`Actions for ${item.name}`">
            <MoreHorizontal :size="19" />
          </summary><div>
            <button @click="edit(item)">
              Edit connection
            </button><button @click="disconnecting = item">
              Remove credentials
            </button><button @click="removing = item">
              Delete connection
            </button>
          </div>
        </details>
      </header>
      <p class="mcp-endpoint" :title="item.transport === 'http' ? item.url : item.command">
        {{ item.transport === 'http' ? item.url : [item.command, ...item.args].join(' ') }}
      </p>
      <div class="mcp-state" :data-state="item.enabled ? item.state : 'disabled'">
        <span /><strong>{{ item.enabled ? labels[item.state] : 'Disabled' }}</strong><KeyRound v-if="item.auth !== 'none'" :size="13" /><small v-if="item.auth !== 'none'">{{ item.auth === 'oauth' ? 'OAuth' : 'Token' }}</small>
      </div>
      <p v-if="item.error" class="mcp-error">
        {{ item.error }}
      </p>
      <div class="mcp-tool-summary">
        <Wrench :size="16" /><strong>{{ item.tools.length }}</strong><span>tools discovered</span><button class="text-button" :aria-label="`Browse tools for ${item.name}`" @click="inspect(item)">
          Browse <ArrowUpRight :size="14" />
        </button>
      </div>
      <footer>
        <small>{{ item.checkedAt ? `Checked ${date(item.checkedAt)}` : 'Ready to configure' }}</small><button class="button small" :disabled="!!busy[item.id]" @click="action(item, 'test')">
          <LoaderCircle v-if="busy[item.id] === 'test'" :size="14" class="activity-spinning" />Test
        </button><button v-if="item.auth === 'oauth'" class="button small primary" :disabled="!!busy[item.id]" @click="action(item, 'connect')">
          <LoaderCircle v-if="busy[item.id] === 'connect'" :size="14" class="activity-spinning" /><Plug v-else :size="14" />{{ item.state === 'connected' ? 'Reconnect' : 'Connect' }}
        </button>
      </footer>
    </article>
  </div>
  <Empty v-else :title="loading ? 'Loading connections…' : query ? 'No matching connections' : 'Connect your tools'">
    <p v-if="!loading && !query">
      Add an MCP server, connect your account, and choose which agents can use it.
    </p><button v-if="!loading && !query" class="button primary" @click="edit()">
      <Plus :size="16" />Add your first MCP
    </button>
  </Empty>
  <Modal v-if="open" :title="editing ? 'Edit MCP' : 'Add MCP'" @close="open = false">
    <form @submit.prevent="save">
      <div class="modal-body mcp-form">
        <p v-if="formError" class="error" role="alert">
          {{ formError }}
        </p><label>Name<input v-model="form.name" required maxlength="100" placeholder="e.g. Design tools"></label><VirtualSelect v-model="form.transport" label="Connection type" :options="[{ value: 'http', label: 'Remote HTTP server', icon: Server }, { value: 'stdio', label: 'Command in agent container', icon: Terminal }]" />
        <template v-if="form.transport === 'http'">
          <label>Server URL<input v-model="form.url" type="url" required placeholder="https://example.com/mcp"></label><VirtualSelect v-model="form.auth" label="Authentication" :options="[{ value: 'none', label: 'No authentication' }, { value: 'oauth', label: 'OAuth · sign in with your account' }, { value: 'bearer', label: 'Bearer token' }]" /><label v-if="form.auth === 'bearer'">Bearer token<input v-model="form.token" type="password" autocomplete="new-password" :placeholder="editing?.hasToken ? 'Saved · leave blank to keep' : 'Enter token'"></label><template v-if="form.auth === 'oauth'">
            <div class="mcp-oauth-note">
              <KeyRound :size="19" /><span>Save, then Connect to sign in with your provider.</span>
            </div><details class="mcp-advanced">
              <summary>OAuth settings</summary><label>Client ID<input v-model="form.clientId" placeholder="Automatic registration when supported"></label><label>Client secret<input v-model="form.clientSecret" type="password" autocomplete="new-password" :placeholder="editing?.hasClientSecret ? 'Saved · leave blank to keep' : 'Only if your provider requires it'"></label><label>Scopes<input v-model="form.scopes" placeholder="Optional, separated by spaces"></label><label>Callback URL<div class="copy-field"><input readonly :value="editing?.callbackUrl || callbackUrl"><button type="button" class="icon-button" aria-label="Copy callback URL" @click="copyCallback"><Copy :size="15" /></button></div></label>
            </details>
          </template><label class="checkbox"><input v-model="form.allowPrivateNetwork" type="checkbox">Allow private network endpoints</label><small v-if="form.allowPrivateNetwork">Allows this server and its OAuth provider to reach internal addresses. Use for trusted self-hosted services.</small>
        </template>
        <template v-else>
          <label>Command<input v-model="form.command" required placeholder="e.g. pnpm"></label><label>Arguments<textarea v-model="argsText" rows="3" placeholder="One argument per line" /></label><small>Commands run in the agent’s environment. Test starts the command in the manager’s home.</small><fieldset class="mcp-env">
            <legend>Environment variables</legend><div v-for="(row, index) in envRows" :key="index" class="mcp-env-row">
              <input v-model="row.key" :readonly="row.saved" :aria-label="`Variable ${index + 1} name`" placeholder="NAME"><input v-model="row.value" type="password" autocomplete="new-password" :aria-label="`Variable ${index + 1} value`" :placeholder="row.saved ? 'Saved · blank to keep' : 'Value'"><button type="button" class="icon-button" :aria-label="`Remove variable ${index + 1}`" @click="envRows.splice(index, 1)">
                <X :size="15" />
              </button>
            </div><button type="button" class="button small" @click="envRows.push({ key: '', value: '', saved: false })">
              <Plus :size="14" />Add variable
            </button>
          </fieldset>
        </template>
        <label class="checkbox"><input v-model="form.enabled" type="checkbox">Available to agents</label>
      </div><footer class="modal-actions">
        <button type="button" class="button" @click="open = false">
          Cancel
        </button><button class="button primary" :disabled="!!busy.form">
          <LoaderCircle v-if="busy.form" :size="15" class="activity-spinning" />Save MCP
        </button>
      </footer>
    </form>
  </Modal>
  <Modal v-if="inspecting" :title="`${inspecting.name} · tools`" @close="inspecting = undefined">
    <div class="modal-body mcp-form">
      <label class="search-field"><Search :size="16" /><input v-model="toolQuery" aria-label="Search tools" placeholder="Search tools"></label><label class="checkbox"><input type="checkbox" :checked="selectedTools === null" @change="selectedTools = ($event.target as HTMLInputElement).checked ? null : []">Enable all tools, including future tools</label><div class="mcp-tool-list">
        <label v-for="tool in tools" :key="tool.name" class="mcp-tool"><input v-if="selectedTools !== null" v-model="selectedTools" type="checkbox" :value="tool.name"><Check v-else :size="16" /><span><strong>{{ tool.title || tool.name }}</strong><code>{{ tool.name }}</code><p>{{ tool.description || 'No description provided.' }}</p></span></label><p v-if="!tools.length" class="muted">
          {{ inspecting.tools.length ? 'No matching tools.' : 'Test the connection to discover its tools.' }}
        </p>
      </div>
    </div><footer class="modal-actions">
      <button class="button" @click="inspecting = undefined">
        Cancel
      </button><button class="button primary" :disabled="!!busy.tools" @click="saveTools">
        Save tool access
      </button>
    </footer>
  </Modal>
  <Modal v-if="removing || disconnecting" :title="removing ? 'Delete MCP?' : 'Remove credentials?'" @close="removing = undefined; disconnecting = undefined">
    <div class="modal-body">
      <p>{{ removing ? 'This connection will be removed from agent access.' : 'Saved tokens and environment secrets will be deleted. The connection settings stay available.' }} {{ (removing || disconnecting)?.transport === 'http' ? 'Active runs lose access to this remote connection immediately.' : 'Already running commands keep their environment until the run ends.' }}</p>
    </div><footer class="modal-actions">
      <button class="button" @click="removing = undefined; disconnecting = undefined">
        Cancel
      </button><button class="button danger" :disabled="!!busy[(removing || disconnecting)!.id]" @click="remove((removing || disconnecting)!, !!disconnecting)">
        <Trash2 :size="15" />{{ removing ? 'Delete MCP' : 'Remove credentials' }}
      </button>
    </footer>
  </Modal>
</template>
