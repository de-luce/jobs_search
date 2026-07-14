<script setup lang="ts">
import PageHeader from '@/components/PageHeader.vue'
import AppIcon from '@/components/AppIcon.vue'
import { getPlatformTheme } from '@/lib/platformTheme'
import type { PlatformId } from '@/lib/platform'

const props = defineProps<{
  platform: PlatformId
  title?: string
  subtitle?: string
}>()

const theme = getPlatformTheme(props.platform)
</script>

<template>
  <PageHeader
    :title="title ?? `${theme.label}配置`"
    :subtitle="subtitle ?? `配置 ${theme.label} 求职参数`"
    :accent-bg-class="theme.accentBgClass"
  >
    <template #icon>
      <slot name="icon">
        <AppIcon :icon="theme.icon" :size="20" class="text-white" />
      </slot>
    </template>
    <template v-if="$slots.actions" #actions>
      <slot name="actions" />
    </template>
  </PageHeader>
</template>
