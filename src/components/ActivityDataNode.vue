<script setup lang="ts">
import type { DataValue } from '../activity-data'
import { Check, ChevronRight, CircleAlert, FileCode } from '@lucide/vue'
import { computed, ref } from 'vue'
import { dataObject, dataSummary, fieldLabel, statusTone } from '../activity-data'

const props = withDefaults(defineProps<{ value: DataValue, depth?: number, field?: string }>(), { depth: 0, field: '' })
const limit = ref(8)
const opened = ref(new Set<string>())
const closed = ref(new Set<string>())
function isOpen(key: string) {
  return !closed.value.has(key) && (opened.value.has(key) || (props.depth < 2 && ['jobs', 'files'].includes(key)))
}
function toggled(key: string, event: Event) {
  const open = (event.target as HTMLDetailsElement).open
  if (open) {
    opened.value.add(key)
    closed.value.delete(key)
  }
  else {
    opened.value.delete(key)
    closed.value.add(key)
  }
}
const items = computed(() => Array.isArray(props.value) ? props.value.slice(0, limit.value) : [])
const fields = computed(() => dataObject(props.value) ? Object.entries(props.value) : [])
const scalars = computed(() => fields.value.filter(([, value]) => value === null || typeof value !== 'object'))
const nested = computed(() => fields.value.filter(([, value]) => value !== null && typeof value === 'object'))
function itemTitle(value: DataValue, index: number) {
  if (dataObject(value)) {
    for (const key of ['name', 'title', 'path', 'id']) {
      if (typeof value[key] === 'string' || typeof value[key] === 'number')
        return `${key === 'id' ? 'Run #' : ''}${value[key]}`
    }
  }
  return `Item ${index + 1}`
}
const checks = computed(() => Array.isArray(props.value) ? props.value.filter(item => dataObject(item) && typeof item.name === 'string' && typeof item.conclusion === 'string') : [])
const passed = computed(() => checks.value.filter(item => dataObject(item) && ['success', 'succeeded', 'passed'].includes(`${item.conclusion}`)).length)
function compactCheck(value: DataValue) {
  return dataObject(value) && typeof value.name === 'string' && typeof value.conclusion === 'string' && Object.keys(value).every(key => ['name', 'status', 'conclusion'].includes(key))
}
</script>

<template>
  <div class="data-node">
    <template v-if="Array.isArray(value)">
      <div v-if="checks.length" class="data-check-summary">
        <Check :size="15" /><strong>{{ passed }} passed</strong><span>{{ checks.length - passed }} other · {{ checks.length }} checks</span>
      </div>
      <p v-if="!value.length" class="data-empty">
        No items
      </p>
      <ol v-else class="data-list" :class="{ 'data-simple-list': items.every(item => item === null || typeof item !== 'object') }">
        <li v-for="(item, index) in items" :key="index">
          <template v-if="item === null || typeof item !== 'object'">
            <FileCode v-if="field.toLowerCase() === 'files'" :size="15" /><span>{{ dataSummary(item) }}</span>
          </template>
          <div v-else-if="compactCheck(item) && dataObject(item)" class="data-check-row">
            <span>{{ item.name }}</span><span class="data-check-outcome"><span class="data-status" :data-tone="statusTone('conclusion', item.conclusion)"><Check v-if="statusTone('conclusion', item.conclusion) === 'success'" :size="12" /><CircleAlert v-else-if="statusTone('conclusion', item.conclusion) === 'error'" :size="12" />{{ fieldLabel(dataSummary(item.conclusion)) }}</span><small v-if="item.status">{{ fieldLabel(dataSummary(item.status)) }}</small></span>
          </div>
          <template v-else>
            <div class="data-item-title">
              {{ itemTitle(item, index) }}
            </div>
            <ActivityDataNode v-if="depth < 12" :value="item" :depth="depth + 1" />
            <p v-else class="data-empty">
              {{ dataSummary(item) }} · Available in JSON view
            </p>
          </template>
        </li>
      </ol>
      <button v-if="value.length > limit" class="data-more" @click="limit += 50">
        Show more ({{ value.length - limit }} remaining)
      </button>
    </template>
    <template v-else-if="dataObject(value)">
      <p v-if="!fields.length" class="data-empty">
        No fields
      </p>
      <dl v-if="scalars.length" class="data-fields">
        <div v-for="([key, item]) in scalars" :key="key">
          <dt>{{ fieldLabel(key) }}</dt>
          <dd :class="{ 'data-mono': /(?:oid|sha|head|path)$/i.test(key) }">
            <span v-if="statusTone(key, item)" class="data-status" :data-tone="statusTone(key, item)"><Check v-if="statusTone(key, item) === 'success'" :size="12" /><CircleAlert v-else-if="statusTone(key, item) === 'error'" :size="12" />{{ fieldLabel(dataSummary(item)) }}</span>
            <span v-else>{{ dataSummary(item) }}</span>
          </dd>
        </div>
      </dl>
      <details v-for="([key, item]) in nested" :key="key" class="data-section" :open="isOpen(key)" @toggle="toggled(key, $event)">
        <summary><ChevronRight :size="14" /><strong>{{ fieldLabel(key) }}</strong><span>{{ dataSummary(item) }}</span></summary>
        <div v-if="isOpen(key) && depth < 12" class="data-section-body">
          <ActivityDataNode :value="item" :field="key" :depth="depth + 1" />
        </div>
        <p v-else-if="depth >= 12" class="data-empty">
          Available in JSON view
        </p>
      </details>
    </template>
    <span v-else>{{ dataSummary(value) }}</span>
  </div>
</template>
