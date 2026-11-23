<script setup lang="ts">
import type { TaskSummary } from '~~/shared/types'

defineProps<{ task: TaskSummary }>()
</script>

<template>
  <NuxtLink
    :to="`/tasks/${task.id}`"
    class="group grid grid-cols-[minmax(0,1fr)_auto] items-center gap-x-3 gap-y-1 px-5 py-3.5 transition hover:bg-soft sm:grid-cols-[minmax(0,1.7fr)_minmax(0,1fr)_96px_112px_64px] sm:gap-4"
  >
    <div class="min-w-0">
      <p class="truncate text-[13px] font-600 text-ink">{{ task.title }}</p>
      <p class="mt-0.5 flex items-center gap-1.5 text-[10.5px] text-faint sm:hidden">
        <span>{{ task.deviceName || task.deviceId }}</span>
        <span>·</span>
        <span><TimeText :value="task.createdAt" /></span>
      </p>
    </div>
    <span class="hidden truncate font-mono text-[11px] text-faint sm:block">
      {{ task.deviceName || task.deviceId }}
    </span>
    <span class="justify-self-end sm:justify-self-start">
      <TaskStatusPill :status="task.status" />
    </span>
    <span class="hidden text-[11px] text-faint sm:block">
      <TimeText :value="task.createdAt" />
    </span>
    <span class="hidden text-right font-mono text-[11px] text-muted sm:block">
      {{ formatDuration(task.startedAt, task.finishedAt) }}
    </span>
  </NuxtLink>
</template>
