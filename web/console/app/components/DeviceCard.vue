<script setup lang="ts">
import type { DeviceSummary } from '~~/shared/types'

const props = defineProps<{ device: DeviceSummary }>()
const meta = computed(() => deviceStatusMeta(props.device.status))
</script>

<template>
  <NuxtLink
    :to="`/devices/${device.id}`"
    class="flat-card group p-5 transition hover:border-ink/25"
  >
    <div class="flex items-start gap-2">
      <div class="min-w-0 flex-1">
        <p class="truncate text-[14px] font-600 text-ink">{{ device.name }}</p>
        <p class="mt-1 truncate font-mono text-[11px] text-faint">{{ device.id }}</p>
      </div>
      <StatusPill
        class="shrink-0"
        :label="meta.label"
        :class-name="meta.className"
        :dot-class-name="meta.dotClassName"
        :pulse="device.status === 'online'"
      />
    </div>

    <p class="mt-3 flex flex-wrap items-center gap-x-2 gap-y-1 text-[12px] text-muted">
      <span>Android {{ device.androidVersion || '—' }}</span>
      <span class="text-line">·</span>
      <span>Agent {{ device.agentVersion || '未上报版本' }}</span>
    </p>

    <div class="mt-3 flex min-h-[24px] flex-wrap gap-1.5">
      <span v-for="capability in device.capabilities.slice(0, 3)" :key="capability" class="chip">
        {{ capability }}
      </span>
      <span v-if="device.capabilities.length > 3" class="chip">
        +{{ device.capabilities.length - 3 }}
      </span>
      <span v-if="device.capabilities.length === 0" class="text-[11px] text-faint">
        尚未上报能力
      </span>
    </div>

    <div class="mt-4 flex items-center justify-between border-t border-line/70 pt-3 text-[11px] text-faint">
      <span>最后心跳 <TimeText :value="device.lastHeartbeatAt" /></span>
      <span class="i-lucide-arrow-up-right text-[13px] transition group-hover:text-ink" />
    </div>
  </NuxtLink>
</template>
