<script setup lang="ts">
defineProps<{ status: string }>()
</script>

<template>
  <span class="status" :class="[status]"><span class="status-dot" />{{ status }}</span>
</template>
