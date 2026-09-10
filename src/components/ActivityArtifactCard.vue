<script setup lang="ts">
import type { ActivityArtifact } from '../activity'
import { BookOpen, Check, ChevronDown, CircleAlert, Clock, FileCode, FolderSearch, Globe, Info, ListChecks, LoaderCircle, Search, Sparkles, Terminal, Wrench } from '@lucide/vue'
import { computed, ref, useId } from 'vue'
import ActivityCode from './ActivityCode.vue'
import ActivityContent from './ActivityContent.vue'
import Markdown from './Markdown.vue'
import '../activity-artifact.css'

const props = defineProps<{ artifact: ActivityArtifact, active: boolean, expanded: boolean }>()
defineEmits<{ toggle: [] }>()
const id = useId()
const raw = ref(false)
const commandOpen = ref(false)
const preview = ref(true)
function markdownParts(content: string) {
  const match = content.match(/^---\r?\n([\s\S]*?)\r?\n---(?:\r?\n|$)/)
  return { metadata: match?.[1] ?? '', body: match ? content.slice(match[0].length) : content }
}
const icons = { command: Terminal, read: BookOpen, browse: FolderSearch, output: Terminal, files: FileCode, search: Search, tool: Wrench, plan: ListChecks, thinking: Sparkles, notice: Info }
const labels = { command: 'Terminal', read: 'File read', browse: 'Workspace', output: 'Output', files: 'File edit', search: 'Search', tool: 'Tool call', plan: 'Plan', thinking: 'Thinking', notice: 'Session' }
const running = computed(() => props.artifact.status === 'running' && props.active)
const status = computed(() => props.artifact.statusLabel ?? (running.value ? 'Running' : props.artifact.status === 'running' ? 'Stopped' : props.artifact.status === 'error' ? 'Failed' : props.artifact.historical ? 'Recorded' : props.artifact.status === 'info' ? 'Info' : 'Completed'))
const blocks = computed(() => props.artifact.blocks.filter(block => block.label !== 'Command'))
const outputLines = computed(() => blocks.value.reduce((total, block) => total + (block.code ? block.code.trimEnd().split('\n').length : 0), 0))
const diff = computed(() => {
  let added = 0
  let removed = 0
  for (const block of props.artifact.blocks.filter(block => block.language === 'diff')) {
    for (const line of block.code.split('\n')) {
      if (line.startsWith('+') && !line.startsWith('+++'))
        added++
      if (line.startsWith('-') && !line.startsWith('---'))
        removed++
    }
  }
  return { added, removed }
})
const elapsed = computed(() => {
  const ms = props.artifact.durationMs
  if (ms === undefined)
    return ''
  if (ms < 1000)
    return `${ms} ms`
  return ms < 60000 ? `${(ms / 1000).toFixed(1)} s` : `${Math.floor(ms / 60000)}m ${Math.floor(ms % 60000 / 1000)}s`
})
</script>

<template>
  <article class="activity-artifact operation-card" :data-kind="artifact.kind" :data-status="artifact.status">
    <button class="artifact-toggle" :aria-expanded="expanded" :aria-controls="id" @click="$emit('toggle')">
      <span class="operation-icon"><Globe v-if="artifact.kind === 'search' && !artifact.command" :size="19" /><component :is="icons[artifact.kind]" v-else :size="19" /></span>
      <span class="artifact-heading"><span class="operation-label">{{ labels[artifact.kind] }}</span><strong>{{ artifact.title }}</strong><span v-if="artifact.subtitle" class="operation-description" :class="{ 'artifact-command': artifact.command || artifact.kind === 'read' }">{{ artifact.subtitle }}</span></span>
      <span class="operation-state" :data-state="status"><LoaderCircle v-if="running" class="activity-spinning" :size="13" /><CircleAlert v-else-if="artifact.status === 'error'" :size="13" /><Check v-else-if="status === 'Completed'" :size="13" /><span>{{ status }}</span></span>
      <ChevronDown class="artifact-chevron" :class="{ rotated: expanded }" :size="15" />
    </button>
    <div class="operation-facts">
      <span v-if="elapsed"><Clock :size="12" />{{ elapsed }}</span>
      <span v-if="artifact.exitCode !== undefined" :class="{ 'operation-error': artifact.status === 'error' }">Exit {{ artifact.exitCode }}</span>
      <span v-if="artifact.files.length">{{ artifact.files.length }} {{ artifact.files.length === 1 ? 'file' : 'files' }}</span>
      <span v-if="diff.added || diff.removed" class="operation-diff"><b>+{{ diff.added }}</b><b>−{{ diff.removed }}</b></span>
      <span v-if="outputLines">{{ outputLines.toLocaleString() }} {{ outputLines === 1 ? 'line' : 'lines' }}</span>
      <span v-if="artifact.cwd" class="operation-cwd">{{ artifact.cwd }}</span>
    </div>
    <div v-if="expanded" :id="id" class="artifact-body">
      <ul v-if="artifact.files.length" class="artifact-files">
        <li v-for="(file, index) in artifact.files" :key="`${file.path}:${index}`">
          <FileCode :size="16" /><code>{{ file.path }}</code><span :data-change="file.kind">{{ file.kind }}</span>
        </li>
      </ul>
      <ul v-if="artifact.tasks.length" class="artifact-plan">
        <li v-for="(task, index) in artifact.tasks" :key="index" :class="{ completed: task.completed }">
          <span><Check v-if="task.completed" :size="13" /></span>{{ task.text }}
        </li>
      </ul>
      <template v-if="artifact.command">
        <ActivityCode v-if="artifact.kind === 'command' || commandOpen" label="Command" :code="artifact.command" language="bash" />
        <button v-if="artifact.kind !== 'command'" class="operation-command-toggle" :aria-expanded="commandOpen" @click="commandOpen = !commandOpen">
          <Terminal :size="13" />{{ commandOpen ? 'Hide' : 'Show' }} command
        </button>
      </template>
      <template v-for="(block, index) in blocks" :key="index">
        <div v-if="artifact.kind === 'read' && block.language === 'markdown'" class="read-preview">
          <div class="read-preview-tabs">
            <button :aria-pressed="preview" @click="preview = true">
              Preview file
            </button><button :aria-pressed="!preview" @click="preview = false">
              View source
            </button>
          </div>
          <div v-if="preview" class="read-preview-content">
            <details v-if="markdownParts(block.code).metadata" class="read-metadata">
              <summary>File metadata</summary><ActivityCode label="Metadata" :code="markdownParts(block.code).metadata" language="yaml" />
            </details>
            <Markdown :content="markdownParts(block.code).body" />
          </div>
          <ActivityCode v-else :label="block.label" :code="block.code" language="markdown" />
        </div>
        <ActivityContent v-else :label="block.label" :content="block.code" :language="block.language" />
      </template>
      <p v-if="!blocks.length && !artifact.files.length && !artifact.tasks.length" class="artifact-no-output">
        {{ running ? 'Waiting for output…' : 'No output for this step.' }}
      </p>
      <p v-if="artifact.historical" class="operation-history">
        <Info :size="13" />This older step saved output only. Its command and exit code weren’t recorded.
      </p>
      <button class="artifact-raw-toggle" :aria-expanded="raw" @click="raw = !raw">
        {{ raw ? 'Hide' : 'View' }} event details
      </button>
      <ActivityCode v-if="raw" label="Event details" :code="artifact.raw" :language="artifact.historical ? 'plaintext' : 'json'" />
    </div>
  </article>
</template>
