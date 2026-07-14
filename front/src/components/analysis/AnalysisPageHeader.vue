<script setup lang="ts">
import PageHeader from '@/components/PageHeader.vue'
import AppIcon from '@/components/AppIcon.vue'
import { getPlatformTheme } from '@/lib/platformTheme'
import type { PlatformId } from '@/lib/platform'

const props = defineProps<{
  platform: PlatformId
}>()

const theme = getPlatformTheme(props.platform)
</script>

<template>
  <PageHeader
    :title="`${theme.shortLabel} 投递分析`"
    subtitle="岗位统计与投递记录"
    :accent-bg-class="theme.accentBgClass"
  >
    <template #icon>
      <slot name="icon">
        <AppIcon icon="bi:bar-chart" :size="20" class="text-white" />
      </slot>
    </template>
  </PageHeader>
</template>
