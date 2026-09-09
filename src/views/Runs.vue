<script setup lang="ts">
import { ArrowUpRight, CircleCheck, CirclePause, Clock, ListFilter, LoaderCircle, RefreshCw, Square, TriangleAlert } from '@lucide/vue'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api, date, duration } from '../api'
import Empty from '../components/Empty.vue'
import Status from '../components/Status.vue'
import VirtualSelect from '../components/VirtualSelect.vue'

const runs = ref<any[]>([])
const status = ref('')
const error = ref('')
const offset = ref(0)
const busy = ref(false)
const outcomes = [
  { value: '', label: 'All outcomes', icon: ListFilter },
  { value: 'queued', label: 'Queued', icon: Clock, group: 'In progress' },
  { value: 'running', label: 'Running', icon: LoaderCircle, group: 'In progress' },
  { value: 'succeeded', label: 'Succeeded', icon: CircleCheck, group: 'Finished' },
  { value: 'failed', label: 'Failed', icon: TriangleAlert, group: 'Finished' },
  { value: 'cancelled', label: 'Cancelled', icon: Square, group: 'Finished' },
  { value: 'interrupted', label: 'Interrupted', icon: CirclePause, group: 'Finished' },
]
async function load() {
  busy.value = true
  try {
    runs.value = await api(
      `/runs?offset=${offset.value}&limit=30${status.value ? `&status=${status.value}` : ''}`,
    )
    error.value = ''
  }
  catch (e) {
    error.value = (e as Error).message
  }
  finally {
    busy.value = false
  }
}
watch(status, () => {
  offset.value = 0
  load()
})
watch(offset, load)
let timer: ReturnType<typeof setInterval>
onMounted(() => {
  load()
  timer = setInterval(() => {
    if (!document.hidden)
      load()
  }, 5000)
})
onBeforeUnmount(() => clearInterval(timer))
</script>

<template>
  <div class="page-heading">
    <div>
      <span class="eyebrow">EVERY STEP, ACCOUNTED FOR</span>
      <h1>Run history</h1>
      <p>The progress, outcomes, and details behind each assignment.</p>
    </div>
    <button class="button" :disabled="busy" @click="load">
      <RefreshCw :size="16" />Refresh
    </button>
  </div>
  <div class="toolbar">
    <span class="muted">{{ runs.length }} runs on this page</span><div class="inline-label">
      <span>Status</span><VirtualSelect v-model="status" label="Status" :options="outcomes" compact hide-label clearable />
    </div>
  </div>
  <p v-if="error" class="error" role="alert">
    {{ error }}
  </p>
  <div v-if="runs.length" class="panel table-wrap">
    <table class="run-table">
      <thead>
        <tr>
          <th>Task</th>
          <th>Outcome</th>
          <th>Started</th>
          <th>Duration</th>
          <th>Trigger</th>
          <th><span class="sr-only">Details</span></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="run in runs" :key="run.id">
          <td>
            <RouterLink :to="`/runs/${run.id}`" class="table-name">
              {{ run.taskName }}
            </RouterLink><small>{{ run.agentName }}</small>
          </td>
          <td data-label="Outcome">
            <Status :status="run.status" />
          </td>
          <td data-label="Started">
            {{ date(run.createdAt) }}
          </td>
          <td data-label="Duration">
            {{ duration(run.startedAt, run.finishedAt) }}
          </td>
          <td data-label="Trigger">
            <span class="pill muted-pill">{{ run.trigger }}</span>
          </td>
          <td>
            <RouterLink
              :to="`/runs/${run.id}`"
              class="icon-button"
              :aria-label="`View ${run.taskName}`"
            >
              <ArrowUpRight :size="18" />
            </RouterLink>
          </td>
        </tr>
      </tbody>
    </table>
  </div>
  <Empty
    v-else
    title="A clean slate"
    description="Once an agent starts working, you’ll find its progress and final result here."
  />
  <div v-if="offset || runs.length === 30" class="pagination">
    <button
      class="button"
      :disabled="offset === 0"
      @click="offset = Math.max(0, offset - 30)"
    >
      Previous
    </button><span>Page {{ offset / 30 + 1 }}</span><button class="button" :disabled="runs.length < 30" @click="offset += 30">
      Next
    </button>
  </div>
</template>
