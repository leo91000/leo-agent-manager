<script setup lang="ts">
import { X } from '@lucide/vue'
import { nextTick, onBeforeUnmount, onMounted, ref, useId } from 'vue'

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
      class="modal"
      :aria-labelledby="titleId"
      :class="[{ wide }]"
      @cancel.prevent="emit('close')"
      @click="
        (e) => {
          if (e.target === dialog) emit('close');
        }
      "
    >
      <div class="modal-inner">
        <header class="modal-head">
          <h2 :id="titleId">
            {{ title }}
          </h2>
          <button
            class="icon-button"
            aria-label="Close dialog"
            @click="emit('close')"
          >
            <X :size="20" />
          </button>
        </header>
        <slot />
      </div>
    </dialog>
  </Teleport>
</template>
