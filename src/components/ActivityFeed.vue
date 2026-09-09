<script setup lang="ts">
import type { RunEvent } from '../../shared/contracts'
import type { ActivityArtifact } from '../activity'
import { ArrowDown, Check, ChevronDown, CircleAlert, FileCode, Globe, Layers, Leaf, ListChecks, LoaderCircle, Maximize2, Minimize2, Sparkles, Terminal, Wrench } from '@lucide/vue'
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { activityEntries } from '../activity'
import ActivityCode from './ActivityCode.vue'
import ActivityContent from './ActivityContent.vue'
import '../activity.css'

const props = defineProps<{ events: RunEvent[], active: boolean, agent: string, task: string, more: boolean, loading: boolean, trimmed: number }>()
defineEmits<{ load: [] }>()
const entries = computed(() => activityEntries(props.events))
const follow = ref(true)
const fullscreen = ref(false)
const viewer = ref<HTMLDialogElement>()
const fullscreenHost = ref<HTMLElement>()
const fullscreenButton = ref<HTMLButtonElement>()
const scroller = ref<HTMLElement>()
const opened = ref(new Set<string>())
const expanded = ref(new Set<string>())
const raw = ref(new Set<string>())
const icons = { command: Terminal, files: FileCode, search: Globe, tool: Wrench, plan: ListChecks, thinking: Sparkles, notice: CircleAlert }
function toggle(set: Set<string>, id: string) {
  if (set.has(id))
    set.delete(id)
  else
    set.add(id)
}
function groupLabel(artifacts: ActivityArtifact[]) {
  const actions = artifacts.filter(item => item.kind !== 'notice')
  if (!actions.length)
    return artifacts.some(item => item.status === 'error') ? 'Session updates · needs attention' : 'Session updates'
  const kinds = [...new Set(actions.map(item => ({ command: 'terminal', files: 'files', search: 'research', tool: 'tools', plan: 'plan', thinking: 'thinking', notice: 'updates' })[item.kind]))]
  return kinds.map(value => value[0].toUpperCase() + value.slice(1)).join(' · ')
}
function jump() {
  scroller.value?.scrollTo({ top: scroller.value.scrollHeight })
}
function followChanged() {
  if (follow.value)
    jump()
}
function scrolled() {
  const el = scroller.value
  if (el && el.scrollHeight - el.scrollTop - el.clientHeight > 60)
    follow.value = false
}
watch(() => props.events.at(-1)?.id, async () => {
  if (!follow.value)
    return
  await nextTick()
  jump()
})
async function enterFullscreen() {
  viewer.value?.showModal()
  fullscreen.value = true
  await nextTick()
  fullscreenButton.value?.focus()
  if (follow.value)
    jump()
}
async function exitFullscreen() {
  fullscreen.value = false
  await nextTick()
  viewer.value?.close()
  // WebKit releases the dialog's inert state on the next rendering frame.
  await new Promise(requestAnimationFrame)
  fullscreenButton.value?.focus()
}
onBeforeUnmount(() => viewer.value?.close())
</script>

