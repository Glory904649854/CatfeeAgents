<script setup lang="ts">
const props = defineProps<{ mobile?: boolean }>()
const emit = defineEmits<{ navigate: [] }>()

const route = useRoute()

const items = [
  { to: '/', label: '概览', short: '概览', icon: 'i-lucide-layout-dashboard' },
  { to: '/devices', label: '设备', short: '设备', icon: 'i-lucide-smartphone' },
  { to: '/tasks', label: '任务', short: '任务', icon: 'i-lucide-list-checks' },
  { to: '/logs', label: '事件日志', short: '日志', icon: 'i-lucide-scroll-text' },
  { to: '/settings', label: '设置', short: '设置', icon: 'i-lucide-settings' }
]

function isActive(to: string): boolean {
  return to === '/' ? route.path === '/' : route.path.startsWith(to)
}

function onClick() {
  if (props.mobile) emit('navigate')
}
</script>

<template>
  <nav class="space-y-0.5">
    <NuxtLink
      v-for="item in items"
      :key="item.to"
      :to="item.to"
      class="nav-item"
      :class="{ 'nav-item-active': isActive(item.to) }"
      @click="onClick"
    >
      <span
        v-if="isActive(item.to)"
        class="absolute left-0 top-1/2 h-4 w-[3px] -translate-y-1/2 rounded-full bg-brand"
      />
      <span :class="item.icon" class="text-[17px]" />
      <span>{{ item.label }}</span>
    </NuxtLink>
  </nav>
</template>
