<script setup lang="ts">
import type { Component, CSSProperties } from 'vue'
import type { SelectOption } from '../select'
import { Check, ChevronDown, LoaderCircle, Search, SearchX, X } from '@lucide/vue'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, useId, watch } from 'vue'
import { filterOptions, selectRows, visibleRows } from '../select'
import '../select.css'

const props = withDefaults(defineProps<{
  label: string
  options: SelectOption[]
  placeholder?: string
  searchPlaceholder?: string
  emptyText?: string
  icon?: Component
  required?: boolean
  disabled?: boolean
  loading?: boolean
  clearable?: boolean
  compact?: boolean
  hideLabel?: boolean
}>(), { placeholder: 'Select an option', emptyText: 'No options available' })
const model = defineModel<string>({ default: '' })
const id = useId()
const root = ref<HTMLElement>()
const input = ref<HTMLInputElement>()
const popup = ref<HTMLElement>()
const viewport = ref<HTMLElement>()
const open = ref(false)
const query = ref('')
const active = ref(-1)
const scrollTop = ref(0)
const viewportHeight = ref(280)
const position = ref<CSSProperties>({ visibility: 'hidden' })
const invalid = ref(false)
const selected = computed(() => props.options.find(option => option.value === model.value))
const filtered = computed(() => filterOptions(props.options, query.value))
const layout = computed(() => selectRows(filtered.value))
const rows = computed(() => visibleRows(layout.value.rows, scrollTop.value, viewportHeight.value))
const activeId = computed(() => rows.value.some(row => row.optionIndex === active.value) ? `${id}-option-${active.value}` : undefined)
const value = computed(() => open.value ? query.value : selected.value?.label ?? '')
const leadingIcon = computed(() => open.value ? Search : selected.value?.icon || props.icon)
let resize: ResizeObserver | undefined

