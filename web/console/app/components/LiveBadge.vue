<script setup lang="ts">
import { useStore } from 'vuex'
import type { RootState } from '~/store'

withDefaults(
  defineProps<{
    label?: string
    pill?: boolean
    dark?: boolean
  }>(),
  { label: '实时', pill: false, dark: false }
)

const store = useStore<RootState>()
const connected = computed(() => store.state.events.liveConnected)
</script>

<template>
  <span
    class="inline-flex items-center gap-1.5 text-[10.5px] font-600"
    :class="[
      pill ? 'rounded-full px-2.5 py-1' : '',
      connected
        ? pill ? 'bg-success/15 text-success' : 'text-success'
        : pill ? 'bg-white/10 text-white/45' : 'text-faint'
    ]"
  >
    <span
      class="h-1.5 w-1.5 rounded-full"
      :class="[connected ? 'bg-success live-dot' : 'bg-faint']"
    />
    {{ connected ? label : '未连接' }}
  </span>
</template>
