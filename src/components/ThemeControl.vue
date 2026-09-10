<script setup lang="ts">
import type { ThemePreference } from '../theme'
import { Check, Monitor, Moon, Sun } from '@lucide/vue'
import { nextTick, onBeforeUnmount, ref, useId } from 'vue'
import { setThemePreference, themePreference } from '../theme'

const props = defineProps<{ compact?: boolean }>()
const id = useId()
const trigger = ref<HTMLButtonElement>()
const popup = ref<HTMLElement>()
const choices = [
  { value: 'system' as const, label: 'System', description: 'Follow your device', icon: Monitor },
  { value: 'light' as const, label: 'Light', description: 'Light surfaces', icon: Sun },
  { value: 'dark' as const, label: 'Dark', description: 'Dark surfaces', icon: Moon },
]
function position() {
  if (!trigger.value || !popup.value)
    return
  const rect = trigger.value.getBoundingClientRect()
  popup.value.style.left = `${Math.max(12, Math.min(rect.right - 250, innerWidth - 262))}px`
  popup.value.style.top = `${Math.max(12, Math.min(rect.bottom + 9, innerHeight - popup.value.offsetHeight - 12))}px`
}
async function toggle() {
  if (popup.value?.matches(':popover-open')) {
    popup.value.hidePopover()
    return
  }
  popup.value?.showPopover()
  await nextTick()
  position()
  popup.value?.querySelector<HTMLInputElement>('input:checked')?.focus()
}
function choose(value: ThemePreference) {
  setThemePreference(value)
  if (props.compact) {
    popup.value?.hidePopover()
    trigger.value?.focus()
  }
}
function dismiss() {
  if (popup.value?.matches(':popover-open'))
    popup.value.hidePopover()
}
window.addEventListener('resize', dismiss)
onBeforeUnmount(() => window.removeEventListener('resize', dismiss))
</script>

<template>
  <div class="theme-control" :class="{ 'theme-compact': compact }">
    <button v-if="compact" ref="trigger" class="icon-button theme-trigger" :aria-label="`Appearance: ${themePreference}`" :title="`Appearance: ${themePreference}`" :popovertarget="id" @click.prevent="toggle">
      <Moon v-if="themePreference === 'dark'" :size="18" /><Sun v-else-if="themePreference === 'light'" :size="18" /><Monitor v-else :size="18" />
    </button>
    <div :id="id" ref="popup" :popover="compact ? 'auto' : undefined" :class="{ 'theme-popover': compact }">
      <fieldset class="theme-options">
        <legend :class="compact ? 'theme-menu-heading' : 'sr-only'">
          Appearance
        </legend>
        <label v-for="choice in choices" :key="choice.value" class="theme-choice" :class="{ selected: themePreference === choice.value }">
          <input type="radio" :name="`${id}-theme`" :value="choice.value" :checked="themePreference === choice.value" @change="choose(choice.value)">
          <span v-if="!compact" class="theme-preview" :data-preview="choice.value"><i /><span><i /><i /><i /></span></span>
          <span class="theme-choice-title"><component :is="choice.icon" :size="16" /><strong>{{ choice.label }}</strong><Check v-if="themePreference === choice.value" :size="14" /></span>
          <small>{{ choice.description }}</small>
        </label>
      </fieldset>
    </div>
  </div>
</template>
