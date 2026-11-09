<script setup lang="ts">
withDefaults(
  defineProps<{
    title?: string
    subtitle?: string
    bodyClass?: string
    flush?: boolean
  }>(),
  { title: '', subtitle: '', bodyClass: '', flush: false }
)
</script>

<template>
  <section class="flat-card overflow-hidden">
    <header
      v-if="title"
      class="flex items-center justify-between gap-3 border-b border-line/80 px-4 py-3.5 sm:px-5"
    >
      <div class="min-w-0">
        <h2 class="text-[13.5px] font-600 text-ink">{{ title }}</h2>
        <p v-if="subtitle" class="mt-0.5 text-[11px] text-faint">{{ subtitle }}</p>
      </div>
      <slot name="extra" />
    </header>
    <div :class="flush ? '' : ['p-4 sm:p-5', bodyClass]">
      <slot />
    </div>
  </section>
</template>
