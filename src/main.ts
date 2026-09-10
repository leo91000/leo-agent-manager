import { createApp } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import App from './App.vue'
import '@fontsource-variable/dm-sans/wght.css'
import '@fontsource-variable/manrope/wght.css'
import './style.css'
import './theme.css'
import './focus.css'
import './workspace-layout.css'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/tasks' },
    { path: '/mcps', component: () => import('./views/Mcps.vue') },
    { path: '/tasks', component: () => import('./views/Tasks.vue') },
    { path: '/runs', component: () => import('./views/Runs.vue') },
    { path: '/runs/:id', component: () => import('./views/RunDetail.vue') },
    {
      path: '/agents',
      component: () => import('./views/Resources.vue'),
      props: { kind: 'agents' },
    },
    {
      path: '/projects',
      component: () => import('./views/Resources.vue'),
      props: { kind: 'projects' },
    },
    { path: '/skills', component: () => import('./views/Skills.vue') },
    {
      path: '/connections',
      component: () => import('./views/Connections.vue'),
    },
    { path: '/settings', component: () => import('./views/Settings.vue') },
    { path: '/authorize', component: () => import('./views/Authorize.vue') },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})
createApp(App).use(router).mount('#app')
