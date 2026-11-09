<script setup lang="ts">
import type { EventSummary } from '~~/shared/types'

const props = withDefaults(
  defineProps<{
    event: EventSummary
    defaultExpanded?: boolean
    hideTask?: boolean
  }>(),
  { defaultExpanded: false, hideTask: false }
)

const expanded = ref(props.defaultExpanded)
const meta = computed(() => eventLevelMeta(props.event.level))

const EVENT_SUMMARY: Record<string, string> = {
  'task.queued': '任务已排队，等待设备接入',
  'task.accepted': '设备已接收任务',
  'task.started': '任务开始执行',
  'task.paused': '任务已暂停',
  'task.resumed': '任务已继续',
  'task.stopped': '任务已停止',
  'task.succeeded': '任务执行成功',
  'task.failed': '任务执行失败',
  'task.expired': '任务已过期',
  'device.online': '设备已上线',
  'device.offline': '设备已离线',
  'device.enrolled': '设备已完成注册',
  'command.ack': '设备已确认控制指令',
  'security.device_revoked': '设备授权已撤销'
}

const summary = computed(() => {
  const payload = props.event.payload || {}
  const candidate =
    payload.summary ??
    payload.message ??
    payload.error ??
    payload.goal ??
    payload.reason ??
    payload.tool
  if (typeof candidate === 'string' && candidate.length > 0) return candidate
  return EVENT_SUMMARY[props.event.type] ?? ''
})
</script>

<template>
  <div class="border-b border-line/70 last:border-b-0">
    <button
      type="button"
      class="flex w-full items-start gap-3 px-4 py-3 text-left transition hover:bg-soft"
      @click="expanded = !expanded"
    >
      <span class="mt-1.5 h-1.5 w-1.5 shrink-0 rounded-full" :class="meta.dotClassName" />
      <div class="min-w-0 flex-1">
        <div class="flex flex-wrap items-center gap-x-2 gap-y-1">
          <StatusPill
            :label="meta.label"
            :class-name="meta.className"
            :dot-class-name="meta.dotClassName"
          />
          <span class="font-mono text-[11.5px] font-600 text-ink">{{ event.type }}</span>
          <span class="text-[10.5px] text-faint">{{ event.deviceName || event.deviceId }}</span>
          <span v-if="event.taskId && !hideTask" class="text-[10.5px] text-faint">
            · {{ shortId(event.taskId) }}
          </span>
          <span class="ml-auto whitespace-nowrap text-[10.5px] text-faint">
            <TimeText :value="event.occurredAt" />
          </span>
        </div>
        <p v-if="summary" class="mt-1 truncate text-[11.5px] text-muted">{{ summary }}</p>
      </div>
      <span
        class="i-lucide-chevron-down mt-1 shrink-0 text-[14px] text-faint transition"
        :class="{ 'rotate-180': expanded }"
      />
    </button>
    <div v-if="expanded" class="px-4 pb-3.5 pl-10">
      <JsonBlock :value="event.payload" />
    </div>
  </div>
</template>
