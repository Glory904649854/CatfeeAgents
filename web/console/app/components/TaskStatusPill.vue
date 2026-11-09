<script setup lang="ts">
import type { TaskStatus } from '~~/shared/types'

const props = defineProps<{ status: TaskStatus }>()
const meta = computed(() => taskStatusMeta(props.status))
</script>

<template>
  <StatusPill
    :label="meta.label"
    :class-name="meta.className"
    :dot-class-name="meta.dotClassName"
    :pulse="status === 'running'"
  />
</template>
