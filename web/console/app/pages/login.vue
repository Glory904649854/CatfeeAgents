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

const features = [
  { icon: 'i-lucide-shield-check', text: '设备主动接入，凭证可随时撤销' },
  { icon: 'i-lucide-radio', text: '实时事件流，任务状态一眼可见' },
  { icon: 'i-lucide-scroll-text', text: '逐步日志与操作审计，过程可回溯' }
]
</script>

<template>
  <div class="grid min-h-screen lg:grid-cols-[1.05fr_1fr]">
    <!-- 品牌侧 -->
    <section class="relative hidden flex-col justify-between overflow-hidden bg-ink p-10 lg:flex xl:p-14">
      <div
        class="pointer-events-none absolute inset-0 opacity-[0.14]"
        style="background-image: radial-gradient(circle, #fff 1px, transparent 1px); background-size: 24px 24px"
      />
      <div
        class="pointer-events-none absolute -bottom-24 -right-24 h-[320px] w-[320px] rounded-full bg-brand/20 blur-[120px]"
      />

      <div class="relative">
        <AppBrand dark />
      </div>

      <div class="relative max-w-[520px]">
        <span
          class="inline-flex items-center gap-2 rounded-full border border-white/15 px-3 py-1 text-[10px] font-600 tracking-[0.16em] text-white/55"
        >
          REMOTE AGENT MANAGEMENT
        </span>
        <h1 class="mt-5 text-[34px] font-700 leading-[1.28] tracking-tight text-white xl:text-[40px]">
          把设备 Agent<br>收进一个控制台。
        </h1>
        <p class="mt-4 max-w-[430px] text-[13px] leading-6 text-white/55">
          面向已授权 Android 设备的远程 Agent 管理。任务下发、实时事件、暂停继续停止，
          全部经过权限校验并可审计。
        </p>

        <div class="mt-8 space-y-3.5">
          <div
            v-for="feature in features"
            :key="feature.text"
            class="flex items-center gap-3 text-[12.5px] text-white/75"
          >
            <span
              class="flex h-8 w-8 shrink-0 items-center justify-center rounded-[10px] border border-white/10 bg-white/5"
            >
              <span :class="feature.icon" class="text-[15px] text-white/85" />
            </span>
            {{ feature.text }}
          </div>
        </div>
      </div>

      <div class="relative flex items-center justify-between text-[10px] text-white/30">
        <span>CatfeeAgents · 控制台</span>
        <span>v0.1.0</span>
      </div>
    </section>

    <!-- 登录表单 -->
    <section class="flex items-center justify-center px-5 py-10 sm:px-8">
      <div class="w-full max-w-[380px]">
        <div class="mb-9 lg:hidden">
          <AppBrand />
        </div>

        <p class="label-xs">SIGN IN</p>
        <h2 class="mt-2 text-[24px] font-700 tracking-tight text-ink">登录控制台</h2>
        <p class="mt-1.5 text-[12.5px] leading-5 text-muted">
          使用管理员账号进入设备与任务管理工作台。
        </p>

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
            size="large"
            block
            attr-type="submit"
            :loading="loading"
            :disabled="loading"
          >
            进入控制台
          </n-button>
        </form>

        <p class="mt-5 text-[11px] leading-5 text-faint">
          初始账号在首次启动时创建，可通过
          <code class="rounded bg-subtle px-1 py-0.5 font-mono text-[10.5px]">NUXT_ADMIN_USER</code>
          与
          <code class="rounded bg-subtle px-1 py-0.5 font-mono text-[10.5px]">NUXT_ADMIN_PASSWORD</code>
          配置。
        </p>

        <div class="mt-8 flex items-center gap-2 border-t border-line pt-4 text-[10.5px] text-faint">
          <span class="i-lucide-lock text-[13px]" />
          仅限已授权设备与内部环境使用
        </div>
      </div>
    </section>
  </div>
</template>
