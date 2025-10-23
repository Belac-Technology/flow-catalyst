<template>
  <div :class="cardClasses">
    <div v-if="$slots.header" class="px-6 py-4 border-b border-gray-200">
      <slot name="header" />
    </div>
    <div class="px-6 py-4">
      <slot />
    </div>
    <div v-if="$slots.footer" class="px-6 py-4 border-t border-gray-200 bg-gray-50">
      <slot name="footer" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

export interface CardProps {
  variant?: 'default' | 'bordered' | 'elevated'
}

const props = withDefaults(defineProps<CardProps>(), {
  variant: 'default',
})

const cardClasses = computed(() => {
  const base = 'bg-white rounded-lg overflow-hidden'

  const variants = {
    default: '',
    bordered: 'border border-gray-200',
    elevated: 'shadow-lg'
  }

  return `${base} ${variants[props.variant]}`
})
</script>
