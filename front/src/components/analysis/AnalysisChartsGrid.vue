<script setup lang="ts">
import { Icon } from '@iconify/vue'
import Card from '@/components/ui/Card.vue'
import CardContent from '@/components/ui/CardContent.vue'
import CardHeader from '@/components/ui/CardHeader.vue'
import CardTitle from '@/components/ui/CardTitle.vue'
import ChartCanvas from '@/components/analysis/ChartCanvas.vue'
import type { ChartConfig } from '@/lib/chartConfigs'

defineProps<{
  charts: ChartConfig[]
  loading?: boolean
}>()
</script>

<template>
  <div class="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4">
    <Card v-for="chart in charts" :key="chart.key">
      <CardHeader class="pb-2">
        <CardTitle class="text-sm flex items-center gap-2">
          <Icon v-if="chart.icon" :icon="chart.icon" />
          {{ chart.title }}
        </CardTitle>
      </CardHeader>
      <CardContent>
        <ChartCanvas
          :type="chart.type"
          :labels="chart.labels"
          :data="chart.data"
          :colors="chart.colors"
          :color="chart.color"
          :empty-text="loading ? '加载中…' : '暂无数据'"
        />
      </CardContent>
    </Card>
  </div>
</template>
