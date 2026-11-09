<script setup lang="ts">
import { useStore } from 'vuex'
import type { RootState } from '~/store'

useHead({ title: '任务详情 · Catfee 控制台' })

const route = useRoute()
const router = useRouter()
const store = useStore<RootState>()
const { message, dialog } = useFeedback()

const id = computed(() => String(route.params.id ?? ''))
const detail = computed(() => store.state.tasks.detail)
const task = computed(() => detail.value?.task ?? null)
const device = computed(() => detail.value?.device ?? null)
const events = computed(() => detail.value?.events ?? [])
const commands = computed(() => detail.value?.commands ?? [])
const loading = computed(() => store.state.tasks.detailLoading)
const acting = ref<'pause' | 'resume' | 'stop' | ''>('')

const terminalStatuses = ['succeeded', 'failed', 'stopped', 'expired', 'interrupted']
const canPause = computed(() => Boolean(task.value && ['accepted', 'running'].includes(task.value.status)))
const canResume = computed(() => Boolean(task.value && task.value.status === 'paused'))
const canStop = computed(() => Boolean(task.value && !terminalStatuses.includes(task.value.status)))

function load() {
  return store.dispatch('tasks/fetchDetail', id.value)
}

onMounted(load)
watch(id, () => void load())
watch(
  () => store.state.events.latest?.id,
  () => {
    const latest = store.state.events.latest
    if (latest && latest.taskId === id.value) void load()
  }
)

async function control(action: 'pause' | 'resume' | 'stop') {
  if (!task.value) return
  acting.value = action
  try {
    const result = await store.dispatch('tasks/control', { id: id.value, action })
    if (result.note) message.warning(result.note)
    else message.success('控制指令已下发')
    await load()
  } catch (err: unknown) {
    const requestError = err as { data?: { message?: string } }
    message.error(requestError?.data?.message || '操作失败')
  } finally {
    acting.value = ''
  }
}

function requestStop() {
  if (!task.value) return
  dialog.warning({
    title: '停止任务',
    content: '停止后设备端 Agent 会结束当前执行。已完成的步骤不会回滚。',
    positiveText: '停止任务',
    negativeText: '取消',
    onPositiveClick: () => control('stop')
  })
}

const infoFields = computed(() => [
  { label: '任务 ID', value: task.value?.id ?? '—', mono: true },
  { label: '任务类型', value: task.value ? TASK_TYPE_LABELS[task.value.type] : '—' },
  { label: '创建人', value: task.value?.createdByName || task.value?.createdBy || '—' },
  { label: '创建时间', value: formatFullDateTime(task.value?.createdAt) },
  { label: '开始时间', value: formatFullDateTime(task.value?.startedAt) },
  { label: '结束时间', value: formatFullDateTime(task.value?.finishedAt) },
  { label: '执行耗时', value: task.value ? formatDuration(task.value.startedAt, task.value.finishedAt) : '—' }
])
</script>

