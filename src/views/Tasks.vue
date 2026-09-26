<script setup lang="ts">
import type { RunListItem, Task } from '../../shared/contracts'
import { twMerge } from 'tailwind-merge'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, date, notify, refresh, state } from '../api'
import Empty from '../components/Empty.vue'
import Icon from '../components/Icon.vue'
import Modal from '../components/Modal.vue'
import Outcome from '../components/Outcome.vue'
import RunWorkspace from '../components/RunWorkspace.vue'
import Status from '../components/Status.vue'
import TaskEditor from '../components/TaskEditor.vue'
import UiAlert from '../components/UiAlert.vue'
import UiButton from '../components/UiButton.vue'
import UiSegments from '../components/UiSegments.vue'
import { AlertCircle, Archive, ArrowUpRight, Check, ChevronDown, Clock, Copy, Info, ListTodo, MoreHorizontal, Pause, Pencil, Play, Plus, Search, Square, Trash2, Zap } from '../icons'
import { iconButton } from '../ui'

const router = useRouter()
const route = useRoute()
const latestRuns = ref<RunListItem[]>([])
const choosing = ref(false)
const chooser = ref<HTMLButtonElement>()
const runWorkspace = ref<InstanceType<typeof RunWorkspace>>()
const details = ref(false)
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
    notify('Mission added to the queue')
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
    notify('Mission removed')
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
        ? 'Mission restored with its schedule paused'
        : 'Mission archived',
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
    notify('Mission duplicated with its schedule paused')
  }
  catch (e) {
    error.value = (e as Error).message
  }
}
const selected = computed(() => tasks.value.find(task => task.id === route.query.task) || tasks.value.find(task => ['running', 'queued'].includes(latest.value.get(task.id)?.status || '')) || tasks.value[0])
const selectedRun = computed(() => selected.value && latest.value.get(selected.value.id))
function needsAttention(task: Task) {
  const run = latest.value.get(task.id)
  return !!run && (['failed', 'interrupted'].includes(run.status) || (run.status === 'succeeded' && !!run.outcome && run.outcome.status !== 'completed'))
}
const groups = computed(() => [
  { label: 'In progress', items: tasks.value.filter(task => ['running', 'queued'].includes(latest.value.get(task.id)?.status || '')) },
  { label: 'Needs attention', items: tasks.value.filter(needsAttention) },
  { label: 'Ready', items: tasks.value.filter(task => !latest.value.has(task.id)) },
  { label: 'Finished', items: tasks.value.filter(task => !needsAttention(task) && ['succeeded', 'cancelled'].includes(latest.value.get(task.id)?.status || '')) },
].filter(group => group.items.length))
function closeMenu(event: MouseEvent) {
  if ((event.target as HTMLElement).closest('button, a'))
    (event.currentTarget as HTMLDetailsElement).open = false
}
function select(task: Task) {
  const returnFocus = choosing.value
  choosing.value = false
  if (returnFocus)
    nextTick(() => chooser.value?.focus())
  details.value = false
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
  <UiAlert v-if="error">
    {{ error }}
  </UiAlert>
  <div class="task-focus-layout">
    <section class="task-inbox" :class="{ choosing }" aria-label="Mission list">
      <header>
        <h1 class="task-inbox-heading" aria-label="Missions">
          Missions <small>{{ tasks.length }}</small>
        </h1>
        <button ref="chooser" class="task-chooser" aria-label="Choose mission" :aria-expanded="choosing" aria-controls="task-inbox-items" @click="choosing = !choosing">
          <Icon :name="ListTodo" :size="18" /><span>Missions</span><Icon :name="ChevronDown" :size="14" />
        </button>
        <button :class="iconButton" aria-label="Search missions" @click="toggleSearch">
          <Icon :name="Search" :size="17" />
        </button>
        <button :class="iconButton" aria-label="New mission" @click="editor = true">
          <Icon :name="Plus" :size="19" />
        </button>
      </header>
      <div class="task-inbox-controls">
        <label class="search-field"><Icon :name="Search" :size="16" /><input ref="searchInput" v-model="query" placeholder="Search missions" aria-label="Search missions"></label>
        <UiSegments v-model="filter" label="Filter missions" compact :options="[{ value: 'all', label: 'All missions' }, { value: 'scheduled', label: 'Scheduled' }, { value: 'once', label: 'One-off' }, { value: 'paused', label: 'Paused' }, { value: 'archived', label: 'Archived' }]" />
      </div>
      <div id="task-inbox-items" class="task-inbox-items flex-1 min-h-0 overflow-y-auto overscroll-contain [scrollbar-width:thin]">
        <section v-for="group in groups" :key="group.label" class="task-inbox-group">
          <h3>{{ group.label }}</h3><button v-for="task in group.items" :key="task.id" class="task-inbox-row flex items-start text-left gap-[9px] border border-transparent rounded-lg w-full px-[9px] py-3.5 phone:px-[9px] phone:py-3" :class="{ selected: selected?.id === task.id }" :aria-pressed="selected?.id === task.id" @click="select(task)">
            <span class="inbox-status"><Icon v-if="['running', 'queued'].includes(latest.get(task.id)?.status || '')" :name="Zap" :size="15" /><Icon v-else-if="needsAttention(task)" :name="AlertCircle" :size="15" /><Icon v-else-if="latest.has(task.id)" :name="Check" :size="15" /><Icon v-else :name="Clock" :size="15" /></span><span><strong>{{ task.name }}</strong><small>{{ state.agents.find(agent => agent.id === task.agentId)?.name || 'Deleted agent' }}<template v-if="task.projectId"> · {{ state.projects.find(project => project.id === task.projectId)?.name || 'Project' }}</template></small></span><Icon :name="ArrowUpRight" :size="13" />
          </button>
        </section>
      </div>
    </section>
    <section v-if="selected" class="task-focus-detail task-card" :class="{ 'has-run': selectedRun }" aria-label="Selected mission">
      <header class="task-detail-header">
        <div class="task-title-row">
          <div class="task-title">
            <span class="task-project">{{ state.projects.find(project => project.id === selected.projectId)?.name || 'Agent workspace' }}</span><h2>{{ selected.name }}</h2>
          </div>
          <div class="task-focus-actions">
            <UiButton v-if="!['running', 'queued'].includes(selectedRun?.status || '')" class="task-run-button" size="small" :disabled="busy === selected.id || selected.archived" @click="run(selected)">
              <Icon :name="Play" :size="14" />{{ busy === selected.id ? 'Starting…' : 'Run now' }}
            </UiButton><UiButton v-else-if="selectedRun && ['running', 'queued'].includes(selectedRun.status)" variant="danger-outline" size="small" :disabled="!runWorkspace?.canStop" @click="runWorkspace?.requestStop()">
              <Icon :name="Square" :size="14" />Stop run
            </UiButton><RouterLink v-if="selectedRun" :to="`/runs/${selectedRun.id}`" :class="twMerge(iconButton, 'icon-button')" aria-label="Open run">
              <Icon :name="ArrowUpRight" :size="17" />
            </RouterLink><details class="task-action-menu relative" @click="closeMenu">
              <summary :class="twMerge(iconButton, 'icon-button')" aria-label="Mission actions">
                <Icon :name="MoreHorizontal" :size="19" />
              </summary><div>
                <button v-if="!['running', 'queued'].includes(selectedRun?.status || '')" class="task-menu-run" :disabled="busy === selected.id || selected.archived" @click="run(selected)">
                  <Icon :name="Play" :size="15" />{{ busy === selected.id ? 'Starting…' : 'Run now' }}
                </button>
                <RouterLink v-if="selectedRun" class="task-menu-run" :to="`/runs/${selectedRun.id}`" aria-label="Open run">
                  <Icon :name="ArrowUpRight" :size="15" />Open run
                </RouterLink>
                <button :aria-label="`Edit ${selected.name}`" @click="editor = selected">
                  <Icon :name="Pencil" :size="15" />Edit
                </button><button :aria-label="`Duplicate ${selected.name}`" @click="duplicate(selected)">
                  <Icon :name="Copy" :size="15" />Duplicate
                </button><button v-if="selected.cron && !selected.archived" :aria-label="selected.enabled ? 'Pause schedule' : 'Resume schedule'" @click="pause(selected)">
                  <Icon :name="Pause" :size="15" />{{ selected.enabled ? 'Pause schedule' : 'Resume schedule' }}
                </button><button :aria-label="selected.archived ? `Restore ${selected.name}` : `Archive ${selected.name}`" @click="archive(selected)">
                  <Icon :name="Archive" :size="15" />{{ selected.archived ? 'Restore' : 'Archive' }}
                </button><button :aria-label="`Delete ${selected.name}`" @click="deleting = selected">
                  <Icon :name="Trash2" :size="15" />Delete
                </button>
              </div>
            </details>
          </div>
        </div>
        <div class="task-context run-title-meta">
          <button v-if="selectedRun?.status === 'succeeded' && selectedRun.outcome && selectedRun.outcome.status !== 'completed'" class="task-attention" @click="details = true">
            <Icon :name="AlertCircle" :size="14" />{{ selectedRun.outcome.status === 'needs_input' ? 'Your input needed' : 'Blocked' }}
          </button>
          <Status v-else-if="selectedRun" :status="selectedRun.status" /><span v-else class="text-muted">Ready</span>
          <span v-if="selected.cron" class="focus-schedule"><Icon :name="Clock" :size="13" />{{ selected.enabled ? `Next: ${date(selected.nextRun)}` : 'Schedule paused' }}</span>
          <button class="task-details-trigger" @click="details = true">
            <Icon :name="Info" :size="14" />Details
          </button>
        </div>
      </header>
      <RunWorkspace v-if="selectedRun" :key="selectedRun.id" ref="runWorkspace" :run-id="selectedRun.id" embedded @run="loadActivity" />
      <template v-else>
        <p v-if="loading" class="muted text-muted">
          Loading activity…
        </p><div v-else class="task-ready grid justify-items-center gap-4.5 px-[15px] py-10">
          <span class="ready-mark grid place-items-center w-13.5 h-13.5 border border-[light-dark(#34334e,_#817c9e)] bg-[light-dark(#ffdf70,_#e8c95e)] text-[#282537] [box-shadow:3px_3px_0_light-dark(#34334e,_#080912)] rounded-card [transform:rotate(-7deg)]"><Icon :name="Zap" :size="25" /></span><h3>Ready to run</h3>
        </div><details class="task-instructions text-xs border-t border-line pt-[15px]" open>
          <summary>Mission brief</summary><pre class="brief-text whitespace-pre-wrap text-xs leading-[1.9] text-muted">{{ selected.prompt }}</pre>
        </details>
      </template>
    </section>
    <Empty v-if="!selected" class="task-empty" :title="query || filter !== 'all' ? 'No matching missions' : 'No missions yet'">
      <UiButton @click="editor = true">
        <Icon :name="Plus" :size="16" />Create a task
      </UiButton>
    </Empty>
  </div>
  <Modal v-if="details && selected" title="Mission details" sheet @close="details = false">
    <div class="task-details-body">
      <h3>{{ selected.name }}</h3>
      <Outcome v-if="selectedRun" :outcome="selectedRun.outcome" :status="selectedRun.status" />
      <dl>
        <dt>Project</dt><dd>{{ state.projects.find(project => project.id === selected.projectId)?.name || 'Agent workspace' }}</dd>
        <dt>Agent</dt><dd>{{ state.agents.find(agent => agent.id === selected.agentId)?.name || 'Deleted agent' }}</dd>
        <dt>Schedule</dt><dd>{{ selected.cron || 'One-off mission' }}</dd>
        <template v-if="selected.cron">
          <dt>Next execution</dt><dd>{{ selected.enabled ? date(selected.nextRun) : 'Paused' }}</dd>
        </template>
        <template v-if="selectedRun?.accountName">
          <dt>Account</dt><dd>{{ selectedRun.accountName }}</dd>
        </template>
      </dl>
      <h3>Mission brief</h3><pre class="brief-text">{{ selected.prompt }}</pre>
      <div class="task-details-buttons">
        <UiButton @click="details = false; editor = selected">
          <Icon :name="Pencil" :size="15" />Edit task
        </UiButton>
        <UiButton v-if="selected.cron && !selected.archived" @click="pause(selected)">
          {{ selected.enabled ? 'Pause schedule' : 'Resume schedule' }}
        </UiButton>
        <UiButton v-if="selectedRun" @click="details = false; runWorkspace?.openDetails()">
          Execution details
        </UiButton>
      </div>
    </div>
  </Modal>
  <TaskEditor v-if="editor" :task="editor === true ? undefined : editor" @saved="select" @close="editor = null" />
  <Modal v-if="deleting" title="Remove this task?" @close="deleting = null">
    <div class="modal-body px-6.5 py-6 phone:p-5">
      <p>
        “{{ deleting.name }}” will stop scheduling. Its existing run history
        will remain available.
      </p>
    </div>
    <footer class="modal-actions flex justify-end gap-2.5 bg-surface border-t border-line sticky bottom-0 phone:flex-wrap px-6.5 py-4.5 phone:px-5 phone:py-4">
      <UiButton @click="deleting = null">
        Keep task
      </UiButton><UiButton variant="danger" @click="remove">
        Remove task
      </UiButton>
    </footer>
  </Modal>
</template>
