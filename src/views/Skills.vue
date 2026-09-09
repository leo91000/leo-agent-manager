<script setup lang="ts">
import type { Skill } from '../../shared/contracts'
import { BookOpen, Code, Eye, Pencil, Plus, Search, Trash2 } from '@lucide/vue'
import { computed, ref } from 'vue'
import { api, notify, refresh, state } from '../api'
import Empty from '../components/Empty.vue'
import Markdown from '../components/Markdown.vue'
import Modal from '../components/Modal.vue'

const query = ref('')
const scopeFilter = ref('all')
const open = ref(false)
const preview = ref(false)
const original = ref<Skill>()
const name = ref('')
const scope = ref('global')
const content = ref('')
const error = ref('')
const busy = ref(false)
const deleting = ref<Skill>()
const files = ref<string[]>([])
const file = ref('SKILL.md')
const fileContent = ref('')
const newFile = ref('')
const items = computed(() =>
  state.skills.filter(
    s =>
      (scopeFilter.value === 'all' || s.scope === scopeFilter.value)
      && `${s.name} ${s.description}`
        .toLowerCase()
        .includes(query.value.toLowerCase()),
  ),
)
async function edit(skill?: Skill) {
  original.value = skill
  name.value = skill?.name ?? ''
  scope.value = skill?.scope ?? 'global'
  content.value
    = skill?.content
      ?? '---\nname: my-skill\ndescription: Describe when this skill should be used.\n---\n\n# My skill\n\nDescribe the workflow and how to verify completion.\n'
  file.value = 'SKILL.md'
  error.value = ''
  preview.value = false
  open.value = true
  files.value = skill
    ? await api(`/skills/${skill.scope}/${skill.name}/files`)
    : []
}
async function save() {
  busy.value = true
  error.value = ''
  try {
    if (file.value === 'SKILL.md') {
      await api(`/skills/${scope.value}/${name.value}`, {
        method: 'PUT',
        body: JSON.stringify({ content: content.value }),
      })
    }
    else {
      await api(`/skills/${scope.value}/${name.value}/file`, {
        method: 'PUT',
        body: JSON.stringify({ path: file.value, content: fileContent.value }),
      })
    }
    await refresh()
    notify('Skill saved')
    open.value = false
  }
  catch (e) {
    error.value = (e as Error).message
  }
  finally {
    busy.value = false
  }
}
async function readFile(value: string) {
  error.value = ''
  try {
    if (value !== 'SKILL.md') {
      fileContent.value = (
        await api<{ content: string }>(
          `/skills/${scope.value}/${name.value}/file?path=${encodeURIComponent(value)}`,
        )
      ).content
    }
    file.value = value
  }
  catch (e) {
    error.value = (e as Error).message
  }
}
async function remove() {
  try {
    await api(`/skills/${deleting.value!.scope}/${deleting.value!.name}`, {
      method: 'DELETE',
    })
    deleting.value = undefined
    await refresh()
    notify('Skill removed')
  }
  catch (e) {
    error.value = (e as Error).message
  }
}
</script>

