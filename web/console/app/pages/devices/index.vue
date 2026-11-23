<script setup lang="ts">
import QRCode from 'qrcode'
import { useStore } from 'vuex'
import type { RootState } from '~/store'

useHead({ title: '设备 · CatfeeAgents 控制台' })

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
  connection: { baseUrl: string; wsUrl: string; enrollUrl: string }
} | null>(null)
const inviteQr = ref('')

function enrollPayload(value: { token: string; connection: { baseUrl: string } }) {
  const base = value.connection.baseUrl.replace(/\/+$/, '')
  return `catfee://enroll?url=${encodeURIComponent(base)}&code=${encodeURIComponent(value.token)}`
}

watch(invite, async (value) => {
  if (!value) {
    inviteQr.value = ''
    return
  }
  try {
    inviteQr.value = await QRCode.toDataURL(enrollPayload(value), {
      width: 480,
      margin: 1,
      errorCorrectionLevel: 'M',
      color: { dark: '#111827', light: '#FFFFFF' }
    })
  } catch {
    inviteQr.value = ''
  }
})

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
let bindWatcher: ReturnType<typeof setInterval> | undefined
let baselineIds = new Set<string>()

function stopBindWatch() {
  if (bindWatcher) {
    clearInterval(bindWatcher)
    bindWatcher = undefined
  }
}

function startBindWatch() {
  stopBindWatch()
  bindWatcher = setInterval(async () => {
    if (!inviteOpen.value || !invite.value) {
      stopBindWatch()
      return
    }
    try {
      const snapshot = await apiRequest<{ devices: { id: string; name: string }[] }>(
        '/api/devices',
        { query: { status: 'all' } }
      )
      const added = snapshot.devices.find((device) => !baselineIds.has(device.id))
      if (!added) return
      stopBindWatch()
      inviteOpen.value = false
      message.success(`设备「${added.name}」已接入`)
      status.value = 'all'
      await load()
    } catch {
      // 轮询失败时保留弹窗，下一次继续
    }
  }, 2000)
}

watch(inviteOpen, (open) => {
  if (!open) {
    invite.value = null
    stopBindWatch()
  }
})

onMounted(async () => {
  await load()
  if (route.query.enroll === '1') {
    await openInvite()
    await router.replace({ query: {} })
  }
})

onBeforeUnmount(() => {
  clearTimeout(searchTimer)
  stopBindWatch()
})

async function openInvite() {
  if (invite.value) {
    inviteOpen.value = true
    return
  }
  inviteOpen.value = true
  inviteLoading.value = true
  try {
    // 先记下当前设备，绑定完成后用新出现的设备判断接入成功
    baselineIds = await apiRequest<{ devices: { id: string }[] }>('/api/devices', {
      query: { status: 'all' }
    })
      .then((snapshot) => new Set(snapshot.devices.map((device) => device.id)))
      .catch(() => new Set(devices.value.map((device) => device.id)))

    invite.value = await apiRequest<{
      token: string
      expiresAt: string
      connection: { baseUrl: string; wsUrl: string; enrollUrl: string }
    }>('/api/devices/enroll', { method: 'POST', body: {} })
    startBindWatch()
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
    <PageHeader title="设备">
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
          class="inline-flex h-9 shrink-0 items-center gap-1.5 whitespace-nowrap rounded-full border px-3.5 text-[12px] font-600 transition"
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
          <p class="mt-2 text-[10.5px] text-faint">
            10 分钟内有效 · 仅可使用一次 · 支持扫码或手动输入
          </p>
          <div class="mt-2 flex justify-center">
            <CopyButton :value="invite.token" label="复制注册码" />
          </div>
        </div>

        <div class="rounded-[12px] border border-line bg-white p-4 text-center">
          <p class="label-xs">SCAN TO BIND</p>
          <img
            v-if="inviteQr"
            :src="inviteQr"
            alt="设备绑定二维码"
            class="mx-auto mt-3 h-[180px] w-[180px] rounded-[8px]"
          >
          <div
            v-else
            class="mx-auto mt-3 h-[180px] w-[180px] animate-pulse rounded-[8px] bg-subtle"
          />
          <p class="mt-3 text-[11px] text-muted">APK：设置 → 控制台 → 扫码绑定</p>
          <p class="mt-0.5 text-[10px] text-faint">
            扫码后自动绑定 · 成功后本窗口关闭
          </p>
        </div>

        <div
          class="rounded-[10px] border border-warning/20 bg-warning/5 px-3 py-2.5 text-[11px] leading-5 text-warning"
        >
          让 APK 扫码或输入注册码完成接入。
        </div>

        <div class="flex justify-end gap-2 pt-1">
          <n-button size="small" @click="regenerate">重新生成</n-button>
          <n-button size="small" type="primary" @click="inviteOpen = false">完成</n-button>
        </div>
      </div>
    </n-modal>
  </div>
</template>
