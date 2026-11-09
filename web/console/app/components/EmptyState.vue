<script setup lang="ts">
withDefaults(
  defineProps<{
    icon?: string
    title: string
    description?: string
  }>(),
  { icon: 'i-lucide-inbox', description: '' }
)
</script>

<template>
  <div class="flex flex-col items-center justify-center px-4 py-12 text-center">
    <span class="flex h-12 w-12 items-center justify-center rounded-[14px] bg-subtle text-faint">
      <span :class="icon" class="text-[21px]" />
    </span>
    <h3 class="mt-3 text-[13.5px] font-600 text-ink">{{ title }}</h3>
    <p v-if="description" class="mt-1 max-w-[320px] text-[11.5px] leading-5 text-muted">
      {{ description }}
    </p>
    <div class="mt-4">
      <slot />
    </div>
  </div>
</template>
