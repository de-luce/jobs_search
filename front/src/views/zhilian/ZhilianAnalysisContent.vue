<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { Icon } from '@iconify/vue'
import Button from '@/components/ui/Button.vue'
import AnalysisPageHeader from '@/components/analysis/AnalysisPageHeader.vue'
import AnalysisKpiGrid from '@/components/analysis/AnalysisKpiGrid.vue'
import AnalysisFilterPanel from '@/components/analysis/AnalysisFilterPanel.vue'
import AnalysisChartsGrid from '@/components/analysis/AnalysisChartsGrid.vue'
import AnalysisJobTable, { type TableColumn } from '@/components/analysis/AnalysisJobTable.vue'
import { formatAvgSalaryK } from '@/lib/analysisKpi'
import {
  DEFAULT_FILTER,
  filterToParams,
  buildQuery,
  exportCsv,
  formatDateOnly,
  PLATFORM_STATUS_OPTIONS,
  PLATFORM_HEADHUNTER_FILTER,
  PLATFORM_RELOAD,
  type StatsResponse,
  type PagedResult,
  type AnalysisFilterState,
} from '@/lib/analysis'
import { getApiBase } from '@/lib/platform'
import { parseSalary } from '@/lib/salary'
import { useAnalysisRealtimeRefresh } from '@/composables/useRealtime'
import { buildChartConfigs } from '@/lib/chartConfigs'
import type { KpiItem } from '@/lib/analysisKpi'

type ZhilianJob = {
  jobId: string
  companyName?: string
  jobTitle?: string
  salary?: string
  location?: string
  experience?: string
  degree?: string
  deliveryStatus?: string
  jobLink?: string
  createTime?: string
}

withDefaults(defineProps<{ showHeader?: boolean }>(), { showHeader: false })

const API = getApiBase()
const stats = ref<StatsResponse | null>(null)
const loadingStats = ref(true)
const items = ref<ZhilianJob[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const filter = ref<AnalysisFilterState>({ ...DEFAULT_FILTER })
const loadingList = ref(false)
const exporting = ref(false)
const detailJob = ref<ZhilianJob | null>(null)

const filterParams = computed(() => filterToParams(filter.value))

async function loadStats(opts?: { silent?: boolean }) {
  try {
    if (!opts?.silent) loadingStats.value = true
    const res = await fetch(`${API}/api/zhilian/stats?${buildQuery(filterParams.value)}`)
    stats.value = await res.json()
  } catch (e) {
    console.error('fetch stats failed', e)
  } finally {
    if (!opts?.silent) loadingStats.value = false
  }
}

async function loadList(toPage = page.value, toSize = size.value, opts?: { silent?: boolean }) {
  try {
    if (!opts?.silent) loadingList.value = true
    const q = buildQuery({ ...filterParams.value, page: String(toPage), size: String(toSize) })
    const res = await fetch(`${API}/api/zhilian/list?${q}`)
    const data: PagedResult<ZhilianJob> = await res.json()
    items.value = data.items || []
    total.value = data.total || 0
    page.value = data.page || toPage
    size.value = data.size || toSize
  } catch (e) {
    console.error('fetch list failed', e)
  } finally {
    if (!opts?.silent) loadingList.value = false
  }
}

function refreshAll() {
  void loadStats({ silent: true })
  void loadList(page.value, size.value, { silent: true })
}

onMounted(() => {
  void loadList(1, size.value)
  void loadStats()
})

useAnalysisRealtimeRefresh({ platform: 'zhilian', onRefresh: refreshAll })

async function applyFilters() {
  await loadList(1, size.value)
  await loadStats()
}

async function exportCSV() {
  try {
    exporting.value = true
    const pageSize = 1000
    let currentPage = 1
    let all: ZhilianJob[] = []
    let totalCount = 0
    while (true) {
      const q = buildQuery({ ...filterParams.value, page: String(currentPage), size: String(pageSize) })
      const res = await fetch(`${API}/api/zhilian/list?${q}`)
      const data: PagedResult<ZhilianJob> = await res.json()
      const chunk = data.items || []
      if (currentPage === 1) totalCount = data.total || chunk.length
      all = all.concat(chunk)
      if (all.length >= totalCount || chunk.length === 0) break
      currentPage += 1
    }
    const header = ['公司', '岗位', '薪资', '地点', '经验', '学历', '状态', '链接']
    const rows = all.map((it) => [
      it.companyName || '',
      it.jobTitle || '',
      it.salary || '',
      it.location || '',
      it.experience || '',
      it.degree || '',
      it.deliveryStatus || '',
      it.jobLink || '',
    ])
    exportCsv(`zhilian_${new Date().toISOString().slice(0, 10)}.csv`, header, rows)
  } finally {
    exporting.value = false
  }
}

const kpi = computed(() => stats.value?.kpi)
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / size.value)))

