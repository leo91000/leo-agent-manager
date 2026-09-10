<script setup lang="ts">
import type { Chat, ChatDetail, ChatMessage, ChatView } from '../../shared/chats'
import type { RunEvent } from '../../shared/contracts'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { MAIN_AGENT_ID } from '../../shared/constants'
import { api, state } from '../api'
import ActivityFeed from '../components/ActivityFeed.vue'
import ChatQuestions from '../components/ChatQuestions.vue'
import Icon from '../components/Icon.vue'
import Modal from '../components/Modal.vue'
import NotificationSettings from '../components/NotificationSettings.vue'
import UiAlert from '../components/UiAlert.vue'
import UiButton from '../components/UiButton.vue'
import VirtualSelect from '../components/VirtualSelect.vue'
import { Bell, Bot, ChevronDown, Clock, FolderGit2, MessageCircle, Pause, Pencil, Play, Plus, Send, Settings, Square, Trash2, X, Zap } from '../icons'
import { iconButton } from '../ui'

const router = useRouter()
const route = useRoute()
const chats = ref<ChatView[]>([])
const detail = ref<(ChatDetail & { error?: string }) | null>(null)
const events = ref<RunEvent[]>([])
const draft = ref('')
const model = ref('')
const options = ref(false)
const notifications = ref(false)
const history = ref(false)
const queueOpen = ref(true)
const error = ref('')
const busy = ref(false)
const editing = ref<string | null>(null)
const agentId = ref(typeof route.query.agent === 'string' ? route.query.agent : MAIN_AGENT_ID)
const projectId = ref(typeof route.query.project === 'string' ? route.query.project : '')
const textarea = ref<HTMLTextAreaElement>()
const active = computed(() => !!detail.value?.run && ['queued', 'running'].includes(detail.value.run.status))
const pending = computed(() => detail.value?.messages.filter(message => message.status !== 'delivered') ?? [])
const selectedAgent = computed(() => state.agents.find(agent => agent.id === agentId.value))
const agents = computed(() => state.agents.map(agent => ({ value: agent.id, label: agent.name, icon: Bot, description: agent.id === MAIN_AGENT_ID ? 'Full access' : agent.description })))
const projects = computed(() => [{ value: '', label: 'No project', description: 'Use the agent’s available workspaces', icon: FolderGit2 }, ...state.projects.filter(project => selectedAgent.value?.access.projects === null || selectedAgent.value?.access.projects.includes(project.id)).map(project => ({ value: project.id, label: project.name, icon: FolderGit2 }))])
const draftKey = `leo-chat-draft:${route.params.id || `new:${agentId.value}:${projectId.value}`}`
draft.value = sessionStorage.getItem(draftKey) ?? ''
watch(draft, value => sessionStorage.setItem(draftKey, value))
watch(agentId, () => {
  if (!projects.value.some(project => project.value === projectId.value))
    projectId.value = ''
})
let disposed = false
let loading = false
let timer: ReturnType<typeof setInterval> | undefined
async function load() {
  if (loading || disposed || document.hidden)
    return
  loading = true
  try {
    const list = await api<ChatView[]>('/chats')
    if (disposed)
      return
    chats.value = list
    if (route.params.id) {
      const result = await api<ChatDetail & { error?: string }>(`/chats/${route.params.id}`)
      if (disposed)
        return
      detail.value = result
      if (result.run) {
        // Incremental batches keep polling inexpensive even for long chats.
        const batch = await api<RunEvent[]>(`/runs/${result.run.id}/events?after=${events.value.at(-1)?.id ?? 0}&limit=500`)
        if (!disposed)
          events.value.push(...batch)
      }
    }
  }
  catch (e) {
    if (!disposed)
      error.value = (e as Error).message
  }
  finally { loading = false }
}
onMounted(() => {
  void load()
  timer = setInterval(load, 1200)
})
onBeforeUnmount(() => {
  disposed = true
  clearInterval(timer)
})
let submission: { id: string, text: string, mode: 'queue' | 'steer', model: string } | undefined
let createdChat: Chat | undefined
async function send(mode: 'queue' | 'steer' = 'queue') {
  const originalDraft = draft.value
  const text = originalDraft.trim()
  if (!text || busy.value)
    return
  busy.value = true
  error.value = ''
  try {
    const chat = detail.value ?? createdChat ?? await api<Chat>('/chats', { method: 'POST', body: JSON.stringify({ agentId: agentId.value, projectId: projectId.value || null }) })
    createdChat = chat
    // Retain the id after a network failure so retry cannot duplicate a message.
    if (!submission || submission.text !== text || submission.mode !== mode || submission.model !== model.value)
      submission = { id: crypto.randomUUID(), text, mode, model: model.value }
    await api(`/chats/${chat.id}/messages${editing.value ? `/${editing.value}` : ''}`, { method: editing.value ? 'PUT' : 'POST', body: JSON.stringify(submission) })
    if (draft.value === originalDraft)
      draft.value = ''
    editing.value = null
    submission = undefined
    if (!detail.value) {
      sessionStorage.setItem(`leo-chat-draft:${chat.id}`, draft.value)
      await router.push(`/chats/${chat.id}`)
    }
    else {
      await load()
    }
    textarea.value?.focus()
  }
  catch (e) { error.value = (e as Error).message }
  finally { busy.value = false }
}
async function action(name: 'pause' | 'stop', body?: object) {
  if (!detail.value || busy.value)
    return
  busy.value = true
  error.value = ''
  try {
    await api(`/chats/${detail.value.id}/${name}`, { method: 'POST', ...(body ? { body: JSON.stringify(body) } : {}) })
    await load()
  }
  catch (e) { error.value = (e as Error).message }
  finally { busy.value = false }
}
async function update(message: ChatMessage, mode?: 'steer') {
  error.value = ''
  try {
    await api(`/chats/${detail.value!.id}/messages/${message.id}`, { method: mode ? 'PUT' : 'DELETE', ...(mode ? { body: JSON.stringify({ ...message, mode }) } : {}) })
    await load()
  }
  catch (e) { error.value = (e as Error).message }
}
function edit(message: ChatMessage) {
  if (draft.value.trim() && !editing.value) {
    error.value = 'Send or clear your draft before editing a queued message.'
    return
  }
  editing.value = message.id
  draft.value = message.text
  model.value = message.model
  textarea.value?.focus()
}
function key(event: KeyboardEvent) {
  if (event.key !== 'Enter' || event.shiftKey || event.isComposing)
    return
  // Touch keyboards keep Enter for newlines. The visible send button is always available.
  if (window.matchMedia('(pointer: coarse)').matches && !event.ctrlKey && !event.metaKey)
    return
  event.preventDefault()
  void send(event.altKey && active.value ? 'steer' : 'queue')
}
</script>

