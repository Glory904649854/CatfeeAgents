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
const payload = computed<Record<string, any>>(
  () => (props.event.payload ?? {}) as Record<string, any>
)

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

function truncate(value: string, max: number): string {
  return value.length > max ? `${value.slice(0, max)}…` : value
}

function pretty(value: unknown): string {
  if (typeof value !== 'string') return JSON.stringify(value, null, 2) ?? ''
  const trimmed = value.trim()
  if (!trimmed) return ''
  try {
    return JSON.stringify(JSON.parse(trimmed), null, 2)
  } catch {
    return value
  }
}

const tool = computed(() => {
  const p = payload.value
  if (Array.isArray(p.tools) && p.tools.length > 0) return p.tools[0]
  if (Array.isArray(p.output?.toolCalls) && p.output.toolCalls.length > 0) {
    return p.output.toolCalls[0]
  }
  return null
})

const title = computed(() => {
  const p = payload.value
  for (const value of [p.summary, p.title, p.message, p.error]) {
    if (typeof value === 'string' && value.trim()) return value.trim()
  }
  return EVENT_SUMMARY[props.event.type] ?? props.event.type
})

type ChipTone = 'default' | 'success' | 'warn' | 'danger'
interface Chip {
  label: string
  icon?: string
  tone?: ChipTone
}

const chips = computed<Chip[]>(() => {
  const p = payload.value
  const out: Chip[] = []

  if (typeof p.step === 'number') {
    out.push({ label: `Step ${p.step}`, icon: 'i-lucide-list-ordered' })
  }
  if (tool.value?.name) {
    out.push({ label: String(tool.value.name), icon: 'i-lucide-wrench' })
  }
  if (typeof p.llmMs === 'number' && p.llmMs > 0) {
    out.push({ label: `LLM ${formatDurationMs(p.llmMs)}`, icon: 'i-lucide-brain' })
  }
  if (typeof p.durationMs === 'number' && p.durationMs > 0) {
    out.push({ label: formatDurationMs(p.durationMs), icon: 'i-lucide-timer' })
  }
  if (p.context && typeof p.context.chars === 'number') {
    const messages = typeof p.context.messages === 'number' ? ` · ${p.context.messages} 条` : ''
    out.push({
      label: `${formatCompactNumber(p.context.chars)} 字符${messages}`,
      icon: 'i-lucide-file-text'
    })
  }
  if (typeof p.steps === 'number') {
    out.push({ label: `${p.steps} 步`, icon: 'i-lucide-footprints' })
  }
  if (typeof p.result === 'string' && p.result.trim() && p.result !== p.summary) {
    out.push({
      label: truncate(p.result.trim(), 36),
      icon: 'i-lucide-flag',
      tone: p.success === false ? 'danger' : 'default'
    })
  }
  if (typeof p.error === 'string' && p.error.trim() && p.error !== p.summary) {
    out.push({
      label: truncate(p.error.trim(), 48),
      icon: 'i-lucide-triangle-alert',
      tone: 'danger'
    })
  }
  if (typeof p.reason === 'string' && p.reason.trim() && props.event.type.startsWith('device.')) {
    out.push({ label: p.reason.trim(), icon: 'i-lucide-unplug' })
  }
  if (typeof p.agentVersion === 'string' && p.agentVersion) {
    out.push({ label: `Agent ${p.agentVersion}`, icon: 'i-lucide-smartphone' })
  }
  if (typeof p.androidVersion === 'string' && p.androidVersion) {
    out.push({ label: `Android ${p.androidVersion}` })
  }

  return out
})

function chipClass(tone: ChipTone = 'default'): string {
  if (tone === 'danger') return 'bg-danger/10 text-danger'
  if (tone === 'success') return 'bg-success/10 text-success'
  if (tone === 'warn') return 'bg-warning/10 text-warning'
  return 'bg-subtle text-muted'
}

