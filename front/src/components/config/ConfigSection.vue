<script setup lang="ts">
import Card from '@/components/ui/Card.vue'
import CardHeader from '@/components/ui/CardHeader.vue'
import CardTitle from '@/components/ui/CardTitle.vue'
import CardDescription from '@/components/ui/CardDescription.vue'
import CardContent from '@/components/ui/CardContent.vue'
import { cn } from '@/lib/utils'
import { getPlatformTheme } from '@/lib/platformTheme'
import type { PlatformId } from '@/lib/platform'

const props = withDefaults(
  defineProps<{
    title: string
    description?: string
    sectionClass?: string
    delay?: number
    platform?: PlatformId
    iconClassName?: string
  }>(),
  { delay: 5 }
)

const iconCls = props.iconClassName ?? (props.platform ? getPlatformTheme(props.platform).sectionIconClass : 'text-primary')
</script>

<template>
  <Card :class="cn(`animate-in fade-in slide-in-from-bottom-${props.delay} duration-700`, props.sectionClass)">
    <CardHeader>
      <CardTitle class="flex items-center gap-2">
        <span v-if="$slots.icon" :class="iconCls">
          <slot name="icon" />
        </span>
        {{ title }}
      </CardTitle>
      <CardDescription v-if="description">{{ description }}</CardDescription>
    </CardHeader>
    <CardContent>
      <slot />
    </CardContent>
  </Card>
</template>
