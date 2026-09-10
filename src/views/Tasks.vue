<script setup lang="ts">
import type { RunListItem, Task } from '../../shared/contracts'
import {
  AlertCircle,
  Archive,
  ArrowUpRight,
  Bot,
  Check,
  ChevronDown,
  Clock,
  Copy,
  MoreHorizontal,
  Pause,
  Pencil,
  Play,
  Plus,
  Search,
  Trash2,
  Zap,
} from '@lucide/vue'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, date, notify, refresh, state } from '../api'
import Empty from '../components/Empty.vue'
import Modal from '../components/Modal.vue'
import RunWorkspace from '../components/RunWorkspace.vue'
import TaskEditor from '../components/TaskEditor.vue'

const router = useRouter()
const route = useRoute()
const latestRuns = ref<RunListItem[]>([])
const choosing = ref(false)
const searching = ref(false)
const searchInput = ref<HTMLInputElement>()
async function toggleSearch() {
  searching.value = !searching.value
  if (searching.value) {
    choosing.value = true
    await nextTick()
    searchInput.value?.focus()
  }
}
const loading = ref(true)
let disposed = false
let refreshing = false
const latest = computed(() => new Map(latestRuns.value.map(run => [run.taskId, run])))
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
    await api(`/tasks/${task.id}/run`, { method: 'POST' })
    notify('Task added to the queue')
    select(task)
    await loadActivity()
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
const selected = computed(() => tasks.value.find(task => task.id === route.query.task) || tasks.value.find(task => ['running', 'queued'].includes(latest.value.get(task.id)?.status || '')) || tasks.value[0])
const selectedRun = computed(() => selected.value && latest.value.get(selected.value.id))
const groups = computed(() => [
  { label: 'In progress', items: tasks.value.filter(task => ['running', 'queued'].includes(latest.value.get(task.id)?.status || '')) },
  { label: 'Needs attention', items: tasks.value.filter(task => ['failed', 'interrupted'].includes(latest.value.get(task.id)?.status || '')) },
  { label: 'Ready', items: tasks.value.filter(task => !latest.value.has(task.id)) },
  { label: 'Finished', items: tasks.value.filter(task => ['succeeded', 'cancelled'].includes(latest.value.get(task.id)?.status || '')) },
].filter(group => group.items.length))
function closeMenu(event: MouseEvent) {
  if ((event.target as HTMLElement).closest('button'))
    (event.currentTarget as HTMLDetailsElement).open = false
}
function select(task: Task) {
  choosing.value = false
  router.replace({ query: { ...route.query, task: task.id } })
}
async function loadActivity() {
  if (refreshing || disposed)
    return
  refreshing = true
  try {
    const [runs, currentTasks] = await Promise.all([api<RunListItem[]>('/tasks/activity'), api<Task[]>('/tasks')])
    if (disposed)
      return
    latestRuns.value = runs
    state.tasks = currentTasks
    if (!route.query.task && selected.value)
      select(selected.value)
    error.value = ''
  }
  catch (e) {
    if (!disposed)
      error.value = (e as Error).message
  }
  finally {
    refreshing = false
    loading.value = false
  }
}
let timer: ReturnType<typeof setInterval>
onMounted(() => {
  loadActivity()
  timer = setInterval(() => {
    if (!document.hidden)
      loadActivity()
  }, 5000)
})
onBeforeUnmount(() => {
  disposed = true
  clearInterval(timer)
})
watch(() => route.query.new, (value) => {
  if (value !== '1')
    return
  editor.value = true
  router.replace({ query: { ...route.query, new: undefined } })
}, { immediate: true })
</script>