function positionPopup() {
  if (!open.value || !input.value)
    return
  const anchor = input.value.parentElement!.getBoundingClientRect()
  const visual = window.visualViewport
  const left = visual?.offsetLeft ?? 0
  const top = visual?.offsetTop ?? 0
  const width = visual?.width ?? innerWidth
  const height = visual?.height ?? innerHeight
  const popupWidth = Math.min(Math.max(anchor.width, 290), width - 24)
  const below = top + height - anchor.bottom - 14
  const above = anchor.top - top - 14
  const desired = Math.min(layout.value.height || 96, 280) + 78
  const upwards = below < Math.min(desired, 210) && above > below
  const available = Math.max(90, upwards ? above : below)
  viewportHeight.value = Math.max(44, Math.min(layout.value.height || 96, 280, available - 78))
  const popupHeight = viewportHeight.value + 78
  position.value = {
    left: `${Math.max(left + 12, Math.min(anchor.left, left + width - popupWidth - 12))}px`,
    top: `${Math.max(top + 8, upwards ? anchor.top - popupHeight - 6 : anchor.bottom + 6)}px`,
    width: `${popupWidth}px`,
  }
}
function revealActive() {
  const row = layout.value.rows.find(row => row.optionIndex === active.value)
  if (!row || !viewport.value)
    return
  let next = viewport.value.scrollTop
  if (row.top < next)
    next = row.top
  else if (row.top + row.height > next + viewportHeight.value)
    next = row.top + row.height - viewportHeight.value
  viewport.value.scrollTop = next
  scrollTop.value = next
}
function firstEnabled(from = 0, direction = 1) {
  for (let index = from; index >= 0 && index < layout.value.options.length; index += direction) {
    if (!layout.value.options[index].disabled)
      return index
  }
  return -1
}
async function show(initialQuery = '') {
  if (props.disabled || open.value)
    return
  query.value = initialQuery
  open.value = true
  await nextTick()
  if (!open.value || !popup.value?.isConnected)
    return
  popup.value?.showPopover()
  positionPopup()
  const index = layout.value.options.findIndex(option => option.value === model.value && !option.disabled)
  active.value = index >= 0 ? index : firstEnabled()
  await nextTick()
  revealActive()
}
function close() {
  popup.value?.hidePopover()
  open.value = false
  query.value = ''
}
function choose(option: SelectOption) {
  if (option.disabled || props.loading)
    return
  model.value = option.value
  invalid.value = false
  close()
  input.value?.focus({ preventScroll: true })
}
function clear() {
  model.value = ''
  query.value = ''
  input.value?.focus({ preventScroll: true })
}
function updateQuery(event: Event) {
  const value = (event.target as HTMLInputElement).value
  if (!open.value)
    void show(value)
  else
    query.value = value
}
function keydown(event: KeyboardEvent) {
  if (event.isComposing)
    return
  if (event.key === 'Tab') {
    close()
    return
  }
  if (event.key === 'Escape' && open.value) {
    event.preventDefault()
    event.stopPropagation()
    close()
    return
  }
  if (event.key === 'Enter') {
    event.preventDefault()
    if (!open.value)
      void show()
    else if (active.value >= 0)
      choose(layout.value.options[active.value])
    return
  }
  if (['ArrowDown', 'ArrowUp', 'Home', 'End', 'PageDown', 'PageUp'].includes(event.key)) {
    if (!open.value && !event.key.startsWith('Arrow'))
      return
    event.preventDefault()
    if (!open.value) {
      void show()
      return
    }
    const direction = ['ArrowUp', 'End', 'PageUp'].includes(event.key) ? -1 : 1
    const step = event.key.startsWith('Page') ? Math.max(1, Math.floor(viewportHeight.value / 50)) : 1
    const start = event.key === 'Home' ? 0 : event.key === 'End' ? layout.value.options.length - 1 : Math.max(0, Math.min(layout.value.options.length - 1, active.value + direction * step))
    const index = firstEnabled(start, direction)
    if (index >= 0)
      active.value = index
    revealActive()
  }
  else if (!open.value && event.key.length === 1 && !event.ctrlKey && !event.metaKey && !event.altKey) {
    event.preventDefault()
    void show(event.key)
  }
}
function outside(event: Event) {
  if (open.value && event.target instanceof Node && !root.value?.contains(event.target))
    close()
}
function ancestorScroll(event: Event) {
  if (event.target !== viewport.value)
    positionPopup()
}
watch(query, async () => {
  active.value = firstEnabled()
  scrollTop.value = 0
  if (viewport.value)
    viewport.value.scrollTop = 0
  await nextTick()
  positionPopup()
})
watch(() => props.options, async () => {
  if (!open.value)
    return
  active.value = firstEnabled()
  await nextTick()
  positionPopup()
  revealActive()
})
watch(() => props.disabled, disabled => disabled && close())
watch(model, () => invalid.value = false)
onMounted(() => {
  document.addEventListener('pointerdown', outside, true)
  document.addEventListener('focusin', outside)
  document.addEventListener('scroll', ancestorScroll, true)
  window.addEventListener('resize', positionPopup)
  window.visualViewport?.addEventListener('resize', positionPopup)
  window.visualViewport?.addEventListener('scroll', positionPopup)
  resize = new ResizeObserver(positionPopup)
  if (root.value)
    resize.observe(root.value)
})
onBeforeUnmount(() => {
  close()
  resize?.disconnect()
  document.removeEventListener('pointerdown', outside, true)
  document.removeEventListener('focusin', outside)
  document.removeEventListener('scroll', ancestorScroll, true)
  window.removeEventListener('resize', positionPopup)
  window.visualViewport?.removeEventListener('resize', positionPopup)
  window.visualViewport?.removeEventListener('scroll', positionPopup)
})
</script>

