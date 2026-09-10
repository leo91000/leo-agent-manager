<script setup lang="ts">
import { twMerge } from 'tailwind-merge'
import { nextTick, onBeforeUnmount, onMounted, ref, useId } from 'vue'
import { X } from '../icons'
import { iconButton } from '../ui'
import Icon from './Icon.vue'

defineProps<{ title: string, wide?: boolean }>()
const emit = defineEmits<{ close: [] }>()
const dialog = ref<HTMLDialogElement>()
const titleId = useId()
const previous = document.activeElement as HTMLElement | null
onMounted(async () => {
  await nextTick()
  dialog.value?.showModal()
})
onBeforeUnmount(() => previous?.focus())
</script>

<template>
  <Teleport to="body">
    <dialog
      ref="dialog"
      class="modal m-auto border border-line rounded-[15px] max-w-[min(560px,_calc(100vw_-_28px))] w-full max-h-[calc(100dvh_-_32px_-_env(safe-area-inset-top)_-_env(safe-area-inset-bottom))] bg-raised text-ink [box-shadow:0_25px_90px_light-dark(#12112335,_#00000035)] p-0"
      :aria-labelledby="titleId"
      :class="[{ wide }]"
      @cancel.prevent="emit('close')"
      @click="
        (e) => {
          if (e.target === dialog) emit('close');
        }
      "
    >
      <div class="modal-inner max-h-[calc(100dvh_-_34px_-_env(safe-area-inset-top)_-_env(safe-area-inset-bottom))] overflow-auto overscroll-contain">
        <header class="modal-head flex items-center justify-between border-b border-line sticky top-0 bg-raised z-2 px-6.5 py-5.5 phone:p-[19px]">
          <h2 :id="titleId">
            {{ title }}
          </h2>
          <button
            :class="twMerge(iconButton, 'icon-button')"
            aria-label="Close dialog"
            @click="emit('close')"
          >
            <Icon :name="X" :size="20" />
          </button>
        </header>
        <slot />
      </div>
    </dialog>
  </Teleport>
</template>
