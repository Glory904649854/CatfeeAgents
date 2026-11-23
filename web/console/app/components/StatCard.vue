<script setup lang="ts">
const props = withDefaults(
  defineProps<{
    label: string
    value: string | number
    icon: string
    hint?: string
    tone?: 'neutral' | 'brand' | 'success' | 'warning' | 'violet' | 'danger'
  }>(),
  { hint: '', tone: 'neutral' }
)

const valueTone = computed(() =>
  props.tone === 'brand' || props.tone === 'danger' || props.tone === 'warning'
    ? 'text-brand'
    : 'text-ink'
)
</script>

<template>
  <div class="flat-card p-5">
    <p class="text-[12px] text-muted">{{ label }}</p>
    <p class="metric-value mt-3" :class="valueTone">{{ value }}</p>
    <p v-if="hint" class="mt-3 flex items-center gap-1 text-[11px] text-faint">
      <span class="h-1 w-1 rounded-full bg-faint" />
      {{ hint }}
    </p>
  </div>
</template>
