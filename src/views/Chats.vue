<script setup lang="ts">
import type { Chat, ChatAttachment, ChatDetail, ChatMessage, ChatView } from '../../shared/chats'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { MAIN_AGENT_ID } from '../../shared/constants'
import { api, state } from '../api'
import ActivityFeed from '../components/ActivityFeed.vue'
import ChatAttachments from '../components/ChatAttachments.vue'
import ChatQuestions from '../components/ChatQuestions.vue'
import Icon from '../components/Icon.vue'
import Modal from '../components/Modal.vue'
import ModelSettings from '../components/ModelSettings.vue'
import NotificationSettings from '../components/NotificationSettings.vue'
import UiAlert from '../components/UiAlert.vue'
import UiButton from '../components/UiButton.vue'
import VirtualSelect from '../components/VirtualSelect.vue'
import { Bell, Bot, ChevronDown, Clock, FolderGit2, MessageCircle, Paperclip, Pause, Pencil, Play, Plus, Send, Settings, Square, Trash2, X, Zap } from '../icons'
import { iconButton } from '../ui'
import { useLiveRun } from '../use-live-run'

const router = useRouter()
const route = useRoute()
const chats = ref<ChatView[]>([])
const detail = ref<(ChatDetail & { error?: string }) | null>(null)
const live = useLiveRun(() => route.params.id ? `/chats/${route.params.id}/stream` : '/chats/stream')
const { events, connectionNotice, catchingUp } = live
const deliverables = computed(() => live.snapshot.value?.artifacts ?? [])
const draft = ref('')
const model = ref('')
const reasoning = ref('')
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
const fileInput = ref<HTMLInputElement>()
const attachments = ref<ChatAttachment[]>([])
const previews = ref<Record<string, string>>({})
const files = new Map<string, File>()
const uploaded = new Set<string>()
const dragging = ref(0)
const uploadProgress = ref('')
const canSend = computed(() => (!route.params.id || detail.value?.id === route.params.id) && !live.error.value && (!!draft.value.trim() || attachments.value.length > 0))
function removeAttachment(id: string) {
  if (previews.value[id])
    URL.revokeObjectURL(previews.value[id])
  delete previews.value[id]
  files.delete(id)
  uploaded.delete(id)
  attachments.value = attachments.value.filter(attachment => attachment.id !== id)
}
function clearAttachments() {
  for (const attachment of attachments.value) removeAttachment(attachment.id)
}
function addFiles(selected: File[]) {
  if (busy.value)
    return
  if (attachments.value.length + selected.length > 8) {
    error.value = 'Attach up to 8 files per message.'
    return
  }
  if (selected.some(file => file.size > 10 * 1024 * 1024)) {
    error.value = 'Files must be 10 MB or smaller.'
    return
  }
  if ([...attachments.value, ...selected].reduce((total, file) => total + file.size, 0) > 40 * 1024 * 1024) {
    error.value = 'Attachments must total at most 40 MB per message.'
    return
  }
  error.value = ''
  for (const file of selected) {
    const id = crypto.randomUUID()
    const image = ['image/png', 'image/jpeg', 'image/webp', 'image/gif'].includes(file.type)
    files.set(id, file)
    if (image)
      previews.value[id] = URL.createObjectURL(file)
    attachments.value.push({ id, name: file.name || 'pasted-image.png', size: file.size, kind: image ? 'image' : 'file', mediaType: file.type, chatId: '' })
  }
}
function pickFiles(event: Event) {
  const input = event.target as HTMLInputElement
  addFiles(Array.from(input.files ?? []))
  input.value = ''
}
function dropFiles(event: DragEvent) {
  dragging.value = 0
  addFiles(Array.from(event.dataTransfer?.files ?? []))
}
function pasteFiles(event: ClipboardEvent) {
  const pasted = Array.from(event.clipboardData?.files ?? [])
  if (!pasted.length)
    return
  event.preventDefault()
  addFiles(pasted)
}
const active = computed(() => !!detail.value?.run && ['queued', 'running'].includes(detail.value.run.status))
const pending = computed(() => detail.value?.messages.filter(message => message.status !== 'delivered') ?? [])
const selectedAgent = computed(() => state.agents.find(agent => agent.id === (detail.value?.agentId || agentId.value)))
const agents = computed(() => state.agents.map(agent => ({ value: agent.id, label: agent.name, icon: Bot, description: agent.id === MAIN_AGENT_ID ? 'Full access' : agent.description })))
const projects = computed(() => [{ value: '', label: 'No project', description: 'Use the agent’s available workspaces', icon: FolderGit2 }, ...state.projects.filter(project => selectedAgent.value?.access.projects === null || selectedAgent.value?.access.projects.includes(project.id)).map(project => ({ value: project.id, label: project.name, icon: FolderGit2 }))])
const draftKey = `leo-chat-draft:${route.params.id || `new:${agentId.value}:${projectId.value}`}`
draft.value = sessionStorage.getItem(draftKey) ?? ''
watch(draft, value => sessionStorage.setItem(draftKey, value))
watch(agentId, () => {
  if (!projects.value.some(project => project.value === projectId.value))
    projectId.value = ''
})
watch(live.snapshot, (value) => {
  detail.value = value?.chat ?? null
  chats.value = value?.chats ?? []
})
watch(live.error, (value) => {
  if (value)
    error.value = value
})
onBeforeUnmount(clearAttachments)
let submission: { id: string, text: string, mode: 'queue' | 'steer', model: string, reasoning: string, attachmentIds: string[] } | undefined
let createdChat: Chat | undefined
async function send(mode: 'queue' | 'steer' = 'queue') {
  const originalDraft = draft.value
  const text = originalDraft.trim()
  if (!canSend.value || busy.value)
    return
  busy.value = true
  error.value = ''
  try {
    const chat = detail.value ?? createdChat ?? await api<Chat>('/chats', { method: 'POST', body: JSON.stringify({ agentId: agentId.value, projectId: projectId.value || null }) })
    createdChat = chat
    for (const [index, attachment] of attachments.value.entries()) {
      const file = files.get(attachment.id)
      if (!file || uploaded.has(attachment.id))
        continue
      uploadProgress.value = `Uploading ${index + 1} of ${attachments.value.length}…`
      const saved = await api<ChatAttachment>(`/chats/${chat.id}/attachments/${attachment.id}?name=${encodeURIComponent(attachment.name)}`, { method: 'PUT', headers: { 'Content-Type': 'application/octet-stream' }, body: file })
      Object.assign(attachment, saved)
      uploaded.add(attachment.id)
    }
    uploadProgress.value = ''
    const attachmentIds = attachments.value.map(attachment => attachment.id)
    // Retain the id after a network failure so retry cannot duplicate a message.
    if (!submission || submission.text !== text || submission.mode !== mode || submission.model !== model.value || submission.reasoning !== reasoning.value || submission.attachmentIds.join() !== attachmentIds.join())
      submission = { id: crypto.randomUUID(), text, mode, model: model.value, reasoning: reasoning.value, attachmentIds }
    await api(`/chats/${chat.id}/messages${editing.value ? `/${editing.value}` : ''}`, { method: editing.value ? 'PUT' : 'POST', body: JSON.stringify(submission) })
    if (draft.value === originalDraft)
      draft.value = ''
    editing.value = null
    clearAttachments()
    submission = undefined
    if (!detail.value) {
      sessionStorage.setItem(`leo-chat-draft:${chat.id}`, draft.value)
      await router.push(`/chats/${chat.id}`)
    }
    textarea.value?.focus()
  }
  catch (e) { error.value = (e as Error).message }
  finally {
    busy.value = false
    uploadProgress.value = ''
  }
}
async function action(name: 'pause' | 'stop', body?: object) {
  if (!detail.value || busy.value)
    return
  busy.value = true
  error.value = ''
  try {
    await api(`/chats/${detail.value.id}/${name}`, { method: 'POST', ...(body ? { body: JSON.stringify(body) } : {}) })
  }
  catch (e) { error.value = (e as Error).message }
  finally { busy.value = false }
}
async function update(message: ChatMessage, mode?: 'steer') {
  error.value = ''
  try {
    await api(`/chats/${detail.value!.id}/messages/${message.id}`, { method: mode ? 'PUT' : 'DELETE', ...(mode ? { body: JSON.stringify({ ...message, mode, attachmentIds: message.attachments?.map(attachment => attachment.id) ?? [] }) } : {}) })
  }
  catch (e) { error.value = (e as Error).message }
}
function edit(message: ChatMessage) {
  if (canSend.value && !editing.value) {
    error.value = 'Send or clear your draft before editing a queued message.'
    return
  }
  editing.value = message.id
  clearAttachments()
  attachments.value = [...(message.attachments ?? [])]
  draft.value = message.text
  model.value = message.model
  reasoning.value = message.reasoning || ''
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
  <div class="chat-page flex h-full min-h-0 flex-col gap-3 phone:gap-2">
    <header class="flex shrink-0 items-center justify-between gap-3">
      <div class="flex min-w-0 items-center gap-3">
        <button :class="iconButton" aria-label="Chat history" title="Show or hide chat history" :aria-expanded="history" @click="history = !history">
          <Icon :name="MessageCircle" :size="21" />
        </button>
        <h1 v-if="!detail" class="text-xs! font-normal! tracking-normal! text-muted">
          Chats
        </h1>
        <div v-else class="min-w-0">
          <h1 class="truncate text-sm! tracking-normal!">
            {{ detail.agentName }}
          </h1>
          <p class="m-0! flex items-center gap-2 text-[11px] text-muted">
            <span class="truncate phone:max-w-22">{{ detail.projectName || 'Agent workspace' }}</span>
            <span class="size-1 shrink-0 rounded-full" :class="active ? 'bg-accent' : 'bg-muted'" />
            <span>{{ detail.paused ? 'Paused' : active ? detail.run?.status === 'queued' ? 'Waiting' : 'Working' : 'Ready' }}</span>
          </p>
        </div>
      </div>
      <div class="flex items-center gap-2">
        <button :class="iconButton" aria-label="Question notifications" @click="notifications = true">
          <Icon :name="Bell" :size="20" />
        </button>
        <RouterLink to="/chats" class="flex shrink-0 items-center gap-2 whitespace-nowrap rounded-lg px-3 py-2 text-xs font-medium text-muted hover:bg-hover hover:text-ink" @click="draft = ''; clearAttachments(); agentId = MAIN_AGENT_ID; projectId = ''; createdChat = undefined; editing = null">
          <Icon :name="Plus" :size="16" />New chat
        </RouterLink>
      </div>
    </header>
    <div class="grid min-h-0 flex-1 gap-8 phone:gap-0" :class="history ? 'grid-cols-[210px_minmax(0,1fr)] tablet:grid-cols-1' : 'grid-cols-1'">
      <aside v-if="history" class="flex min-h-0 flex-col overflow-hidden" aria-label="Chat history">
        <div class="flex items-center justify-between p-4 text-xs font-semibold text-muted">
          Recent chats <button class="hidden! tablet:inline-flex!" :class="iconButton" aria-label="Close chat history" @click="history = false">
            <Icon :name="X" :size="16" />
          </button>
        </div>
        <div class="min-h-0 flex-1 overflow-auto p-2">
          <p v-if="!chats.length" class="px-3 py-5 text-xs text-muted">
            Your conversations live here.
          </p>
          <RouterLink v-for="chat in chats" :key="chat.id" :to="`/chats/${chat.id}`" class="mb-1 block rounded-lg px-3 py-3 hover:bg-hover" :class="chat.id === detail?.id ? 'bg-hover text-ink' : ''" @click="history = false">
            <span class="mb-1 block truncate text-xs font-semibold">{{ chat.title }}</span><span v-if="chat.pendingQuestions" class="mb-1 inline-block rounded-full bg-accent/12 px-2 py-0.5 text-[10px] font-semibold text-accent">{{ chat.pendingQuestions }} awaiting answer</span>
            <span class="flex items-center gap-1.5 truncate text-[10px] text-muted"><span v-if="chat.status === 'running'" class="size-1.5 shrink-0 rounded-full bg-accent" />{{ chat.agentName }}<span v-if="chat.projectName"> · {{ chat.projectName }}</span></span>
          </RouterLink>
        </div>
      </aside>
      <section class="flex min-h-0 min-w-0 flex-col overflow-hidden" :class="{ 'tablet:hidden': history }" aria-label="Chat workspace">
        <div v-if="route.params.id && !detail" role="status" class="flex flex-1 items-center justify-center text-sm text-muted">
          Loading conversation…
        </div>
        <div v-else-if="!detail?.run" class="flex min-h-0 flex-1 flex-col items-center justify-center overflow-auto px-6 pb-[8vh] pt-8 text-center phone:px-2 phone:py-5">
          <h2 class="mb-7 text-[30px] font-semibold tracking-tight phone:text-2xl">
            What are we building?
          </h2>
          <div v-if="!detail" class="flex flex-wrap justify-center gap-2">
            <button v-for="idea in ['Explore this project', 'Review recent changes', 'Help me build…']" :key="idea" class="rounded-full bg-surface px-4 py-2.5 text-xs text-muted hover:bg-hover hover:text-ink" @click="draft = idea; textarea?.focus()">
              {{ idea }}
            </button>
          </div>
        </div>
        <ActivityFeed v-else :deliverables="deliverables" :events="events" :active="active" :agent="detail.agentName" :task="detail.title" :more="false" :loading="catchingUp" :trimmed="0" chat />
        <div class="mx-auto w-full max-w-205 shrink-0 px-5 pb-1 pt-3 phone:px-0 phone:pt-2">
          <ChatQuestions v-if="detail" :questions="detail.questions || []" :active="active" :highlighted="typeof route.query.question === 'string' ? route.query.question : undefined" />
          <p v-if="connectionNotice" role="status" class="px-4 py-2 text-xs text-muted">
            {{ connectionNotice }}
          </p>
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
                <span class="min-w-0 flex-1 truncate" :title="message.text">{{ message.text || message.attachments?.[0]?.name }}<span v-if="message.attachments?.length" class="ml-2 text-muted">· {{ message.attachments.length }} attached</span></span>
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
          <form class="chat-composer relative rounded-2xl border border-line/60 bg-raised p-4 shadow-[0_4px_24px_#00000006] focus-within:border-accent/50 phone:p-3" @submit.prevent="send()" @dragenter.prevent="dragging++" @dragover.prevent @dragleave.prevent="dragging = Math.max(0, dragging - 1)" @drop.prevent="dropFiles" @paste="pasteFiles">
            <div v-if="dragging" class="pointer-events-none absolute inset-0 z-10 flex items-center justify-center gap-2 rounded-xl border-2 border-dashed border-accent bg-surface/95 text-sm font-semibold text-accent">
              <Icon :name="Paperclip" :size="20" />Drop files here
            </div>
            <div v-if="!detail" class="mb-3 flex max-w-100 items-center gap-1 phone:mb-2">
              <VirtualSelect v-model="agentId" :options="agents" label="Chat agent" hide-label compact variant="ghost" />
              <VirtualSelect v-model="projectId" :options="projects" label="Chat project" hide-label compact variant="ghost" />
            </div>
            <div v-if="editing" class="mb-2 flex items-center justify-between text-[11px] text-accent">
              Editing queued message<button type="button" :class="iconButton" aria-label="Cancel edit" @click="editing = null; draft = ''; clearAttachments()">
                <Icon :name="X" :size="14" />
              </button>
            </div>
            <ChatAttachments v-if="attachments.length" :attachments="attachments" :previews="previews" removable :disabled="busy" class="mb-3!" @remove="removeAttachment" />
            <div v-if="uploadProgress" class="mb-2 text-xs text-accent" role="status">
              {{ uploadProgress }}
            </div>
            <textarea ref="textarea" v-model="draft" aria-label="Message" :placeholder="active ? 'Add a follow-up…' : 'Message your agent…'" rows="2" maxlength="50000" class="block max-h-40 min-h-14 w-full resize-none border-0! bg-transparent! p-0! text-sm! phone:text-[16px]! shadow-none! outline-none! focus:ring-0!" @keydown="key" />
            <div v-if="options" class="mb-3 border-t border-line pt-3">
              <ModelSettings v-model:model="model" v-model:reasoning="reasoning" inherit :default-model="selectedAgent?.model" :default-reasoning="selectedAgent?.reasoning" :disabled="busy" /><p class="my-1! text-[10px] text-muted">
                Model and reasoning changes apply to the next turn.
              </p>
            </div>
            <div class="flex items-center justify-between gap-2 pt-2">
              <div class="flex items-center gap-1">
                <input ref="fileInput" type="file" multiple class="hidden" aria-label="Attach files" :disabled="busy" @change="pickFiles">
                <button type="button" :class="iconButton" aria-label="Add images or files" title="Add images or files · up to 8 files, 10 MB each" :disabled="busy || attachments.length >= 8" @click="fileInput?.click()">
                  <Icon :name="Paperclip" :size="18" />
                </button>
                <button type="button" class="flex items-center gap-1.5 rounded-md px-1 py-1 text-[10px] text-muted hover:text-accent" :aria-expanded="options" aria-label="Message options" @click="options = !options">
                  <Icon :name="Settings" :size="14" /><span class="max-w-28 truncate phone:hidden">{{ model || 'Agent default' }}</span>
                </button>
              </div>
              <div class="flex items-center gap-2">
                <button v-if="active && !editing" type="button" :class="iconButton" aria-label="Stop response" title="Stop response and pause queue" :disabled="busy" @click="action('stop')">
                  <Icon :name="Square" :size="14" />
                </button>
                <UiButton v-if="active && !editing" type="button" size="small" :disabled="busy || !canSend" aria-label="Steer now" title="Send into the current turn (Alt + Enter)" @click="send('steer')">
                  <Icon :name="Zap" :size="14" /><span class="phone:hidden">Steer now</span><span class="hidden phone:inline">Steer</span>
                </UiButton>
                <UiButton type="submit" variant="primary" size="small" :disabled="busy || !canSend">
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
