<script setup lang="ts">
import { Sparkles } from '@lucide/vue'

defineProps<{ title: string, description?: string }>()
</script>

<template>
  <div class="empty">
    <div class="empty-icon">
      <Sparkles :size="27" />
    </div>
    <h3>{{ title }}</h3>
    <p v-if="description">
      {{ description }}
    </p>
    <slot />
  </div>
</template>
