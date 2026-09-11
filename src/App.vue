<script setup lang="ts">
import { twMerge } from 'tailwind-merge'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, notify, refresh, session, state } from './api'
import Icon from './components/Icon.vue'
import Modal from './components/Modal.vue'
import ThemeControl from './components/ThemeControl.vue'
import UiAlert from './components/UiAlert.vue'
import UiButton from './components/UiButton.vue'
import { Activity, ArrowUpRight, BookOpen, Check, FolderGit2, ListTodo, LogOut, Menu, MessageCircle, Plug, Plus, Robot, Search, Settings, X, Zap } from './icons'
import { iconButton } from './ui'

const router = useRouter()
const route = useRoute()
const password = ref('')
const setupToken = ref('')
const busy = ref(false)
const error = ref('')
const mobile = ref(false)
const navigation = ref<HTMLElement>()
const menuButton = ref<HTMLButtonElement>()
const mobileQuery = window.matchMedia('(max-width: 640px)')
const narrow = ref(mobileQuery.matches)
function viewportChanged() {
  narrow.value = mobileQuery.matches
  if (!narrow.value)
    mobile.value = false
}
const searchOpen = ref(false)
const search = ref('')
const nav = [
  { to: '/chats', label: 'Chats', icon: MessageCircle },
  { to: '/tasks', label: 'Tasks', icon: ListTodo },
  { to: '/runs', label: 'Runs', icon: Activity },
  { to: '/agents', label: 'Agents', icon: Robot },
  { to: '/projects', label: 'Projects', icon: FolderGit2 },
  { to: '/skills', label: 'Skills', icon: BookOpen },
  { to: '/mcps', label: 'MCPs', icon: Plug },
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
  mobileQuery.addEventListener('change', viewportChanged)
})
onBeforeUnmount(() => {
  document.removeEventListener('keydown', key)
  mobileQuery.removeEventListener('change', viewportChanged)
})
watch(mobile, async (open) => {
  await nextTick()
  if (open)
    navigation.value?.querySelector<HTMLButtonElement>('.navigation-close')?.focus()
  else if (narrow.value)
    menuButton.value?.focus()
})
watch(
  () => route.path,
  () => (mobile.value = false),
)
function key(event: KeyboardEvent) {
  if (event.key === 'Escape' && mobile.value) {
    mobile.value = false
    return
  }
  if ((event.metaKey || event.ctrlKey) && event.key === 'k') {
    event.preventDefault()
    if (state.authenticated)
      searchOpen.value = !searchOpen.value
  }
}
function navigationKey(event: KeyboardEvent) {
  if (!mobile.value || event.key !== 'Tab')
    return
  const controls = navigation.value?.querySelectorAll<HTMLElement>('a[href], button:not([disabled])')
  const first = controls?.[0]
  const last = controls?.[controls.length - 1]
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault()
    last?.focus()
  }
  else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first?.focus()
  }
}
function navigationClick(event: MouseEvent) {
  if (event.target instanceof Element && event.target.closest('a[href]'))
    mobile.value = false
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
  <div v-if="!state.ready" class="loading-screen min-h-dvh grid place-items-center text-muted">
    Loading…
  </div>
  <main v-else-if="!state.authenticated" class="auth-screen grid grid-cols-[1fr_1fr] min-h-dvh phone:flex phone:flex-col">
    <div class="auth-story bg-[light-dark(#eeedff,_#222033)] text-ink flex flex-col justify-between relative overflow-hidden phone:min-h-auto phone:gap-[35px] px-[65px] py-[55px] compact:p-[45px] tablet:p-7.5 phone:p-[25px]">
      <div class="wordmark flex items-center gap-[9px] text-[#f0eff7] [font-size:29px] tracking-[-1px] font-bold font-heading tablet:[font-size:25px]">
        <span class="logo-mark w-8 h-8 grid place-items-center bg-brand rounded-[9px] text-white [transform:rotate(-7deg)] [box-shadow:2px_2px_0_light-dark(#292943,_#0d0d18)]"><Icon :name="Zap" :size="25" /></span>leo<span
          class="wordmark-tag [font:600_8px/1.5_'DM_Sans_Variable',_sans-serif] tracking-[1.7px] max-w-[65px] whitespace-normal ml-[3px] text-[#a7a5ba] hidden tablet:[font-size:7px]"
        >AGENT MANAGER</span>
      </div>
      <div>
        <h1>Your agents.<br>Your workspace.</h1>
      </div>
      <span class="auth-foot text-xs text-[#6b6892] tracking-[0.5px] phone:hidden">Leo Agent Manager</span>
    </div>
    <div class="auth-form flex items-center justify-center relative phone:pt-16.5 phone:pb-[35px] phone:flex-1 p-10 tablet:p-7.5 phone:px-[25px]">
      <div class="auth-appearance absolute right-6 top-6 z-2 phone:top-3 phone:right-3">
        <ThemeControl compact />
      </div>
      <div class="auth-card max-w-[345px] w-full">
        <h2>
          {{ state.setupRequired ? "Create workspace" : "Sign in" }}
        </h2>
        <p>
          {{
            state.setupRequired
              ? "Create your administrator account to get started."
              : ""
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
          <UiAlert v-if="error">
            {{ error }}
          </UiAlert>
          <UiButton class="w-full" variant="primary" type="submit" :disabled="busy">
            {{
              busy
                ? "Please wait…"
                : state.setupRequired
                  ? "Create workspace"
                  : "Sign in"
            }}<Icon :name="ArrowUpRight" :size="18" />
          </UiButton>
        </form>
      </div>
    </div>
  </main>
  <div v-else class="shell min-h-0 flex h-dvh overflow-hidden">
    <div v-if="mobile" class="mobile-backdrop phone:fixed phone:[inset:0] phone:bg-[light-dark(#17152980,_#020208b3)] phone:z-25" @click="mobile = false" />
    <aside
      id="workspace-navigation"
      ref="navigation"
      class="sidebar w-50.5 fixed [inset:0_auto_0_0] bg-sidebar text-muted pt-7 pb-0 flex flex-col z-30 h-dvh overflow-y-auto overscroll-contain [scrollbar-width:thin] [scrollbar-color:light-dark(#c1bfd6,_#67647f)_transparent] border-r border-line phone:[transform:translateX(-100%)] phone:[transition:transform_0.2s] phone:[padding:calc(20px_+_env(safe-area-inset-top))_20px_env(safe-area-inset-bottom)] phone:w-[min(290px,_calc(100%_-_36px))] phone:pt-6 phone:pb-0 [@media(max-width:_1150px)_and_(min-width:_641px)]:w-45 [@media(max-width:_1150px)_and_(min-width:_641px)]:pl-[13px] [@media(max-width:_1150px)_and_(min-width:_641px)]:pr-[13px] px-[17px] phone:px-4.5"
      :class="[{ open: mobile }]"
      :inert="narrow && !mobile"
      :role="narrow && mobile ? 'dialog' : undefined"
      :aria-modal="narrow && mobile ? true : undefined"
      :aria-hidden="narrow && !mobile ? true : undefined"
      aria-label="Workspace navigation"
      @keydown="navigationKey"
      @click="navigationClick"
    >
      <div class="sidebar-heading flex items-center justify-between gap-2">
        <RouterLink to="/" class="wordmark flex items-center gap-[9px] text-[#f0eff7] [font-size:29px] tracking-[-1px] font-bold font-heading tablet:[font-size:25px]">
          <span class="logo-mark w-8 h-8 grid place-items-center bg-brand rounded-[9px] text-white [transform:rotate(-7deg)] [box-shadow:2px_2px_0_light-dark(#292943,_#0d0d18)]"><Icon :name="Zap" :size="24" /></span>leo<span
            class="wordmark-tag [font:600_8px/1.5_'DM_Sans_Variable',_sans-serif] tracking-[1.7px] max-w-[65px] whitespace-normal ml-[3px] text-[#a7a5ba] hidden tablet:[font-size:7px]"
          >AGENT MANAGER</span>
        </RouterLink>
        <button :class="twMerge(iconButton, 'icon-button navigation-close hidden text-muted phone:inline-flex')" aria-label="Close navigation" @click="mobile = false">
          <Icon :name="X" :size="22" />
        </button>
      </div>
      <div class="workspace-switch flex items-center gap-2.5 text-left mt-9 mb-[25px] border-0 rounded-[9px] text-2xs font-semibold bg-transparent text-ink px-1.5 py-0 mx-0">
        <span class="workspace-avatar grid place-items-center bg-[light-dark(#eeedff,_#34314c)] border-0 text-accent text-2xs w-7 h-7 rounded-full shrink-0">L</span><span>Personal workspace</span>
      </div>

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
          <Icon :name="item.icon" :size="18" />{{ item.label
          }}<span
            v-if="item.to === '/tasks' && state.tasks.length"
            class="nav-count ml-auto text-3xs bg-hover text-muted min-w-4.5 text-center rounded-[4px] p-0.5"
          >{{ state.tasks.length }}</span>
        </RouterLink>
      </nav>
      <div class="sidebar-bottom mt-auto pt-6">
        <RouterLink
          to="/connections"
          :class="{ active: route.path === '/connections' }"
        >
          <Icon :name="Plug" :size="18" />Connections
        </RouterLink><RouterLink
          to="/settings"
          :class="{ active: route.path === '/settings' }"
        >
          <Icon :name="Settings" :size="18" />Settings
        </RouterLink><button @click="logout">
          <Icon :name="LogOut" :size="18" />Sign out
        </button>
        <div class="sidebar-user mt-[17px] border-t flex items-center gap-[9px] text-2xs text-ink border-line phone:pb-[max(22px,_env(safe-area-inset-bottom))] px-1 py-5.5">
          <span class="workspace-avatar grid place-items-center bg-[light-dark(#eeedff,_#34314c)] border-0 text-accent text-2xs w-7 h-7 rounded-full shrink-0">L</span><span>Workspace owner</span><span class="online-dot w-1.5 h-1.5 rounded-full bg-[light-dark(#7772e4,_#b8b2ff)] ml-auto" />
        </div>
      </div>
    </aside>
    <div class="main-area ml-50.5 flex h-full min-h-0 min-w-0 w-[calc(100%_-_202px)] flex-col phone:ml-0 phone:w-full [@media(641px<=width<=1150px)]:ml-45 [@media(641px<=width<=1150px)]:w-[calc(100%_-_180px)]" :inert="narrow && mobile">
      <header class="topbar short:h-[calc(48px_+_env(safe-area-inset-top))] short:min-h-12 h-15 flex items-center justify-between bg-transparent phone:min-h-[calc(62px_+_env(safe-area-inset-top))] phone:[padding:env(safe-area-inset-top)_max(12px,_env(safe-area-inset-right))_0_max(12px,_env(safe-area-inset-left))] phone:h-[63px] [@media(max-width:_1150px)_and_(min-width:_641px)]:pt-0 [@media(max-width:_1150px)_and_(min-width:_641px)]:pr-6 [@media(max-width:_1150px)_and_(min-width:_641px)]:pb-0 [@media(max-width:_1150px)_and_(min-width:_641px)]:pl-6 px-9 py-0 phone:px-4.5 phone:py-0 shrink-0">
        <div class="breadcrumb flex items-center gap-3 text-2xs text-muted phone:gap-[7px] phone:text-3xs">
          <button
            ref="menuButton"
            :class="twMerge(iconButton, 'icon-button mobile-menu hidden phone:inline-flex')"
            aria-label="Open navigation"
            aria-controls="workspace-navigation"
            :aria-expanded="mobile"
            @click="mobile = true"
          >
            <Icon :name="Menu" :size="22" />
          </button><span>Workspace</span><span class="slash text-subtle">/</span><strong>{{
            route.path.startsWith("/chats")
              ? "Chats"
              : route.path.startsWith("/runs/")
                ? "Run details"
                : nav.find((n) => n.to === route.path)?.label
                  || route.path.slice(1).replace(/^./, (c) => c.toUpperCase())
          }}</strong>
        </div>
        <div class="topbar-actions flex items-center gap-[17px] phone:gap-[7px]">
          <button
            class="search-trigger flex items-center gap-2.5 text-xs text-muted w-9 h-9 justify-center rounded-lg phone:min-h-11 phone:min-w-11"
            aria-label="Search workspace"
            @click="searchOpen = true"
          >
            <Icon :name="Search" :size="16" /><span>Find anything…</span><kbd>⌘ K</kbd>
          </button><ThemeControl compact />
        </div>
      </header>
      <main class="page mx-auto flex-1 min-h-0 min-w-0 w-full max-w-340 overflow-auto overscroll-contain [scrollbar-width:thin] px-9 pt-6 pb-7 [@media(641px<=width<=1150px)]:px-6 phone:mb-[calc(74px_+_env(safe-area-inset-bottom))] phone:px-3 phone:py-3.5 short:py-2.5 [&.page-chat]:max-w-none [&.page-chat]:pt-1 [&.page-chat]:pb-4 phone:[&.page-chat]:pb-2 [&.page-tasks]:flex [&.page-chat]:max-w-none [&.page-chat]:pt-1 [&.page-chat]:pb-4 phone:[&.page-chat]:pb-2 [&.page-tasks]:flex-col [&.page-tasks]:overflow-hidden [&.page-run]:flex [&.page-run]:flex-col [&.page-run]:overflow-hidden" :class="{ 'page-tasks': route.path === '/tasks', 'page-run': route.path.startsWith('/runs/') || route.path.startsWith('/chats'), 'page-chat': route.path.startsWith('/chats') }">
        <RouterView :key="route.path" />
      </main>
      <nav v-if="!mobile" class="mobile-bottom-nav hidden phone:fixed phone:bottom-0 phone:left-0 phone:right-0 phone:z-20 phone:flex phone:items-center phone:justify-around phone:[padding:11px_10px_max(15px,_env(safe-area-inset-bottom))] phone:border-t border-line phone:bg-surface" aria-label="Quick navigation">
        <RouterLink to="/tasks" aria-label="Tasks" :class="{ active: route.path === '/tasks' }">
          <Icon :name="ListTodo" :size="20" />
        </RouterLink>
        <RouterLink to="/runs" aria-label="Activity" :class="{ active: route.path.startsWith('/runs') }">
          <Icon :name="Activity" :size="20" />
        </RouterLink>
        <RouterLink to="/tasks?new=1" class="mobile-new-task" aria-label="New task">
          <Icon :name="Plus" :size="20" />
        </RouterLink>
        <RouterLink to="/chats" aria-label="Chats" :class="{ active: route.path.startsWith('/chats') }">
          <Icon :name="MessageCircle" :size="20" />
        </RouterLink>
        <button aria-label="More navigation" @click="mobile = true">
          <Icon :name="Menu" :size="20" />
        </button>
      </nav>
    </div>
  </div>
  <Transition name="toast">
    <div v-if="state.toast" class="toast fixed bottom-[25px] left-[50%] [transform:translateX(-50%)] bg-[light-dark(#272443,_var(--dark-accent-surface))] text-subtle border border-line rounded-[10px] [box-shadow:0_6px_20px_light-dark(#1a182b20,_#00000020)] z-100 flex items-center gap-[9px] text-xs max-w-[calc(100vw_-_30px)] phone:bottom-[calc(90px_+_env(safe-area-inset-bottom))] px-5 py-[13px]" role="status">
      <Icon :name="Check" :size="17" />{{ state.toast }}
    </div>
  </Transition>
  <Modal
    v-if="searchOpen"
    title="Find in your workspace"
    @close="searchOpen = false"
  >
    <div class="modal-body px-6.5 py-6 phone:p-5">
      <input
        v-model="search"
        autofocus
        placeholder="Search tasks, agents, projects, and skills"
        aria-label="Search"
      >
      <div class="search-results mt-4">
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
        <p v-if="!results.length" class="muted text-muted">
          No results yet. Try another search.
        </p>
      </div>
    </div>
  </Modal>
</template>
