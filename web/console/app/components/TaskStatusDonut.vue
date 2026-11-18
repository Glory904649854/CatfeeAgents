<script setup lang="ts">
import type { OverviewTaskStatusCounts } from '~~/shared/types'

const props = defineProps<{
  counts: OverviewTaskStatusCounts
  total: number
  successRate: number
  avgDurationMs: number
}>()

const SEGMENTS = [
  { key: 'running', label: '执行中', color: '#E11D2E' },
  { key: 'waiting_device', label: '等待设备', color: '#D97706' },
  { key: 'queued', label: '排队中', color: '#9CA3AF' },
  { key: 'paused', label: '已暂停', color: '#7C3AED' },
  { key: 'succeeded', label: '已完成', color: '#16A34A' },
  { key: 'failed', label: '失败', color: '#DC2626' },
  { key: 'stopped', label: '已停止', color: '#64748B' },
  { key: 'expired', label: '已过期', color: '#CBD5E1' }
] as const

const mounted = ref(false)
onMounted(() => requestAnimationFrame(() => (mounted.value = true)))

const radius = 46
const circumference = 2 * Math.PI * radius

const segments = computed(() => {
  const total = props.total || 0
  if (total <= 0) return []
  let offset = 0
  const result: Array<{
    key: string
    label: string
    color: string
    value: number
    dashArray: string
    dashOffset: number
  }> = []

  for (const segment of SEGMENTS) {
    const value = Number(props.counts[segment.key] ?? 0)
    if (value <= 0) continue
    const length = (value / total) * circumference
    const visible = Math.max(length - 2, 0)
    result.push({
      key: segment.key,
      label: segment.label,
      color: segment.color,
      value,
      dashArray: `${mounted.value ? visible : 0} ${circumference}`,
      dashOffset: -offset
    })
    offset += length
  }
  return result
})
</script>

<template>
  <div class="flex flex-col items-center gap-5 sm:flex-row sm:items-center sm:gap-7">
    <div class="relative h-[150px] w-[150px] shrink-0">
      <svg viewBox="0 0 120 120" class="h-full w-full -rotate-90">
        <circle cx="60" cy="60" :r="radius" fill="none" stroke="#F1F3F5" stroke-width="12" />
        <circle
          v-for="segment in segments"
          :key="segment.key"
          cx="60"
          cy="60"
          :r="radius"
          fill="none"
          :stroke="segment.color"
          stroke-width="12"
          stroke-linecap="round"
          :stroke-dasharray="segment.dashArray"
          :stroke-dashoffset="segment.dashOffset"
          class="transition-all duration-700 ease-out"
        />
      </svg>
      <div class="absolute inset-0 flex flex-col items-center justify-center">
        <span class="text-[28px] font-700 leading-none tabular-nums text-ink">{{ total }}</span>
        <span class="mt-1 text-[10px] text-faint">今日任务</span>
        <span v-if="total > 0" class="mt-1 text-[10px] font-600 text-success">
          {{ successRate }}% 成功
        </span>
      </div>
    </div>

    <div class="w-full min-w-0 flex-1">
      <div class="grid grid-cols-2 gap-x-4 gap-y-2">
        <div
          v-for="segment in SEGMENTS"
          :key="segment.key"
          class="flex min-w-0 items-center gap-2"
        >
          <span
            class="h-2 w-2 shrink-0 rounded-full"
            :style="{ backgroundColor: segment.color }"
          />
          <span class="min-w-0 flex-1 truncate text-[11px] text-muted">{{ segment.label }}</span>
          <span class="text-[11px] font-600 tabular-nums text-ink">
            {{ counts[segment.key] ?? 0 }}
          </span>
        </div>
      </div>
      <p class="mt-3 text-[10px] text-faint">
        今日平均耗时 {{ avgDurationMs > 0 ? formatDurationMs(avgDurationMs) : '—' }}
      </p>
    </div>
  </div>
</template>
