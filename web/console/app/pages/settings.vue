<script setup lang="ts">
import { useStore } from 'vuex'
import type { AuditSummary } from '~~/shared/types'
import type { RootState } from '~/store'

useHead({ title: '设置 · CatfeeAgents 控制台' })

interface SystemInfo {
  version: string
  uptimeSeconds: number
  dbPath: string
  connection: {
    baseUrl: string
    wsUrl: string
    enrollUrl: string
  }
  auth: {
    sessionDays: number
  }
  runtime: {
    node: string
    platform: string
  }
  counts: {
    devices: number
    tasks: number
    events: number
  }
}

const store = useStore<RootState>()
const user = computed(() => store.state.auth.user)

const { data: info } = await useFetch<SystemInfo>('/api/system/info', { key: 'system-info' })
const { data: auditResponse, refresh: refreshAudit } = await useFetch<{
  audits: AuditSummary[]
}>('/api/audit', { key: 'audit-list', query: { limit: 20 } })

const audits = computed(() => auditResponse.value?.audits ?? [])

const roleLabels: Record<string, string> = {
  owner: '所有者',
  admin: '管理员',
  operator: '操作员',
  viewer: '只读'
}

const actionLabels: Record<string, string> = {
  'auth.login': '登录控制台',
  'auth.logout': '退出登录',
  'device.enroll_invite': '生成设备注册码',
  'device.enrolled_by_agent': '设备接入',
  'device.revoke': '撤销设备授权',
  'task.create': '创建任务',
  'task.pause': '暂停任务',
  'task.resume': '继续任务',
  'task.stop': '停止任务'
}

const systemFields = computed(() => [
  { label: '控制台版本', value: info.value?.version ?? '—' },
  { label: '运行时长', value: info.value ? formatSeconds(info.value.uptimeSeconds) : '—' },
  { label: '设备数', value: String(info.value?.counts.devices ?? 0) },
  { label: '任务数', value: String(info.value?.counts.tasks ?? 0) },
  { label: '事件数', value: String(info.value?.counts.events ?? 0) }
])

const securityNotes = [
  '仅管理已授权设备，撤销后该设备指令立即失效。',
  'Root 能力默认关闭，需要在设备策略中显式授权。',
  '控制台包含页面文字与任务内容，请限制访问权限。'
]

const tab = ref<'account' | 'system' | 'agent' | 'security' | 'audit'>('account')
const tabs = [
  { value: 'account', label: '账号' },
  { value: 'system', label: '系统信息' },
  { value: 'agent', label: 'Agent 接入' },
  { value: 'security', label: '安全说明' },
  { value: 'audit', label: '审计日志' }
] as const
</script>

