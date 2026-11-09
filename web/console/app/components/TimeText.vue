<script setup lang="ts">
const props = withDefaults(
  defineProps<{
    value?: string | null
    mode?: 'relative' | 'datetime' | 'full'
  }>(),
  { value: null, mode: 'relative' }
)

const display = computed(() => {
  if (!props.value) return '—'
  if (props.mode === 'full') return formatFullDateTime(props.value)
  if (props.mode === 'datetime') return formatDateTime(props.value)
  return formatRelative(props.value)
})
</script>

<template>
  <span :title="formatFullDateTime(value)">{{ display }}</span>
</template>
