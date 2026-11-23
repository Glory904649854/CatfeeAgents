<script setup lang="ts">
import { useStore } from 'vuex'
import type { OverviewPayload } from '~~/shared/types'
import type { RootState } from '~/store'

useHead({ title: '概览 · CatfeeAgents 控制台' })

const store = useStore<RootState>()
const router = useRouter()

const { data, pending, refresh } = await useFetch<OverviewPayload>('/api/overview', {
  key: 'overview'
})

const stats = computed(() => data.value?.stats)
const devices = computed(() => data.value?.devices ?? [])
const tasks = computed(() => data.value?.tasks ?? [])
const events = computed(() => data.value?.events ?? [])
const statusCounts = computed(() => data.value?.taskStatusCounts)
const taskTrend = computed(() => data.value?.taskTrend ?? [])
const todayTotal = computed(() =>
  Object.values(statusCounts.value ?? {}).reduce((sum, value) => sum + Number(value), 0)
)

watch(
  () => store.state.events.latest?.id,
  () => {
    void refresh()
  }
)

const statCards = computed(() => [
  {
    label: '在线设备',
    value: `${stats.value?.devicesOnline ?? 0} / ${stats.value?.devicesTotal ?? 0}`,
    icon: 'i-lucide-smartphone',
    tone: 'success' as const,
  },
  {
    label: '活跃任务',
    value: stats.value?.tasksRunning ?? 0,
    icon: 'i-lucide-activity',
    tone: 'brand' as const,
  },
  {
    label: '今日事件',
    value: stats.value?.eventsToday ?? 0,
    icon: 'i-lucide-scroll-text',
    tone: 'violet' as const,
  },
  {
    label: '今日失败',
    value: stats.value?.tasksFailed ?? 0,
    icon: 'i-lucide-triangle-alert',
    tone: 'warning' as const,
  }
])
</script>

<template>
  <div>
    <PageHeader title="概览">
      <template #actions>
        <n-button size="small" :loading="pending" @click="refresh">
          <template #icon>
            <span class="i-lucide-refresh-cw text-[14px]" />
          </template>
          刷新
        </n-button>
        <NuxtLink
          to="/tasks?create=1"
          class="btn-brand"
        >
          <span class="i-lucide-plus text-[14px]" />
          新建任务
        </NuxtLink>
      </template>
    </PageHeader>

    <div class="grid grid-cols-2 gap-3 sm:gap-4 xl:grid-cols-4">
      <StatCard
        v-for="card in statCards"
        :key="card.label"
        :label="card.label"
        :value="card.value"
        :icon="card.icon"
        :tone="card.tone"
      />
    </div>

    <div class="mt-4 grid gap-4 lg:grid-cols-2">
      <SectionCard title="今日任务状态">
        <TaskStatusDonut
          v-if="statusCounts"
          :counts="statusCounts"
          :total="todayTotal"
          :success-rate="stats?.successRate ?? 0"
          :avg-duration-ms="stats?.avgDurationMs ?? 0"
        />
        <div v-else class="h-[150px] animate-pulse rounded-[12px] bg-subtle" />
      </SectionCard>
      <SectionCard title="近 7 天任务趋势">
        <TaskTrendBars :points="taskTrend" />
      </SectionCard>
    </div>

    <div class="mt-4 grid gap-4 lg:grid-cols-3">
      <SectionCard
        class="lg:col-span-2"
        title="运行中的任务"
        flush
      >
        <template #extra>
          <NuxtLink to="/tasks" class="link-quiet text-[11px]">全部任务 →</NuxtLink>
        </template>

        <div v-if="pending && tasks.length === 0" class="space-y-2 p-4">
          <div v-for="index in 3" :key="index" class="h-12 animate-pulse rounded-[10px] bg-subtle" />
        </div>
        <div
          v-else-if="tasks.length === 0"
          class="flex flex-col gap-3 px-4 py-6 sm:flex-row sm:items-center sm:justify-between"
        >
          <div class="flex items-center gap-3">
            <span
              class="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-subtle text-muted"
            >
              <span class="i-lucide-activity text-[16px]" />
            </span>
            <p class="text-[12.5px] font-600 text-ink">当前没有运行中的任务</p>
          </div>
          <n-button size="small" @click="router.push('/tasks?create=1')">新建任务</n-button>
        </div>
        <div v-else>
          <TaskTableHeader />
          <TaskRow v-for="task in tasks" :key="task.id" :task="task" />
        </div>
      </SectionCard>

      <SectionCard class="self-start" title="设备状态" flush>
        <template #extra>
          <NuxtLink to="/devices" class="link-quiet text-[11px]">全部设备 →</NuxtLink>
        </template>

        <div v-if="pending && devices.length === 0" class="space-y-2 p-4">
          <div v-for="index in 3" :key="index" class="h-12 animate-pulse rounded-[10px] bg-subtle" />
        </div>
        <div v-else-if="devices.length === 0">
          <EmptyState
            icon="i-lucide-smartphone"
            title="还没有设备"
          >
            <n-button size="small" type="primary" @click="router.push('/devices?enroll=1')">
              注册设备
            </n-button>
          </EmptyState>
        </div>
        <div v-else>
          <NuxtLink
            v-for="device in devices"
            :key="device.id"
            :to="`/devices/${device.id}`"
            class="flex items-center gap-2.5 border-b border-line/70 px-4 py-3 transition last:border-b-0 hover:bg-soft"
          >
            <span
              class="h-1.5 w-1.5 shrink-0 rounded-full"
              :class="deviceStatusMeta(device.status).dotClassName"
            />
            <div class="min-w-0 flex-1">
              <p class="truncate text-[12.5px] font-600 text-ink">{{ device.name }}</p>
              <p class="mt-0.5 truncate text-[10.5px] text-faint">
                {{ device.model || 'Android 设备' }} ·
                <TimeText :value="device.lastHeartbeatAt" />
              </p>
            </div>
            <StatusPill
              :label="deviceStatusMeta(device.status).label"
              :class-name="deviceStatusMeta(device.status).className"
              :dot-class-name="deviceStatusMeta(device.status).dotClassName"
              :pulse="device.status === 'online'"
            />
          </NuxtLink>
        </div>
      </SectionCard>
    </div>

    <SectionCard class="mt-4" title="实时事件" flush>
      <template #extra>
        <LiveBadge />
      </template>

      <div v-if="events.length === 0" class="p-2">
        <EmptyState
          icon="i-lucide-radio"
          title="暂无事件"
          description="设备接入并开始执行任务后，事件会实时出现在这里。"
        />
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
</template>