<template>
  <Modal v-if="notifications" title="Notifications" @close="notifications = false">
    <div class="p-6">
      <NotificationSettings />
    </div>
  </Modal>
  <div class="flex h-full min-h-0 flex-col gap-5 phone:gap-3">
    <header class="flex shrink-0 items-center justify-between gap-3">
      <div class="flex min-w-0 items-center gap-3">
        <button :class="iconButton" aria-label="Chat history" :aria-expanded="history" @click="history = !history">
          <Icon :name="MessageCircle" :size="21" />
        </button>
        <h1 class="truncate text-2xl! tracking-tight!">
          Chats<span class="text-accent">.</span>
        </h1>
      </div>
      <div class="flex items-center gap-2">
        <button :class="iconButton" aria-label="Question notifications" @click="notifications = true">
          <Icon :name="Bell" :size="20" />
        </button>
        <RouterLink to="/chats" class="flex items-center gap-2 rounded-lg border border-line bg-surface px-3 py-2 text-xs font-semibold hover:bg-soft" @click="draft = ''; agentId = MAIN_AGENT_ID; projectId = ''; createdChat = undefined; editing = null">
          <Icon :name="Plus" :size="16" />New chat
        </RouterLink>
      </div>
    </header>
    <div class="grid min-h-0 flex-1 grid-cols-[230px_minmax(0,1fr)] gap-5 tablet:grid-cols-1 phone:gap-0">
      <aside class="flex min-h-0 flex-col overflow-hidden rounded-2xl border border-line bg-surface tablet:hidden" :class="{ 'tablet:flex!': history, 'tablet:flex-1': history }" aria-label="Chat history">
        <div class="flex items-center justify-between p-4 text-xs font-semibold text-muted">
          Recent chats <button class="hidden! tablet:inline-flex!" :class="iconButton" aria-label="Close chat history" @click="history = false">
            <Icon :name="X" :size="16" />
          </button>
        </div>
        <div class="min-h-0 flex-1 overflow-auto p-2">
          <p v-if="!chats.length" class="px-3 py-5 text-xs text-muted">
            Your conversations live here.
          </p>
          <RouterLink v-for="chat in chats" :key="chat.id" :to="`/chats/${chat.id}`" class="mb-1 block rounded-xl border border-transparent px-3 py-3 hover:bg-soft" :class="chat.id === detail?.id ? 'border-accent/30! bg-accent/8 text-accent' : ''" @click="history = false">
            <span class="mb-1 block truncate text-xs font-semibold">{{ chat.title }}</span><span v-if="chat.pendingQuestions" class="mb-1 inline-block rounded-full bg-accent/12 px-2 py-0.5 text-[10px] font-semibold text-accent">{{ chat.pendingQuestions }} awaiting answer</span>
            <span class="flex items-center gap-1.5 truncate text-[10px] text-muted"><span v-if="chat.status === 'running'" class="size-1.5 shrink-0 rounded-full bg-accent" />{{ chat.agentName }}<span v-if="chat.projectName"> · {{ chat.projectName }}</span></span>
          </RouterLink>
        </div>
      </aside>
      <section class="flex min-h-0 min-w-0 flex-col overflow-hidden rounded-2xl border border-line bg-surface shadow-[3px_3px_0_var(--color-line)] phone:shadow-none" :class="{ 'tablet:hidden': history }" aria-label="Chat workspace">
        <header v-if="detail" class="flex shrink-0 items-center gap-3 border-b border-line px-5 py-3 phone:px-3">
          <span class="grid size-9 shrink-0 place-items-center rounded-xl bg-soft text-accent"><Icon :name="Bot" :size="20" /></span>
          <div class="min-w-0 flex-1">
            <h2 class="truncate text-sm">
              {{ detail.agentName }}
            </h2><p class="m-0! truncate text-[11px] text-muted">
              {{ detail.projectName || 'Agent workspace' }}
            </p>
          </div>
          <span class="flex items-center gap-1.5 text-[10px] font-medium text-muted"><span class="size-1.5 rounded-full" :class="active ? 'bg-accent' : 'bg-muted'" />{{ detail.paused ? 'Paused' : active ? detail.run?.status === 'queued' ? 'Waiting' : 'Working' : 'Ready' }}</span>
        </header>
        <div v-if="!detail?.run" class="flex min-h-0 flex-1 flex-col items-center justify-center overflow-auto px-6 py-8 text-center phone:px-4 phone:py-5">
          <div class="mb-5 grid size-14 rotate-[-6deg] place-items-center rounded-2xl border border-accent/30 bg-accent/10 text-accent shadow-[3px_3px_0_var(--color-line)]">
            <Icon :name="MessageCircle" :size="28" />
          </div>
          <h2 class="mb-2 text-2xl tracking-tight">
            What are we building?
          </h2>
          <p class="mt-0! mb-7! text-sm text-muted">
            A quick question. A fresh idea. Your next release.
          </p>
          <div v-if="!detail" class="grid w-full max-w-115 grid-cols-2 gap-3 phone:grid-cols-1">
            <div class="text-left">
              <VirtualSelect v-model="agentId" :options="agents" label="Chat agent" />
            </div>
            <div class="text-left">
              <VirtualSelect v-model="projectId" :options="projects" label="Chat project" />
            </div>
          </div>
          <div v-if="!detail" class="mt-5 flex flex-wrap justify-center gap-2">
            <button v-for="idea in ['Explore this project', 'Review recent changes', 'Help me build…']" :key="idea" class="rounded-full border border-line px-3 py-2 text-[11px] text-muted hover:border-accent hover:text-accent" @click="draft = idea; textarea?.focus()">
              {{ idea }}
            </button>
          </div>
        </div>
        <ActivityFeed v-else :events="events" :active="active" :agent="detail.agentName" :task="detail.title" :more="false" :loading="false" :trimmed="0" chat />
        <div class="shrink-0 border-t border-line bg-surface p-4 phone:p-3">
          <ChatQuestions v-if="detail" :questions="detail.questions || []" :active="active" :highlighted="typeof route.query.question === 'string' ? route.query.question : undefined" @answered="load" />
          <UiAlert v-if="error || detail?.error || detail?.run?.status === 'failed'" class="mb-3">
            {{ error || detail?.error || detail?.run?.summary }}<button :class="iconButton" aria-label="Dismiss error" @click="error = ''">
              <Icon :name="X" :size="14" />
            </button>
          </UiAlert>
          <div v-if="detail?.run?.accountWaitReason" class="mb-2 text-xs text-muted" role="status">
            {{ detail.run.accountWaitReason }}
          </div>
          <div v-if="pending.length || detail?.paused" class="mb-3 overflow-hidden rounded-xl border border-line bg-soft">
            <div class="flex items-center gap-2 px-3 py-2">
              <button class="flex min-w-0 flex-1 items-center gap-2 text-left text-[11px] font-semibold" :aria-expanded="queueOpen" @click="queueOpen = !queueOpen">
                <Icon :name="Clock" :size="14" />{{ pending.length }} queued<Icon :name="ChevronDown" :size="13" :class="queueOpen ? 'rotate-180' : ''" />
              </button>
              <button class="flex items-center gap-1 text-[11px] text-accent" :disabled="busy" @click="action('pause', { paused: !detail?.paused })">
                <Icon :name="detail?.paused ? Play : Pause" :size="13" />{{ detail?.paused ? 'Resume' : 'Pause queue' }}
              </button>
            </div>
            <ul v-if="queueOpen" class="max-h-32 overflow-auto border-t border-line px-3 py-1">
              <li v-for="message in pending" :key="message.id" class="flex items-center gap-1.5 py-1.5 text-xs">
                <span class="min-w-0 flex-1 truncate" :title="message.text">{{ message.text }}</span>
                <template v-if="message.status === 'queued'">
                  <button v-if="active && message.mode !== 'steer'" :class="iconButton" aria-label="Steer with this message" title="Steer now" @click="update(message, 'steer')">
                    <Icon :name="Zap" :size="14" />
                  </button>
                  <button v-if="!message.questionId" :class="iconButton" aria-label="Edit queued message" @click="edit(message)">
                    <Icon :name="Pencil" :size="14" />
                  </button>
                  <button :class="iconButton" aria-label="Remove queued message" @click="update(message)">
                    <Icon :name="Trash2" :size="14" />
                  </button>
                </template>
                <span v-else class="text-[10px] text-muted">Sending…</span>
              </li>
            </ul>
          </div>
          <form class="rounded-xl border border-line bg-raised p-3 focus-within:border-accent/60 focus-within:ring-2 focus-within:ring-accent/10" @submit.prevent="send()">
            <div v-if="editing" class="mb-2 flex items-center justify-between text-[11px] text-accent">
              Editing queued message<button type="button" :class="iconButton" aria-label="Cancel edit" @click="editing = null; draft = ''">
                <Icon :name="X" :size="14" />
              </button>
            </div>
            <textarea ref="textarea" v-model="draft" aria-label="Message" :placeholder="active ? 'Add a follow-up…' : 'Message your agent…'" rows="2" maxlength="50000" class="block max-h-40 min-h-14 w-full resize-none border-0! bg-transparent! p-0! text-sm! phone:text-[16px]! shadow-none! outline-none! focus:ring-0!" @keydown="key" />
            <div v-if="options" class="mb-3 border-t border-line pt-3">
              <label class="text-xs text-muted">Model for next message<input v-model="model" placeholder="Agent default" maxlength="120" aria-label="Message model" class="mt-1!" autocomplete="off"></label><p class="my-1! text-[10px] text-muted">
                Model changes take effect on the next turn.
              </p>
            </div>
            <div class="flex items-center justify-between gap-2 pt-2">
              <button type="button" class="flex items-center gap-1.5 rounded-md px-1 py-1 text-[10px] text-muted hover:text-accent" :aria-expanded="options" aria-label="Message options" @click="options = !options">
                <Icon :name="Settings" :size="14" /><span class="max-w-28 truncate phone:hidden">{{ model || 'Agent default' }}</span>
              </button>
              <div class="flex items-center gap-2">
                <button v-if="active && !editing" type="button" :class="iconButton" aria-label="Stop response" title="Stop response and pause queue" :disabled="busy" @click="action('stop')">
                  <Icon :name="Square" :size="14" />
                </button>
                <UiButton v-if="active && !editing" type="button" size="small" :disabled="busy || !draft.trim()" aria-label="Steer now" title="Send into the current turn (Alt + Enter)" @click="send('steer')">
                  <Icon :name="Zap" :size="14" />Steer now
                </UiButton>
                <UiButton type="submit" variant="primary" size="small" :disabled="busy || !draft.trim()">
                  <Icon :name="editing ? Pencil : active ? Plus : Send" :size="16" />{{ editing ? 'Save' : active || detail?.paused ? 'Queue' : 'Send' }}
                </UiButton>
              </div>
            </div>
          </form>
        </div>
      </section>
    </div>
  </div>
</template>
