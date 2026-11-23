<script setup lang="ts">
import { useStore } from 'vuex'
import type { RootState } from '~/store'

const props = defineProps<{ dark?: boolean }>()

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
      class="flex h-9 w-9 shrink-0 items-center justify-center rounded-full text-[13px] font-600 text-white"
      :class="props.dark ? 'bg-brand' : 'bg-ink'"
    >
      {{ user.displayName.slice(0, 1) }}
    </span>
    <span class="min-w-0 flex-1">
      <span
        class="block truncate text-[12.5px] font-600"
        :class="props.dark ? 'text-white' : 'text-ink'"
      >{{ user.displayName }}</span>
      <span
        class="block truncate text-[10.5px]"
        :class="props.dark ? 'text-white/45' : 'text-faint'"
      >
        {{ roleLabels[user.role] || user.role }} · {{ user.username }}
      </span>
    </span>
    <button
      class="flex h-8 w-8 items-center justify-center rounded-full transition"
      :class="props.dark ? 'text-white/50 hover:bg-white/10 hover:text-white' : 'text-muted hover:bg-subtle'"
      title="退出登录"
      @click="confirmLogout"
    >
      <span class="i-lucide-log-out text-[16px]" />
    </button>
  </div>
</template>
