<script setup lang="ts">
import { useStore } from 'vuex'
import type { RootState } from '~/store'

useHead({ title: '设备详情 · CatfeeAgents 控制台' })

const route = useRoute()
const router = useRouter()
const store = useStore<RootState>()
const { message, dialog } = useFeedback()

const id = computed(() => String(route.params.id ?? ''))
const detail = computed(() => store.state.devices.detail)
const device = computed(() => detail.value?.device ?? null)
const tasks = computed(() => detail.value?.tasks ?? [])
const events = computed(() => detail.value?.events ?? [])
const commands = computed(() => detail.value?.commands ?? [])
const loading = computed(() => store.state.devices.detailLoading)

const statusMeta = computed(() =>
  device.value ? deviceStatusMeta(device.value.status) : deviceStatusMeta('offline')
)

function load() {
  return store.dispatch('devices/fetchDetail', id.value)
}

onMounted(load)
watch(id, () => void load())
watch(
  () => store.state.events.latest?.id,
  () => {
    const latest = store.state.events.latest
    if (latest && latest.deviceId === id.value) void load()
  }
)

function confirmRevoke() {
  if (!device.value) return
  dialog.warning({
    title: '撤销设备授权',
    content: `撤销后「${device.value.name}」将无法再连接控制台，进行中的任务会被中断。此操作不可自动恢复。`,
    positiveText: '撤销授权',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await store.dispatch('devices/revoke', id.value)
        message.success('已撤销设备授权')
        await load()
      } catch (err: unknown) {
        const requestError = err as { data?: { message?: string } }
        message.error(requestError?.data?.message || '撤销失败')
      }
    }
  })
}

const infoFields = computed(() => [
  { label: '设备 ID', value: device.value?.id ?? '—', mono: true },
  { label: '型号', value: device.value?.model || '—' },
  { label: 'Android 版本', value: device.value?.androidVersion || '—' },
  { label: 'Agent 版本', value: device.value?.agentVersion || '—' },
  { label: '最近心跳', value: formatRelative(device.value?.lastHeartbeatAt) },
  { label: '最近在线', value: formatRelative(device.value?.lastSeenAt) },
  { label: '接入时间', value: formatFullDateTime(device.value?.createdAt) },
  {
    label: '授权状态',
    value: device.value?.revokedAt ? '已撤销' : '正常'
  }
])
</script>

