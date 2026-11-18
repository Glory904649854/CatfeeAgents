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
        <h1 class="text-[34px] font-700 leading-[1.28] tracking-tight text-white xl:text-[40px]">
          CatfeeAgents<br>控制台
        </h1>
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

        <h2 class="text-[24px] font-700 tracking-tight text-ink">登录控制台</h2>

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

        <div class="mt-8 flex items-center gap-2 border-t border-line pt-4 text-[10.5px] text-faint">
          <span class="i-lucide-lock text-[13px]" />
          仅限内部使用
        </div>
      </div>
    </section>
  </div>
</template>
