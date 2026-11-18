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
  { label: 'Node', value: info.value?.runtime.node ?? '—' },
  { label: '平台', value: info.value?.runtime.platform ?? '—' },
  { label: '设备数', value: String(info.value?.counts.devices ?? 0) },
  { label: '任务数', value: String(info.value?.counts.tasks ?? 0) },
  { label: '事件数', value: String(info.value?.counts.events ?? 0) },
  { label: '会话有效期', value: `${info.value?.auth.sessionDays ?? 30} 天` }
])

const securityNotes = [
  '默认管理员密码仅在数据库为空时创建。部署前请通过 NUXT_ADMIN_PASSWORD 设置强密码。',
  '生产环境请使用 HTTPS。NUXT_PUBLIC_URL 为 https 时会自动启用 Secure Cookie。',
  '设备凭证保存在设备安全存储中，撤销授权后该设备的所有指令立即失效。',
  'Root 能力默认关闭，Web 端只暴露任务与控制指令，不提供任意 Shell 执行入口。',
  '日志与截图按任务授权回传，建议在网关层限制访问来源并设置保留周期。'
]

const stack = [
  'Nuxt 4',
  'Vue 3',
  'TypeScript',
  'Naive UI',
  'UnoCSS',
  'Vuex 4',
  'Nitro',
  'SQLite'
]
</script>

<template>
  <div>
    <PageHeader title="设置" subtitle="控制台信息、Agent 接入方式与安全说明。" />

    <div class="grid items-start gap-4 lg:grid-cols-2">
      <SectionCard title="当前账号" subtitle="控制台登录身份">
        <div class="flex items-center gap-3">
          <span
            class="flex h-12 w-12 items-center justify-center rounded-full bg-ink text-[16px] font-700 text-white"
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
          <div class="flex items-center justify-between text-[11.5px]">
            <span class="text-faint">密码管理</span>
            <span class="text-muted">部署时通过环境变量配置</span>
          </div>
        </div>
      </SectionCard>

      <SectionCard title="系统信息" subtitle="当前 Nitro / SQLite 运行状态">
        <div class="grid grid-cols-2 gap-x-4 gap-y-3.5">
          <div v-for="field in systemFields" :key="field.label" class="min-w-0">
            <p class="text-[10px] text-faint">{{ field.label }}</p>
            <p class="mt-0.5 truncate text-[11.5px] font-600 text-ink">{{ field.value }}</p>
          </div>
        </div>
        <div class="mt-4 border-t border-line/70 pt-3">
          <p class="text-[10px] text-faint">SQLite 路径</p>
          <div class="mt-1 flex items-center justify-between gap-2">
            <span class="truncate font-mono text-[10.5px] text-muted">{{ info?.dbPath || '—' }}</span>
            <CopyButton v-if="info?.dbPath" :value="info.dbPath" />
          </div>
        </div>
      </SectionCard>
    </div>

    <SectionCard
      class="mt-4"
      title="Agent 接入"
      subtitle="单端口同时承载控制台、API 与设备 WebSocket"
    >
      <div class="space-y-2.5">
        <div class="rounded-[10px] border border-line bg-soft px-3 py-2.5">
          <div class="flex items-center justify-between gap-2">
            <p class="text-[10px] text-faint">Agent WebSocket</p>
            <CopyButton v-if="info?.connection.wsUrl" :value="info.connection.wsUrl" />
          </div>
          <p class="mt-1 break-all font-mono text-[11px] text-ink">
            {{ info?.connection.wsUrl || '—' }}
          </p>
        </div>
        <div class="rounded-[10px] border border-line bg-soft px-3 py-2.5">
          <div class="flex items-center justify-between gap-2">
            <p class="text-[10px] text-faint">设备注册接口</p>
            <CopyButton v-if="info?.connection.enrollUrl" :value="info.connection.enrollUrl" />
          </div>
          <p class="mt-1 break-all font-mono text-[11px] text-ink">
            {{ info?.connection.enrollUrl || '—' }}
          </p>
        </div>
        <div class="rounded-[10px] border border-line bg-soft px-3 py-2.5">
          <div class="flex items-center justify-between gap-2">
            <p class="text-[10px] text-faint">控制台基地址</p>
            <CopyButton v-if="info?.connection.baseUrl" :value="info.connection.baseUrl" />
          </div>
          <p class="mt-1 break-all font-mono text-[11px] text-ink">
            {{ info?.connection.baseUrl || '—' }}
          </p>
        </div>
      </div>

      <div class="mt-4 grid gap-2 sm:grid-cols-3">
        <div class="rounded-[10px] border border-line px-3 py-2.5">
          <p class="text-[10px] text-faint">协议版本</p>
          <p class="mt-0.5 text-[11.5px] font-600 text-ink">v1</p>
        </div>
        <div class="rounded-[10px] border border-line px-3 py-2.5">
          <p class="text-[10px] text-faint">心跳间隔</p>
          <p class="mt-0.5 text-[11.5px] font-600 text-ink">30 秒</p>
        </div>
        <div class="rounded-[10px] border border-line px-3 py-2.5">
          <p class="text-[10px] text-faint">注册码有效期</p>
          <p class="mt-0.5 text-[11.5px] font-600 text-ink">10 分钟 / 一次性</p>
        </div>
      </div>
    </SectionCard>

    <div class="mt-4 grid gap-4 lg:grid-cols-2">
      <SectionCard title="安全说明" subtitle="远程 Agent 管理的默认边界">
        <ul class="space-y-3">
          <li
            v-for="(note, index) in securityNotes"
            :key="index"
            class="flex items-start gap-2 text-[11.5px] leading-5 text-muted"
          >
            <span class="i-lucide-shield-check mt-0.5 shrink-0 text-[14px] text-success" />
            {{ note }}
          </li>
        </ul>
      </SectionCard>

      <SectionCard title="审计日志" subtitle="最近的管理端操作" flush>
        <template #extra>
          <button class="link-quiet text-[11px]" @click="refreshAudit()">刷新</button>
        </template>
        <div v-if="audits.length === 0">
          <EmptyState icon="i-lucide-history" title="暂无审计记录" />
        </div>
        <div v-else class="max-h-[360px] divide-y divide-line/70 overflow-y-auto">
          <div v-for="audit in audits" :key="audit.id" class="px-4 py-3">
            <div class="flex items-center gap-2">
              <p class="text-[11.5px] font-600 text-ink">
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

    <SectionCard class="mt-4" title="关于" subtitle="CatfeeAgents · 控制台">
      <div class="flex flex-wrap gap-1.5">
        <span v-for="item in stack" :key="item" class="chip">{{ item }}</span>
      </div>
      <p class="mt-3 text-[11px] leading-5 text-muted">
        本控制台只管理已注册、已授权的设备。所有任务、控制指令与设备事件都带设备、任务和指令标识，
        便于后续接入权限、审计与多设备调度。
      </p>
    </SectionCard>
  </div>
</template>
