<script setup lang="ts">
import type { RunListItem, Task } from '../../shared/contracts'
import { twMerge } from 'tailwind-merge'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, date, notify, refresh, state } from '../api'
import Empty from '../components/Empty.vue'
import Icon from '../components/Icon.vue'
import Modal from '../components/Modal.vue'
import RunWorkspace from '../components/RunWorkspace.vue'
import Status from '../components/Status.vue'
import TaskEditor from '../components/TaskEditor.vue'
import UiAlert from '../components/UiAlert.vue'
import UiButton from '../components/UiButton.vue'
import UiSegments from '../components/UiSegments.vue'
import { AlertCircle, Archive, ArrowUpRight, Bot, Check, ChevronDown, Clock, Copy, MoreHorizontal, Pause, Pencil, Play, Plus, Search, Square, Trash2, Zap } from '../icons'
import { iconButton } from '../ui'

const router = useRouter()
const route = useRoute()
const latestRuns = ref<RunListItem[]>([])
const choosing = ref(false)
const runWorkspace = ref<InstanceType<typeof RunWorkspace>>()
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
  <div class="page-heading flex items-center justify-between gap-5 mb-[27px] phone:gap-2.5 phone:flex-wrap phone:mb-[21px]">
    <h1>Tasks</h1><UiButton variant="primary" @click="editor = true">
      <Icon :name="Plus" :size="17" />New task
    </UiButton>
  </div>
  <UiAlert v-if="error">
    {{ error }}
  </UiAlert>
  <div class="toolbar flex items-center justify-between gap-5 mb-[23px] tablet:items-start tablet:flex-wrap phone:gap-4 phone:min-w-0 focus-filters mt-0 phone:mb-[19px] mx-0">
    <UiSegments v-model="filter" label="Filter tasks" compact :options="[{ value: 'all', label: 'All tasks' }, { value: 'scheduled', label: 'Scheduled' }, { value: 'once', label: 'One-off' }, { value: 'paused', label: 'Paused' }, { value: 'archived', label: 'Archived' }]" />
  </div>
  <div v-if="tasks.length" class="task-focus-layout grid flex-1 min-h-0 grid-cols-[minmax(230px,300px)_minmax(0,1fr)] grid-rows-[minmax(0,1fr)] items-stretch gap-7 pr-1 pb-1 [@media(901px<=width<=1150px)]:grid-cols-[225px_minmax(0,1fr)] [@media(901px<=width<=1150px)]:gap-5 tablet:grid-cols-1 tablet:grid-rows-[auto_minmax(0,1fr)] tablet:gap-3 short:gap-2">
    <section class="task-inbox flex min-h-0 min-w-0 flex-col px-3 py-[17px] tablet:px-2.5 tablet:py-2 short:py-0" :class="{ choosing }" aria-label="Task list">
      <header>
        <div class="task-inbox-title flex-1 min-w-0 mr-auto">
          <h2 class="task-inbox-heading">
            Tasks <small>{{ tasks.length }}</small>
          </h2>
          <span v-if="selected" class="task-inbox-selection hidden" :title="selected.name"><strong>{{ selected.name }}</strong><Status v-if="selectedRun" :status="selectedRun.status" /></span>
        </div><button :class="twMerge(iconButton, 'icon-button')" aria-label="Search tasks" :aria-expanded="searching" @click="toggleSearch">
          <Icon :name="Search" :size="16" />
        </button><button :class="twMerge(iconButton, 'icon-button choose-task hidden tablet:inline-flex')" :aria-expanded="choosing" aria-controls="task-inbox-items" aria-label="Choose task" @click="choosing = !choosing">
          <Icon :name="ChevronDown" :size="18" />
        </button>
      </header>
      <label v-if="searching || query" class="search-field flex flex-row items-center gap-[7px] text-subtle bg-raised border border-line rounded-[7px] min-w-0 phone:w-full px-2.5 py-0"><Icon :name="Search" :size="16" /><input ref="searchInput" v-model="query" placeholder="Search tasks" aria-label="Search tasks" @focus="choosing = true"></label>
      <div id="task-inbox-items" class="task-inbox-items flex-1 min-h-0 overflow-y-auto overscroll-contain [scrollbar-width:thin]">
        <section v-for="group in groups" :key="group.label" class="task-inbox-group">
          <h3>{{ group.label }}</h3><button v-for="task in group.items" :key="task.id" class="task-inbox-row flex items-start text-left gap-[9px] border border-transparent rounded-lg w-full px-[9px] py-3.5 phone:px-[9px] phone:py-3" :class="{ selected: selected?.id === task.id }" :aria-pressed="selected?.id === task.id" @click="select(task)">
            <span class="inbox-status"><Icon v-if="['running', 'queued'].includes(latest.get(task.id)?.status || '')" :name="Zap" :size="15" /><Icon v-else-if="['failed', 'interrupted'].includes(latest.get(task.id)?.status || '')" :name="AlertCircle" :size="15" /><Icon v-else-if="latest.has(task.id)" :name="Check" :size="15" /><Icon v-else :name="Clock" :size="15" /></span><span><strong>{{ task.name }}</strong><small>{{ state.agents.find(agent => agent.id === task.agentId)?.name || 'Deleted agent' }}<template v-if="task.projectId"> · {{ state.projects.find(project => project.id === task.projectId)?.name || 'Project' }}</template></small></span><Icon :name="ArrowUpRight" :size="13" />
          </button>
        </section>
      </div>
    </section>
    <section v-if="selected" class="task-focus-detail task-card min-h-0 min-w-0 wrap-anywhere overflow-auto overscroll-contain [scrollbar-width:thin] border-l border-line tablet:border-l-0 tablet:border-t p-[25px] compact:p-5 phone:p-3 [&.has-run]:flex [&.has-run]:flex-col [&.has-run]:overflow-hidden" :class="{ 'has-run': selectedRun }" aria-label="Selected task">
      <header class="task-focus-actions static flex shrink-0 items-center gap-[11px] mb-3.5 tablet:gap-1.5 tablet:mb-2">
        <span v-if="selected.cron" class="focus-schedule flex items-center gap-1.5 text-muted text-3xs mt-[-7px] mb-5 mx-0">
          <Icon :name="Clock" :size="13" />{{ selected.enabled ? `Next: ${date(selected.nextRun)}` : 'Schedule paused' }}
        </span>
        <span v-if="!selectedRun" class="task-agent-label flex items-center gap-2 min-w-0 flex-1 text-2xs text-muted wrap-anywhere phone:text-3xs phone:gap-[5px]"><Icon :name="Bot" :size="16" />{{ state.agents.find(agent => agent.id === selected.agentId)?.name || 'Deleted agent' }}</span><UiButton v-if="!['running', 'queued'].includes(selectedRun?.status || '')" variant="primary" size="small" :disabled="busy === selected.id || selected.archived" @click="run(selected)">
          <Icon :name="Play" :size="14" />{{ busy === selected.id ? 'Starting…' : 'Run now' }}
        </UiButton><UiButton v-else-if="selectedRun && ['running', 'queued'].includes(selectedRun.status)" variant="danger-outline" size="small" :disabled="!runWorkspace?.canStop" @click="runWorkspace?.requestStop()">
          <Icon :name="Square" :size="14" />Stop run
        </UiButton><RouterLink v-if="selectedRun" :to="`/runs/${selectedRun.id}`" :class="twMerge(iconButton, 'icon-button')" aria-label="Open run">
          <Icon :name="ArrowUpRight" :size="17" />
        </RouterLink><details class="task-action-menu relative" @click="closeMenu">
          <summary :class="twMerge(iconButton, 'icon-button')" aria-label="Task actions">
            <Icon :name="MoreHorizontal" :size="19" />
          </summary><div>
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
      </header>
      <RunWorkspace v-if="selectedRun" :key="selectedRun.id" ref="runWorkspace" :run-id="selectedRun.id" embedded @run="loadActivity" />
      <template v-else>
        <h2 class="unstarted-title [font-size:23px] mb-5 wrap-anywhere phone:[font-size:21px]">
          {{ selected.name }}
        </h2><p v-if="loading" class="muted text-muted">
          Loading activity…
        </p><div v-else class="task-ready grid justify-items-center gap-4.5 px-[15px] py-10">
          <span class="ready-mark grid place-items-center w-13.5 h-13.5 border border-[light-dark(#34334e,_#817c9e)] bg-[light-dark(#ffdf70,_#e8c95e)] text-[#282537] [box-shadow:3px_3px_0_light-dark(#34334e,_#080912)] rounded-card [transform:rotate(-7deg)]"><Icon :name="Zap" :size="25" /></span><h3>Ready to run</h3>
        </div><details class="task-instructions text-xs border-t border-line pt-[15px]" open>
          <summary>Task brief</summary><pre class="brief-text whitespace-pre-wrap text-xs leading-[1.9] text-muted">{{ selected.prompt }}</pre>
        </details>
      </template>
    </section>
  </div>
  <template v-else>
    <label class="search-field flex flex-row items-center gap-[7px] text-subtle bg-raised border border-line rounded-[7px] min-w-0 phone:w-full px-2.5 py-0"><Icon :name="Search" :size="16" /><input v-model="query" placeholder="Search tasks" aria-label="Search tasks"></label><Empty :title="query || filter !== 'all' ? 'No matching tasks' : 'No tasks yet'">
      <UiButton @click="editor = true">
        <Icon :name="Plus" :size="16" />Create a task
      </UiButton>
    </Empty>
  </template>
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
