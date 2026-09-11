<script setup lang="ts">
import { twMerge } from 'tailwind-merge'
import { computed, ref } from 'vue'
import { MAIN_AGENT_ID } from '../../shared/constants'
import { api, notify, refresh, state } from '../api'
import Empty from '../components/Empty.vue'
import Icon from '../components/Icon.vue'
import Modal from '../components/Modal.vue'
import ModelSettings from '../components/ModelSettings.vue'
import UiAlert from '../components/UiAlert.vue'
import UiButton from '../components/UiButton.vue'
import VirtualSelect from '../components/VirtualSelect.vue'
import { Bot, FolderGit2, Pencil, Plus, ShieldCheck, Trash2 } from '../icons'
import { iconButton } from '../ui'

const props = defineProps<{
  kind: 'agents' | 'projects'
}>()
const isAgent = computed(() => props.kind === 'agents')
const items = computed(() => [...state[props.kind]].sort((a, b) => Number(b.id === MAIN_AGENT_ID) - Number(a.id === MAIN_AGENT_ID)))
const editing = ref<string | null>(null)
const open = ref(false)
const deleting = ref<any>(null)
const busy = ref(false)
const error = ref('')
const form = ref<any>({})
const githubToken = ref('')
const githubConfigured = ref(false)
const removeGithub = ref(false)
const sandboxOptions = [
  { value: 'yolo', label: 'YOLO', description: 'Autonomous execution · default' },
  { value: 'workspace-write', label: 'Workspace write', description: 'Codex sandbox limits writes to task workspaces' },
  { value: 'read-only', label: 'Read only', description: 'Project mounts are read-only; no approval escalation' },
]
const allProjects = computed({ get: () => form.value.access?.projects === null, set: (value: boolean) => {
  form.value.access.projects = value ? null : []
  if (!value)
    form.value.access.github = false
} })
const allMcps = computed({ get: () => form.value.access?.mcps === null, set: (value: boolean) => {
  form.value.access.mcps = value ? null : []
  form.value.access.mcpTools = {}
} })
function toggleMcp(id: string, enabled: boolean) {
  if (enabled) {
    form.value.access.mcps.push(id)
  }
  else {
    form.value.access.mcps = form.value.access.mcps.filter((value: string) => value !== id)
    delete form.value.access.mcpTools[id]
  }
}
const allSkills = computed({ get: () => form.value.access?.skills === null, set: (value: boolean) => {
  form.value.access.skills = value ? null : []
} })
const permittedSkills = computed(() => state.skills.filter(skill => skill.valid && (skill.scope === 'global' || form.value.access?.projects === null || form.value.access?.projects?.includes(skill.scope))))
async function edit(item?: any) {
  githubToken.value = ''
  githubConfigured.value = false
  removeGithub.value = false
  editing.value = item?.id ?? null
  form.value = item
    ? structuredClone(JSON.parse(JSON.stringify(item)))
    : isAgent.value
      ? {
          name: '',
          description: '',
          model: '',
          reasoning: '',
          instructions: '',
          timeoutMinutes: 120,
          access: { projects: null, skills: null, mcps: null, mcpTools: {}, github: true, sandbox: 'yolo' },
        }
      : { name: '', description: '', path: '', baseBranch: 'main' }
  error.value = ''
  open.value = true
  if (isAgent.value && editing.value) {
    const id = editing.value
    try {
      const connection = await api(`/agents/${id}/github-token`)
      if (editing.value === id)
        githubConfigured.value = connection.configured
    }
    catch (e) {
      error.value = (e as Error).message
    }
  }
}
async function save() {
  busy.value = true
  error.value = ''
  try {
    const saved = await api(`/${props.kind}${editing.value ? `/${editing.value}` : ''}`, {
      method: editing.value ? 'PUT' : 'POST',
      body: JSON.stringify(form.value),
    })
    if (isAgent.value && (githubToken.value || removeGithub.value))
      await api(`/agents/${saved.id}/github-token`, { method: 'PUT', body: JSON.stringify({ token: removeGithub.value ? '' : githubToken.value }) })
    githubToken.value = ''
    await refresh()
    open.value = false
    notify(`${isAgent.value ? 'Agent' : 'Project'} saved`)
  }
  catch (e) {
    error.value = (e as Error).message
  }
  finally {
    busy.value = false
  }
}
async function remove() {
  try {
    await api(`/${props.kind}/${deleting.value.id}`, { method: 'DELETE' })
    await refresh()
    deleting.value = null
    notify('Removed')
  }
  catch (e) {
    error.value = (e as Error).message
  }
}
</script>

