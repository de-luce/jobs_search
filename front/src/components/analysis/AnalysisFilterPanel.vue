<script setup lang="ts">
import Card from '@/components/ui/Card.vue'
import CardContent from '@/components/ui/CardContent.vue'
import CardHeader from '@/components/ui/CardHeader.vue'
import CardTitle from '@/components/ui/CardTitle.vue'
import Button from '@/components/ui/Button.vue'
import Input from '@/components/ui/Input.vue'
import Select from '@/components/ui/Select.vue'
import Label from '@/components/ui/Label.vue'
import { statusFilterClass } from '@/components/analysis/kpiHelpers'
import type { AnalysisFilterState } from '@/lib/analysis'

type ExperienceFieldType = 'input' | 'select'

const props = defineProps<{
  filter: AnalysisFilterState
  statusOptions: string[]
  showHeadhunterFilter?: boolean
  showReload?: boolean
  loadingList?: boolean
  reloading?: boolean
  exporting?: boolean
  experienceFieldType?: ExperienceFieldType
}>()

const emit = defineEmits<{
  'update:filter': [patch: Partial<AnalysisFilterState>]
  apply: []
  reload: []
  export: []
}>()

function patch(p: Partial<AnalysisFilterState>) {
  emit('update:filter', p)
}

function toggleStatus(s: string, checked: boolean) {
  patch({
    statuses: checked
      ? [...props.filter.statuses, s]
      : props.filter.statuses.filter((x) => x !== s),
  })
}
</script>

<template>
  <Card>
    <CardHeader class="pb-3">
      <CardTitle class="text-base">筛选条件</CardTitle>
    </CardHeader>
    <CardContent class="space-y-4">
      <div class="flex flex-wrap gap-2">
        <label
          v-for="s in statusOptions"
          :key="s"
          :class="statusFilterClass(s, filter.statuses.includes(s))"
        >
          <input
            type="checkbox"
            class="sr-only"
            :checked="filter.statuses.includes(s)"
            @change="toggleStatus(s, ($event.target as HTMLInputElement).checked)"
          />
          {{ s }}
        </label>
        <label v-if="showHeadhunterFilter" class="inline-flex items-center gap-1.5 text-sm ml-2 cursor-pointer">
          <input
            type="checkbox"
            :checked="filter.filterHeadhunter"
            @change="patch({ filterHeadhunter: ($event.target as HTMLInputElement).checked })"
          />
          过滤猎头
        </label>
      </div>

      <div class="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-6 gap-3">
        <div>
          <Label>城市</Label>
          <Input
            :model-value="filter.location"
            placeholder="深圳"
            @update:model-value="patch({ location: $event })"
          />
        </div>
        <div>
          <Label>经验</Label>
          <Select
            v-if="experienceFieldType === 'select'"
            :model-value="filter.experience"
            @update:model-value="patch({ experience: $event })"
          >
            <option value="">不限</option>
            <option value="1-3年">1-3年</option>
            <option value="3-5年">3-5年</option>
            <option value="5-10年">5-10年</option>
          </Select>
          <Input
            v-else
            :model-value="filter.experience"
            placeholder="3-5年"
            @update:model-value="patch({ experience: $event })"
          />
        </div>
        <div>
          <Label>学历</Label>
          <Select
            v-if="experienceFieldType === 'select'"
            :model-value="filter.degree"
            @update:model-value="patch({ degree: $event })"
          >
            <option value="">不限</option>
            <option value="本科">本科</option>
            <option value="硕士">硕士</option>
          </Select>
          <Input
            v-else
            :model-value="filter.degree"
            placeholder="本科"
            @update:model-value="patch({ degree: $event })"
          />
        </div>
        <div>
          <Label>最低K</Label>
          <Input
            type="number"
            :model-value="filter.minK"
            @update:model-value="patch({ minK: $event })"
          />
        </div>
        <div>
          <Label>最高K</Label>
          <Input
            type="number"
            :model-value="filter.maxK"
            @update:model-value="patch({ maxK: $event })"
          />
        </div>
        <div>
          <Label>关键词</Label>
          <Input
            :model-value="filter.keyword"
            placeholder="公司/岗位"
            @update:model-value="patch({ keyword: $event })"
          />
        </div>
      </div>

      <div class="flex flex-wrap gap-2">
        <Button :disabled="loadingList" @click="emit('apply')">应用筛选</Button>
        <Button v-if="showReload" variant="outline" :disabled="reloading" @click="emit('reload')">
          {{ reloading ? '刷新中…' : '刷新数据' }}
        </Button>
        <Button variant="outline" :disabled="exporting" @click="emit('export')">
          {{ exporting ? '导出中…' : '导出 CSV' }}
        </Button>
      </div>
    </CardContent>
  </Card>
</template>
