<script setup lang="ts">
import { useStore } from 'vuex'
import type { RootState } from '~/store'

const store = useStore<RootState>()
const connected = computed(() => store.state.events.liveConnected)
</script>

<template>
  <span
    class="inline-flex items-center gap-1.5 text-[10.5px] font-600"
    :class="connected ? 'text-success' : 'text-faint'"
  >
    <span
      class="h-1.5 w-1.5 rounded-full"
      :class="[connected ? 'bg-success live-dot' : 'bg-faint']"
    />
    {{ connected ? '实时' : '未连接' }}
  </span>
</template>
