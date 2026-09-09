<script setup lang="ts">
import {
  Activity,
  ArrowUpRight,
  BookOpen,
  Bot,
  Check,
  FolderGit2,
  LayoutDashboard,
  Leaf,
  ListTodo,
  LogOut,
  Menu,
  Plug,
  Search,
  Settings,
} from '@lucide/vue'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, notify, refresh, session, state } from './api'
import Modal from './components/Modal.vue'

const router = useRouter()
const route = useRoute()
const password = ref('')
const setupToken = ref('')
const busy = ref(false)
const error = ref('')
const mobile = ref(false)
const searchOpen = ref(false)
const search = ref('')
const nav = [
  { to: '/', label: 'Overview', icon: LayoutDashboard },
  { to: '/tasks', label: 'Tasks', icon: ListTodo },
  { to: '/runs', label: 'Runs', icon: Activity },
  { to: '/agents', label: 'Agents', icon: Bot },
  { to: '/projects', label: 'Projects', icon: FolderGit2 },
  { to: '/skills', label: 'Skills', icon: BookOpen },
]
const results = computed(() =>
  [
    ...state.skills.map(skill => ({
      name: skill.name,
      type: 'Skill',
      to: '/skills',
    })),
    ...state.tasks.map(t => ({ name: t.name, type: 'Task', to: '/tasks' })),
    ...state.agents.map(t => ({
      name: t.name,
      type: 'Agent',
      to: '/agents',
    })),
    ...state.projects.map(t => ({
      name: t.name,
      type: 'Project',
      to: '/projects',
    })),
  ]
    .filter(t => t.name.toLowerCase().includes(search.value.toLowerCase()))
    .slice(0, 12),
)
onMounted(async () => {
  try {
    await session()
    if (state.authenticated)
      await refresh()
  }
  catch (e) {
    error.value = (e as Error).message
    state.ready = true
  }
  document.addEventListener('keydown', key)
})
onBeforeUnmount(() => document.removeEventListener('keydown', key))
watch(
  () => route.path,
  () => (mobile.value = false),
)
function key(event: KeyboardEvent) {
  if ((event.metaKey || event.ctrlKey) && event.key === 'k') {
    event.preventDefault()
    if (state.authenticated)
      searchOpen.value = !searchOpen.value
  }
}
async function login() {
  busy.value = true
  error.value = ''
  try {
    const value = await api(state.setupRequired ? '/setup' : '/login', {
      method: 'POST',
      body: JSON.stringify({
        password: password.value,
        setupToken: setupToken.value,
      }),
    })
    Object.assign(state, value)
    state.setupRequired = false
    password.value = ''
    await refresh()
  }
  catch (e) {
    error.value = (e as Error).message
  }
  finally {
    busy.value = false
  }
}
async function logout() {
  try {
    await api('/logout', { method: 'POST' })
    state.authenticated = false
    state.csrf = ''
    notify('Signed out')
  }
  catch (e) {
    error.value = (e as Error).message
  }
}
</script>

