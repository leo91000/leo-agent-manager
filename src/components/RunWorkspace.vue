<script setup lang="ts">
import type { Deliverable } from '../../shared/artifacts'
import type { Run, RunEvent } from '../../shared/contracts'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, date, duration, notify } from '../api'
import { ArrowLeft, Copy, FileText, RotateCw, Square, Terminal } from '../icons'
import ActivityFeed from './ActivityFeed.vue'
import ArtifactGallery from './ArtifactGallery.vue'
import ArtifactViewer from './ArtifactViewer.vue'
import Icon from './Icon.vue'
import Markdown from './Markdown.vue'
import Modal from './Modal.vue'
import Status from './Status.vue'
import UiAlert from './UiAlert.vue'
import UiButton from './UiButton.vue'
import UiSegments from './UiSegments.vue'

const props = defineProps<{ runId: string, embedded?: boolean }>()
const emit = defineEmits<{ run: [id: string] }>()
let disposed = false
const router = useRouter()
const run = ref<Run>()
const deliverables = ref<Deliverable[]>([])
const artifactViewer = ref<string | null>(null)
const events = ref<RunEvent[]>([])
const moreEvents = ref(false)
const loading = ref(false)
const error = ref('')
const tab = ref(props.embedded ? 'events' : 'result')
const confirm = ref(false)
const confirmCleanup = ref(false)
const trimmedEvents = ref(0)
const active = computed(
  () => run.value && ['running', 'queued'].includes(run.value.status),
)
function requestStop() {
  if (active.value)
    confirm.value = true
}
defineExpose({ requestStop, canStop: active })
async function load() {
  if (loading.value)
    return
  loading.value = true
  try {
    error.value = ''
    const current = await api<Run>(`/runs/${props.runId}`)
    if (disposed)
      return
    run.value = current
    deliverables.value = await api<Deliverable[]>(`/runs/${props.runId}/artifacts`)
    const items = await api<RunEvent[]>(
      `/runs/${props.runId}/events?after=${events.value.at(-1)?.id ?? 0}`,
    )
    if (disposed)
      return
    events.value.push(...items)
    moreEvents.value = items.length === 100
    if (events.value.length > 2000) {
      trimmedEvents.value += events.value.length - 2000
      events.value = events.value.slice(-2000)
    }
  }
  catch (e) {
    error.value = (e as Error).message
  }
  finally {
    loading.value = false
  }
}
let timer: ReturnType<typeof setInterval>
onMounted(async () => {
  await load()
  if (disposed)
    return
  if (active.value)
    tab.value = 'events'
  timer = setInterval(() => {
    if ((active.value || error.value) && !document.hidden)
      load()
  }, 1500)
})
onBeforeUnmount(() => {
  disposed = true
  clearInterval(timer)
})
async function cancel() {
  try {
    await api(`/runs/${run.value!.id}/cancel`, { method: 'POST' })
    confirm.value = false
    notify('Cancellation requested')
    await load()
  }
  catch (e) {
    error.value = (e as Error).message
  }
}
async function resume() {
  try {
    await api(`/runs/${run.value!.id}/resume`, { method: 'POST' })
    tab.value = 'events'
    notify('Resuming saved conversation')
    await load()
  }
  catch (e) { error.value = (e as Error).message }
}
async function retry() {
  try {
    const next = await api(`/runs/${run.value!.id}/retry`, { method: 'POST' })
    if (props.embedded)
      emit('run', next.id)
    else
      router.push(`/runs/${next.id}`)
  }
  catch (e) {
    error.value = (e as Error).message
  }
}
async function cleanup() {
  try {
    await api(`/runs/${run.value!.id}/cleanup`, { method: 'POST' })
    confirmCleanup.value = false
    notify('Worktree removed. Its Git branch is preserved.')
    await load()
  }
  catch (e) {
    error.value = (e as Error).message
  }
}
async function copy() {
  try {
    await navigator.clipboard.writeText(run.value?.summary ?? '')
    notify('Result copied')
  }
  catch {
    error.value = 'Clipboard unavailable. Select and copy the result manually.'
  }
}
</script>

