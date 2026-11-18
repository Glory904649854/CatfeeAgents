<script setup lang="ts">
import { useStore } from 'vuex'
import type { DeviceSummary, TaskType } from '~~/shared/types'
import type { RootState } from '~/store'

useHead({ title: '任务 · CatfeeAgents 控制台' })

const store = useStore<RootState>()
const route = useRoute()
const router = useRouter()
const { message } = useFeedback()

const status = ref('all')
const deviceFilter = ref('')
const createOpen = ref(false)
const submitting = ref(false)
const devices = ref<DeviceSummary[]>([])
const devicesLoading = ref(false)

const form = reactive({
  deviceId: '',
  title: '',
  type: 'agent.goal' as TaskType,
  goal: '',
  timeoutMs: -1,
  allowScreenshot: true,
  allowRoot: false
})

const tabs = [
  { value: 'all', label: '全部' },
  { value: 'active', label: '进行中' },
  { value: 'paused', label: '已暂停' },
  { value: 'failed', label: '失败' },
  { value: 'succeeded', label: '已完成' },
  { value: 'stopped', label: '已停止' }
]

const typeOptions = [
  { label: 'Agent 目标（自然语言目标）', value: 'agent.goal' },
  { label: '设备诊断（采集与检查）', value: 'device.diagnostic' },
  { label: '应用流程（受控步骤）', value: 'app.flow' }
]

const timeoutOptions = [
  { label: '不限制（直到任务完成）', value: -1 },
  { label: '5 分钟', value: 300000 },
  { label: '15 分钟', value: 900000 },
  { label: '30 分钟', value: 1800000 },
  { label: '1 小时', value: 3600000 }
]

const canGrantRoot = computed(() => {
  const role = store.state.auth.user?.role
  return role === 'owner' || role === 'admin'
})

const tasks = computed(() => store.state.tasks.items)
const counts = computed(() => store.state.tasks.counts)
const loading = computed(() => store.state.tasks.loading)

const deviceOptions = computed(() =>
  devices.value
    .filter((device) => device.status !== 'revoked')
    .map((device) => ({
      label: `${device.name} · ${deviceStatusMeta(device.status).label}`,
      value: device.id
    }))
)

function load() {
  return store.dispatch('tasks/fetch', {
    status: status.value,
    deviceId: deviceFilter.value || undefined
  })
}

watch(status, () => void load())

onMounted(async () => {
  if (typeof route.query.deviceId === 'string') deviceFilter.value = route.query.deviceId
  await load()
  if (route.query.create === '1') {
    await openCreate()
    await router.replace({ query: { ...route.query, create: undefined } })
  }
})

async function loadDevices() {
  if (devices.value.length > 0 || devicesLoading.value) return
  devicesLoading.value = true
  try {
    const response = await apiRequest<{ devices: DeviceSummary[] }>('/api/devices')
    devices.value = response.devices
  } finally {
    devicesLoading.value = false
  }
}

async function openCreate() {
  createOpen.value = true
  await loadDevices()
  if (!form.deviceId && deviceFilter.value) form.deviceId = deviceFilter.value
}

function resetForm() {
  form.title = ''
  form.goal = ''
  form.type = 'agent.goal'
  form.timeoutMs = -1
  form.allowScreenshot = true
  form.allowRoot = false
}