<template>
  <div v-if="!state.ready" class="loading-screen">
    Opening your workspace…
  </div>
  <main v-else-if="!state.authenticated" class="auth-screen">
    <div class="auth-story">
      <div class="wordmark">
        <span class="logo-mark"><Leaf :size="25" /></span>leo<span
          class="wordmark-tag"
        >AGENT MANAGER</span>
      </div>
      <div>
        <span class="eyebrow">LESS SUPERVISION. MORE MOMENTUM.</span>
        <h1>A little direction.<br>A lot of progress.</h1>
        <p>
          Your agents, projects, and ideas.<br>One thoughtful place to put
          them to work.
        </p>
      </div>
      <span class="auth-foot">Your infrastructure. Your accounts. Your work.</span>
    </div>
    <div class="auth-form">
      <div class="auth-card">
        <span class="eyebrow">YOUR CONTROL ROOM</span>
        <h2>
          {{ state.setupRequired ? "Make yourself at home." : "Welcome back." }}
        </h2>
        <p>
          {{
            state.setupRequired
              ? "Create your administrator account to get started."
              : "Sign in to see what your agents have been working on."
          }}
        </p>
        <form @submit.prevent="login">
          <label v-if="state.setupRequired">Setup token<input
            v-model="setupToken"
            type="password"
            required
            autocomplete="off"
            placeholder="From your server’s setup-token file"
          ><small>Stored in the data directory on your server.</small></label><label>Password<input
            v-model="password"
            type="password"
            required
            :minlength="state.setupRequired ? 12 : 1"
            :autocomplete="
              state.setupRequired ? 'new-password' : 'current-password'
            "
            placeholder="At least 12 characters"
          ></label>
          <p v-if="error" class="error" role="alert">
            {{ error }}
          </p>
          <button class="button primary full" :disabled="busy">
            {{
              busy
                ? "Please wait…"
                : state.setupRequired
                  ? "Create workspace"
                  : "Sign in"
            }}<ArrowUpRight :size="18" />
          </button>
        </form>
      </div>
    </div>
  </main>
  <div v-else class="shell">
    <div v-if="mobile" class="mobile-backdrop" @click="mobile = false" />
    <aside class="sidebar" :class="[{ open: mobile }]">
      <RouterLink to="/" class="wordmark">
        <span class="logo-mark"><Leaf :size="24" /></span>leo<span
          class="wordmark-tag"
        >AGENT MANAGER</span>
      </RouterLink>
      <div class="workspace-switch">
        <span class="workspace-avatar">L</span><span>Personal workspace<small>Your agent control room</small></span>
      </div>
      <span class="nav-caption">WORKSPACE</span>
      <nav>
        <RouterLink
          v-for="item in nav"
          :key="item.to"
          :to="item.to"
          :aria-label="item.label"
          :class="{
            active:
              item.to === '/'
                ? route.path === '/'
                : route.path.startsWith(item.to),
          }"
        >
          <component :is="item.icon" :size="18" />{{ item.label
          }}<span
            v-if="item.to === '/tasks' && state.tasks.length"
            class="nav-count"
          >{{ state.tasks.length }}</span>
        </RouterLink>
      </nav>
      <div class="sidebar-bottom">
        <div class="sidebar-note">
          <span class="small-orbit" />Built for work that keeps moving.<small>Set the direction. Stay in control.</small>
        </div>
        <RouterLink
          to="/connections"
          :class="{ active: route.path === '/connections' }"
        >
          <Plug :size="18" />Connections
        </RouterLink><RouterLink
          to="/settings"
          :class="{ active: route.path === '/settings' }"
        >
          <Settings :size="18" />Settings
        </RouterLink><button @click="logout">
          <LogOut :size="18" />Sign out
        </button>
        <div class="sidebar-user">
          <span class="workspace-avatar">L</span><span>Workspace owner<small>Self-hosted · Private</small></span><span class="online-dot" />
        </div>
      </div>
    </aside>
    <div class="main-area">
      <header class="topbar">
        <div class="breadcrumb">
          <button
            class="icon-button mobile-menu"
            aria-label="Open navigation"
            @click="mobile = true"
          >
            <Menu :size="22" />
          </button><span>Workspace</span><span class="slash">/</span><strong>{{
            route.path.startsWith("/runs/")
              ? "Run details"
              : nav.find((n) => n.to === route.path)?.label
                || route.path.slice(1).replace(/^./, (c) => c.toUpperCase())
          }}</strong>
        </div>
        <div class="topbar-actions">
          <button
            class="search-trigger"
            aria-label="Search workspace"
            @click="searchOpen = true"
          >
            <Search :size="16" /><span>Find anything…</span><kbd>⌘ K</kbd>
          </button><span class="top-avatar">L</span>
        </div>
      </header>
      <main class="page">
        <RouterView :key="route.path" />
      </main>
      <footer class="page-footer">
        A quieter way to get things done.<span>Leo Agent Manager</span>
      </footer>
    </div>
  </div>
  <Transition name="toast">
    <div v-if="state.toast" class="toast" role="status">
      <Check :size="17" />{{ state.toast }}
    </div>
  </Transition>
  <Modal
    v-if="searchOpen"
    title="Find in your workspace"
    @close="searchOpen = false"
  >
    <div class="modal-body">
      <input
        v-model="search"
        autofocus
        placeholder="Search tasks, agents, projects, and skills"
        aria-label="Search"
      >
      <div class="search-results">
        <button
          v-for="(item, index) in results"
          :key="index"
          @click="
            router.push(item.to);
            searchOpen = false;
          "
        >
          <span>{{ item.name }}</span><small>{{ item.type }}</small>
        </button>
        <p v-if="!results.length" class="muted">
          No results yet. Try another search.
        </p>
      </div>
    </div>
  </Modal>
</template>
