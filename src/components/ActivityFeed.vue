<script setup lang="ts">
import type { Deliverable } from '../../shared/artifacts'
import type { RunEvent } from '../../shared/contracts'
import type { ActivityArtifact } from '../activity'
import { twMerge } from 'tailwind-merge'
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { latestArtifacts } from '../../shared/artifacts'
import { activityEntries } from '../activity'
import { deliveryEntries } from '../deliverables'
import { ArrowDown, ChevronDown, Layers, LoaderCircle, Maximize2, Minimize2, Zap } from '../icons'
import { iconButton } from '../ui'
import ActivityArtifactCard from './ActivityArtifactCard.vue'
import ActivityContent from './ActivityContent.vue'
import ArtifactGallery from './ArtifactGallery.vue'
import ArtifactViewer from './ArtifactViewer.vue'
import ChatAttachments from './ChatAttachments.vue'
import Icon from './Icon.vue'
import UiButton from './UiButton.vue'

const props = defineProps<{ events: RunEvent[], active: boolean, agent: string, task: string, more: boolean, loading: boolean, trimmed: number, preview?: boolean, chat?: boolean, deliverables?: Deliverable[] }>()
defineEmits<{ load: [] }>()
const entries = computed(() => deliveryEntries(activityEntries(props.events), props.deliverables ?? []))
const artifactViewer = ref<string | null>(null)
const follow = ref(true)
const fullscreen = ref(false)
const viewer = ref<HTMLDialogElement>()
const fullscreenHost = ref<HTMLElement>()
const fullscreenButton = ref<HTMLButtonElement>()
const scroller = ref<HTMLElement>()
const opened = ref(new Set<string>())
const expanded = ref(new Set<string>())
let previewed = false
let resizeObserver: ResizeObserver | undefined
watch(scroller, (element) => {
  resizeObserver?.disconnect()
  if (!element)
    return
  resizeObserver = new ResizeObserver(() => {
    if (follow.value)
      jump()
  })
  resizeObserver.observe(element)
})
onBeforeUnmount(() => resizeObserver?.disconnect())
watch(entries, (items) => {
  if (!props.preview || previewed)
    return
  const group = items.findLast(item => item.kind === 'group' && item.artifacts.some(artifact => artifact.kind !== 'notice'))
  if (!group || group.kind !== 'group')
    return
  opened.value.add(group.id)
  previewed = true
}, { immediate: true })
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
watch(entries, async () => {
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
  <div class="activity-mount flex flex-1 min-h-0 flex-col">
    <Teleport to="body">
      <dialog ref="viewer" class="activity-fullscreen fixed [inset:0] w-full max-w-none h-dvh max-h-none border-0 bg-raised text-ink p-0 m-0" aria-label="Fullscreen activity" @cancel.prevent="exitFullscreen">
        <div ref="fullscreenHost" class="activity-fullscreen-host h-full rounded-[0]" />
      </dialog>
    </Teleport>
    <Teleport :to="fullscreenHost || 'body'" :disabled="!fullscreen">
      <section class="activity-feed flex flex-1 min-h-0 min-w-0 flex-col overflow-hidden bg-transparent" :class="{ 'is-fullscreen': fullscreen }" aria-label="Run conversation">
        <header :class="chat ? 'py-0! phone:py-0!' : ''" class="activity-toolbar flex justify-between items-center gap-4.5 bg-transparent shrink-0 phone:gap-2.5 phone:flex-wrap px-6 py-[17px] phone:px-[15px] phone:py-[13px]">
          <div v-if="!chat" class="activity-toolbar-title flex items-center gap-[11px] font-semibold text-sm min-w-0 phone:[flex:1_1_160px]">
            <span class="activity-presence w-[7px] h-[7px] rounded-full bg-[light-dark(#8e8baa,_var(--dark-accent-surface))] shrink-0" :class="{ live: active }" /><span>{{ fullscreen ? task : chat ? (active ? 'Working' : 'Conversation') : 'Agent activity' }}</span>
          </div>
          <div class="activity-toolbar-controls ml-auto flex items-center gap-5 shrink-0 phone:flex-1 phone:justify-end phone:gap-4">
            <button v-if="deliverables?.length" class="rounded-md px-2 py-2 text-xs text-muted hover:bg-hover hover:text-ink" @click="artifactViewer = ''">
              Files · {{ latestArtifacts(deliverables).length }}
            </button>
            <button v-if="chat" :class="iconButton" aria-label="Follow output" :aria-pressed="follow" :title="follow ? 'Pause auto-scroll' : 'Follow latest output'" @click="follow = !follow; followChanged()">
              <Icon :name="ArrowDown" :size="17" />
            </button>
            <label v-else class="checkbox flex-row items-center gap-2 text-xs font-normal phone:text-xs phone:leading-[1.6] mx-0 my-[9px]"><input v-model="follow" type="checkbox" @change="followChanged">Follow output</label>
            <button ref="fullscreenButton" :class="twMerge(iconButton, 'icon-button')" :aria-label="fullscreen ? 'Exit fullscreen' : 'Open activity fullscreen'" @click="fullscreen ? exitFullscreen() : enterFullscreen()">
              <Icon v-if="fullscreen" :name="Minimize2" :size="19" /><Icon v-else :name="Maximize2" :size="19" />
            </button>
          </div>
        </header>
        <div ref="scroller" class="activity-scroll flex-1 min-h-0 overflow-auto overscroll-contain [scrollbar-width:thin] [scrollbar-color:var(--color-control)_transparent] focus-visible:outline-2 focus-visible:outline-offset-[-3px] focus-visible:outline-accent" tabindex="0" role="region" aria-label="Activity output" @scroll="scrolled">
          <div class="activity-conversation max-w-205 pt-5 pb-7 px-9 mx-auto my-0 phone:px-4 phone:py-6">
            <div v-if="!chat" class="activity-intro flex items-center gap-3 mb-7 phone:gap-2.5">
              <span class="activity-avatar bg-surface text-ink grid place-items-center w-[39px] h-[39px] rounded-card border border-line shrink-0"><Icon :name="Zap" :size="19" /></span><div><strong>{{ agent }}</strong></div>
            </div>
            <p v-if="trimmed" class="activity-retention text-2xs text-muted leading-[1.8]">
              Showing the latest {{ events.length.toLocaleString() }} events. {{ trimmed.toLocaleString() }} earlier events are outside this view.
            </p>
            <template v-for="entry in entries" :key="entry.id">
              <div v-if="entry.kind === 'deliverables'" class="my-5">
                <ArtifactGallery :items="entry.files" @open="artifactViewer = $event.id" />
              </div>
              <article v-else-if="entry.kind === 'message'" class="activity-message mt-6.5 mb-7.5 mx-0" :class="entry.role === 'user' ? 'ml-auto! max-w-[85%] rounded-2xl rounded-br-md bg-hover px-5 py-3' : ''">
                <header><span class="message-dot w-[5px] h-[5px] bg-[light-dark(#4f4c73,_var(--dark-accent-surface))] rounded-full" /><strong>{{ entry.role === 'user' ? 'You' : agent }}</strong><time :datetime="new Date(entry.time).toISOString()" :title="new Date(entry.time).toLocaleString()">{{ new Date(entry.time).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) }}</time></header>
                <p v-if="entry.role === 'user'" class="whitespace-pre-wrap text-sm leading-relaxed">
                  {{ entry.text }}
                </p>
                <ActivityContent v-else :content="entry.text" />
                <ChatAttachments v-if="entry.attachments?.length" :attachments="entry.attachments" class="mt-3!" />
              </article>
              <section v-else class="activity-group border-line/70 border rounded-xl bg-transparent overflow-hidden mx-0 my-4.5" :class="{ 'expanded': opened.has(entry.id), 'notice-only': entry.artifacts.every(item => item.kind === 'notice') }">
                <button class="activity-group-toggle bg-transparent flex items-center gap-[11px] w-full text-left border-0 text-ink cursor-pointer phone:gap-[9px] px-[17px] py-[15px] phone:px-3 phone:py-[13px]" :aria-expanded="opened.has(entry.id)" :aria-controls="`activity-${entry.id}`" @click="toggle(opened, entry.id)">
                  <span class="activity-group-icon w-8 h-8 grid place-items-center bg-transparent rounded-lg shrink-0"><Icon v-if="active && entry.artifacts.some(item => item.status === 'running')" :name="LoaderCircle" class="activity-spinning [animation:activity-spin_1.5s_linear_infinite] [@media(prefers-reduced-motion:_reduce)]:[animation:none]" :size="17" /><Icon v-else :name="Layers" :size="17" /></span>
                  <span class="activity-group-label min-w-0 flex-1"><strong>{{ groupLabel(entry.artifacts) }}</strong><small>{{ entry.artifacts.length }} {{ entry.artifacts.length === 1 ? 'step' : 'steps' }}<span v-if="entry.artifacts.some(item => item.status === 'error')" class="activity-attention text-danger"> · Includes errors</span></small></span>
                  <Icon :name="ChevronDown" class="activity-chevron [transition:transform_.18s] shrink-0" :size="17" />
                </button>
                <div v-if="opened.has(entry.id)" :id="`activity-${entry.id}`" class="activity-artifacts bg-transparent border-t border-line px-4 py-0 phone:px-3 phone:py-0">
                  <ActivityArtifactCard v-for="artifact in entry.artifacts" :key="artifact.id" :artifact="artifact" :active="active" :expanded="expanded.has(artifact.id)" @toggle="toggle(expanded, artifact.id)" />
                </div>
              </section>
            </template>
            <UiButton v-if="more" class="activity-load mt-6 mb-0 flex text-xs mx-auto" :disabled="loading" @click="$emit('load')">
              {{ loading ? 'Loading activity…' : 'Load next activity' }}<Icon :name="ArrowDown" :size="15" />
            </UiButton>
            <div v-else-if="active" class="activity-working flex items-center justify-center gap-3 text-muted text-3xs mt-7.5 mb-0.5 mx-0">
              <span class="activity-presence w-[7px] h-[7px] rounded-full bg-[light-dark(#8e8baa,_var(--dark-accent-surface))] shrink-0 live" />{{ events.length ? 'Your agent is working…' : 'Waiting for the worker…' }}
            </div>
            <div v-else-if="!chat" class="activity-end flex items-center justify-center gap-3 text-muted text-3xs mt-7.5 mb-0.5 mx-0">
              <span />{{ events.length ? 'End of activity' : 'No activity recorded yet' }}<span />
            </div>
          </div>
        </div>
      </section>
    </Teleport>
  </div>
  <ArtifactViewer v-if="artifactViewer !== null" :items="deliverables ?? []" :initial="artifactViewer" @close="artifactViewer = null" />
</template>