<template>
  <div class="page-heading flex items-center justify-between gap-5 mb-[27px] phone:gap-2.5 phone:flex-wrap phone:mb-[21px]">
    <div>
      <h1>{{ isAgent ? "Agents" : "Projects" }}</h1>
    </div>
    <UiButton variant="primary" @click="edit()">
      <Icon :name="Plus" :size="17" />{{ isAgent ? "New agent" : "Add project" }}
    </UiButton>
  </div>
  <UiAlert v-if="error && !open">
    {{ error }}
  </UiAlert>
  <div v-if="items.length" class="resource-grid grid grid-cols-2 gap-5.5 tablet:grid-cols-1">
    <article v-for="item in items" :key="item.id" class="resource-card min-w-0 wrap-anywhere bg-surface border border-line rounded-card p-6 phone:p-5.5">
      <div class="resource-top flex justify-between items-center mb-5">
        <span class="resource-avatar w-11.5 h-11.5 bg-soft text-accent grid place-items-center rounded-xl border border-line"><Icon v-if="isAgent" :name="Bot" :size="25" /><Icon v-else :name="FolderGit2" :size="25" /></span>
        <div>
          <button
            :class="twMerge(iconButton, 'icon-button')"
            :aria-label="`Edit ${item.name}`"
            @click="edit(item)"
          >
            <Icon :name="Pencil" :size="17" />
          </button><button
            v-if="item.id !== MAIN_AGENT_ID"
            :class="twMerge(iconButton, 'icon-button')" :aria-label="`Delete ${item.name}`"
            @click="deleting = item"
          >
            <Icon :name="Trash2" :size="16" />
          </button>
        </div>
      </div>
      <h2>{{ item.name }}</h2>
      <p>
        {{
          item.description
            || (isAgent
              ? "Ready for a clear brief and a meaningful assignment."
              : "A dedicated workspace for your agent’s next assignment.")
        }}
      </p>
      <template v-if="'reasoning' in item">
        <div class="resource-facts grid grid-cols-[1fr_1fr] text-xs text-subtle">
          <span>Access<strong>{{ item.access.projects === null ? 'All projects' : `${item.access.projects.length} projects` }}</strong></span><span>Model<strong>{{ item.model || "Codex default" }}</strong></span><span>Reasoning<strong>{{ item.reasoning || 'Model default' }}</strong></span>
        </div>
        <div class="resource-bottom flex items-center gap-1.5 pt-4.5 [border-top:1px_solid_light-dark(#e7e6f1,_var(--dark-border))] text-xs text-muted mt-5">
          <Icon :name="ShieldCheck" :size="15" />{{ item.access.sandbox === 'yolo' ? 'YOLO mode' : item.access.sandbox }}<span>{{ item.timeoutMinutes }} min limit</span>
        </div>
      </template><template v-else-if="'path' in item">
        <code class="path-label block bg-surface rounded-[6px] text-muted p-2.5">{{ item.path }}</code>
        <p v-if="item.origin" class="path-label block bg-surface rounded-[6px] text-muted p-2.5">
          {{ item.origin }}
        </p>
        <div class="resource-bottom flex items-center gap-1.5 pt-4.5 [border-top:1px_solid_light-dark(#e7e6f1,_var(--dark-border))] text-xs text-muted mt-5">
          <Icon :name="FolderGit2" :size="15" />{{ item.baseBranch
          }}<span>{{
            state.tasks.filter((t) => t.projectId === item.id).length
          }}
            tasks</span>
        </div>
      </template>
      <RouterLink :to="{ path: '/chats', query: { [isAgent ? 'agent' : 'project']: item.id } }" class="mt-5 flex items-center justify-center gap-2 rounded-lg border border-line bg-soft px-3 py-2.5 text-sm font-semibold text-accent hover:bg-control focus-visible:outline-2 focus-visible:outline-accent">
        Start chat
      </RouterLink>
    </article>
  </div>
  <Empty
    v-else
    :title="isAgent ? 'Meet your first agent' : 'Where should the work happen?'"
    :description="
      isAgent
        ? 'Create a profile with the right instructions and model for the job.'
        : 'Add an existing directory on your server. Agents work on private copies in their VMs.'
    "
  >
    <UiButton @click="edit()">
      <Icon :name="Plus" :size="16" />{{ isAgent ? "Create an agent" : "Add a project" }}
    </UiButton>
  </Empty><Modal
    v-if="open"
    :title="`${editing ? 'Edit' : 'New'} ${isAgent ? 'agent' : 'project'}`"
    @close="open = false"
  >
    <form @submit.prevent="save">
      <div class="modal-body form-grid grid grid-cols-[1fr_1fr] gap-5 phone:grid-cols-1 phone:gap-4.5 px-6.5 py-6 phone:p-5">
        <label class="span-2 col-span-2 phone:col-span-1">Name<input
          v-model="form.name"
          required
          autofocus
          maxlength="100"
          :placeholder="
            isAgent ? 'e.g. Release engineer' : 'e.g. SheetOM'
          "
        ></label><label class="span-2 col-span-2 phone:col-span-1">Description<textarea
          v-model="form.description"
          rows="2"
          maxlength="500"
          placeholder="A short reminder of what this is for."
        /></label><template v-if="isAgent">
          <ModelSettings v-model:model="form.model" v-model:reasoning="form.reasoning" :disabled="busy" /><div class="span-2 col-span-2 phone:col-span-1 agent-access-panel">
            <div class="agent-access-heading">
              <Icon :name="ShieldCheck" :size="20" /><div><h3>Access &amp; execution</h3><p>Choose the resources this agent can use.</p></div>
            </div>
            <template v-if="editing !== MAIN_AGENT_ID">
              <label class="checkbox flex-row items-center gap-2 text-xs font-normal phone:text-xs phone:leading-[1.6] mx-0 my-[9px]"><input v-model="allProjects" type="checkbox">All projects, including future projects</label>
              <fieldset v-if="!allProjects" class="access-choices">
                <legend>Allowed projects</legend><label v-for="project in state.projects" :key="project.id" class="checkbox flex-row items-center gap-2 text-xs font-normal phone:text-xs phone:leading-[1.6] mx-0 my-[9px]"><input v-model="form.access.projects" type="checkbox" :value="project.id">{{ project.name }}</label><small v-if="!state.projects.length">No projects registered yet.</small>
              </fieldset>
              <label class="checkbox flex-row items-center gap-2 text-xs font-normal phone:text-xs phone:leading-[1.6] mx-0 my-[9px]"><input v-model="allSkills" type="checkbox">All available skills</label>
              <fieldset v-if="!allSkills" class="access-choices">
                <legend>Allowed skills</legend><label v-for="skill in permittedSkills" :key="skill.path" class="checkbox flex-row items-center gap-2 text-xs font-normal phone:text-xs phone:leading-[1.6] mx-0 my-[9px]"><input v-model="form.access.skills" type="checkbox" :value="`${skill.scope}/${skill.name}`">{{ skill.name }}</label>
              </fieldset>
              <label class="checkbox flex-row items-center gap-2 text-xs font-normal phone:text-xs phone:leading-[1.6] mx-0 my-[9px]"><input v-model="allMcps" type="checkbox">All MCP connections, including future connections</label>
              <fieldset class="access-choices">
                <legend>MCP permissions</legend>
                <div v-for="connection in state.mcps" :key="connection.id">
                  <label class="checkbox flex-row items-center gap-2 text-xs font-normal phone:text-xs phone:leading-[1.6] mx-0 my-[9px]"><input type="checkbox" :checked="allMcps || form.access.mcps.includes(connection.id)" :disabled="allMcps" @change="toggleMcp(connection.id, ($event.target as HTMLInputElement).checked)">{{ connection.name }}</label>
                  <details v-if="allMcps || form.access.mcps.includes(connection.id)" class="mcp-agent-tools mt-[7px] mr-0 mb-4 ml-6 text-xs">
                    <summary>Tool access</summary>
                    <label class="checkbox flex-row items-center gap-2 text-xs font-normal phone:text-xs phone:leading-[1.6] mx-0 my-[9px]"><input type="checkbox" :checked="!(connection.id in form.access.mcpTools)" @change="($event.target as HTMLInputElement).checked ? delete form.access.mcpTools[connection.id] : form.access.mcpTools[connection.id] = []">All enabled tools</label>
                    <template v-if="connection.id in form.access.mcpTools">
                      <label v-for="tool in connection.tools" :key="tool.name" class="checkbox flex-row items-center gap-2 text-xs font-normal phone:text-xs phone:leading-[1.6] mx-0 my-[9px]"><input v-model="form.access.mcpTools[connection.id]" type="checkbox" :value="tool.name">{{ tool.name }}</label><small v-if="!connection.tools.length">Test this connection in MCPs to discover its tools.</small>
                    </template>
                  </details>
                </div>
                <small v-if="!state.mcps.length">Add connections in MCPs first.</small>
              </fieldset>
              <label class="checkbox flex-row items-center gap-2 text-xs font-normal phone:text-xs phone:leading-[1.6] mx-0 my-[9px]"><input v-model="form.access.github" type="checkbox" :disabled="!allProjects">Shared GitHub connection</label>
              <label v-if="!form.access.github">Dedicated GitHub token<input v-model="githubToken" type="password" autocomplete="new-password" :placeholder="githubConfigured ? 'Saved token · leave blank to keep' : 'Optional fine-grained GitHub token'"><small>Select only this agent’s repositories and permissions when creating the token on GitHub. Its remote permissions are determined by the token.</small></label>
              <label v-if="githubConfigured && !form.access.github" class="checkbox flex-row items-center gap-2 text-xs font-normal phone:text-xs phone:leading-[1.6] mx-0 my-[9px]"><input v-model="removeGithub" type="checkbox">Remove saved GitHub token</label>
              <small>Shared GitHub credentials can access other repositories, so they are available only to agents with all-project access. Restricted agents receive only their selected MCP connections.</small>
            </template>
            <p v-else>
              The main agent has access to all registered projects, skills, and shared connections.
            </p>
            <VirtualSelect v-model="form.access.sandbox" label="Execution mode" :options="sandboxOptions" :icon="ShieldCheck" />
            <p class="muted text-muted">
              YOLO is the default. Every agent runs in a private VM. Sandboxed runs never bypass denied operations or wait for unattended approvals.
            </p>
          </div><label>Time limit (minutes)<input
            v-model.number="form.timeoutMinutes"
            type="number"
            min="1"
            max="720"
            required
          ></label><label class="span-2 col-span-2 phone:col-span-1">Additional instructions<textarea
            v-model="form.instructions"
            rows="4"
            placeholder="Conventions, responsibilities, or checks this agent should always follow."
          />
          </label>
        </template><template v-else>
          <label class="span-2 col-span-2 phone:col-span-1">Project directory<input
            v-model="form.path"
            required
            placeholder="/workspaces/my-project"
          ><small>Must be inside one of your configured workspace roots.</small></label><label class="span-2 col-span-2 phone:col-span-1">Base branch<input
            v-model="form.baseBranch"
            required
            placeholder="main"
          ><small>Used for new private workspaces.</small></label>
        </template>
        <UiAlert v-if="error" class="col-span-2 phone:col-span-1">
          {{ error }}
        </UiAlert>
      </div>
      <footer class="modal-actions flex justify-end gap-2.5 bg-surface border-t border-line sticky bottom-0 phone:flex-wrap px-6.5 py-4.5 phone:px-5 phone:py-4">
        <UiButton type="button" @click="open = false">
          Cancel
        </UiButton><UiButton variant="primary" type="submit" :disabled="busy">
          {{ busy ? "Saving…" : `Save ${isAgent ? "agent" : "project"}` }}
        </UiButton>
      </footer>
    </form>
  </Modal><Modal v-if="deleting" title="Remove this item?" @close="deleting = null">
    <div class="modal-body px-6.5 py-6 phone:p-5">
      <p>
        Remove “{{ deleting.name }}” from your workspace? Referenced items must
        be removed from tasks first.
      </p>
      <UiAlert v-if="error">
        {{ error }}
      </UiAlert>
    </div>
    <footer class="modal-actions flex justify-end gap-2.5 bg-surface border-t border-line sticky bottom-0 phone:flex-wrap px-6.5 py-4.5 phone:px-5 phone:py-4">
      <UiButton @click="deleting = null">
        Cancel
      </UiButton><UiButton variant="danger" @click="remove">
        Remove
      </UiButton>
    </footer>
  </Modal>
</template>

<style scoped>
.agent-access-panel { display: grid; gap: 1rem; padding: 1.2rem; border: 1px solid var(--color-line); border-radius: 16px; background: var(--color-soft); }
.agent-access-heading { display: flex; gap: .75rem; align-items: center; }
.agent-access-heading h3, .agent-access-heading p { margin: 0; }
.agent-access-heading p { margin-top: .25rem; color: var(--color-muted); font-size: .85rem; }
.access-choices { max-height: 230px; overflow: auto; display: grid; gap: .6rem; padding: .75rem; }
</style>
