<script setup lang="ts">
import {
  Bot,
  FolderGit2,
  Pencil,
  Plus,
  ShieldCheck,
  Sparkles,
  Trash2,
} from '@lucide/vue'
import { computed, ref } from 'vue'
import { MAIN_AGENT_ID } from '../../shared/constants'
import { api, notify, refresh, state } from '../api'
import Empty from '../components/Empty.vue'
import Modal from '../components/Modal.vue'
import VirtualSelect from '../components/VirtualSelect.vue'

const props = defineProps<{ kind: 'agents' | 'projects' }>()
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
const reasoningOptions = [
  { value: 'low', label: 'Low', description: 'Quick responses for straightforward work' },
  { value: 'medium', label: 'Medium', description: 'A balance of speed and depth' },
  { value: 'high', label: 'High', description: 'More time for complex reasoning' },
  { value: 'xhigh', label: 'Extra high', description: 'The deepest reasoning for demanding tasks' },
]
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
          reasoning: 'high',
          instructions: '',
          timeoutMinutes: 120,
          access: { projects: null, skills: null, github: true, sandbox: 'yolo' },
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
    catch (e) { error.value = (e as Error).message }
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
  <div class="page-heading">
    <div>
      <h1>{{ isAgent ? "Agents" : "Projects" }}</h1>
    </div>
    <button class="button primary" @click="edit()">
      <Plus :size="17" />{{ isAgent ? "New agent" : "Add project" }}
    </button>
  </div>
  <p v-if="error && !open" class="error" role="alert">
    {{ error }}
  </p>
  <div v-if="items.length" class="resource-grid">
    <article v-for="item in items" :key="item.id" class="resource-card">
      <div class="resource-top">
        <span class="resource-avatar"><Bot v-if="isAgent" :size="25" /><FolderGit2 v-else :size="25" /></span>
        <div>
          <button
            class="icon-button"
            :aria-label="`Edit ${item.name}`"
            @click="edit(item)"
          >
            <Pencil :size="17" />
          </button><button
            v-if="item.id !== MAIN_AGENT_ID"
            class="icon-button" :aria-label="`Delete ${item.name}`"
            @click="deleting = item"
          >
            <Trash2 :size="16" />
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
        <div class="resource-facts">
          <span>Access<strong>{{ item.access.projects === null ? 'All projects' : `${item.access.projects.length} projects` }}</strong></span><span>Model<strong>{{ item.model || "Codex default" }}</strong></span><span>Reasoning<strong>{{ item.reasoning }}</strong></span>
        </div>
        <div class="resource-bottom">
          <ShieldCheck :size="15" />{{ item.access.sandbox === 'yolo' ? 'YOLO mode' : item.access.sandbox }}<span>{{ item.timeoutMinutes }} min limit</span>
        </div>
      </template><template v-else-if="'path' in item">
        <code class="path-label">{{ item.path }}</code>
        <p v-if="item.origin" class="path-label">
          {{ item.origin }}
        </p>
        <div class="resource-bottom">
          <FolderGit2 :size="15" />{{ item.baseBranch
          }}<span>{{
            state.tasks.filter((t) => t.projectId === item.id).length
          }}
            tasks</span>
        </div>
      </template>
    </article>
  </div>
  <Empty
    v-else
    :title="isAgent ? 'Meet your first agent' : 'Where should the work happen?'"
    :description="
      isAgent
        ? 'Create a profile with the right instructions and model for the job.'
        : 'Add an existing directory on your server. Your agents can use isolated worktrees to keep changes separate.'
    "
  >
    <button class="button" @click="edit()">
      <Plus :size="16" />{{ isAgent ? "Create an agent" : "Add a project" }}
    </button>
  </Empty><Modal
    v-if="open"
    :title="`${editing ? 'Edit' : 'New'} ${isAgent ? 'agent' : 'project'}`"
    @close="open = false"
  >
    <form @submit.prevent="save">
      <div class="modal-body form-grid">
        <label class="span-2">Name<input
          v-model="form.name"
          required
          autofocus
          maxlength="100"
          :placeholder="
            isAgent ? 'e.g. Release engineer' : 'e.g. SheetOM'
          "
        ></label><label class="span-2">Description<textarea
          v-model="form.description"
          rows="2"
          maxlength="500"
          placeholder="A short reminder of what this is for."
        /></label><template v-if="isAgent">
          <label>Model<input
            v-model="form.model"
            placeholder="Use Codex default"
          ><small>Leave blank to follow CLI settings.</small></label><VirtualSelect v-model="form.reasoning" label="Reasoning" :options="reasoningOptions" :icon="Sparkles" /><div class="span-2 agent-access-panel">
            <div class="agent-access-heading">
              <ShieldCheck :size="20" /><div><h3>Access &amp; execution</h3><p>Choose the resources this agent can use.</p></div>
            </div>
            <template v-if="editing !== MAIN_AGENT_ID">
              <label class="checkbox"><input v-model="allProjects" type="checkbox">All projects, including future projects</label>
              <fieldset v-if="!allProjects" class="access-choices">
                <legend>Allowed projects</legend><label v-for="project in state.projects" :key="project.id" class="checkbox"><input v-model="form.access.projects" type="checkbox" :value="project.id">{{ project.name }}</label><small v-if="!state.projects.length">No projects registered yet.</small>
              </fieldset>
              <label class="checkbox"><input v-model="allSkills" type="checkbox">All available skills</label>
              <fieldset v-if="!allSkills" class="access-choices">
                <legend>Allowed skills</legend><label v-for="skill in permittedSkills" :key="skill.path" class="checkbox"><input v-model="form.access.skills" type="checkbox" :value="`${skill.scope}/${skill.name}`">{{ skill.name }}</label>
              </fieldset>
              <label class="checkbox"><input v-model="form.access.github" type="checkbox" :disabled="!allProjects">Shared GitHub connection</label>
              <label v-if="!form.access.github">Dedicated GitHub token<input v-model="githubToken" type="password" autocomplete="new-password" :placeholder="githubConfigured ? 'Saved token · leave blank to keep' : 'Optional fine-grained GitHub token'"><small>Select only this agent’s repositories and permissions when creating the token on GitHub. Its remote permissions are determined by the token.</small></label>
              <label v-if="githubConfigured && !form.access.github" class="checkbox"><input v-model="removeGithub" type="checkbox">Remove saved GitHub token</label>
              <small>Shared GitHub credentials can access other repositories, so they are available only to agents with all-project access. Restricted agents receive no shared GitHub or MCP credentials.</small>
            </template>
            <p v-else>
              The main agent has access to all registered projects, skills, and shared connections.
            </p>
            <VirtualSelect v-model="form.access.sandbox" label="Execution mode" :options="sandboxOptions" :icon="ShieldCheck" />
            <p class="muted">
              YOLO is the default. Resource restrictions use a separate container. Sandboxed runs never bypass denied operations or wait for unattended approvals.
            </p>
          </div><label>Time limit (minutes)<input
            v-model.number="form.timeoutMinutes"
            type="number"
            min="1"
            max="720"
            required
          ></label><label class="span-2">Additional instructions<textarea
            v-model="form.instructions"
            rows="4"
            placeholder="Conventions, responsibilities, or checks this agent should always follow."
          />
          </label>
        </template><template v-else>
          <label class="span-2">Project directory<input
            v-model="form.path"
            required
            placeholder="/workspaces/my-project"
          ><small>Must be inside one of your configured workspace roots.</small></label><label class="span-2">Base branch<input
            v-model="form.baseBranch"
            required
            placeholder="main"
          ><small>Used when creating isolated worktrees.</small></label>
        </template>
        <p v-if="error" class="error span-2" role="alert">
          {{ error }}
        </p>
      </div>
      <footer class="modal-actions">
        <button type="button" class="button" @click="open = false">
          Cancel
        </button><button class="button primary" :disabled="busy">
          {{ busy ? "Saving…" : `Save ${isAgent ? "agent" : "project"}` }}
        </button>
      </footer>
    </form>
  </Modal><Modal v-if="deleting" title="Remove this item?" @close="deleting = null">
    <div class="modal-body">
      <p>
        Remove “{{ deleting.name }}” from your workspace? Referenced items must
        be removed from tasks first.
      </p>
      <p v-if="error" class="error">
        {{ error }}
      </p>
    </div>
    <footer class="modal-actions">
      <button class="button" @click="deleting = null">
        Cancel
      </button><button class="button danger" @click="remove">
        Remove
      </button>
    </footer>
  </Modal>
</template>

<style scoped>
.agent-access-panel { display: grid; gap: 1rem; padding: 1.2rem; border: 1px solid var(--line); border-radius: 16px; background: var(--soft); }
.agent-access-heading { display: flex; gap: .75rem; align-items: center; }
.agent-access-heading h3, .agent-access-heading p { margin: 0; }
.agent-access-heading p { margin-top: .25rem; color: var(--muted); font-size: .85rem; }
.access-choices { max-height: 230px; overflow: auto; display: grid; gap: .6rem; padding: .75rem; }
</style>