<template>
  <div class="page-heading">
    <h1>Tasks</h1><button class="button primary" @click="editor = true">
      <Plus :size="17" />New task
    </button>
  </div>
  <p v-if="error" class="error" role="alert">
    {{ error }}
  </p>
  <div class="toolbar focus-filters">
    <div class="tabs">
      <button v-for="tab in [{ id: 'all', label: 'All tasks' }, { id: 'scheduled', label: 'Scheduled' }, { id: 'once', label: 'One-off' }, { id: 'paused', label: 'Paused' }, { id: 'archived', label: 'Archived' }]" :key="tab.id" :class="{ selected: filter === tab.id }" @click="filter = tab.id">
        {{ tab.label }}
      </button>
    </div>
  </div>
  <div v-if="tasks.length" class="task-focus-layout">
    <section class="task-inbox" :class="{ choosing }" aria-label="Task list">
      <header>
        <h2>Tasks <small>{{ tasks.length }}</small></h2><button class="icon-button" aria-label="Search tasks" :aria-expanded="searching" @click="toggleSearch">
          <Search :size="16" />
        </button><button class="icon-button choose-task" :aria-expanded="choosing" aria-controls="task-inbox-items" aria-label="Choose task" @click="choosing = !choosing">
          <ChevronDown :size="18" />
        </button>
      </header>
      <label v-if="searching || query" class="search-field"><Search :size="16" /><input ref="searchInput" v-model="query" placeholder="Search tasks" aria-label="Search tasks" @focus="choosing = true"></label>
      <div id="task-inbox-items" class="task-inbox-items">
        <section v-for="group in groups" :key="group.label" class="task-inbox-group">
          <h3>{{ group.label }}</h3><button v-for="task in group.items" :key="task.id" class="task-inbox-row" :class="{ selected: selected?.id === task.id }" :aria-pressed="selected?.id === task.id" @click="select(task)">
            <span class="inbox-status"><Zap v-if="['running', 'queued'].includes(latest.get(task.id)?.status || '')" :size="15" /><AlertCircle v-else-if="['failed', 'interrupted'].includes(latest.get(task.id)?.status || '')" :size="15" /><Check v-else-if="latest.has(task.id)" :size="15" /><Clock v-else :size="15" /></span><span><strong>{{ task.name }}</strong><small>{{ state.agents.find(agent => agent.id === task.agentId)?.name || 'Deleted agent' }}<template v-if="task.projectId"> · {{ state.projects.find(project => project.id === task.projectId)?.name || 'Project' }}</template></small></span><ArrowUpRight :size="13" />
          </button>
        </section>
      </div>
    </section>
    <section v-if="selected" class="task-focus-detail task-card" :class="{ 'has-run': selectedRun }" aria-label="Selected task">
      <header class="task-focus-actions">
        <span v-if="!selectedRun" class="task-agent-label"><Bot :size="16" />{{ state.agents.find(agent => agent.id === selected.agentId)?.name || 'Deleted agent' }}</span><button v-if="!['running', 'queued'].includes(selectedRun?.status || '')" class="button small primary" :disabled="busy === selected.id || selected.archived" @click="run(selected)">
          <Play :size="14" />{{ busy === selected.id ? 'Starting…' : 'Run now' }}
        </button><RouterLink v-if="selectedRun" :to="`/runs/${selectedRun.id}`" class="icon-button" aria-label="Open run">
          <ArrowUpRight :size="17" />
        </RouterLink><details class="task-action-menu" @click="closeMenu">
          <summary class="icon-button" aria-label="Task actions">
            <MoreHorizontal :size="19" />
          </summary><div>
            <button :aria-label="`Edit ${selected.name}`" @click="editor = selected">
              <Pencil :size="15" />Edit
            </button><button :aria-label="`Duplicate ${selected.name}`" @click="duplicate(selected)">
              <Copy :size="15" />Duplicate
            </button><button v-if="selected.cron && !selected.archived" :aria-label="selected.enabled ? 'Pause schedule' : 'Resume schedule'" @click="pause(selected)">
              <Pause :size="15" />{{ selected.enabled ? 'Pause schedule' : 'Resume schedule' }}
            </button><button :aria-label="selected.archived ? `Restore ${selected.name}` : `Archive ${selected.name}`" @click="archive(selected)">
              <Archive :size="15" />{{ selected.archived ? 'Restore' : 'Archive' }}
            </button><button :aria-label="`Delete ${selected.name}`" @click="deleting = selected">
              <Trash2 :size="15" />Delete
            </button>
          </div>
        </details>
      </header>
      <div v-if="selected.cron" class="focus-schedule">
        <Clock :size="13" />{{ selected.enabled ? `Next: ${date(selected.nextRun)}` : 'Schedule paused' }}
      </div>
      <RunWorkspace v-if="selectedRun" :key="selectedRun.id" :run-id="selectedRun.id" embedded @run="loadActivity" />
      <template v-else>
        <h2 class="unstarted-title">
          {{ selected.name }}
        </h2><p v-if="loading" class="muted">
          Loading activity…
        </p><div v-else class="task-ready">
          <span class="ready-mark"><Zap :size="25" /></span><h3>Ready to run</h3>
        </div><details class="task-instructions" open>
          <summary>Task brief</summary><pre class="brief-text">{{ selected.prompt }}</pre>
        </details>
      </template>
    </section>
  </div>
  <template v-else>
    <label class="search-field"><Search :size="16" /><input v-model="query" placeholder="Search tasks" aria-label="Search tasks"></label><Empty :title="query || filter !== 'all' ? 'No matching tasks' : 'No tasks yet'">
      <button class="button" @click="editor = true">
        <Plus :size="16" />Create a task
      </button>
    </Empty>
  </template>
  <TaskEditor v-if="editor" :task="editor === true ? undefined : editor" @saved="select" @close="editor = null" />
  <Modal v-if="deleting" title="Remove this task?" @close="deleting = null">
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
