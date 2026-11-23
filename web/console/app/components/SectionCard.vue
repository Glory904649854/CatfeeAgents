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
      class="flex items-center justify-between gap-3 px-5 pb-3 pt-5"
    >
      <div class="min-w-0">
        <h2 class="section-title">{{ title }}</h2>
        <p v-if="subtitle" class="mt-0.5 text-[11px] text-faint">{{ subtitle }}</p>
      </div>
      <slot name="extra" />
    </header>
    <div :class="flush ? '' : ['px-5 pb-5', bodyClass]">
      <slot />
    </div>
  </section>
</template>
