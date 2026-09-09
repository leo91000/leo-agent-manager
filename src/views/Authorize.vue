<script setup lang="ts">
import { ShieldCheck } from '@lucide/vue'
import { onMounted, ref } from 'vue'
import { api } from '../api'

const parameters = Object.fromEntries(new URLSearchParams(location.search))
const details = ref<any>()
const error = ref('')
const busy = ref(false)
onMounted(async () => {
  try {
    details.value = await api('/oauth/preview', {
      method: 'POST',
      body: JSON.stringify(parameters),
    })
  }
  catch (e) {
    error.value = (e as Error).message
  }
})
async function consent(approved: boolean) {
  busy.value = true
  try {
    const result = await api('/oauth/consent', {
      method: 'POST',
      body: JSON.stringify({ parameters, approved }),
    })
    location.assign(result.redirect)
  }
  catch (e) {
    error.value = (e as Error).message
    busy.value = false
  }
}
</script>

<template>
  <section class="panel consent-card">
    <span class="empty-icon"><ShieldCheck :size="30" /></span>
    <h1>Connect an assistant</h1>
    <p v-if="error" class="error" role="alert">
      {{ error }}
    </p>
    <template v-if="details">
      <p>
        <strong>{{ details.client.client_name }}</strong> is requesting access
        to your Leo workspace.
      </p>
      <ul class="consent-permissions">
        <li v-for="scope in details.scopes" :key="scope">
          {{
            scope === "read"
              ? "Read tasks, profiles, skills, and run results"
              : scope === "run"
                ? "Start and cancel tasks in YOLO mode with full container access"
                : "Create and modify tasks, projects, agents, and skills"
          }}
        </li>
      </ul>
      <p class="muted">
        You can revoke this connection at any time in Settings.
      </p>
      <div class="consent-actions">
        <button class="button" :disabled="busy" @click="consent(false)">
          Deny
        </button><button class="button primary" :disabled="busy" @click="consent(true)">
          Allow access
        </button>
      </div>
    </template>
  </section>
</template>
