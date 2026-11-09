<script setup lang="ts">
const props = defineProps<{ value: unknown }>()

const text = computed(() => {
  try {
    return JSON.stringify(props.value ?? {}, null, 2)
  } catch {
    return String(props.value)
  }
})
</script>

<template>
  <div class="relative">
    <div class="absolute right-1.5 top-1.5 z-10">
      <CopyButton :value="text" />
    </div>
    <pre
      class="max-h-[340px] overflow-auto whitespace-pre-wrap break-all rounded-[12px] border border-line bg-soft p-3 pr-10 font-mono text-[11px] leading-[1.65] text-muted"
    >{{ text }}</pre>
  </div>
</template>
