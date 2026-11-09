<script setup lang="ts">
import { useStore } from 'vuex'
import type { RootState } from '~/store'

const store = useStore<RootState>()
const user = computed(() => store.state.auth.user)
const { logout } = useSession()
const { dialog, message } = useFeedback()

const roleLabels: Record<string, string> = {
  owner: '所有者',
  admin: '管理员',
  operator: '操作员',
  viewer: '只读'
}

function confirmLogout() {
  dialog.warning({
    title: '退出登录',
    content: '确认退出当前控制台会话？',
    positiveText: '退出',
    negativeText: '取消',
    onPositiveClick: async () => {
      await logout()
      message.success('已退出登录')
      await navigateTo('/login')
    }
  })
}
</script>

<template>
  <div v-if="user" class="flex items-center gap-2.5 rounded-[12px] px-1 py-1">
    <span
      class="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-ink text-[13px] font-600 text-white"
    >
      {{ user.displayName.slice(0, 1) }}
    </span>
    <span class="min-w-0 flex-1">
      <span class="block truncate text-[12.5px] font-600 text-ink">{{ user.displayName }}</span>
      <span class="block truncate text-[10.5px] text-faint">
        {{ roleLabels[user.role] || user.role }} · {{ user.username }}
      </span>
    </span>
    <button class="icon-btn h-8 w-8 border-0 bg-transparent" title="退出登录" @click="confirmLogout">
      <span class="i-lucide-log-out text-[16px]" />
    </button>
  </div>
</template>