<template>
  <div ref="root" class="virtual-select" :class="{ 'vs-compact': compact, 'vs-open': open, 'vs-disabled': disabled, 'vs-invalid': invalid }">
    <label v-if="!hideLabel" :for="`${id}-input`" class="vs-label">{{ label }}</label>
    <div class="vs-control">
      <span v-if="leadingIcon" class="vs-leading"><component :is="leadingIcon" :size="17" /></span>
      <input
        :id="`${id}-input`" ref="input" :value="value" role="combobox" :aria-label="label"
        :aria-expanded="open" :aria-controls="`${id}-list`" :aria-activedescendant="open && !loading ? activeId : undefined"
        aria-autocomplete="list" :aria-required="required" :aria-invalid="invalid || undefined" :aria-describedby="invalid ? `${id}-error` : undefined"
        :disabled="disabled" :placeholder="open ? searchPlaceholder || `Search ${label.toLowerCase()}…` : placeholder"
        autocomplete="off" autocapitalize="off" :spellcheck="false"
        @click="show()" @focus="($event.target as HTMLInputElement).select()" @input="updateQuery" @keydown="keydown" @compositionstart="show()"
      >
      <button v-if="clearable && model && !disabled" type="button" class="vs-clear" :aria-label="`Clear ${label.toLowerCase()}`" @click="clear">
        <X :size="14" />
      </button>
      <button type="button" class="vs-chevron" tabindex="-1" :disabled="disabled" :aria-label="`Toggle ${label.toLowerCase()} options`" @mousedown.prevent @click="input?.focus({ preventScroll: true }); open ? close() : show()">
        <LoaderCircle v-if="loading" class="vs-spin" :size="16" /><ChevronDown v-else :size="16" />
      </button>
    </div>
    <input v-if="required" class="vs-validation" tabindex="-1" aria-hidden="true" :value="selected && model ? model : ''" :disabled="disabled" required @invalid.prevent="invalid = true; input?.focus(); show()">
    <span v-if="invalid" :id="`${id}-error`" class="vs-error">Choose {{ label.toLowerCase() }} to continue.</span>
    <div ref="popup" popover="manual" class="vs-popup" :style="position" @keydown.esc.prevent.stop="close(); input?.focus({ preventScroll: true })">
      <header class="vs-popup-header">
        <span>{{ query ? 'Search results' : label }}</span><span class="vs-count">{{ filtered.length.toLocaleString() }} {{ filtered.length === 1 ? 'option' : 'options' }}</span>
      </header>
      <div :id="`${id}-list`" ref="viewport" class="vs-viewport" role="listbox" :aria-label="label" :aria-busy="loading" :style="{ height: `${viewportHeight}px` }" @scroll="scrollTop = ($event.target as HTMLElement).scrollTop">
        <div v-if="loading" class="vs-empty">
          <LoaderCircle class="vs-spin" :size="22" /><strong>Loading options…</strong>
        </div>
        <div v-else-if="!filtered.length" class="vs-empty">
          <SearchX :size="23" /><strong>{{ query ? 'No matches found' : emptyText }}</strong><span v-if="query">Try another name or keyword.</span>
        </div>
        <div v-else class="vs-spacer" :style="{ height: `${layout.height}px` }">
          <template v-for="row in rows" :key="row.key">
            <div v-if="row.group" class="vs-group" role="presentation" :style="{ top: `${row.top}px`, height: `${row.height}px` }">
              {{ row.group }}
            </div>
            <div v-else-if="row.option" :id="`${id}-option-${row.optionIndex}`" role="option" class="vs-option" :aria-setsize="layout.options.length" :aria-posinset="row.optionIndex! + 1" :aria-selected="row.option.value === model" :aria-disabled="row.option.disabled || undefined" :aria-label="row.option.label" :aria-description="[row.option.group, row.option.description].filter(Boolean).join(' · ')" :class="{ 'is-active': active === row.optionIndex, 'is-selected': row.option.value === model, 'is-disabled': row.option.disabled }" :style="{ top: `${row.top}px`, height: `${row.height}px` }" @pointermove="!row.option.disabled && (active = row.optionIndex!)" @mousedown.prevent @click="choose(row.option)">
              <span v-if="row.option.icon || icon" class="vs-option-icon"><component :is="row.option.icon || icon" :size="17" /></span>
              <span class="vs-option-copy"><strong>{{ row.option.label }}</strong><span v-if="row.option.description">{{ row.option.description }}</span></span>
              <Check v-if="row.option.value === model" class="vs-check" :size="16" /><span v-else-if="row.option.disabled" class="vs-unavailable">Unavailable</span>
            </div>
          </template>
        </div>
      </div>
      <footer class="vs-popup-footer">
        <span><kbd>↑</kbd><kbd>↓</kbd> Navigate <kbd>↵</kbd> Choose</span><button v-if="query" type="button" @mousedown.prevent @click="query = ''; input?.focus({ preventScroll: true })">
          Clear search
        </button><button v-else type="button" aria-label="Close options" @mousedown.prevent @click="close(); input?.focus({ preventScroll: true })">
          <kbd>esc</kbd> Close
        </button>
      </footer>
      <span class="sr-only" role="status">{{ loading ? 'Loading options' : `${filtered.length} options available` }}</span>
    </div>
  </div>
</template>