<template>
  <div>
    <PageHeader
      back-to="/devices"
      :title="device?.name || '设备详情'"
      :subtitle="device ? device.id : '加载设备信息…'"
    >
      <template #actions>
        <n-button size="small" :loading="loading" @click="load">
          <template #icon>
            <span class="i-lucide-refresh-cw text-[14px]" />
          </template>
          刷新
        </n-button>
        <n-button
          v-if="device && !device.revokedAt"
          size="small"
          type="error"
          secondary
          @click="confirmRevoke"
        >
          <template #icon>
            <span class="i-lucide-shield-off text-[14px]" />
          </template>
          撤销授权
        </n-button>
      </template>
    </PageHeader>

    <div v-if="loading && !device" class="space-y-4">
      <div class="flat-card h-[220px] animate-pulse" />
      <div class="grid gap-4 lg:grid-cols-2">
        <div class="flat-card h-[260px] animate-pulse" />
        <div class="flat-card h-[260px] animate-pulse" />
      </div>
    </div>

    <template v-else-if="device">
      <div
        v-if="device.revokedAt"
        class="mb-4 flex items-start gap-2 rounded-[12px] border border-danger/20 bg-danger/5 px-4 py-3 text-[11.5px] leading-5 text-danger"
      >
        <span class="i-lucide-shield-off mt-0.5 shrink-0 text-[15px]" />
        该设备的授权已于 {{ formatFullDateTime(device.revokedAt) }} 撤销，将不再接收任何任务或控制指令。
      </div>

      <SectionCard>
        <div class="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
          <div class="flex items-start gap-3">
            <span
              class="flex h-11 w-11 shrink-0 items-center justify-center rounded-[13px]"
              :class="device.status === 'revoked' ? 'bg-subtle text-faint' : 'bg-ink text-white'"
            >
              <span class="i-lucide-smartphone text-[20px]" />
            </span>
            <div class="min-w-0">
              <div class="flex flex-wrap items-center gap-2">
                <h2 class="text-[15px] font-700 text-ink">{{ device.name }}</h2>
                <StatusPill
                  :label="statusMeta.label"
                  :class-name="statusMeta.className"
                  :dot-class-name="statusMeta.dotClassName"
                  :pulse="device.status === 'online'"
                />
              </div>
              <p class="mt-1 font-mono text-[10.5px] text-faint">{{ device.id }}</p>
            </div>
          </div>
          <div class="flex flex-wrap gap-1.5">
            <span v-for="capability in device.capabilities" :key="capability" class="chip">
              {{ capability }}
            </span>
            <span v-if="device.capabilities.length === 0" class="chip">未上报能力</span>
          </div>
        </div>

        <div class="mt-5 grid grid-cols-2 gap-x-4 gap-y-4 border-t border-line/70 pt-4 sm:grid-cols-4">
          <div v-for="field in infoFields" :key="field.label" class="min-w-0">
            <p class="text-[10px] text-faint">{{ field.label }}</p>
            <p
              class="mt-0.5 truncate text-[12px] font-600 text-ink"
              :class="field.mono ? 'font-mono text-[10.5px]' : ''"
              :title="field.value"
            >
              {{ field.value }}
            </p>
          </div>
        </div>
      </SectionCard>

      <div class="mt-4 grid gap-4 lg:grid-cols-2">
        <SectionCard title="最近任务" subtitle="该设备最近 20 个任务" flush>
          <template #extra>
            <NuxtLink :to="`/tasks?deviceId=${device.id}`" class="link-quiet text-[11px]">
              查看全部 →
            </NuxtLink>
          </template>
          <div v-if="tasks.length === 0">
            <EmptyState
              icon="i-lucide-list-checks"
              title="暂无任务"
              description="给这台设备下发第一个任务。"
            />
          </div>
          <div v-else>
            <TaskRow v-for="task in tasks" :key="task.id" :task="task" />
          </div>
        </SectionCard>

        <SectionCard title="最近事件" subtitle="按发生时间倒序" flush>
          <template #extra>
            <LiveBadge />
          </template>
          <div v-if="events.length === 0">
            <EmptyState icon="i-lucide-radio" title="暂无事件" description="设备开始执行后会回传事件。" />
          </div>
          <TransitionGroup
            v-else
            name="event-list"
            tag="div"
            class="relative max-h-[420px] overflow-y-auto"
          >
            <EventRow v-for="event in events" :key="event.id" :event="event" />
          </TransitionGroup>
        </SectionCard>
      </div>

      <SectionCard class="mt-4" title="控制指令" flush>
        <div v-if="commands.length === 0">
          <EmptyState icon="i-lucide-terminal" title="暂无指令" description="任务下发或控制操作会记录在这里。" />
        </div>
        <div v-else class="divide-y divide-line/70">
          <div
            v-for="command in commands"
            :key="command.id"
            class="flex flex-wrap items-center gap-2 px-4 py-3"
          >
            <span class="chip">{{ COMMAND_TYPE_LABELS[command.type] || command.type }}</span>
            <StatusPill
              :label="commandStatusMeta(command.status).label"
              :class-name="commandStatusMeta(command.status).className"
              :dot-class-name="commandStatusMeta(command.status).dotClassName"
            />
            <span class="font-mono text-[10.5px] text-faint">{{ shortId(command.id) }}</span>
            <span class="ml-auto text-[10.5px] text-faint">
              <TimeText :value="command.createdAt" />
            </span>
          </div>
        </div>
      </SectionCard>
    </template>

    <div v-else class="flat-card">
      <EmptyState
        icon="i-lucide-circle-help"
        title="设备不存在"
        description="设备可能已被删除，或链接中的 ID 不正确。"
      >
        <n-button size="small" @click="router.push('/devices')">返回设备列表</n-button>
      </EmptyState>
    </div>
  </div>
</template>