<template>
  <div>
    <PageHeader
      back-to="/tasks"
      :title="task?.title || '任务详情'"
      :subtitle="
        task
          ? `${device?.name || task.deviceName || task.deviceId} · ${TASK_TYPE_LABELS[task.type]}`
          : '加载任务信息…'
      "
    >
      <template #actions>
        <TaskStatusPill v-if="task" :status="task.status" />
        <n-button
          v-if="canPause"
          size="small"
          :loading="acting === 'pause'"
          @click="control('pause')"
        >
          <template #icon>
            <span class="i-lucide-pause text-[14px]" />
          </template>
          暂停
        </n-button>
        <n-button
          v-if="canResume"
          size="small"
          :loading="acting === 'resume'"
          @click="control('resume')"
        >
          <template #icon>
            <span class="i-lucide-play text-[14px]" />
          </template>
          继续
        </n-button>
        <n-button
          v-if="canStop"
          size="small"
          type="error"
          secondary
          :loading="acting === 'stop'"
          @click="requestStop"
        >
          <template #icon>
            <span class="i-lucide-square text-[13px]" />
          </template>
          停止
        </n-button>
        <n-button size="small" :loading="loading" @click="load">
          <template #icon>
            <span class="i-lucide-refresh-cw text-[14px]" />
          </template>
        </n-button>
      </template>
    </PageHeader>

    <div v-if="loading && !task" class="grid gap-4 lg:grid-cols-3">
      <div class="flat-card h-[420px] animate-pulse lg:col-span-2" />
      <div class="flat-card h-[420px] animate-pulse" />
    </div>

    <template v-else-if="task">
      <div
        v-if="task.error"
        class="mb-4 flex items-start gap-2 rounded-[12px] border border-danger/20 bg-danger/5 px-4 py-3 text-[11.5px] leading-5 text-danger"
      >
        <span class="i-lucide-circle-alert mt-0.5 shrink-0 text-[15px]" />
        <span>{{ task.error }}</span>
      </div>

      <div class="grid gap-4 lg:grid-cols-3">
        <div class="space-y-4 lg:col-span-2">
          <SectionCard
            title="执行时间线"
            :subtitle="`${events.length} 条事件，按发生顺序排列`"
            flush
          >
            <template #extra>
              <LiveBadge />
            </template>
            <div v-if="events.length === 0">
              <EmptyState
                icon="i-lucide-radio"
                title="暂无执行事件"
                description="任务下发后，设备端 Agent 的步骤会实时回传。"
              />
            </div>
            <div v-else class="max-h-[560px] overflow-y-auto">
              <EventRow v-for="event in events" :key="event.id" :event="event" hide-task />
            </div>
          </SectionCard>

          <SectionCard title="控制指令" subtitle="任务下发与远程控制记录" flush>
            <div v-if="commands.length === 0">
              <EmptyState icon="i-lucide-terminal" title="暂无指令" />
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
        </div>

        <div class="space-y-4">
          <SectionCard title="任务信息">
            <div class="space-y-3">
              <div
                v-for="field in infoFields"
                :key="field.label"
                class="flex items-start justify-between gap-3"
              >
                <span class="shrink-0 text-[10.5px] text-faint">{{ field.label }}</span>
                <span
                  class="min-w-0 truncate text-right text-[11.5px] font-600 text-ink"
                  :class="field.mono ? 'font-mono text-[10.5px]' : ''"
                  :title="field.value"
                >
                  {{ field.value }}
                </span>
              </div>
              <div class="flex items-start justify-between gap-3 border-t border-line/70 pt-3">
                <span class="shrink-0 text-[10.5px] text-faint">目标设备</span>
                <NuxtLink
                  :to="`/devices/${task.deviceId}`"
                  class="min-w-0 truncate text-right text-[11.5px] font-600 text-ink underline-offset-3 hover:underline"
                >
                  {{ device?.name || task.deviceName || task.deviceId }}
                </NuxtLink>
              </div>
            </div>
          </SectionCard>

          <SectionCard title="执行策略">
            <div class="flex flex-wrap gap-1.5">
              <span class="chip">截图回传：{{ task.policy.allowScreenshot ? '允许' : '禁止' }}</span>
              <span class="chip">Root：{{ task.policy.allowRoot ? '允许' : '禁止' }}</span>
              <span class="chip">超时：{{ formatSeconds(Math.round(task.policy.timeoutMs / 1000)) }}</span>
            </div>
          </SectionCard>

          <SectionCard title="下发参数">
            <JsonBlock :value="task.payload" />
          </SectionCard>
        </div>
      </div>
    </template>

    <div v-else class="flat-card">
      <EmptyState
        icon="i-lucide-circle-help"
        title="任务不存在"
        description="任务可能已被删除，或链接中的 ID 不正确。"
      >
        <n-button size="small" @click="router.push('/tasks')">返回任务列表</n-button>
      </EmptyState>
    </div>
  </div>
</template>
