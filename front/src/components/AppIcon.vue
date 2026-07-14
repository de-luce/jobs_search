<script setup lang="ts">
import { Icon } from '@iconify/vue'

withDefaults(
  defineProps<{
    icon: string
    size?: number
    iconClass?: string
  }>(),
  { size: 18 }
)
</script>

<template>
  <Icon :icon="icon" :width="size" :height="size" :class="iconClass" aria-hidden="true" />
</template>
