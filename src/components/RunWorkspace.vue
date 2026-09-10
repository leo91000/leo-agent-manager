<script setup lang="ts">
import type { Run, RunEvent } from '../../shared/contracts'
import {
  ArrowLeft,
  Copy,
  FileText,
  RotateCw,
  Square,
  Terminal,
} from '@lucide/vue'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, date, duration, notify } from '../api'
import ActivityFeed from './ActivityFeed.vue'
import Markdown from './Markdown.vue'
import Modal from './Modal.vue'
import Status from './Status.vue'

const props = defineProps<{ runId: string, embedded?: boolean }>()
const emit = defineEmits<{ run: [id: string] }>()
let disposed = false
const router = useRouter()
const run = ref<Run>()
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
    if (active.value && !document.hidden)
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
  <div class="run-workspace" :class="{ embedded }">
    <div v-if="!embedded" class="run-navigation">
      <RouterLink to="/runs" class="back-link">
        <ArrowLeft :size="16" />Back to runs
      </RouterLink>
      <button v-if="run && active" class="button danger-outline" @click="confirm = true">
        <Square :size="15" />Stop run
      </button><button v-else-if="run" class="button" @click="retry">
        <RotateCw :size="16" />Run again
      </button>
    </div>
    <p v-if="error" class="error" role="alert">
      {{ error }}
    </p>
    <template v-if="run">
      <div class="page-heading">
        <div>
          <span v-if="embedded" class="eyebrow">{{ run.snapshot.projects?.map(project => project.name).join(', ') || run.snapshot.project?.name || 'Agent workspace' }}</span>
          <component :is="embedded ? 'h2' : 'h1'" :title="run.snapshot.task.name">
            {{ run.snapshot.task.name }}
          </component>
          <div class="run-title-meta">
            <Status :status="run.status" /><span>{{ run.snapshot.agent.name }} · {{ date(run.createdAt) }}</span>
          </div>
        </div>
      </div>
      <section class="panel run-panel">
        <header class="run-panel-head">
          <div class="tabs">
            <button
              :class="{ selected: tab === 'result' }"
              @click="tab = 'result'"
            >
              <FileText :size="16" />Result
            </button><button
              :class="{ selected: tab === 'events' }"
              @click="tab = 'events'"
            >
              <Terminal :size="16" />Activity<span>{{
                events.length
              }}</span>
            </button><button
              :class="{ selected: tab === 'brief' }"
              @click="tab = 'brief'"
            >
              Task brief
            </button>
          </div>
        </header>
        <div v-if="tab === 'result' && run.summary" class="run-panel-actions">
          <button
            v-if="tab === 'result' && run.summary"
            class="button small"
            aria-label="Copy result"
            @click="copy"
          >
            <Copy :size="16" />Copy result
          </button>
        </div>
        <div v-if="tab === 'result'" class="result-content">
          <Markdown v-if="run.summary" :content="run.summary" />
          <div v-else class="mini-empty">
            <span class="pulse-ring" />
            <h3>{{ active ? "Work is underway" : "No summary yet" }}</h3>
            <p>
              Open Activity to follow this run.
            </p>
          </div>
        </div>
        <ActivityFeed v-else-if="tab === 'events'" :events="events" :active="!!active" :agent="run.snapshot.agent.name" :task="run.snapshot.task.name" :more="moreEvents" :loading="loading" :trimmed="trimmedEvents" :preview="embedded" @load="load" />
        <div v-else class="result-content">
          <div class="run-facts">
            <span>Project<strong>{{ run.snapshot.projects?.map(project => project.name).join(', ') || run.snapshot.project?.name || 'Agent workspace' }}</strong></span><span>Duration<strong>{{ duration(run.startedAt, run.finishedAt) }}</strong></span><span>Triggered by<strong>{{ run.trigger }}</strong></span><span>Model<strong>{{ run.snapshot.agent.model || 'Codex default' }}</strong></span>
          </div>
          <h3>Original instructions</h3>
          <pre class="brief-text">{{ run.snapshot.task.prompt }}</pre>
          <h3>Workspace</h3>
          <code>{{
            run.workspace
              || (run.workspaceCleanedAt ? "Worktree cleaned up" : "Not prepared yet")
          }}</code>
          <button
            v-if="!active && run.workspace && run.snapshot.task.worktree && !run.isolated"
            class="button small"
            @click="confirmCleanup = true"
          >
            Clean up worktree
          </button>
          <p v-if="!active && run.isolated && run.snapshot.task.worktree" class="muted">
            Isolated clones are retained for review. Preserve your changes before removing their directories through the server terminal.
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
      <p v-if="!embedded" class="muted run-id">
        Run {{ run.id
        }}<span v-if="run.sessionId"> · Codex session {{ run.sessionId }}</span>
      </p>
    </template><Modal v-if="confirm" title="Stop this run?" @close="confirm = false">
      <div class="modal-body">
        <p>
          The running process will stop. Files and external changes already made
          will remain, so review them before running the task again.
        </p>
      </div>
      <footer class="modal-actions">
        <button class="button" @click="confirm = false">
          Keep running
        </button><button class="button danger" @click="cancel">
          Stop run
        </button>
      </footer>
    </Modal>
    <Modal
      v-if="confirmCleanup"
      title="Clean up this worktree?"
      @close="confirmCleanup = false"
    >
      <div class="modal-body">
        <p>
          This removes the saved worktree directory. Its Git branch and run
          history remain available. Worktrees containing changes or untracked
          files cannot be removed.
        </p>
        <p v-if="error" class="error" role="alert">
          {{ error }}
        </p>
      </div>
      <footer class="modal-actions">
        <button class="button" @click="confirmCleanup = false">
          Keep worktree
        </button><button class="button danger" @click="cleanup">
          Clean up worktree
        </button>
      </footer>
    </Modal>
  </div>
</template>
