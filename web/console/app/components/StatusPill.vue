<script setup lang="ts">
withDefaults(
  defineProps<{
    label: string
    className?: string
    dotClassName?: string
    pulse?: boolean
  }>(),
  {
    className: 'bg-subtle text-muted',
    dotClassName: 'bg-faint',
    pulse: false
  }
)
</script>

<template>
  <span
    class="inline-flex items-center gap-1.5 rounded-full px-2 py-[3px] text-[11px] font-600 whitespace-nowrap"
    :class="className"
  >
    <span
      class="h-1.5 w-1.5 rounded-full"
      :class="[dotClassName, pulse ? 'live-dot' : '']"
    />
    {{ label }}
  </span>
</template>
