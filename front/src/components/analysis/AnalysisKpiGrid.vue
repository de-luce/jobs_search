<script setup lang="ts">
import { cn } from '@/lib/utils'
import Card from '@/components/ui/Card.vue'
import CardContent from '@/components/ui/CardContent.vue'
import { HIGHLIGHT_CLASS, VALUE_CLASS, type KpiItem } from '@/components/analysis/kpiHelpers'

defineProps<{
  items: KpiItem[]
  loading?: boolean
  class?: string
}>()
</script>

<template>
  <div :class="cn('grid grid-cols-2 md:grid-cols-3 lg:grid-cols-6 gap-3', $props.class)">
    <Card
      v-for="item in items"
      :key="item.label"
      :class="cn('border', HIGHLIGHT_CLASS[item.highlight ?? 'default'])"
    >
      <CardContent class="pt-4 pb-4">
        <p class="text-xs text-muted-foreground">{{ item.label }}</p>
        <p :class="cn('text-2xl font-semibold mt-1', VALUE_CLASS[item.highlight ?? 'default'])">
          {{ loading ? '…' : item.value }}
        </p>
      </CardContent>
    </Card>
  </div>
</template>
