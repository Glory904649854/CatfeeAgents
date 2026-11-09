<script setup lang="ts">
import type { TaskSummary } from '~~/shared/types'

defineProps<{ task: TaskSummary }>()
</script>

<template>
  <NuxtLink
    :to="`/tasks/${task.id}`"
    class="flex items-center gap-3 px-4 py-3.5 transition hover:bg-soft"
  >
    <span
      class="hidden h-8 w-8 shrink-0 items-center justify-center rounded-[10px] bg-subtle text-muted sm:flex"
    >
      <span class="i-lucide-list-checks text-[15px]" />
    </span>
    <div class="min-w-0 flex-1">
      <div class="flex flex-wrap items-center gap-2">
        <p class="truncate text-[13px] font-600 text-ink">{{ task.title }}</p>
        <TaskStatusPill :status="task.status" />
      </div>
      <p class="mt-1 flex flex-wrap items-center gap-x-2 gap-y-1 text-[11px] text-faint">
        <span>{{ task.deviceName || task.deviceId }}</span>
        <span>·</span>
        <span>{{ TASK_TYPE_LABELS[task.type] || task.type }}</span>
        <span>·</span>
        <span><TimeText :value="task.createdAt" /></span>
      </p>
    </div>
    <span class="hidden shrink-0 text-[11px] text-faint sm:block">
      {{ formatDuration(task.startedAt, task.finishedAt) }}
    </span>
    <span class="i-lucide-chevron-right shrink-0 text-[15px] text-faint" />
  </NuxtLink>
</template>
