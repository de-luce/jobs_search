<script setup lang="ts" generic="T extends Record<string, unknown>">
import { Icon } from '@iconify/vue'
import Card from '@/components/ui/Card.vue'
import CardContent from '@/components/ui/CardContent.vue'
import CardDescription from '@/components/ui/CardDescription.vue'
import CardHeader from '@/components/ui/CardHeader.vue'
import CardTitle from '@/components/ui/CardTitle.vue'
import Button from '@/components/ui/Button.vue'
import Select from '@/components/ui/Select.vue'
import { deliveryStatusClass } from '@/components/analysis/kpiHelpers'
import { formatDateOnly } from '@/lib/analysis'

export type TableColumn<T> = {
  key: string
  header: string
  className?: string
  type?: 'text' | 'status' | 'link' | 'date'
  accessor?: keyof T | ((row: T) => string | undefined)
}

withDefaults(
  defineProps<{
    title?: string
    total: number
    items: T[]
    columns: TableColumn<T>[]
    rowKey: (row: T) => string | number
    loading?: boolean
    page: number
    size: number
    totalPages: number
  }>(),
  { title: '岗位列表' }
)

const emit = defineEmits<{
  'page-change': [page: number]
  'size-change': [size: number]
  'row-click': [row: T]
}>()

function getCellValue(row: T, col: TableColumn<T>): string {
  if (!col.accessor) return '-'
  if (typeof col.accessor === 'function') return col.accessor(row) || '-'
  const v = row[col.accessor]
  return v != null ? String(v) : '-'
}
</script>

<template>
  <Card>
    <CardHeader class="pb-2">
      <CardTitle class="text-base flex items-center gap-2">
        <Icon icon="bi:briefcase" /> {{ title }}
      </CardTitle>
      <CardDescription>点击行查看详情，共 {{ total }} 条</CardDescription>
    </CardHeader>
    <CardContent>
      <div class="overflow-x-auto max-h-[520px] overflow-y-auto rounded-lg border">
        <table class="w-full text-sm">
          <thead class="sticky top-0 bg-muted/80 backdrop-blur-sm">
            <tr class="text-left border-b">
              <th
                v-for="col in columns"
                :key="col.key"
                :class="['px-3 py-2', col.className || '']"
              >
                {{ col.header }}
              </th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="items.length === 0">
              <td :colspan="columns.length" class="px-3 py-10 text-center text-muted-foreground">
                {{ loading ? '加载中…' : '暂无数据' }}
              </td>
            </tr>
            <tr
              v-for="row in items"
              v-else
              :key="rowKey(row)"
              class="border-b hover:bg-muted/40 cursor-pointer"
              @click="emit('row-click', row)"
            >
              <td
                v-for="col in columns"
                :key="col.key"
                :class="['px-3 py-2', col.className || '']"
              >
                <span v-if="col.type === 'status'" :class="deliveryStatusClass(getCellValue(row, col))">
                  {{ getCellValue(row, col) }}
                </span>
                <a
                  v-else-if="col.type === 'link' && getCellValue(row, col) !== '-'"
                  :href="getCellValue(row, col)"
                  target="_blank"
                  rel="noreferrer"
                  class="text-primary underline"
                  @click.stop
                >
                  打开
                </a>
                <span v-else-if="col.type === 'link'">-</span>
                <span v-else-if="col.type === 'date'">{{ formatDateOnly(getCellValue(row, col)) }}</span>
                <span v-else>{{ getCellValue(row, col) }}</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="mt-3 flex flex-wrap items-center gap-2">
        <Button
          variant="outline"
          size="sm"
          :disabled="loading || page <= 1"
          @click="emit('page-change', page - 1)"
        >
          上一页
        </Button>
        <span class="text-sm text-muted-foreground">第 {{ page }} / {{ totalPages }} 页</span>
        <Button
          variant="outline"
          size="sm"
          :disabled="loading || page >= totalPages"
          @click="emit('page-change', page + 1)"
        >
          下一页
        </Button>
        <Select
          :model-value="String(size)"
          class="h-8 w-24 ml-auto"
          @update:model-value="emit('size-change', Number($event))"
        >
          <option value="20">20 条</option>
          <option value="50">50 条</option>
          <option value="100">100 条</option>
        </Select>
      </div>
    </CardContent>
  </Card>
</template>
