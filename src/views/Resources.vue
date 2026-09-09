<script setup lang="ts">
import {
  Bot,
  FolderGit2,
  Pencil,
  Plus,
  ShieldCheck,
  Trash2,
} from '@lucide/vue'
import { computed, ref } from 'vue'
import { api, notify, refresh, state } from '../api'
import Empty from '../components/Empty.vue'
import Modal from '../components/Modal.vue'

const props = defineProps<{ kind: 'agents' | 'projects' }>()
const isAgent = computed(() => props.kind === 'agents')
const items = computed(() => state[props.kind])
const editing = ref<string | null>(null)
const open = ref(false)
const deleting = ref<any>(null)
const busy = ref(false)
const error = ref('')
const form = ref<any>({})
function edit(item?: any) {
  editing.value = item?.id ?? null
  form.value = item
    ? { ...item }
    : isAgent.value
      ? {
          name: '',
          description: '',
          model: '',
          reasoning: 'high',
          instructions: '',
          timeoutMinutes: 120,
        }
      : { name: '', description: '', path: '', baseBranch: 'main' }
  error.value = ''
  open.value = true
}
async function save() {
  busy.value = true
  error.value = ''
  try {
    await api(`/${props.kind}${editing.value ? `/${editing.value}` : ''}`, {
      method: editing.value ? 'PUT' : 'POST',
      body: JSON.stringify(form.value),
    })
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
      <span class="eyebrow">{{
        isAgent
          ? "GOOD WORK STARTS WITH A GOOD TEAM"
          : "GIVE YOUR AGENTS A PLACE TO WORK"
      }}</span>
      <h1>{{ isAgent ? "Agents" : "Projects" }}</h1>
      <p>
        {{
          isAgent
            ? "Different strengths. Shared direction. Profiles for the way you work."
            : "Connect the codebases and directories your agents can work in."
        }}
      </p>
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
            class="icon-button"
            :aria-label="`Delete ${item.name}`"
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
          <span>Model<strong>{{ item.model || "Codex default" }}</strong></span><span>Reasoning<strong>{{ item.reasoning }}</strong></span>
        </div>
        <div class="resource-bottom">
          <ShieldCheck :size="15" />YOLO mode<span>{{ item.timeoutMinutes }} min limit</span>
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
          ><small>Leave blank to follow CLI settings.</small></label><label>Reasoning<select v-model="form.reasoning">
            <option value="low">Low</option>
            <option value="medium">Medium</option>
            <option value="high">High</option>
            <option value="xhigh">Extra high</option>
          </select></label><p class="span-2 muted">
            All agents run in YOLO mode with full container access and no approval prompts. Docker provides isolation.
          </p><label>Time limit (minutes)<input
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
