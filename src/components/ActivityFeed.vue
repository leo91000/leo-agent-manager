<script setup lang="ts">
import type { RunEvent } from '../../shared/contracts'
import type { ActivityArtifact } from '../activity'
import { ArrowDown, ChevronDown, Layers, Leaf, LoaderCircle, Maximize2, Minimize2 } from '@lucide/vue'
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { activityEntries } from '../activity'
import ActivityArtifactCard from './ActivityArtifactCard.vue'
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
  const kinds = [...new Set(actions.map(item => ({ command: 'terminal', read: 'reading', browse: 'workspace', output: 'output', files: 'files', search: 'research', tool: 'tools', plan: 'plan', thinking: 'thinking', notice: 'updates' })[item.kind]))]
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
                  <ActivityArtifactCard v-for="artifact in entry.artifacts" :key="artifact.id" :artifact="artifact" :active="active" :expanded="expanded.has(artifact.id)" @toggle="toggle(expanded, artifact.id)" />
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
