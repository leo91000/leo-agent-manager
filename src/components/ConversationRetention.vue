<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '../api'
import Modal from './Modal.vue'
import UiAlert from './UiAlert.vue'
import UiButton from './UiButton.vue'

interface Policy {
  enabled: boolean
  inactivityDays: number
  coldAfterDays: number
  eligible: number
  configured: boolean
}

const policy = ref<Policy>()
const error = ref('')
const busy = ref(false)
const confirming = ref(false)
onMounted(async () => {
  try {
    policy.value = await api('/conversation-retention')
  }
  catch (e) { error.value = (e as Error).message }
})

async function save(confirmed = false) {
  if (!policy.value)
    return
  busy.value = true
  error.value = ''
  try {
    if (policy.value.enabled && !confirmed) {
      const preview = await api(`/conversation-retention?inactivityDays=${policy.value.inactivityDays}`) as Policy
      policy.value.eligible = preview.eligible
      confirming.value = true
      return
    }

    policy.value = await api('/conversation-retention', { method: 'PUT', body: JSON.stringify({ ...policy.value, confirmExisting: confirmed }) })
    confirming.value = false
  }
  catch (e) { error.value = (e as Error).message }
  finally { busy.value = false }
}
</script>

<template>
  <section class="panel border-b border-line py-7 mb-5.5">
    <h2>Conversation storage</h2>
    <UiAlert v-if="error">
      {{ error }}
    </UiAlert>
    <form v-if="policy" class="grid gap-4 max-w-lg mt-4" @submit.prevent="save()">
      <label class="flex gap-2 items-center"><input v-model="policy.enabled" type="checkbox" :disabled="!policy.configured"> Automatically archive inactive conversations</label>
      <p v-if="!policy.configured" class="text-muted text-sm">
        Configure S3 on the server to enable archival.
      </p>
      <label>Archive after inactivity (days)<input
        v-model.number="policy.inactivityDays"
        type="number"
        min="1"
        max="3650"
        required
      ></label>
      <label>Keep archives immediately accessible before Glacier (days)<input
        v-model.number="policy.coldAfterDays"
        type="number"
        min="1"
        max="3650"
        required
      ></label>
      <p class="text-muted text-sm">
        Archives are kept until you delete them. Deleted conversations stay in the trash for 30 days. Glacier restoration can take several hours.
      </p>
      <UiButton type="submit" :disabled="busy">
        Save storage rules
      </UiButton>
    </form>
    <Modal v-if="confirming" title="Enable conversation archival" @close="confirming = false">
      <p>{{ policy?.eligible }} existing conversations currently qualify under this inactivity rule. Eligible conversations will be archived progressively. Working agents and pending messages are protected.</p>
      <UiButton :disabled="busy" @click="save(true)">
        Confirm and save
      </UiButton>
    </Modal>
  </section>
</template>