async function submitCreate() {
  if (!form.deviceId) {
    message.warning('请选择目标设备')
    return
  }
  if (!form.title.trim()) {
    message.warning('请填写任务标题')
    return
  }

  submitting.value = true
  try {
    const result = await store.dispatch('tasks/create', {
      deviceId: form.deviceId,
      title: form.title.trim(),
      type: form.type,
      goal: form.goal.trim(),
      policy: {
        allowScreenshot: form.allowScreenshot,
        allowRoot: form.allowRoot,
        timeoutMs: form.timeoutMs
      }
    })

    message.success(result.delivered ? '任务已下发到设备' : '设备离线，任务已排队等待接入')
    createOpen.value = false
    resetForm()
    await load()
    await navigateTo(`/tasks/${result.task.id}`)
  } catch (err: unknown) {
    const requestError = err as { data?: { message?: string } }
    message.error(requestError?.data?.message || '任务创建失败')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div>
    <PageHeader title="任务">
      <template #actions>
        <n-button size="small" :loading="loading" @click="load">
          <template #icon>
            <span class="i-lucide-refresh-cw text-[14px]" />
          </template>
          刷新
        </n-button>
        <n-button type="primary" size="small" @click="openCreate">
          <template #icon>
            <span class="i-lucide-plus text-[14px]" />
          </template>
          新建任务
        </n-button>
      </template>
    </PageHeader>

    <div class="mb-4 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
      <div class="hide-scrollbar flex gap-1.5 overflow-x-auto pb-0.5 sm:flex-wrap sm:overflow-visible">
        <button
          v-for="tab in tabs"
          :key="tab.value"
          type="button"
          class="inline-flex shrink-0 items-center gap-1.5 whitespace-nowrap rounded-[9px] border px-2.5 py-1.5 text-[11.5px] font-600 transition"
          :class="
            status === tab.value
              ? 'border-ink bg-ink text-white'
              : 'border-line bg-white text-muted hover:bg-subtle'
          "
          @click="status = tab.value"
        >
          {{ tab.label }}
          <span
            class="rounded-full px-1.5 text-[10px]"
            :class="status === tab.value ? 'bg-white/15 text-white' : 'bg-subtle text-faint'"
          >
            {{ counts[tab.value] ?? 0 }}
          </span>
        </button>
      </div>

      <div
        v-if="deviceFilter"
        class="flex items-center gap-2 rounded-[9px] border border-line bg-white px-2.5 py-1.5 text-[11px] text-muted"
      >
        <span class="i-lucide-filter text-[12px] text-faint" />
        设备 {{ shortId(deviceFilter) }}
        <button
          type="button"
          class="text-faint transition hover:text-ink"
          @click="deviceFilter = ''; load()"
        >
          <span class="i-lucide-x text-[12px]" />
        </button>
      </div>
    </div>

    <SectionCard flush>
      <div v-if="loading && tasks.length === 0" class="space-y-2 p-4">
        <div v-for="index in 4" :key="index" class="h-14 animate-pulse rounded-[10px] bg-subtle" />
      </div>
      <div v-else-if="tasks.length === 0">
        <EmptyState
          icon="i-lucide-list-checks"
          title="还没有任务"
          description="选择一台已接入设备，下发第一个 Agent 任务。"
        >
          <n-button size="small" type="primary" @click="openCreate">新建任务</n-button>
        </EmptyState>
      </div>
      <div v-else>
        <TaskRow v-for="task in tasks" :key="task.id" :task="task" />
      </div>
    </SectionCard>

    <n-modal
      v-model:show="createOpen"
      preset="card"
      title="新建任务"
      :style="{ width: 'min(560px, calc(100vw - 32px))' }"
    >
      <div class="space-y-4">
        <div>
          <label class="field-label mb-1.5 block">目标设备</label>
          <n-select
            v-model:value="form.deviceId"
            :options="deviceOptions"
            :loading="devicesLoading"
            filterable
            placeholder="选择已接入设备"
          />
          <p class="mt-1.5 text-[10.5px] text-faint">
            离线设备会排队，重连后下发。
          </p>
        </div>

        <div>
          <label class="field-label mb-1.5 block">任务标题</label>
          <n-input v-model:value="form.title" maxlength="120" show-count placeholder="例如：检查应用登录页可用性" />
        </div>

        <div class="grid gap-4 sm:grid-cols-2">
          <div>
            <label class="field-label mb-1.5 block">任务类型</label>
            <n-select v-model:value="form.type" :options="typeOptions" />
          </div>
          <div>
            <label class="field-label mb-1.5 block">超时时间</label>
            <n-select v-model:value="form.timeoutMs" :options="timeoutOptions" />
            <p class="mt-1.5 text-[10.5px] text-faint">
              默认不限制，可随时暂停/停止。
            </p>
          </div>
        </div>

        <div>
          <label class="field-label mb-1.5 block">目标描述</label>
          <n-input
            v-model:value="form.goal"
            type="textarea"
            :autosize="{ minRows: 3, maxRows: 6 }"
            maxlength="4000"
            placeholder="描述要完成的目标"
          />
        </div>

        <div class="rounded-[12px] border border-line bg-soft p-3.5">
          <p class="text-[11.5px] font-600 text-ink">执行策略</p>
          <div class="mt-3 flex items-center justify-between gap-3">
            <div>
              <p class="text-[11.5px] text-ink">允许截图回传</p>
              <p class="mt-0.5 text-[10px] text-faint">失败诊断时需要最近一张页面截图</p>
            </div>
            <n-switch v-model:value="form.allowScreenshot" size="small" />
          </div>
          <div class="mt-3 flex items-center justify-between gap-3 border-t border-line/70 pt-3">
            <div>
              <p class="text-[11.5px] text-ink">允许 Root 能力</p>
              <p class="mt-0.5 text-[10px] text-faint">
                仅管理员可授予；文字输入仍依赖无障碍
              </p>
            </div>
            <n-switch
              v-model:value="form.allowRoot"
              size="small"
              :disabled="!canGrantRoot"
            />
          </div>
        </div>
      </div>

      <template #footer>
        <div class="flex justify-end gap-2">
          <n-button size="small" @click="createOpen = false">取消</n-button>
          <n-button size="small" type="primary" :loading="submitting" @click="submitCreate">
            下发任务
          </n-button>
        </div>
      </template>
    </n-modal>
  </div>
</template>