<template>
  <div class="page-heading">
    <div>
      <span class="eyebrow">GOOD INSTRUCTIONS, REUSED</span>
      <h1>Skills library</h1>
      <p>Turn your best workflows into a shared advantage.</p>
    </div>
    <button class="button primary" @click="edit()">
      <Plus :size="17" />New skill
    </button>
  </div>
  <div class="toolbar">
    <label class="inline-label">Scope<select v-model="scopeFilter">
      <option value="all">All skills</option>
      <option value="global">Global</option>
      <option
        v-for="project in state.projects"
        :key="project.id"
        :value="project.id"
      >
        {{ project.name }}
      </option>
    </select></label><label class="search-field"><Search :size="17" /><input
      v-model="query"
      placeholder="Search skills"
      aria-label="Search skills"
    ></label>
  </div>
  <p v-if="error && !open" class="error">
    {{ error }}
  </p>
  <div v-if="items.length" class="skill-grid">
    <article
      v-for="skill in items"
      :key="`${skill.scope}/${skill.name}`"
      class="skill-card"
    >
      <div class="skill-card-top">
        <span class="skill-symbol"><BookOpen :size="21" /></span><span class="pill" :class="{ 'error-pill': !skill.valid }">{{
          !skill.valid
            ? "Needs attention"
            : skill.scope === "global"
              ? "Global"
              : state.projects.find((p) => p.id === skill.scope)?.name
        }}</span><button
          class="icon-button"
          :aria-label="`Edit ${skill.name}`"
          @click="edit(skill)"
        >
          <Pencil :size="16" />
        </button>
      </div>
      <button class="skill-title" @click="edit(skill)">
        {{ skill.name }}
      </button>
      <p>{{ skill.description }}</p>
      <footer>
        <code>.agents/skills</code><button
          class="icon-button"
          :aria-label="`Delete ${skill.name}`"
          @click="deleting = skill"
        >
          <Trash2 :size="15" />
        </button>
      </footer>
    </article>
  </div>
  <Empty
    v-else
    title="Your playbook starts here"
    description="Add repeatable instructions for reviews, releases, research, and the work you do often."
  >
    <button class="button" @click="edit()">
      <Plus :size="16" />Create your first skill
    </button>
  </Empty><Modal
    v-if="open"
    :title="original ? 'Edit skill' : 'Add to your playbook'"
    wide
    @close="open = false"
  >
    <form @submit.prevent="save">
      <div class="modal-body">
        <div class="form-grid">
          <label>Name<input
            v-model="name"
            aria-label="Skill name"
            :disabled="!!original"
            required
            pattern="[a-z0-9][a-z0-9\-]{0,63}"
            placeholder="my-skill"
          ><small>Match the name in your YAML frontmatter.</small></label><label>Scope<select v-model="scope" :disabled="!!original">
            <option value="global">Global · all projects</option>
            <option
              v-for="project in state.projects"
              :key="project.id"
              :value="project.id"
            >
              {{ project.name }}
            </option>
          </select></label>
        </div>
        <div class="editor-toolbar">
          <select
            v-if="original"
            :value="file"
            aria-label="Skill file"
            @change="readFile(($event.target as HTMLSelectElement).value)"
          >
            <option v-for="entry in files" :key="entry">
              {{ entry }}
            </option>
            <option v-if="!files.includes(file)">
              {{ file }}
            </option>
          </select><span v-else>SKILL.md</span>
          <div class="tabs">
            <button
              type="button"
              :class="{ selected: !preview }"
              @click="preview = false"
            >
              <Code :size="15" />Write
            </button><button
              type="button"
              :class="{ selected: preview }"
              @click="preview = true"
            >
              <Eye :size="15" />Preview
            </button>
          </div>
        </div>
        <div v-if="preview" class="skill-preview">
          <Markdown :content="file === 'SKILL.md' ? content : fileContent" />
        </div>
        <textarea
          v-else-if="file === 'SKILL.md'"
          v-model="content"
          class="code-editor"
          rows="15"
          spellcheck="false"
          aria-label="Skill content"
        /><textarea
          v-else
          v-model="fileContent"
          class="code-editor"
          rows="15"
          spellcheck="false"
          aria-label="Supporting file content"
        />
        <div v-if="original" class="supporting-file">
          <input
            v-model="newFile"
            placeholder="Add supporting file, e.g. checklist.md"
            aria-label="New supporting file"
          ><button
            type="button"
            class="button small"
            :disabled="!newFile"
            @click="
              file = newFile;
              fileContent = '';
              newFile = '';
              preview = false;
            "
          >
            Add file
          </button>
        </div>
        <p v-if="error" class="error" role="alert">
          {{ error }}
        </p>
      </div>
      <footer class="modal-actions">
        <button type="button" class="button" @click="open = false">
          Cancel
        </button><button class="button primary" :disabled="busy">
          {{ busy ? "Saving…" : "Save skill" }}
        </button>
      </footer>
    </form>
  </Modal><Modal
    v-if="deleting"
    title="Remove this skill?"
    @close="deleting = undefined"
  >
    <div class="modal-body">
      <p>
        This removes “{{ deleting.name }}” and its supporting files from disk.
        Existing run snapshots remain available.
      </p>
    </div>
    <footer class="modal-actions">
      <button class="button" @click="deleting = undefined">
        Cancel
      </button><button class="button danger" @click="remove">
        Remove skill
      </button>
    </footer>
  </Modal>
</template>
