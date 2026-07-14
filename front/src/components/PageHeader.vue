<script setup lang="ts">
defineProps<{
  title: string
  subtitle?: string
  accentBgClass?: string
}>()
</script>

<template>
  <div class="mb-5 flex flex-wrap items-start justify-between gap-4 border-b border-border pb-4">
    <div class="flex min-w-0 items-start gap-3">
      <div
        v-if="$slots.icon"
        :class="['flex h-10 w-10 shrink-0 items-center justify-center rounded-lg text-white', accentBgClass || 'bg-primary']"
      >
        <slot name="icon" />
      </div>
      <div class="min-w-0">
        <h1 class="text-xl font-semibold tracking-tight">{{ title }}</h1>
        <p v-if="subtitle" class="mt-0.5 text-sm text-muted-foreground">{{ subtitle }}</p>
      </div>
    </div>
    <div v-if="$slots.actions" class="flex flex-wrap items-center gap-2">
      <slot name="actions" />
    </div>
  </div>
</template>
