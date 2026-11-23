<script setup lang="ts">
defineProps<{
  title: string
  subtitle?: string
  backTo?: string
}>()
</script>

<template>
  <div class="mb-5 flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
    <div class="min-w-0">
      <NuxtLink
        v-if="backTo"
        :to="backTo"
        class="mb-2 inline-flex items-center gap-1 text-[11.5px] text-faint transition hover:text-ink"
      >
        <span class="i-lucide-arrow-left text-[13px]" />
        返回
      </NuxtLink>
      <h1 class="page-title truncate">{{ title }}</h1>
      <p v-if="subtitle" class="page-sub">{{ subtitle }}</p>
    </div>
    <div class="page-actions flex shrink-0 flex-wrap items-center gap-2">
      <slot name="actions" />
    </div>
  </div>
</template>

<style scoped>
.page-actions :deep(.n-button) {
  --n-height: 40px !important;
  --n-padding: 0 16px !important;
}
</style>