<template>
  <div class="activity-mount">
    <Teleport to="body">
      <dialog ref="viewer" class="activity-fullscreen" aria-label="Fullscreen activity" @cancel.prevent="exitFullscreen">
        <div ref="fullscreenHost" class="activity-fullscreen-host" />
      </dialog>
    </Teleport>
    <Teleport :to="fullscreenHost || 'body'" :disabled="!fullscreen">
      <section class="activity-feed" :class="{ 'is-fullscreen': fullscreen }" aria-label="Run conversation">
        <header class="activity-toolbar">
          <div class="activity-toolbar-title">
            <span class="activity-presence" :class="{ live: active }" /><span>{{ fullscreen ? task : 'Agent activity' }}<small>{{ active ? 'Working on your task' : 'The story behind this run' }}</small></span>
          </div>
          <div class="activity-toolbar-controls">
            <label class="checkbox"><input v-model="follow" type="checkbox" @change="followChanged">Follow output</label>
            <button ref="fullscreenButton" class="icon-button" :aria-label="fullscreen ? 'Exit fullscreen' : 'Open activity fullscreen'" @click="fullscreen ? exitFullscreen() : enterFullscreen()">
              <Minimize2 v-if="fullscreen" :size="19" /><Maximize2 v-else :size="19" />
            </button>
          </div>
        </header>
        <div ref="scroller" class="activity-scroll" @scroll="scrolled">
          <div class="activity-conversation">
            <div class="activity-intro">
              <span class="activity-avatar"><Leaf :size="19" /></span><div><strong>{{ agent }}</strong><span>Your agent’s updates, work, and discoveries.</span></div>
            </div>
            <p v-if="trimmed" class="activity-retention">
              Showing the latest {{ events.length.toLocaleString() }} events. {{ trimmed.toLocaleString() }} earlier events are outside this view.
            </p>
            <template v-for="entry in entries" :key="entry.id">
              <article v-if="entry.kind === 'message'" class="activity-message">
                <header><span class="message-dot" /><strong>{{ agent }}</strong><time>{{ new Date(entry.time).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) }}</time></header>
                <ActivityContent :content="entry.text" />
              </article>
              <section v-else class="activity-group" :class="{ expanded: opened.has(entry.id) }">
                <button class="activity-group-toggle" :aria-expanded="opened.has(entry.id)" :aria-controls="`activity-${entry.id}`" @click="toggle(opened, entry.id)">
                  <span class="activity-group-icon"><LoaderCircle v-if="active && entry.artifacts.some(item => item.status === 'running')" class="activity-spinning" :size="17" /><Layers v-else :size="17" /></span>
                  <span class="activity-group-label"><strong>{{ groupLabel(entry.artifacts) }}</strong><small>{{ entry.artifacts.length }} {{ entry.artifacts.length === 1 ? 'step' : 'steps' }}<span v-if="entry.artifacts.some(item => item.status === 'error')" class="activity-attention"> · Includes errors</span></small></span>
                  <ChevronDown class="activity-chevron" :size="17" />
                </button>
                <div v-if="opened.has(entry.id)" :id="`activity-${entry.id}`" class="activity-artifacts">
                  <article v-for="artifact in entry.artifacts" :key="artifact.id" class="activity-artifact" :data-status="artifact.status">
                    <button class="artifact-toggle" :aria-expanded="expanded.has(artifact.id)" @click="toggle(expanded, artifact.id)">
                      <component :is="icons[artifact.kind]" class="artifact-kind-icon" :size="17" />
                      <span class="artifact-heading"><strong>{{ artifact.title }}</strong><span v-if="artifact.subtitle" :class="{ 'artifact-command': artifact.kind === 'command' }">{{ artifact.subtitle }}</span></span>
                      <span class="artifact-status" :aria-label="artifact.status"><LoaderCircle v-if="artifact.status === 'running' && active" class="activity-spinning" :size="14" /><CircleAlert v-else-if="artifact.status === 'error'" :size="14" /><Check v-else-if="artifact.status === 'done'" :size="14" /></span>
                      <ChevronDown class="artifact-chevron" :class="{ rotated: expanded.has(artifact.id) }" :size="15" />
                    </button>
                    <div v-if="expanded.has(artifact.id)" class="artifact-body">
                      <ul v-if="artifact.files.length" class="artifact-files">
                        <li v-for="file in artifact.files" :key="file.path">
                          <FileCode :size="16" /><code>{{ file.path }}</code><span :data-change="file.kind">{{ file.kind }}</span>
                        </li>
                      </ul>
                      <ul v-if="artifact.tasks.length" class="artifact-plan">
                        <li v-for="(taskItem, index) in artifact.tasks" :key="index" :class="{ completed: taskItem.completed }">
                          <span><Check v-if="taskItem.completed" :size="13" /></span>{{ taskItem.text }}
                        </li>
                      </ul>
                      <ActivityContent v-for="(block, index) in artifact.blocks" :key="index" :label="block.label" :content="block.code" :language="block.language" />
                      <p v-if="!artifact.blocks.length && !artifact.files.length && !artifact.tasks.length" class="artifact-no-output">
                        {{ artifact.status === 'running' && active ? 'Waiting for output…' : 'No additional output for this step.' }}
                      </p>
                      <button class="artifact-raw-toggle" :aria-expanded="raw.has(artifact.id)" @click="toggle(raw, artifact.id)">
                        {{ raw.has(artifact.id) ? 'Hide' : 'View' }} event details
                      </button>
                      <ActivityCode v-if="raw.has(artifact.id)" label="Event details" :code="artifact.raw" language="json" />
                    </div>
                  </article>
                </div>
              </section>
            </template>
            <button v-if="more" class="button activity-load" :disabled="loading" @click="$emit('load')">
              {{ loading ? 'Loading activity…' : 'Load next activity' }}<ArrowDown :size="15" />
            </button>
            <div v-else-if="active" class="activity-working">
              <span class="activity-presence live" />{{ events.length ? 'Your agent is working…' : 'Waiting for the worker…' }}
            </div>
            <div v-else class="activity-end">
              <span />{{ events.length ? 'End of activity' : 'No activity recorded yet' }}<span />
            </div>
          </div>
        </div>
      </section>
    </Teleport>
  </div>
</template>
