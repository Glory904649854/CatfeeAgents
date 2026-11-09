<script setup lang="ts">
const props = withDefaults(
  defineProps<{
    value: string
    label?: string
  }>(),
  { label: '' }
)

const { message } = useFeedback()
const copied = ref(false)

async function copy() {
  try {
    await navigator.clipboard.writeText(props.value)
  } catch {
    const textarea = document.createElement('textarea')
    textarea.value = props.value
    textarea.style.position = 'fixed'
    textarea.style.opacity = '0'
    document.body.appendChild(textarea)
    textarea.select()
    document.execCommand('copy')
    document.body.removeChild(textarea)
  }
  copied.value = true
  message.success('已复制')
  setTimeout(() => {
    copied.value = false
  }, 1600)
}
</script>

<template>
  <button
    type="button"
    class="inline-flex items-center gap-1 rounded-[8px] px-1.5 py-1 text-[10.5px] text-faint transition hover:bg-subtle hover:text-ink"
    :title="label || '复制'"
    @click.stop="copy"
  >
    <span :class="copied ? 'i-lucide-check' : 'i-lucide-copy'" class="text-[13px]" />
    <span v-if="label">{{ label }}</span>
  </button>
</template>
