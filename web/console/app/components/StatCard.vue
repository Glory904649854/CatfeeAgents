<script setup lang="ts">
withDefaults(
  defineProps<{
    label: string
    value: string | number
    icon: string
    hint?: string
    tone?: 'neutral' | 'brand' | 'success' | 'warning' | 'violet' | 'danger'
  }>(),
  { hint: '', tone: 'neutral' }
)

const toneClass: Record<string, string> = {
  neutral: 'bg-subtle text-ink',
  brand: 'bg-brand/10 text-brand',
  success: 'bg-success/10 text-success',
  warning: 'bg-warning/10 text-warning',
  violet: 'bg-violet/10 text-violet',
  danger: 'bg-danger/10 text-danger'
}
</script>

<template>
  <div class="flat-card p-4 sm:p-5">
    <div class="flex items-start justify-between gap-3">
      <div class="min-w-0">
        <p class="label-xs">{{ label }}</p>
        <p class="metric-value mt-2">{{ value }}</p>
      </div>
      <span
        class="flex h-9 w-9 shrink-0 items-center justify-center rounded-[11px]"
        :class="toneClass[tone]"
      >
        <span :class="icon" class="text-[17px]" />
      </span>
    </div>
    <p v-if="hint" class="mt-2.5 flex items-center gap-1 text-[11px] text-faint">
      <span class="h-1 w-1 rounded-full bg-faint" />
      {{ hint }}
    </p>
  </div>
</template>
