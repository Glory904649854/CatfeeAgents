<script setup lang="ts">
import { useStore } from 'vuex'
import type { DeviceSummary } from '~~/shared/types'
import type { RootState } from '~/store'

useHead({ title: '事件日志 · CatfeeAgents 控制台' })

const store = useStore<RootState>()
const { message } = useFeedback()

const level = ref('all')
const typeKeyword = ref('')
const deviceId = ref<string | null>(null)
const devices = ref<DeviceSummary[]>([])

const levels = [
  { value: 'all', label: '全部' },
  { value: 'info', label: 'INFO' },
  { value: 'warn', label: 'WARN' },
  { value: 'error', label: 'ERROR' },
  { value: 'security', label: 'SECURITY' },
  { value: 'debug', label: 'DEBUG' }
]

const deviceOptions = computed(() =>
  devices.value.map((device) => ({
    label: device.name,
    value: device.id
  }))
)

const events = computed(() => store.state.events.items)
const loading = computed(() => store.state.events.loading)

const displayed = computed(() => {
  return events.value.filter((event) => {
    if (level.value !== 'all' && event.level !== level.value) return false
    if (deviceId.value && event.deviceId !== deviceId.value) return false
    if (typeKeyword.value.trim() && !event.type.startsWith(typeKeyword.value.trim())) return false
    return true
  })
})

let typeTimer: ReturnType<typeof setTimeout> | undefined

function load() {
  return store.dispatch('events/fetch', {
    level: level.value,
    type: typeKeyword.value.trim() || undefined,
    deviceId: deviceId.value || undefined,
    limit: 200
  })
}

async function loadDevices() {
  try {
    const response = await apiRequest<{ devices: DeviceSummary[] }>('/api/devices')
    devices.value = response.devices
  } catch {
    /* 设备列表仅用于过滤，失败不阻塞日志页 */
  }
}

watch(level, () => void load())
watch(deviceId, () => void load())
watch(typeKeyword, () => {
  clearTimeout(typeTimer)
  typeTimer = setTimeout(() => void load(), 300)
})

onMounted(async () => {
  await Promise.all([load(), loadDevices()])
})

onBeforeUnmount(() => clearTimeout(typeTimer))

async function refresh() {
  await load()
  message.success('日志已刷新')
}
</script>

<template>
  <div>
    <PageHeader title="事件日志" subtitle="Agent 执行事件与安全事件的实时流。">
      <template #actions>
        <LiveBadge />
        <n-button size="small" :loading="loading" @click="refresh">
          <template #icon>
            <span class="i-lucide-refresh-cw text-[14px]" />
          </template>
          刷新
        </n-button>
      </template>
    </PageHeader>

    <div class="flat-card mb-4 p-3 sm:p-4">
      <div class="flex flex-col gap-3 xl:flex-row xl:items-center">
        <div class="hide-scrollbar flex gap-1.5 overflow-x-auto pb-0.5 xl:flex-wrap xl:overflow-visible">
          <button
            v-for="item in levels"
            :key="item.value"
            type="button"
            class="shrink-0 whitespace-nowrap rounded-[8px] border px-2.5 py-1.5 text-[11px] font-600 transition"
            :class="
              level === item.value
                ? 'border-ink bg-ink text-white'
                : 'border-line bg-white text-muted hover:bg-subtle'
            "
            @click="level = item.value"
          >
            {{ item.label }}
          </button>
        </div>

        <div class="flex flex-1 flex-col gap-2 sm:flex-row xl:justify-end">
          <n-input
            v-model:value="typeKeyword"
            clearable
            size="small"
            placeholder="按事件类型过滤，如 task."
            class="sm:!w-[220px]"
          >
            <template #prefix>
              <span class="i-lucide-filter text-[13px] text-faint" />
            </template>
          </n-input>
          <n-select
            v-model:value="deviceId"
            :options="deviceOptions"
            clearable
            filterable
            size="small"
            placeholder="全部设备"
            class="sm:!w-[190px]"
          />
        </div>
      </div>
    </div>

    <SectionCard flush>
      <template #extra>
        <span class="text-[10.5px] text-faint">显示 {{ displayed.length }} 条</span>
      </template>

      <div v-if="loading && displayed.length === 0" class="space-y-2 p-4">
        <div v-for="index in 6" :key="index" class="h-12 animate-pulse rounded-[10px] bg-subtle" />
      </div>
      <div v-else-if="displayed.length === 0">
        <EmptyState
          icon="i-lucide-scroll-text"
          title="暂无匹配事件"
          description="调整过滤条件，或等待设备回传新的执行事件。"
        />
      </div>
      <TransitionGroup
        v-else
        name="event-list"
        tag="div"
        class="relative max-h-[70vh] overflow-y-auto"
      >
        <EventRow v-for="event in displayed" :key="event.id" :event="event" />
      </TransitionGroup>
    </SectionCard>
  </div>
</template>