<template>
  <div class="run-workspace flex flex-col flex-1 min-w-0 min-h-0" :class="{ embedded }">
    <div v-if="!embedded" class="run-navigation flex items-center justify-between gap-3 mb-4 phone:mb-2.5">
      <RouterLink to="/runs" class="back-link inline-flex items-center gap-[7px] text-muted text-xs mb-[25px]">
        <Icon :name="ArrowLeft" :size="16" />Back to runs
      </RouterLink>
      <UiButton v-if="run && active" variant="danger-outline" @click="confirm = true">
        <Icon :name="Square" :size="15" />Stop run
      </UiButton>
      <div v-else-if="run" class="flex flex-wrap justify-end gap-2">
        <RouterLink v-if="run.trigger === 'chat'" :to="`/chats/${run.taskId}`" class="text-sm text-accent">
          Open chat
        </RouterLink>
        <UiButton v-else size="small" @click="retry">
          Run again
        </UiButton>
        <UiButton v-if="run.resumeAvailable && run.trigger !== 'chat'" size="small" variant="primary" @click="resume">
          <Icon :name="RotateCw" :size="16" />Resume
        </UiButton>
      </div>
    </div>
    <UiAlert v-if="error">
      {{ error }}
    </UiAlert>
    <template v-if="run">
      <div class="page-heading flex items-center justify-between gap-5 mb-[27px] phone:gap-2.5 phone:flex-wrap phone:mb-[21px]">
        <div>
          <span v-if="embedded" class="eyebrow block text-xs tracking-[1.6px] font-bold text-muted mb-3 phone:text-2xs phone:tracking-[1.3px] phone:mb-[9px]">{{ run.snapshot.projects?.map(project => project.name).join(', ') || run.snapshot.project?.name || 'Agent workspace' }}</span>
          <component :is="embedded ? 'h2' : 'h1'" :title="run.snapshot.task.name">
            {{ run.snapshot.task.name }}
          </component>
          <div class="run-title-meta flex items-center gap-3 mt-3.5 text-subtle text-xs phone:flex-wrap phone:text-xs">
            <Status :status="run.status" /><span v-if="run.codexAccountName">{{ run.codexAccountName }}</span><span>{{ run.snapshot.agent.name }} · {{ date(run.createdAt) }}</span>
          </div>
        </div>
      </div>
      <p v-if="run.accountWaitReason" role="status" class="mb-3 shrink-0 text-sm text-warning">
        {{ run.accountWaitReason }}
      </p>
      <UiButton v-if="embedded && run.resumeAvailable && !active" class="mb-3 self-start" size="small" @click="resume">
        Resume conversation
      </UiButton>
      <section class="panel run-panel flex flex-1 min-h-0 flex-col overflow-hidden">
        <header class="run-panel-head flex shrink-0 items-center justify-between border-b border-line p-[7px] phone:p-[5px]">
          <UiSegments v-model="tab" label="Run view" :options="[{ value: 'result', label: 'Result', icon: FileText }, { value: 'events', label: 'Activity', icon: Terminal, count: events.length }, { value: 'brief', label: 'Task brief' }]" />
        </header>
        <div v-if="tab === 'result' && run.summary" class="run-panel-actions flex items-center justify-end border-b border-line px-5 py-2 phone:px-4 phone:py-1">
          <UiButton
            v-if="tab === 'result' && run.summary"
            size="small"
            aria-label="Copy result"
            @click="copy"
          >
            <Icon :name="Copy" :size="16" />Copy result
          </UiButton>
        </div>
        <div v-if="tab === 'result'" class="result-content flex-1 min-h-0 overflow-auto overscroll-contain [scrollbar-width:thin] text-sm leading-[1.8] p-7.5 phone:p-5.5">
          <ArtifactGallery v-if="deliverables.length" class="mb-6" :items="deliverables.filter(item => !deliverables.some(other => other.key === item.key && other.version > item.version))" @open="artifactViewer = $event.id" />
          <Markdown v-if="run.summary" :content="run.summary" />
          <div v-else class="mini-empty flex flex-col items-center text-center pt-7 pb-8.5 text-subtle px-6">
            <span class="pulse-ring w-8 h-8 rounded-full border-2 border-line [border-top-color:light-dark(#6660a5,_var(--dark-border))] animate-spin mb-[17px]" />
            <h3>{{ active ? "Work is underway" : "No summary yet" }}</h3>
            <p>
              Open Activity to follow this run.
            </p>
          </div>
        </div>
        <ActivityFeed v-else-if="tab === 'events'" :deliverables="deliverables" :events="events" :active="!!active" :agent="run.snapshot.agent.name" :task="run.snapshot.task.name" :more="moreEvents" :loading="loading" :trimmed="trimmedEvents" :preview="embedded" @load="load" />
        <div v-else class="result-content flex-1 min-h-0 overflow-auto overscroll-contain [scrollbar-width:thin] text-sm leading-[1.8] p-7.5 phone:p-5.5">
          <div class="run-facts grid grid-cols-[repeat(4,_1fr)] border border-line bg-raised rounded-[10px] text-xs text-subtle phone:grid-cols-2 phone:gap-5 px-6 py-5 mx-0 my-6.5">
            <span>Project<strong>{{ run.snapshot.projects?.map(project => project.name).join(', ') || run.snapshot.project?.name || 'Agent workspace' }}</strong></span><span>Duration<strong>{{ duration(run.startedAt, run.finishedAt) }}</strong></span><span>Triggered by<strong>{{ run.trigger }}</strong></span><span>Model<strong>{{ run.snapshot.agent.model || 'Codex default' }}</strong></span>
          </div>
          <h3>Original instructions</h3>
          <pre class="brief-text whitespace-pre-wrap text-xs leading-[1.9] text-muted">{{ run.snapshot.task.prompt }}</pre>
          <h3>Workspace</h3>
          <code>{{
            run.workspace
              || (run.workspaceCleanedAt ? "Worktree cleaned up" : "Not prepared yet")
          }}</code>
          <UiButton
            v-if="!active && run.workspace && run.snapshot.task.worktree && !run.isolated"
            size="small"
            @click="confirmCleanup = true"
          >
            Clean up worktree
          </UiButton>
          <p v-if="!active && run.isolated && run.snapshot.task.worktree" class="muted text-muted">
            Workspace and conversation are saved in a private VM disk. Resume this run to continue working.
          </p>
          <h3>Selected skills</h3>
          <p>
            {{
              run.snapshot.skills.map((s) => s.name).join(", ")
                || "No skills selected"
            }}
          </p>
          <h3>Execution access</h3>
          <p>
            {{ run.snapshot.agent.access?.sandbox === 'workspace-write' ? 'Workspace write' : run.snapshot.agent.access?.sandbox === 'read-only' ? 'Read only' : 'YOLO mode' }} ·
            {{ run.isolated ? 'Isolated container' : 'Shared workspace' }} ·
            {{ run.snapshot.agent.timeoutMinutes }} minute limit
          </p>
        </div>
      </section>
      <p v-if="!embedded" class="muted text-muted run-id text-2xs mt-[17px] wrap-anywhere">
        Run {{ run.id
        }}<span v-if="run.sessionId"> · Codex session {{ run.sessionId }}</span>
      </p>
    </template><Modal v-if="confirm" title="Stop this run?" @close="confirm = false">
      <div class="modal-body px-6.5 py-6 phone:p-5">
        <p>
          The running process will stop. Files and external changes already made
          will remain, so review them before running the task again.
        </p>
      </div>
      <footer class="modal-actions flex justify-end gap-2.5 bg-surface border-t border-line sticky bottom-0 phone:flex-wrap px-6.5 py-4.5 phone:px-5 phone:py-4">
        <UiButton @click="confirm = false">
          Keep running
        </UiButton><UiButton variant="danger" @click="cancel">
          Stop run
        </UiButton>
      </footer>
    </Modal>
    <Modal
      v-if="confirmCleanup"
      title="Clean up this worktree?"
      @close="confirmCleanup = false"
    >
      <div class="modal-body px-6.5 py-6 phone:p-5">
        <p>
          This removes the saved worktree directory. Its Git branch and run
          history remain available. Worktrees containing changes or untracked
          files cannot be removed.
        </p>
        <UiAlert v-if="error">
          {{ error }}
        </UiAlert>
      </div>
      <footer class="modal-actions flex justify-end gap-2.5 bg-surface border-t border-line sticky bottom-0 phone:flex-wrap px-6.5 py-4.5 phone:px-5 phone:py-4">
        <UiButton @click="confirmCleanup = false">
          Keep worktree
        </UiButton><UiButton variant="danger" @click="cleanup">
          Clean up worktree
        </UiButton>
      </footer>
    </Modal>
  </div>
  <ArtifactViewer v-if="artifactViewer !== null" :items="deliverables" :initial="artifactViewer" @close="artifactViewer = null" />
</template>
