<script setup lang="ts">
withDefaults(
  defineProps<{
    compact?: boolean
    dark?: boolean
  }>(),
  { compact: false, dark: false }
)
</script>

<template>
  <NuxtLink to="/" class="inline-flex min-w-0 items-center gap-2.5">
    <span
      class="flex shrink-0 items-center justify-center overflow-hidden rounded-[10px] bg-white"
      :class="compact ? 'h-8 w-8' : 'h-10 w-10'"
    >
      <img
        src="/brand/logo.png"
        alt="CatfeeAgents"
        class="h-full w-full object-contain"
        :class="dark ? '' : 'brand-logo'"
      >
    </span>
    <span class="min-w-0 leading-none">
      <span
        class="block font-700 tracking-tight"
        :class="[compact ? 'text-[14px]' : 'text-[15.5px]', dark ? 'text-white' : 'text-ink']"
      >CatfeeAgents</span>
      <span
        v-if="!compact"
        class="mt-1 block text-[8.5px] font-600 tracking-[0.2em]"
        :class="dark ? 'text-white/45' : 'text-faint'"
      >AGENT CONSOLE</span>
    </span>
  </NuxtLink>
</template>