const avgSalaryDisplay = computed(() => {
  if (kpi.value?.avgMonthlyK != null && kpi.value.avgMonthlyK > 0) {
    return formatAvgSalaryK(kpi.value.avgMonthlyK)
  }
  const ks: number[] = []
  for (const it of items.value) {
    const info = parseSalary(it.salary)
    if (info && !Number.isNaN(info.medianK)) ks.push(info.medianK)
  }
  if (!ks.length) return '—'
  return formatAvgSalaryK(ks.reduce((a, b) => a + b, 0) / ks.length)
})

const kpiItems = computed<KpiItem[]>(() => [
  { label: '总岗位', value: kpi.value?.total ?? 0 },
  { label: '已投递', value: kpi.value?.delivered ?? 0, highlight: 'delivered' },
  { label: '未投递', value: kpi.value?.pending ?? 0, highlight: 'pending' },
  { label: '已过滤', value: kpi.value?.filtered ?? 0, highlight: 'filtered' },
  { label: '失败', value: kpi.value?.failed ?? 0, highlight: 'failed' },
  { label: '均薪(K)', value: avgSalaryDisplay.value, highlight: 'salary' },
])

const chartConfigs = computed(() => buildChartConfigs(stats.value?.charts, 'zhilian'))

const columns: TableColumn<ZhilianJob>[] = [
  { key: 'company', header: '公司', className: 'max-w-[140px] truncate', accessor: (it) => it.companyName || '-' },
  { key: 'title', header: '岗位', className: 'max-w-[180px] truncate', accessor: (it) => it.jobTitle || '-' },
  { key: 'salary', header: '薪资', className: 'whitespace-nowrap', accessor: (it) => it.salary || '-' },
  { key: 'location', header: '地点', className: 'whitespace-nowrap', accessor: (it) => it.location || '-' },
  { key: 'status', header: '状态', type: 'status', accessor: (it) => it.deliveryStatus || '-' },
  { key: 'time', header: '时间', className: 'whitespace-nowrap', type: 'date', accessor: (it) => it.createTime },
  { key: 'link', header: '链接', type: 'link', accessor: (it) => it.jobLink || '' },
]

const detailFields = computed(() => {
  if (!detailJob.value) return []
  const j = detailJob.value
  return [
    ['薪资', j.salary],
    ['地点', j.location],
    ['经验', j.experience],
    ['学历', j.degree],
    ['投递状态', j.deliveryStatus],
    ['创建时间', formatDateOnly(j.createTime)],
  ] as const
})

function onFilterChange(patch: Partial<AnalysisFilterState>) {
  filter.value = { ...filter.value, ...patch }
}
</script>

<template>
  <div class="space-y-6">
    <AnalysisPageHeader v-if="showHeader" platform="zhilian">
      <template #icon>
        <Icon icon="bi:bar-chart" :width="24" :height="24" />
      </template>
    </AnalysisPageHeader>

    <AnalysisKpiGrid :items="kpiItems" :loading="loadingStats" />

    <AnalysisFilterPanel
      :filter="filter"
      :status-options="PLATFORM_STATUS_OPTIONS.zhilian"
      :show-headhunter-filter="PLATFORM_HEADHUNTER_FILTER.zhilian"
      :show-reload="PLATFORM_RELOAD.zhilian"
      :loading-list="loadingList"
      :exporting="exporting"
      @update:filter="onFilterChange"
      @apply="applyFilters"
      @export="exportCSV"
    />

    <AnalysisChartsGrid :charts="chartConfigs" :loading="loadingStats" />

    <AnalysisJobTable
      :total="total"
      :items="items"
      :columns="columns"
      :row-key="(it) => it.jobId"
      :loading="loadingList"
      :page="page"
      :size="size"
      :total-pages="totalPages"
      clickable
      @page-change="(p) => loadList(p, size)"
      @size-change="(s) => loadList(1, s)"
      @row-click="detailJob = $event"
    />

    <div
      v-if="detailJob"
      class="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
      @click="detailJob = null"
    >
      <div
        class="bg-background rounded-lg border shadow-lg w-full max-w-lg p-5"
        @click.stop
      >
        <div class="flex items-start justify-between gap-3 mb-4">
          <div>
            <h3 class="font-semibold text-lg">{{ detailJob.jobTitle || '岗位详情' }}</h3>
            <p class="text-sm text-muted-foreground">{{ detailJob.companyName }}</p>
          </div>
          <Button variant="outline" size="sm" @click="detailJob = null">关闭</Button>
        </div>
        <div class="grid grid-cols-2 gap-x-4 gap-y-2 text-sm mb-4">
          <div v-for="[k, v] in detailFields" :key="k">
            <span class="text-muted-foreground">{{ k }}：</span>
            {{ v || '-' }}
          </div>
        </div>
        <a
          v-if="detailJob.jobLink"
          :href="detailJob.jobLink"
          target="_blank"
          rel="noreferrer"
          class="text-primary underline text-sm"
        >
          打开职位链接
        </a>
      </div>
    </div>
  </div>
</template>
