<script setup lang="ts">
defineEmits<{ click: [] }>()
</script>

<template>
  <button
    type="button"
    class="text-xs font-medium text-primary hover:underline underline-offset-2"
    @click="$emit('click')"
  >
    <slot />
  </button>
</template>
