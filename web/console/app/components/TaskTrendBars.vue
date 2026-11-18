<script setup lang="ts">
import type { OverviewTrendPoint } from '~~/shared/types'

const props = defineProps<{
  points: OverviewTrendPoint[]
}>()

const mounted = ref(false)
onMounted(() => requestAnimationFrame(() => (mounted.value = true)))

const max = computed(() =>
  Math.max(
    1,
    ...props.points.flatMap((point) => [point.created, point.succeeded, point.failed])
  )
)

const empty = computed(() =>
  props.points.every((point) => point.created === 0 && point.succeeded === 0 && point.failed === 0)
)

const totals = computed(() =>
  props.points.reduce(
    (sum, point) => {
      sum.created += point.created
      sum.succeeded += point.succeeded
      sum.failed += point.failed
      return sum
    },
    { created: 0, succeeded: 0, failed: 0 }
  )
)

function height(value: number): string {
  return mounted.value ? `${(value / max.value) * 100}%` : '0%'
}
</script>

<template>
  <div>
    <div class="relative">
      <div class="flex h-[146px] items-end gap-2">
        <div
          v-for="(point, index) in points"
          :key="point.date"
          class="flex min-w-0 flex-1 flex-col items-center gap-2"
        >
          <div class="flex h-[124px] w-full items-end justify-center gap-1">
            <span
              class="w-2 rounded-t-[3px] bg-ink/15 transition-all duration-700 ease-out"
              :style="{ height: height(point.created), transitionDelay: `${index * 30}ms` }"
              :title="`${point.label} 创建 ${point.created}`"
            />
            <span
              class="w-2 rounded-t-[3px] bg-success transition-all duration-700 ease-out"
              :style="{ height: height(point.succeeded), transitionDelay: `${index * 30}ms` }"
              :title="`${point.label} 成功 ${point.succeeded}`"
            />
            <span
              class="w-2 rounded-t-[3px] bg-danger transition-all duration-700 ease-out"
              :style="{ height: height(point.failed), transitionDelay: `${index * 30}ms` }"
              :title="`${point.label} 失败 ${point.failed}`"
            />
          </div>
          <span class="text-[9.5px] text-faint">{{ point.label }}</span>
        </div>
      </div>

      <p
        v-if="empty"
        class="absolute inset-0 flex items-center justify-center pb-5 text-[11px] text-faint"
      >
        近 7 天暂无任务
      </p>
    </div>

    <div class="mt-4 flex flex-wrap items-center gap-x-4 gap-y-2">
      <span class="flex items-center gap-1.5 text-[10.5px] text-muted">
        <span class="h-2 w-2 rounded-full bg-ink/15" />
        创建 {{ totals.created }}
      </span>
      <span class="flex items-center gap-1.5 text-[10.5px] text-muted">
        <span class="h-2 w-2 rounded-full bg-success" />
        成功 {{ totals.succeeded }}
      </span>
      <span class="flex items-center gap-1.5 text-[10.5px] text-muted">
        <span class="h-2 w-2 rounded-full bg-danger" />
        失败 {{ totals.failed }}
      </span>
    </div>
  </div>
</template>
