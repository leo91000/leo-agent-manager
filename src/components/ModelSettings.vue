<script setup lang="ts">
import type { SelectOption } from '../select'
import { computed, onMounted } from 'vue'
import { effortLabel } from '../../shared/models'
import { Sparkles } from '../icons'
import { loadModels, modelCatalog } from '../models'
import VirtualSelect from './VirtualSelect.vue'

const props = withDefaults(defineProps<{ inherit?: boolean, defaultModel?: string, defaultReasoning?: string, disabled?: boolean }>(), { defaultModel: '', defaultReasoning: '' })
const model = defineModel<string>('model', { default: '' })
const reasoning = defineModel<string>('reasoning', { default: '' })
const effectiveModel = computed(() => model.value || props.defaultModel || modelCatalog.models.find(model => model.isDefault)?.model || '')
const selected = computed(() => modelCatalog.models.find(model => model.model === effectiveModel.value))
const modelOptions = computed(() => {
  const options: SelectOption[] = [
    { value: '', label: props.inherit ? 'Agent default' : 'Codex default', description: props.inherit ? props.defaultModel || 'Follow the agent’s model' : 'Follow Codex settings' },
    ...modelCatalog.models.filter(item => !item.hidden || item.model === model.value).map(item => ({ value: item.model, label: item.displayName, description: item.description, keywords: [item.model] })),
  ]
  if (model.value && !options.some(option => option.value === model.value))
    options.push({ value: model.value, label: model.value, description: 'Saved model · not in the current catalog', disabled: true })
  return options
})
const defaultEffort = computed(() => props.inherit && !model.value ? props.defaultReasoning || selected.value?.defaultReasoningEffort : selected.value?.defaultReasoningEffort)
const reasoningOptions = computed(() => {
  const options: SelectOption[] = [
    { value: '', label: `${props.inherit && !model.value ? 'Agent' : 'Model'} default${defaultEffort.value ? ` · ${effortLabel(defaultEffort.value)}` : ''}`, description: 'Use the recommended setting' },
    ...(selected.value?.supportedReasoningEfforts ?? []).map(effort => ({ value: effort.reasoningEffort, label: effortLabel(effort.reasoningEffort), description: effort.description })),
  ]
  if (reasoning.value && !options.some(option => option.value === reasoning.value))
    options.push({ value: reasoning.value, label: effortLabel(reasoning.value), description: 'Saved setting · not in the current catalog', disabled: true })
  return options
})
const chosenModel = computed({ get: () => model.value, set: (value: string) => {
  model.value = value
  reasoning.value = ''
} })
const unsupported = computed(() => reasoning.value && selected.value && !selected.value.supportedReasoningEfforts.some(e => e.reasoningEffort === reasoning.value))
onMounted(loadModels)
</script>

<template>
  <div class="col-span-full min-w-0">
    <div class="grid grid-cols-2 gap-3 phone:gap-2" :class="{ 'phone:grid-cols-1': !inherit }">
      <VirtualSelect v-model="chosenModel" :label="inherit ? 'Message model' : 'Model'" :options="modelOptions" :loading="modelCatalog.loading" :disabled="disabled" search-placeholder="Search models…" />
      <VirtualSelect v-model="reasoning" label="Reasoning" :options="reasoningOptions" :icon="inherit ? undefined : Sparkles" :disabled="disabled" :loading="modelCatalog.loading" />
    </div>
    <div v-if="modelCatalog.error || unsupported" class="mt-2 flex items-start justify-between gap-3 text-xs text-muted" role="status">
      <span>{{ unsupported ? 'Choose a supported reasoning level or use the model default.' : modelCatalog.error }}</span>
      <button v-if="modelCatalog.error" type="button" class="shrink-0 text-accent underline" :disabled="modelCatalog.loading" @click="loadModels">
        Retry
      </button>
    </div>
  </div>
</template>
