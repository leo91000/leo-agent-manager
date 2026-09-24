<script setup lang="ts">
import type { GithubRepository, GithubRepositoryPage } from '../../shared/contracts'
import { computed, onMounted, ref } from 'vue'
import { api } from '../api'
import UiAlert from './UiAlert.vue'
import UiButton from './UiButton.vue'

defineProps<{ disabled?: boolean, selected: string }>()
const emit = defineEmits<{ select: [repository: GithubRepository] }>()
const repositories = ref<GithubRepository[]>([])
const query = ref('')
const loading = ref(false)
const error = ref('')
const nextPage = ref<number | null>(1)
const filtered = computed(() => repositories.value.filter(repo => `${repo.fullName} ${repo.description}`.toLowerCase().includes(query.value.toLowerCase())))
async function load() {
  if (loading.value || nextPage.value === null)
    return
  loading.value = true
  error.value = ''
  try {
    const page = await api<GithubRepositoryPage>(`/github/repositories?page=${nextPage.value}`)
    repositories.value.push(...page.repositories)
    nextPage.value = page.nextPage
  }
  catch (e) {
    error.value = (e as Error).message
  }
  finally {
    loading.value = false
  }
}
function select(repo: GithubRepository) {
  emit('select', repo)
}
onMounted(load)
</script>

<template>
  <section class="grid gap-3" aria-label="GitHub repositories" :aria-busy="loading">
    <p class="text-xs text-muted">
      Choose a repository from your shared GitHub connection. It will be cloned automatically when you save.
    </p>
    <label>Filter loaded repositories<input v-model="query" type="search" placeholder="Owner or repository name"></label>
    <UiAlert v-if="error">
      {{ error }} <RouterLink to="/connections" class="underline">
        Connections
      </RouterLink>
    </UiAlert>
    <div class="grid max-h-64 overflow-y-auto gap-1" role="group" aria-label="Available repositories">
      <button v-for="repo in filtered" :key="repo.fullName" type="button" class="rounded-lg border border-line px-3 py-2 text-left hover:bg-soft disabled:opacity-50" :aria-pressed="selected === repo.fullName" :class="selected === repo.fullName ? 'bg-soft border-accent' : ''" :disabled="disabled || repo.imported" @click="select(repo)">
        <span class="block break-all text-sm font-medium">{{ repo.fullName }}</span>
        <span class="block text-xs text-muted">{{ repo.private ? 'Private' : 'Public' }}{{ repo.archived ? ' · Archived' : '' }}{{ repo.imported ? ' · Already added' : '' }}</span>
        <span v-if="repo.description" class="block line-clamp-2 text-xs text-muted">{{ repo.description }}</span>
      </button>
    </div>
    <p v-if="!loading && !error && !filtered.length" class="text-sm text-muted">
      No matching repositories{{ nextPage ? ' in the loaded results. Load more to continue.' : '.' }}
    </p>
    <UiButton v-if="nextPage !== null" type="button" :disabled="disabled || loading" @click="load">
      {{ loading ? 'Loading repositories…' : error ? 'Retry' : 'Load more repositories' }}
    </UiButton>
  </section>
</template>
