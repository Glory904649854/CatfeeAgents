<script setup lang="ts">
import { useStore } from 'vuex'
import type { RootState } from '~/store'

definePageMeta({ layout: 'auth' })
useHead({ title: '登录 · CatfeeAgents 控制台' })

const store = useStore<RootState>()
const route = useRoute()
const form = reactive({ username: '', password: '' })
const error = ref('')
const loading = computed(() => store.state.auth.loading)

async function submit() {
  error.value = ''
  if (!form.username.trim() || !form.password) {
    error.value = '请输入用户名和密码'
    return
  }

  try {
    await store.dispatch('auth/login', {
      username: form.username.trim(),
      password: form.password
    })
    const redirect =
      typeof route.query.redirect === 'string' && route.query.redirect.startsWith('/')
        ? route.query.redirect
        : '/'
    await navigateTo(redirect)
  } catch (err: unknown) {
    const requestError = err as { data?: { message?: string }; statusMessage?: string }
    error.value =
      requestError?.data?.message || requestError?.statusMessage || '登录失败，请稍后重试'
  }
}

</script>

<template>
  <div class="grid min-h-screen bg-canvas lg:grid-cols-2">
    <!-- 品牌侧 -->
    <section class="relative hidden flex-col justify-center overflow-hidden bg-sidebar p-12 lg:flex xl:p-16">
      <div
        class="pointer-events-none absolute inset-0 opacity-[0.10]"
        style="background-image: radial-gradient(circle, #fff 1px, transparent 1px); background-size: 24px 24px"
      />
      <div
        class="pointer-events-none absolute -bottom-32 -right-32 h-[360px] w-[360px] rounded-full bg-brand/20 blur-[120px]"
      />

      <div class="relative max-w-[460px]">
        <span class="flex h-20 w-20 items-center justify-center overflow-hidden rounded-[22px] bg-white">
          <img src="/brand/logo.png" alt="CatfeeConsole" class="h-full w-full object-contain">
        </span>
        <h1 class="mt-8 text-[40px] font-700 leading-none tracking-tight text-white">
          Catfee<span class="text-brand">Console</span>
        </h1>
        <p class="mt-5 text-[15px] text-white/70">管理已授权 Android Agent 设备</p>
        <p class="mt-1.5 text-[11px] text-white/35">仅限授权设备与内部环境</p>
      </div>

      <div class="absolute bottom-10 left-12 right-12 flex items-center justify-between text-[10px] text-white/25 xl:left-16 xl:right-16">
        <span>CatfeeAgents · 控制台</span>
        <span>v0.1.0</span>
      </div>
    </section>

    <!-- 登录表单 -->
    <section class="flex items-center justify-center px-5 py-10 sm:px-8">
      <div class="w-full max-w-[420px]">
        <div class="mb-8 lg:hidden">
          <AppBrand />
        </div>

        <div class="rounded-[16px] border border-line bg-white p-6 sm:p-8">
          <h2 class="text-[28px] font-700 leading-9 tracking-tight text-ink">登录</h2>
          <p class="mt-1 text-[12px] text-muted">使用管理员账号登录控制台</p>

          <form class="mt-7 space-y-4" @submit.prevent="submit">
            <div>
              <label class="field-label mb-1.5 block">用户名</label>
              <n-input
                v-model:value="form.username"
                size="large"
                placeholder="admin"
                :input-props="{ autocomplete: 'username' }"
              />
            </div>

            <div>
              <label class="field-label mb-1.5 block">密码</label>
              <n-input
                v-model:value="form.password"
                type="password"
                show-password-on="click"
                size="large"
                placeholder="请输入密码"
                :input-props="{ autocomplete: 'current-password' }"
              />
            </div>

            <div
              v-if="error"
              class="flex items-start gap-2 rounded-[10px] border border-danger/15 bg-danger/5 px-3 py-2.5 text-[11.5px] leading-5 text-danger"
            >
              <span class="i-lucide-circle-alert mt-0.5 shrink-0 text-[13px]" />
              {{ error }}
            </div>

            <n-button
              type="primary"
              block
              attr-type="submit"
              :loading="loading"
              :disabled="loading"
              class="!h-11"
            >
              登录
            </n-button>
          </form>
        </div>

        <div class="mt-6 flex items-center justify-center gap-2 text-[10.5px] text-faint">
          <span class="i-lucide-lock text-[13px]" />
          仅限内部使用
        </div>
      </div>
    </section>
  </div>
</template>
