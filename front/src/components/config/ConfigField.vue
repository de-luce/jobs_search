<script setup lang="ts">
import Label from '@/components/ui/Label.vue'

defineProps<{
  label: string
  hint?: string
  htmlFor?: string
  fieldClass?: string
}>()
</script>

<template>
  <div :class="fieldClass || 'space-y-2'">
    <div class="flex items-center justify-between">
      <Label :for="htmlFor">{{ label }}</Label>
      <slot name="label-extra" />
    </div>
    <slot />
    <p v-if="hint" class="text-xs text-muted-foreground">{{ hint }}</p>
  </div>
</template>
