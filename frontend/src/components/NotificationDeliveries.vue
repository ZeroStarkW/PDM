<script setup lang="ts">
import { computed } from 'vue'
import type { NotificationDelivery } from '@/api/types'

const props = defineProps<{
  deliveries: NotificationDelivery[]
  notified: boolean
  canRetry: boolean
  busy: boolean
}>()
const emit = defineEmits<{ retry: [id: number] }>()
const legacy = computed(() => !props.deliveries.some(event => event.eventKind === 'ALERT'))
const states = { PENDING: '待投递', DELIVERED: '已送达', FAILED: '投递失败' }
const time = (value: string) => new Date(value).toLocaleString()
</script>

<template>
  <div class="deliveries">
    <small v-if="legacy">{{ notified ? '历史告警已送达' : '历史告警无投递记录' }}</small>
    <div v-for="event in deliveries" :key="event.id">
      <b>{{ event.eventKind === 'ALERT' ? '首次通知' : '超时升级' }} · {{ states[event.state] }}</b>
      <small>已尝试 {{ event.attempts }} 次</small>
      <small v-if="event.sentAt">送达于 {{ time(event.sentAt) }}</small>
      <small v-if="event.lastError">{{ event.lastError }}</small>
      <small v-if="event.attempts && event.nextAttemptAt">下次尝试 {{ time(event.nextAttemptAt) }}</small>
      <button v-if="canRetry && event.state === 'FAILED'" class="small-button" :disabled="busy" @click="emit('retry', event.id)">重新投递</button>
    </div>
  </div>
</template>

<style scoped>
.deliveries { display: grid; gap: .65rem; min-width: 9rem; }
</style>
