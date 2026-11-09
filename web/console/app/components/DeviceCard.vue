<script setup lang="ts">
import type { DeviceSummary } from '~~/shared/types'

const props = defineProps<{ device: DeviceSummary }>()
const meta = computed(() => deviceStatusMeta(props.device.status))
</script>

<template>
  <NuxtLink
    :to="`/devices/${device.id}`"
    class="flat-card group p-4 transition hover:border-ink/25 sm:p-5"
  >
    <div class="flex items-start gap-3">
      <span
        class="flex h-10 w-10 shrink-0 items-center justify-center rounded-[12px]"
        :class="device.status === 'revoked' ? 'bg-subtle text-faint' : 'bg-ink text-white'"
      >
        <span class="i-lucide-smartphone text-[18px]" />
      </span>
      <div class="min-w-0 flex-1">
        <div class="flex items-start gap-2">
          <p class="truncate text-[13.5px] font-600 text-ink">{{ device.name }}</p>
          <StatusPill
            class="ml-auto shrink-0"
            :label="meta.label"
            :class-name="meta.className"
            :dot-class-name="meta.dotClassName"
            :pulse="device.status === 'online'"
          />
        </div>
        <p class="mt-0.5 truncate font-mono text-[10.5px] text-faint">{{ device.id }}</p>
      </div>
    </div>

    <div class="mt-4 grid grid-cols-2 gap-2">
      <div class="rounded-[10px] border border-line bg-soft px-2.5 py-2">
        <p class="text-[9.5px] text-faint">Android</p>
        <p class="mt-0.5 text-[11.5px] font-600 text-ink">{{ device.androidVersion || '—' }}</p>
      </div>
      <div class="rounded-[10px] border border-line bg-soft px-2.5 py-2">
        <p class="text-[9.5px] text-faint">Agent</p>
        <p class="mt-0.5 text-[11.5px] font-600 text-ink">{{ device.agentVersion || '—' }}</p>
      </div>
    </div>

    <div class="mt-3 flex min-h-[22px] flex-wrap gap-1.5">
      <span v-for="capability in device.capabilities.slice(0, 3)" :key="capability" class="chip">
        {{ capability }}
      </span>
      <span v-if="device.capabilities.length > 3" class="chip">
        +{{ device.capabilities.length - 3 }}
      </span>
      <span v-if="device.capabilities.length === 0" class="text-[10.5px] text-faint">
        尚未上报能力
      </span>
    </div>

    <div
      class="mt-3 flex items-center justify-between border-t border-line/70 pt-3 text-[10.5px] text-faint"
    >
      <span>心跳 <TimeText :value="device.lastHeartbeatAt" /></span>
      <span class="i-lucide-arrow-up-right text-[13px] transition group-hover:text-ink" />
    </div>
  </NuxtLink>
</template>
