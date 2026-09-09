<script setup lang="ts">
import type { Task } from '../../shared/contracts'
import {
  Archive,
  Bot,
  Clock,
  Copy,
  FolderGit2,
  Pause,
  Pencil,
  Play,
  Plus,
  Search,
  Trash2,
} from '@lucide/vue'
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, date, notify, refresh, state } from '../api'
import Empty from '../components/Empty.vue'
import Modal from '../components/Modal.vue'
import TaskEditor from '../components/TaskEditor.vue'

const router = useRouter()
const query = ref('')
const filter = ref('all')
const editor = ref<Task | true | null>(null)
const error = ref('')
const busy = ref('')
const deleting = ref<Task | null>(null)
const tasks = computed(() =>
  state.tasks.filter(
    t =>
      t.name.toLowerCase().includes(query.value.toLowerCase())
      && (filter.value === 'archived' ? t.archived : !t.archived)
      && (filter.value === 'all'
        || filter.value === 'archived'
        || (filter.value === 'scheduled' && t.cron && t.enabled)
        || (filter.value === 'paused' && !t.enabled)
        || (filter.value === 'once' && !t.cron)),
  ),
)
async function run(task: Task) {
  busy.value = task.id
  error.value = ''
  try {
    const result = await api(`/tasks/${task.id}/run`, { method: 'POST' })
    notify('Task added to the queue')
    router.push(`/runs/${result.id}`)
  }
  catch (e) {
    error.value = (e as Error).message
  }
  finally {
    busy.value = ''
  }
}
async function pause(task: Task) {
  try {
    await api(`/tasks/${task.id}`, {
      method: 'PUT',
      body: JSON.stringify({ ...task, enabled: !task.enabled }),
    })
    await refresh()
    notify(task.enabled ? 'Schedule paused' : 'Schedule resumed')
  }
  catch (e) {
    error.value = (e as Error).message
  }
}
async function remove() {
  try {
    await api(`/tasks/${deleting.value!.id}`, { method: 'DELETE' })
    deleting.value = null
    await refresh()
    notify('Task removed')
  }
  catch (e) {
    error.value = (e as Error).message
  }
}
async function archive(task: Task) {
  try {
    await api(`/tasks/${task.id}`, {
      method: 'PUT',
      body: JSON.stringify({
        ...task,
        archived: !task.archived,
        enabled: false,
      }),
    })
    await refresh()
    notify(
      task.archived
        ? 'Task restored with its schedule paused'
        : 'Task archived',
    )
  }
  catch (e) {
    error.value = (e as Error).message
  }
}
async function duplicate(task: Task) {
  try {
    await api('/tasks', {
      method: 'POST',
      body: JSON.stringify({
        ...task,
        name: `${task.name} (copy)`,
        enabled: false,
        archived: false,
      }),
    })
    await refresh()
    notify('Task duplicated with its schedule paused')
  }
  catch (e) {
    error.value = (e as Error).message
  }
}
</script>

<template>
  <div class="page-heading">
    <div>
      <span class="eyebrow">A PLACE FOR EVERY ASSIGNMENT</span>
      <h1>Tasks</h1>
      <p>Give your agents clear direction, once or on repeat.</p>
    </div>
    <button class="button primary" @click="editor = true">
      <Plus :size="17" />New task
    </button>
  </div>
  <div class="toolbar">
    <div class="tabs">
      <button
        v-for="tab in [
          { id: 'all', label: 'All tasks' },
          { id: 'scheduled', label: 'Scheduled' },
          { id: 'once', label: 'One-off' },
          { id: 'paused', label: 'Paused' },
          { id: 'archived', label: 'Archived' },
        ]"
        :key="tab.id"
        :class="{ selected: filter === tab.id }"
        @click="filter = tab.id"
      >
        {{ tab.label
        }}<span v-if="tab.id === 'all'">{{ state.tasks.length }}</span>
      </button>
    </div>
    <label class="search-field"><Search :size="17" /><input
      v-model="query"
      placeholder="Search tasks"
      aria-label="Search tasks"
    ></label>
  </div>
  <p v-if="error" class="error" role="alert">
    {{ error }}
  </p>
  <div v-if="tasks.length" class="task-grid">
    <article v-for="task in tasks" :key="task.id" class="task-card">
      <div class="task-card-top">
        <span class="task-symbol" :class="[{ scheduled: task.cron }]"><Clock v-if="task.cron" :size="21" /><Play v-else :size="20" /></span><span class="pill" :class="[{ 'muted-pill': !task.enabled }]">{{
          !task.enabled ? "Paused" : task.cron ? "Scheduled" : "One-off"
        }}</span>
        <div class="card-tools">
          <button
            class="icon-button"
            :aria-label="`Edit ${task.name}`"
            @click="editor = task"
          >
            <Pencil :size="16" />
          </button><button
            class="icon-button"
            :aria-label="`Duplicate ${task.name}`"
            @click="duplicate(task)"
          >
            <Copy :size="16" />
          </button>
        </div>
      </div>
      <h2>{{ task.name }}</h2>
      <p class="task-description">
        {{ task.prompt }}
      </p>
      <div class="task-meta">
        <span><Bot :size="14" />{{
          state.agents.find((a) => a.id === task.agentId)?.name
        }}</span><span><FolderGit2 :size="14" />{{
          state.projects.find((p) => p.id === task.projectId)?.name
        }}</span>
      </div>
      <div class="task-schedule">
        <Clock :size="14" /><span>{{
          task.cron
            ? task.enabled
              ? `Next: ${date(task.nextRun)}`
              : "Schedule is paused"
            : "Ready whenever you are"
        }}</span>
      </div>
      <footer>
        <button
          class="button small primary"
          :disabled="busy === task.id || task.archived"
          @click="run(task)"
        >
          <Play :size="14" />{{ busy === task.id ? "Starting…" : "Run now" }}
        </button>
        <div>
          <button
            v-if="task.cron && !task.archived"
            class="icon-button"
            :aria-label="task.enabled ? 'Pause schedule' : 'Resume schedule'"
            @click="pause(task)"
          >
            <Pause v-if="task.enabled" :size="16" /><Play
              v-else
              :size="16"
            />
          </button><button
            class="icon-button"
            :aria-label="
              task.archived ? `Restore ${task.name}` : `Archive ${task.name}`
            "
            @click="archive(task)"
          >
            <Archive :size="16" />
          </button><button
            class="icon-button"
            :aria-label="`Delete ${task.name}`"
            @click="deleting = task"
          >
            <Trash2 :size="16" />
          </button>
        </div>
      </footer>
    </article>
  </div>
  <Empty
    v-else
    :title="
      query || filter !== 'all'
        ? 'No matching tasks'
        : 'What would you like to get done?'
    "
    description="Start with a clear outcome. Your agent will take it from there."
  >
    <button class="button" @click="editor = true">
      <Plus :size="16" />Create a task
    </button>
  </Empty><TaskEditor
    v-if="editor"
    :task="editor === true ? undefined : editor"
    @close="editor = null"
  /><Modal v-if="deleting" title="Remove this task?" @close="deleting = null">
    <div class="modal-body">
      <p>
        “{{ deleting.name }}” will stop scheduling. Its existing run history
        will remain available.
      </p>
    </div>
    <footer class="modal-actions">
      <button class="button" @click="deleting = null">
        Keep task
      </button><button class="button danger" @click="remove">
        Remove task
      </button>
    </footer>
  </Modal>
</template>
