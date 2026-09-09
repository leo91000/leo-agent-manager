<script setup lang="ts">
import {
  Activity,
  ArrowRight,
  ArrowUpRight,
  Bot,
  CheckCircle2,
  Clock,
  Plus,
} from '@lucide/vue'
import { computed, onMounted, ref } from 'vue'
import { api, date, relative, state } from '../api'
import Empty from '../components/Empty.vue'
import Status from '../components/Status.vue'
import TaskEditor from '../components/TaskEditor.vue'

const data = ref<any>(null)
const error = ref('')
const creating = ref(false)
onMounted(async () => {
  try {
    data.value = await api('/overview')
  }
  catch (e) {
    error.value = (e as Error).message
  }
})
const next = computed(() =>
  state.tasks
    .filter(t => t.enabled && t.nextRun)
    .sort((a, b) => a.nextRun! - b.nextRun!)
    .slice(0, 3),
)
const today = new Intl.DateTimeFormat(undefined, {
  weekday: 'long',
  month: 'long',
  day: 'numeric',
}).format(new Date())
</script>

<template>
  <div class="page-heading">
    <div>
      <span class="eyebrow">{{ today }}</span>
      <h1>
        A little oversight.<br class="mobile-break">
        A lot of progress.
      </h1>
      <p>Here’s what’s happening across your workspace.</p>
    </div>
    <button class="button primary" @click="creating = true">
      <Plus :size="17" />New task
    </button>
  </div>
  <p v-if="error" class="error">
    {{ error }}
  </p>
  <section class="stat-grid">
    <article class="stat-card">
      <div><span>In motion</span><Activity :size="18" /></div>
      <strong>{{ data?.counts.running ?? 0 }}<small>running</small></strong>
      <p>{{ data?.counts.queued ?? 0 }} tasks waiting in the queue</p>
    </article>
    <article class="stat-card">
      <div><span>Completed</span><CheckCircle2 :size="18" /></div>
      <strong>{{ data?.counts.succeeded ?? 0 }}<small>runs</small></strong>
      <p>Work brought across the finish line</p>
    </article>
    <article class="stat-card">
      <div><span>On the calendar</span><Clock :size="18" /></div>
      <strong>{{ state.tasks.filter((t) => t.cron && t.enabled).length
      }}<small>schedules</small></strong>
      <p>Your recurring work, taken care of</p>
    </article>
    <article class="stat-card">
      <div><span>Your team</span><Bot :size="18" /></div>
      <strong>{{ state.agents.length }}<small>agents</small></strong>
      <p>Ready for their next assignment</p>
    </article>
  </section>
  <div class="overview-grid">
    <section class="panel">
      <header class="panel-heading">
        <div>
          <h2>Recent activity</h2>
          <p>A clear trail of what got done.</p>
        </div>
        <RouterLink to="/runs" class="text-link">
          All runs<ArrowUpRight :size="16" />
        </RouterLink>
      </header>
      <div v-if="data?.runs.length" class="activity-list">
        <RouterLink
          v-for="run in data.runs"
          :key="run.id"
          :to="`/runs/${run.id}`"
          class="activity-row"
        >
          <span class="activity-icon"><Bot :size="19" /></span>
          <div class="grow">
            <strong>{{ run.taskName }}</strong><small>{{ run.agentName }} · {{ relative(run.createdAt) }}</small>
          </div>
          <Status :status="run.status" /><ArrowUpRight
            :size="16"
            class="muted"
          />
        </RouterLink>
      </div>
      <Empty
        v-else
        title="Good work starts here"
        description="Your agents’ progress will appear here. Create a task and give them something worth doing."
      >
        <button class="button" @click="creating = true">
          Create your first task<ArrowRight :size="16" />
        </button>
      </Empty>
    </section>
    <div class="overview-side">
      <section class="panel">
        <header class="panel-heading">
          <h2>Coming up</h2>
          <Clock :size="17" class="muted" />
        </header>
        <div v-if="next.length" class="upcoming-list">
          <div v-for="task in next" :key="task.id">
            <span class="timeline-dot" /><strong>{{ task.name }}</strong><small>{{ date(task.nextRun) }}<br>{{ task.timezone }}</small>
          </div>
        </div>
        <div v-else class="mini-empty">
          <Clock :size="27" />
          <p>A little breathing room.</p>
          <small>Scheduled tasks will show up here.</small>
        </div>
      </section>
      <section class="idea-card">
        <span class="eyebrow">MAKE IT A HABIT</span>
        <h2>The best tasks<br>take care of themselves.</h2>
        <p>
          Dependency updates, weekly reviews, release checks. Set them up once
          and let your agents follow through.
        </p>
        <button class="text-link" @click="creating = true">
          Schedule something<ArrowUpRight :size="17" />
        </button>
        <div class="orbit-art" aria-hidden="true">
          <div />
          <div />
          <div />
        </div>
      </section>
    </div>
  </div>
  <section
    v-if="!state.projects.length || !state.agents.length"
    class="getting-started"
  >
    <div>
      <span class="eyebrow">BUILD YOUR WORKSPACE</span>
      <h2>Three small steps. A capable team.</h2>
    </div>
    <RouterLink to="/connections">
      <span>01</span>
      <div>
        <strong>Connect your accounts</strong><small>Use your Codex and GitHub logins</small>
      </div>
      <ArrowUpRight :size="18" />
    </RouterLink><RouterLink to="/agents">
      <span>02</span>
      <div>
        <strong>Meet your first agent</strong><small>Set its instructions and access</small>
      </div>
      <ArrowUpRight :size="18" />
    </RouterLink><RouterLink to="/projects">
      <span>03</span>
      <div>
        <strong>Give it a place to work</strong><small>Add a project workspace</small>
      </div>
      <ArrowUpRight :size="18" />
    </RouterLink>
  </section>
  <TaskEditor v-if="creating" @close="creating = false" />
</template>
