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
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, date, duration, notify } from '../api'
import Markdown from '../components/Markdown.vue'
import Modal from '../components/Modal.vue'
import Status from '../components/Status.vue'

const route = useRoute()
const router = useRouter()
const run = ref<Run>()
const events = ref<RunEvent[]>([])
const moreEvents = ref(false)
const loading = ref(false)
const error = ref('')
const tab = ref('result')
const confirm = ref(false)
const confirmCleanup = ref(false)
const follow = ref(true)
const log = ref<HTMLElement>()
const active = computed(
  () => run.value && ['running', 'queued'].includes(run.value.status),
)
async function load() {
  if (loading.value)
    return
  loading.value = true
  try {
    error.value = ''
    run.value = await api(`/runs/${route.params.id}`)
    const items = await api<RunEvent[]>(
      `/runs/${route.params.id}/events?after=${events.value.at(-1)?.id ?? 0}`,
    )
    events.value.push(...items)
    moreEvents.value = items.length === 100
    if (events.value.length > 2000)
      events.value = events.value.slice(-2000)
    if (follow.value) {
      await nextTick()
      log.value?.scrollTo({ top: log.value.scrollHeight })
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
  if (active.value)
    tab.value = 'events'
  timer = setInterval(() => {
    if (active.value && !document.hidden)
      load()
  }, 1500)
})
onBeforeUnmount(() => clearInterval(timer))
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
  <RouterLink to="/runs" class="back-link">
    <ArrowLeft :size="16" />Back to runs
  </RouterLink>
  <p v-if="error" class="error" role="alert">
    {{ error }}
  </p>
  <template v-if="run">
    <div class="page-heading">
      <div>
        <span class="eyebrow">RUN DETAILS</span>
        <h1>{{ run.snapshot.task.name }}</h1>
        <div class="run-title-meta">
          <Status :status="run.status" /><span>{{ run.snapshot.agent.name }} · {{ date(run.createdAt) }}</span>
        </div>
      </div>
      <button
        v-if="active"
        class="button danger-outline"
        @click="confirm = true"
      >
        <Square :size="15" />Stop run
      </button><button v-else class="button" @click="retry">
        <RotateCw :size="16" />Run again
      </button>
    </div>
    <div class="run-facts">
      <span>Project<strong>{{ run.snapshot.project.name }}</strong></span><span>Duration<strong>{{
        duration(run.startedAt, run.finishedAt)
      }}</strong></span><span>Triggered by<strong>{{ run.trigger }}</strong></span><span>Model<strong>{{
        run.snapshot.agent.model || "Codex default"
      }}</strong></span>
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
      <div v-if="(tab === 'result' && run.summary) || tab === 'events'" class="run-panel-actions">
        <button
          v-if="tab === 'result' && run.summary"
          class="button small"
          aria-label="Copy result"
          @click="copy"
        >
          <Copy :size="16" />Copy result
        </button><label v-if="tab === 'events'" class="checkbox"><input v-model="follow" type="checkbox">Follow output</label>
      </div>
      <div v-if="tab === 'result'" class="result-content">
        <Markdown v-if="run.summary" :content="run.summary" />
        <div v-else class="mini-empty">
          <span class="pulse-ring" />
          <h3>{{ active ? "Work is underway" : "No summary yet" }}</h3>
          <p>
            The final response will appear here. Follow the Activity tab for
            progress.
          </p>
        </div>
      </div>
      <div v-else-if="tab === 'events'" ref="log" class="event-log">
        <button
          v-if="moreEvents"
          class="button"
          :disabled="loading"
          @click="load"
        >
          Load more activity
        </button>
        <div
          v-for="event in events"
          :key="event.id"
          class="event-row"
          :data-event-type="event.type"
        >
          <time>{{ new Date(event.createdAt).toLocaleTimeString() }}</time>
          <div>
            <span class="event-type">{{
              event.type.replaceAll(".", " · ")
            }}</span>
            <pre>{{ event.text }}</pre>
          </div>
        </div>
        <p v-if="!events.length" class="muted">
          Waiting for the worker to pick up this run…
        </p>
      </div>
      <div v-else class="result-content">
        <h3>Original instructions</h3>
        <pre class="brief-text">{{ run.snapshot.task.prompt }}</pre>
        <h3>Workspace</h3>
        <code>{{
          run.workspace
            || (run.workspaceCleanedAt ? "Worktree cleaned up" : "Not prepared yet")
        }}</code>
        <button
          v-if="!active && run.workspace && run.snapshot.task.worktree"
          class="button small"
          @click="confirmCleanup = true"
        >
          Clean up worktree
        </button>
        <h3>Selected skills</h3>
        <p>
          {{
            run.snapshot.skills.map((s) => s.name).join(", ")
              || "No skills selected"
          }}
        </p>
        <h3>Execution access</h3>
        <p>
          YOLO mode ·
          {{ run.snapshot.agent.timeoutMinutes }} minute limit
        </p>
      </div>
    </section>
    <p class="muted run-id">
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
</template>