const sections = computed<Array<{ label: string; value: string }>>(() => {
  const p = payload.value
  const out: Array<{ label: string; value: string }> = []

  if (tool.value?.arguments && tool.value.arguments !== '{}') {
    out.push({ label: '工具参数', value: pretty(tool.value.arguments) })
  }
  if (tool.value?.result) {
    out.push({ label: '工具结果', value: truncate(String(tool.value.result), 1600) })
  }
  if (p.output?.reasoning) {
    out.push({ label: '模型思考', value: truncate(String(p.output.reasoning), 1600) })
  }
  if (p.output?.content) {
    out.push({ label: '模型输出', value: truncate(String(p.output.content), 1200) })
  }
  if (p.input?.lastMessage) {
    out.push({ label: '最近输入', value: truncate(String(p.input.lastMessage), 1200) })
  }
  if (typeof p.error === 'string' && p.error.trim()) {
    out.push({ label: '错误', value: p.error })
  }
  if (typeof p.result === 'string' && p.result.trim()) {
    out.push({ label: '结果', value: p.result })
  }
  if (typeof p.goal === 'string' && p.goal.trim()) {
    out.push({ label: '任务目标', value: p.goal })
  }

  return out
})
</script>

<template>
  <div class="group border-b border-line/70 last:border-b-0">
    <button
      type="button"
      class="flex w-full items-start gap-3 px-4 py-3.5 text-left transition hover:bg-soft/70"
      @click="expanded = !expanded"
    >
      <span
        class="mt-1 flex h-5 w-5 shrink-0 items-center justify-center rounded-full"
        :class="meta.className"
      >
        <span class="h-1.5 w-1.5 rounded-full" :class="meta.dotClassName" />
      </span>

      <div class="min-w-0 flex-1">
        <div class="flex flex-wrap items-center gap-x-2 gap-y-1">
          <span
            class="rounded-[5px] px-1.5 py-0.5 text-[9px] font-700 tracking-[0.08em]"
            :class="meta.className"
          >
            {{ meta.label }}
          </span>
          <span class="font-mono text-[11px] font-700 tracking-tight text-ink">
            {{ event.type }}
          </span>
          <span class="text-[10px] text-faint">{{ event.deviceName || event.deviceId }}</span>
          <span v-if="event.taskId && !hideTask" class="text-[10px] text-faint">
            · {{ shortId(event.taskId) }}
          </span>
          <span class="ml-auto whitespace-nowrap text-[10px] text-faint">
            <TimeText :value="event.occurredAt" />
          </span>
        </div>

        <p class="mt-1 line-clamp-2 text-[12px] font-600 leading-5 text-ink">{{ title }}</p>

        <div v-if="chips.length" class="mt-1.5 flex flex-wrap items-center gap-1.5">
          <span
            v-for="(chip, index) in chips"
            :key="`${chip.label}-${index}`"
            class="inline-flex max-w-full items-center gap-1 rounded-[6px] px-1.5 py-0.5 text-[10px] font-500"
            :class="chipClass(chip.tone)"
          >
            <span v-if="chip.icon" :class="chip.icon" class="text-[10px]" />
            <span class="truncate">{{ chip.label }}</span>
          </span>
        </div>
      </div>

      <span
        class="i-lucide-chevron-down mt-1 shrink-0 text-[14px] text-faint transition-transform"
        :class="{ 'rotate-180': expanded }"
      />
    </button>

    <Transition name="event-detail">
      <div v-if="expanded" class="px-4 pb-4 pl-12">
        <div v-if="sections.length" class="grid gap-2 sm:grid-cols-2">
          <div
            v-for="section in sections"
            :key="section.label"
            class="min-w-0 rounded-[10px] border border-line bg-soft/50 p-2.5"
          >
            <p class="label-xs">{{ section.label }}</p>
            <pre
              class="mt-1.5 max-h-[220px] overflow-auto whitespace-pre-wrap break-words font-mono text-[10.5px] leading-4 text-muted"
            >{{ section.value }}</pre>
          </div>
        </div>

        <details class="mt-2 rounded-[10px] border border-line bg-white">
          <summary
            class="flex cursor-pointer list-none items-center gap-1.5 px-3 py-2 text-[10.5px] font-600 text-muted transition hover:text-ink"
          >
            <span class="i-lucide-code-2 text-[12px]" />
            原始 JSON
          </summary>
          <div class="border-t border-line p-2.5">
            <JsonBlock :value="event.payload" />
          </div>
        </details>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.event-detail-enter-active,
.event-detail-leave-active {
  transition: opacity 0.18s ease, transform 0.18s ease;
}

.event-detail-enter-from,
.event-detail-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}
</style>
