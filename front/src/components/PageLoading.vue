<script setup lang="ts">
defineProps<{
  label?: string
}>()
</script>

<template>
  <div class="flex flex-col items-center justify-center min-h-[50vh] gap-3 text-muted-foreground">
    <div class="h-8 w-8 animate-spin rounded-full border-2 border-primary border-t-transparent" />
    <p class="text-sm">{{ label ?? '加载中…' }}</p>
  </div>
</template>
