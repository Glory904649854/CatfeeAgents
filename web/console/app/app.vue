<script setup lang="ts">
import { naiveThemeOverrides } from '~/utils/theme'
</script>

<template>
  <n-config-provider :theme-overrides="naiveThemeOverrides">
    <NuxtLayout>
      <NuxtPage />
    </NuxtLayout>
  </n-config-provider>
</template>
