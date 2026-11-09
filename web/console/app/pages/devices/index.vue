<script setup lang="ts">
import { useStore } from 'vuex'
import type { RootState } from '~/store'

useHead({ title: '设备 · Catfee 控制台' })

const store = useStore<RootState>()
const route = useRoute()
const router = useRouter()
const { message } = useFeedback()

const status = ref('all')
const keyword = ref('')
const inviteOpen = ref(false)
const inviteLoading = ref(false)
const invite = ref<{
  token: string
  expiresAt: string
  connection: { wsUrl: string; enrollUrl: string }
} | null>(null)

const tabs = [
  { value: 'all', label: '全部' },
  { value: 'online', label: '在线' },
  { value: 'offline', label: '离线' },
  { value: 'pending', label: '待接入' },
  { value: 'revoked', label: '已撤销' }
]

const devices = computed(() => store.state.devices.items)
const counts = computed(() => store.state.devices.counts)
const loading = computed(() => store.state.devices.loading)
const filtered = computed(() => devices.value.length === 0 && loading.value)

let searchTimer: ReturnType<typeof setTimeout> | undefined

function load() {
  return store.dispatch('devices/fetch', { status: status.value, q: keyword.value })
}

watch(status, () => void load())
watch(keyword, () => {
  clearTimeout(searchTimer)
  searchTimer = setTimeout(() => void load(), 240)
})
watch(inviteOpen, (open) => {
  if (!open) invite.value = null
})

onMounted(async () => {
  await load()
  if (route.query.enroll === '1') {
    await openInvite()
    await router.replace({ query: {} })
  }
})

onBeforeUnmount(() => clearTimeout(searchTimer))

async function openInvite() {
  if (invite.value) {
    inviteOpen.value = true
    return
  }
  inviteOpen.value = true
  inviteLoading.value = true
  try {
    invite.value = await apiRequest<{
      token: string
      expiresAt: string
      connection: { wsUrl: string; enrollUrl: string }
    }>('/api/devices/enroll', { method: 'POST', body: {} })
  } catch (err: unknown) {
    inviteOpen.value = false
    const requestError = err as { data?: { message?: string } }
    message.error(requestError?.data?.message || '生成注册码失败')
  } finally {
    inviteLoading.value = false
  }
}
async function regenerate() {
  invite.value = null
  await openInvite()
}
</script>

<template>
  <div>
    <PageHeader title="设备" subtitle="管理已授权接入的 Android Agent 设备。">
      <template #actions>
        <n-button size="small" :loading="loading" @click="load">
          <template #icon>
            <span class="i-lucide-refresh-cw text-[14px]" />
          </template>
          刷新
        </n-button>
        <n-button type="primary" size="small" @click="openInvite">
          <template #icon>
            <span class="i-lucide-plus text-[14px]" />
          </template>
          注册设备
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

      <n-input
        v-model:value="keyword"
        clearable
        placeholder="搜索名称、设备 ID 或型号"
        size="small"
        class="sm:!w-[240px]"
      >
        <template #prefix>
          <span class="i-lucide-search text-[14px] text-faint" />
        </template>
      </n-input>
    </div>

    <div v-if="filtered" class="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
      <div v-for="index in 3" :key="index" class="flat-card h-[190px] animate-pulse p-5">
        <div class="h-9 w-9 rounded-[12px] bg-subtle" />
        <div class="mt-4 h-3 w-1/2 rounded bg-subtle" />
        <div class="mt-2 h-2.5 w-1/3 rounded bg-subtle" />
      </div>
    </div>

    <div v-else-if="devices.length === 0" class="flat-card">
      <EmptyState
        icon="i-lucide-smartphone"
        :title="keyword ? '没有匹配的设备' : '还没有接入设备'"
        :description="
          keyword
            ? '换一个关键词再试试。'
            : '生成一次性注册码，让 APK 主动连接控制台。设备只会接收已授权任务。'
        "
      >
        <n-button v-if="!keyword" size="small" type="primary" @click="openInvite">
          生成注册码
        </n-button>
        <n-button v-else size="small" @click="keyword = ''">清除搜索</n-button>
      </EmptyState>
    </div>

    <div v-else class="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
      <DeviceCard v-for="device in devices" :key="device.id" :device="device" />
    </div>

    <n-modal
      v-model:show="inviteOpen"
      preset="card"
      title="注册新设备"
      :style="{ width: 'min(520px, calc(100vw - 32px))' }"
    >
      <div v-if="inviteLoading" class="flex justify-center py-12">
        <n-spin size="small" />
      </div>

      <div v-else-if="invite" class="space-y-4">
        <div class="rounded-[12px] border border-line bg-soft p-4 text-center">
          <p class="label-xs">ENROLLMENT CODE</p>
          <p
            class="mt-2 select-all break-all font-mono text-[20px] font-700 tracking-[0.06em] text-ink"
          >
            {{ invite.token }}
          </p>
          <p class="mt-2 text-[10.5px] text-faint">10 分钟内有效 · 仅可使用一次</p>
          <div class="mt-2 flex justify-center">
            <CopyButton :value="invite.token" label="复制注册码" />
          </div>
        </div>

        <div class="space-y-2">
          <div class="rounded-[10px] border border-line px-3 py-2.5">
            <p class="text-[10px] text-faint">Agent WebSocket</p>
            <div class="mt-1 flex items-center justify-between gap-2">
              <span class="truncate font-mono text-[11px] text-ink">
                {{ invite.connection.wsUrl }}
              </span>
              <CopyButton :value="invite.connection.wsUrl" />
            </div>
          </div>
          <div class="rounded-[10px] border border-line px-3 py-2.5">
            <p class="text-[10px] text-faint">设备注册接口</p>
            <div class="mt-1 flex items-center justify-between gap-2">
              <span class="truncate font-mono text-[11px] text-ink">
                {{ invite.connection.enrollUrl }}
              </span>
              <CopyButton :value="invite.connection.enrollUrl" />
            </div>
          </div>
        </div>

        <div
          class="rounded-[10px] border border-warning/20 bg-warning/5 px-3 py-2.5 text-[11px] leading-5 text-warning"
        >
          在 APK 的远程管理入口输入注册码即可完成接入。设备上线后请核对名称、型号与能力列表。
        </div>

        <div class="flex justify-end gap-2 pt-1">
          <n-button size="small" @click="regenerate">重新生成</n-button>
          <n-button size="small" type="primary" @click="inviteOpen = false">完成</n-button>
        </div>
      </div>
    </n-modal>
  </div>
</template>