<template>
  <div>
    <PageHeader title="设置" />

    <div class="mb-4 flex gap-2 overflow-x-auto pb-1">
      <button
        v-for="item in tabs"
        :key="item.value"
        type="button"
        class="h-9 shrink-0 rounded-full border px-4 text-[12px] font-600 transition"
        :class="
          tab === item.value
            ? 'border-ink bg-ink text-white'
            : 'border-line bg-white text-muted hover:bg-subtle'
        "
        @click="tab = item.value"
      >
        {{ item.label }}
      </button>
    </div>

    <template v-if="tab === 'account'">
      <div class="grid items-start gap-4 lg:grid-cols-2">
        <SectionCard title="当前账号">
          <div class="flex items-center gap-3">
            <span
              class="flex h-12 w-12 items-center justify-center rounded-full bg-brand text-[16px] font-700 text-white"
            >
              {{ user?.displayName?.slice(0, 1) || 'C' }}
            </span>
            <div class="min-w-0">
              <p class="truncate text-[14px] font-700 text-ink">{{ user?.displayName || '—' }}</p>
              <p class="mt-0.5 text-[11px] text-faint">
                {{ user?.username }} · {{ roleLabels[user?.role || ''] || user?.role }}
              </p>
            </div>
          </div>
          <div class="mt-4 space-y-2.5 border-t border-line/70 pt-4">
            <div class="flex items-center justify-between text-[11.5px]">
              <span class="text-faint">账号 ID</span>
              <span class="font-mono text-[10.5px] text-ink">{{ user?.id || '—' }}</span>
            </div>
            <div class="flex items-center justify-between text-[11.5px]">
              <span class="text-faint">创建时间</span>
              <span class="text-ink">{{ formatFullDateTime(user?.createdAt) }}</span>
            </div>
          </div>
        </SectionCard>

        <SectionCard title="系统信息">
          <div class="grid grid-cols-2 gap-x-4 gap-y-4">
            <div v-for="field in systemFields" :key="field.label" class="min-w-0">
              <p class="text-[11px] text-faint">{{ field.label }}</p>
              <p class="mt-1 truncate text-[12px] font-600 text-ink">{{ field.value }}</p>
            </div>
          </div>
        </SectionCard>
      </div>
    </template>

    <template v-else-if="tab === 'system'">
      <div class="grid gap-4 lg:grid-cols-2">
        <SectionCard title="系统信息">
          <div class="grid grid-cols-2 gap-x-4 gap-y-4">
            <div v-for="field in systemFields" :key="field.label" class="min-w-0">
              <p class="text-[11px] text-faint">{{ field.label }}</p>
              <p class="mt-1 truncate text-[12px] font-600 text-ink">{{ field.value }}</p>
            </div>
          </div>
        </SectionCard>
        <SectionCard title="关于">
          <p class="text-[12px] text-muted">CatfeeAgents 控制台 · v{{ info?.version || '0.1.0' }}</p>
          <p class="mt-2 font-mono text-[11px] text-faint">
            {{ info?.runtime.node || 'Node' }} · {{ info?.runtime.platform || '—' }}
          </p>
        </SectionCard>
      </div>
    </template>

    <template v-else-if="tab === 'agent'">
      <SectionCard title="Agent 接入">
        <div class="space-y-4">
          <div class="flex flex-col gap-1 sm:flex-row sm:items-center">
            <span class="w-[120px] shrink-0 text-[11px] text-faint">WebSocket</span>
            <span class="break-all font-mono text-[11.5px] text-ink">{{ info?.connection.wsUrl || '—' }}</span>
          </div>
          <div class="flex flex-col gap-1 sm:flex-row sm:items-center">
            <span class="w-[120px] shrink-0 text-[11px] text-faint">注册接口</span>
            <span class="break-all font-mono text-[11.5px] text-ink">POST {{ info?.connection.enrollUrl || '—' }}</span>
          </div>
          <div class="flex flex-col gap-1 sm:flex-row sm:items-center">
            <span class="w-[120px] shrink-0 text-[11px] text-faint">心跳 / 会话</span>
            <span class="text-[11.5px] text-ink">30s · {{ info?.auth.sessionDays || 30 }} 天</span>
          </div>
        </div>
      </SectionCard>
    </template>

    <template v-else-if="tab === 'security'">
      <div class="grid gap-4 lg:grid-cols-2">
        <SectionCard title="安全说明">
          <ul class="space-y-3">
            <li
              v-for="(note, index) in securityNotes"
              :key="index"
              class="flex items-start gap-2 text-[12px] leading-5 text-muted"
            >
              <span class="i-lucide-shield-check mt-0.5 shrink-0 text-[14px] text-success" />
              {{ note }}
            </li>
          </ul>
        </SectionCard>
        <SectionCard title="审计日志" flush>
          <div v-if="audits.length === 0">
            <EmptyState icon="i-lucide-history" title="暂无审计记录" />
          </div>
          <div v-else class="max-h-[360px] divide-y divide-line/70 overflow-y-auto">
            <div v-for="audit in audits.slice(0, 8)" :key="audit.id" class="px-5 py-3">
              <div class="flex items-center gap-2">
                <p class="text-[12px] font-600 text-ink">
                  {{ actionLabels[audit.action] || audit.action }}
                </p>
                <span class="ml-auto whitespace-nowrap text-[10px] text-faint">
                  <TimeText :value="audit.createdAt" />
                </span>
              </div>
              <p class="mt-0.5 truncate text-[10.5px] text-faint">
                {{ audit.actorName || '系统' }}
                <span v-if="audit.targetId"> · {{ shortId(audit.targetId) }}</span>
                <span v-if="audit.targetType"> · {{ audit.targetType }}</span>
              </p>
            </div>
          </div>
        </SectionCard>
      </div>
    </template>

    <template v-else>
      <SectionCard title="审计日志" flush>
        <template #extra>
          <button class="link-quiet text-[11px]" @click="refreshAudit()">刷新</button>
        </template>
        <div v-if="audits.length === 0">
          <EmptyState icon="i-lucide-history" title="暂无审计记录" />
        </div>
        <div v-else class="divide-y divide-line/70">
          <div v-for="audit in audits" :key="audit.id" class="px-5 py-3">
            <div class="flex items-center gap-2">
              <p class="text-[12px] font-600 text-ink">
                {{ actionLabels[audit.action] || audit.action }}
              </p>
              <span class="ml-auto whitespace-nowrap text-[10px] text-faint">
                <TimeText :value="audit.createdAt" />
              </span>
            </div>
            <p class="mt-0.5 truncate text-[10.5px] text-faint">
              {{ audit.actorName || '系统' }}
              <span v-if="audit.targetId"> · {{ shortId(audit.targetId) }}</span>
              <span v-if="audit.targetType"> · {{ audit.targetType }}</span>
            </p>
          </div>
        </div>
      </SectionCard>
    </template>
  </div>
</template>
