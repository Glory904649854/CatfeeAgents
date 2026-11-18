<script setup lang="ts">
import { useStore } from 'vuex'
import type { RootState } from '~/store'

const store = useStore<RootState>()
const route = useRoute()

const navItems = [
  { to: '/', short: '概览', icon: 'i-lucide-layout-dashboard' },
  { to: '/devices', short: '设备', icon: 'i-lucide-smartphone' },
  { to: '/tasks', short: '任务', icon: 'i-lucide-list-checks' },
  { to: '/logs', short: '日志', icon: 'i-lucide-scroll-text' },
  { to: '/settings', short: '设置', icon: 'i-lucide-settings' }
]

function isActive(to: string): boolean {
  return to === '/' ? route.path === '/' : route.path.startsWith(to)
}

useLiveStream()

watch(
  () => route.path,
  () => store.commit('ui/SET_DRAWER', false)
)
</script>

<template>
  <div class="min-h-screen bg-canvas">
    <!-- 桌面侧栏 -->
    <aside
      class="fixed inset-y-0 left-0 z-30 hidden w-[232px] flex-col border-r border-line bg-white lg:flex"
    >
      <div class="flex h-16 shrink-0 items-center px-5">
        <AppBrand />
      </div>

      <div class="flex-1 overflow-y-auto px-3 py-3">
        <AppNav />
      </div>

      <div class="shrink-0 px-3 pb-3">
        <div class="rounded-[12px] border border-line bg-soft px-3 py-2.5">
          <div class="flex items-center justify-between">
            <span class="text-[10.5px] text-muted">控制台连接</span>
            <LiveBadge />
          </div>
          </div>
      </div>

      <div class="shrink-0 border-t border-line p-3">
        <AppUserChip />
      </div>
    </aside>

    <!-- 移动端顶栏 -->
    <header
      class="sticky top-0 z-20 flex h-14 items-center gap-2 border-b border-line bg-white/95 px-3 backdrop-blur lg:hidden"
    >
      <button
        type="button"
        class="icon-btn border-0 bg-transparent"
        aria-label="打开菜单"
        @click="store.commit('ui/SET_DRAWER', true)"
      >
        <span class="i-lucide-menu text-[19px]" />
      </button>
      <AppBrand compact />
      <div class="ml-auto pr-1">
        <LiveBadge />
      </div>
    </header>

    <!-- 移动端抽屉 -->
    <Transition name="fade">
      <div
        v-if="store.state.ui.drawerOpen"
        class="fixed inset-0 z-40 bg-ink/35 lg:hidden"
        @click="store.commit('ui/SET_DRAWER', false)"
      />
    </Transition>
    <aside
      class="fixed inset-y-0 left-0 z-50 flex w-[268px] flex-col border-r border-line bg-white transition-transform duration-200 lg:hidden"
      :class="store.state.ui.drawerOpen ? 'translate-x-0' : '-translate-x-full'"
    >
      <div class="flex h-16 shrink-0 items-center justify-between px-4">
        <AppBrand />
        <button
          type="button"
          class="icon-btn border-0 bg-transparent"
          aria-label="关闭菜单"
          @click="store.commit('ui/SET_DRAWER', false)"
        >
          <span class="i-lucide-x text-[18px]" />
        </button>
      </div>
      <div class="flex-1 overflow-y-auto px-3 py-2">
        <AppNav mobile @navigate="store.commit('ui/SET_DRAWER', false)" />
      </div>
      <div class="shrink-0 border-t border-line p-3">
        <AppUserChip />
      </div>
    </aside>

    <!-- 主内容 -->
    <main class="min-h-screen pb-24 lg:pb-0 lg:pl-[232px]">
      <div class="mx-auto w-full max-w-[1420px] px-4 py-5 sm:px-6 lg:px-8 lg:py-7">
        <slot />
      </div>
    </main>

    <!-- 移动端底部导航 -->
    <nav
      class="fixed inset-x-0 bottom-0 z-30 border-t border-line bg-white/95 backdrop-blur lg:hidden"
      style="padding-bottom: env(safe-area-inset-bottom)"
    >
      <div class="flex h-[58px] items-stretch">
        <NuxtLink
          v-for="item in navItems"
          :key="item.to"
          :to="item.to"
          class="flex flex-1 flex-col items-center justify-center gap-0.5 transition"
          :class="isActive(item.to) ? 'text-ink' : 'text-faint'"
        >
          <span :class="item.icon" class="text-[18px]" />
          <span class="text-[9.5px] font-500">{{ item.short }}</span>
          <span
            class="h-1 w-1 rounded-full"
            :class="isActive(item.to) ? 'bg-brand' : 'bg-transparent'"
          />
        </NuxtLink>
      </div>
    </nav>
  </div>
</template>

<style scoped>
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.16s ease;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
