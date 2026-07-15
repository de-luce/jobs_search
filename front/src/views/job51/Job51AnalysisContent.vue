<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { Icon } from '@iconify/vue'
import Button from '@/components/ui/Button.vue'
import AnalysisPageHeader from '@/components/analysis/AnalysisPageHeader.vue'
import AnalysisKpiGrid from '@/components/analysis/AnalysisKpiGrid.vue'
import AnalysisFilterPanel from '@/components/analysis/AnalysisFilterPanel.vue'
import AnalysisChartsGrid from '@/components/analysis/AnalysisChartsGrid.vue'
import AnalysisJobTable, { type TableColumn } from '@/components/analysis/AnalysisJobTable.vue'
import { formatSalaryByUnit, parseSalary } from '@/lib/salary'
import { useAnalysisRealtimeRefresh } from '@/composables/useRealtime'
import { getApiBase } from '@/lib/platform'
import {
  buildQuery,
  filterToParams,
  DEFAULT_FILTER,
  exportCsv,
  PLATFORM_STATUS_OPTIONS,
  PLATFORM_RELOAD,
  PLATFORM_SALARY_UNIT,
  toFilterOptions,
  salaryKpiLabel,
  type StatsResponse,
  type PagedResult,
  type AnalysisFilterState,
  type AnalysisFilterOption,
} from '@/lib/analysis'
import { buildChartConfigs } from '@/lib/chartConfigs'
import type { KpiItem } from '@/lib/analysisKpi'

type Job51Item = {
  jobId: number
  companyName?: string
  jobName?: string
  salary?: string
  location?: string
  experience?: string
  degree?: string
  hrName?: string
  deliveryStatus?: string
  jobUrl?: string
  createdAt?: string
}

withDefaults(defineProps<{ showHeader?: boolean }>(), { showHeader: false })

const API_BASE = getApiBase()
const STATUS_OPTIONS = PLATFORM_STATUS_OPTIONS['51job']
const SHOW_RELOAD = PLATFORM_RELOAD['51job']

const stats = ref<StatsResponse | null>(null)
const loadingStats = ref(true)
const items = ref<Job51Item[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const filter = ref<AnalysisFilterState>({ ...DEFAULT_FILTER })
const loadingList = ref(false)
const reloading = ref(false)
const exporting = ref(false)
const detailJob = ref<Job51Item | null>(null)
const salaryUnit = PLATFORM_SALARY_UNIT['51job']
const locationOptions = ref<AnalysisFilterOption[]>([])

const filterParams = computed(() => filterToParams(filter.value, '51job'))

async function loadFilterOptions() {
  try {
    const res = await fetch(`${API_BASE}/api/51job/config`)
    const data = await res.json()
    locationOptions.value = toFilterOptions(data.options?.jobArea)
  } catch (e) {
    console.error('fetch 51job filter options failed', e)
  }
}

async function loadStats(opts?: { silent?: boolean }) {
  try {
    if (!opts?.silent) loadingStats.value = true
    const res = await fetch(`${API_BASE}/api/51job/stats?${buildQuery(filterParams.value)}`)
    stats.value = await res.json()
  } finally {
    if (!opts?.silent) loadingStats.value = false
  }
}

async function loadList(toPage = page.value, toSize = size.value, opts?: { silent?: boolean }) {
  try {
    if (!opts?.silent) loadingList.value = true
    const q = buildQuery({ ...filterParams.value, page: String(toPage), size: String(toSize) })
    const res = await fetch(`${API_BASE}/api/51job/list?${q}`)
    const data: PagedResult<Job51Item> = await res.json()
    items.value = data.items || []
    total.value = data.total || 0
    page.value = data.page || toPage
    size.value = data.size || toSize
  } finally {
    if (!opts?.silent) loadingList.value = false
  }
}

function refreshAll() {
  void loadStats({ silent: true })
  void loadList(page.value, size.value, { silent: true })
}

onMounted(() => {
  void loadFilterOptions()
  void loadList(1, size.value)
  void loadStats()
})

useAnalysisRealtimeRefresh({ platform: '51job', onRefresh: refreshAll })

async function applyFilters() {
  await loadList(1, size.value)
  await loadStats()
}

async function onReload() {
  try {
    reloading.value = true
    await fetch(`${API_BASE}/api/51job/reload`)
    await loadList(1, size.value)
    await loadStats()
  } finally {
    reloading.value = false
  }
}

async function exportCSV() {
  try {
    exporting.value = true
    let currentPage = 1
    let all: Job51Item[] = []
    let totalCount = 0
    while (true) {
      const q = buildQuery({ ...filterParams.value, page: String(currentPage), size: '1000' })
      const res = await fetch(`${API_BASE}/api/51job/list?${q}`)
      const data: PagedResult<Job51Item> = await res.json()
      const chunk = data.items || []
      if (currentPage === 1) totalCount = data.total || chunk.length
      all = all.concat(chunk)
      if (all.length >= totalCount || chunk.length === 0) break
      currentPage += 1
    }
    const header = ['公司', '岗位', '薪资', '地点', '经验', '学历', 'HR', '状态', '链接']
    const rows = all.map((it) => [
      it.companyName || '',
      it.jobName || '',
      it.salary || '',
      it.location || '',
      it.experience || '',
      it.degree || '',
      it.hrName || '',
      it.deliveryStatus || '',
      it.jobUrl || '',
    ])
    exportCsv(`job51_${new Date().toISOString().slice(0, 10)}.csv`, header, rows)
  } finally {
    exporting.value = false
  }
}

const kpi = computed(() => stats.value?.kpi)
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / size.value)))

const avgSalaryDisplay = computed(() => {
  if (kpi.value?.avgMonthlyK != null && kpi.value.avgMonthlyK > 0) {
    return formatSalaryByUnit(kpi.value.avgMonthlyK, salaryUnit)
  }
  const ks: number[] = []
  for (const it of items.value) {
    const info = parseSalary(it.salary)
    if (info && !Number.isNaN(info.medianK)) ks.push(info.medianK)
  }
  if (!ks.length) return '—'
  return formatSalaryByUnit(ks.reduce((a, b) => a + b, 0) / ks.length, salaryUnit)
})

const kpiItems = computed<KpiItem[]>(() => [
  { label: '总岗位', value: kpi.value?.total ?? 0 },
  { label: '已投递', value: kpi.value?.delivered ?? 0, highlight: 'delivered' },
  { label: '未投递', value: kpi.value?.pending ?? 0, highlight: 'pending' },
  { label: '已过滤', value: kpi.value?.filtered ?? 0, highlight: 'filtered' },
  { label: salaryKpiLabel('51job'), value: avgSalaryDisplay.value, highlight: 'salary' },
])

const chartConfigs = computed(() => {
  const charts = stats.value?.charts ? { ...stats.value.charts, byCity: undefined } : undefined
  return buildChartConfigs(charts, '51job')
})

const tableColumns: TableColumn<Job51Item>[] = [
  {
    key: 'company',
    header: '公司',
    className: 'max-w-[140px] truncate',
    type: 'company-link',
    accessor: (it) => it.companyName || '-',
    hrefAccessor: (it) => it.jobUrl || '',
  },
  { key: 'job', header: '岗位', className: 'max-w-[180px] truncate', accessor: (it) => it.jobName || '-' },
  { key: 'salary', header: '薪资', className: 'whitespace-nowrap', accessor: (it) => it.salary || '-' },
  { key: 'location', header: '地点', className: 'whitespace-nowrap', accessor: (it) => it.location || '-' },
  { key: 'status', header: '状态', type: 'status', accessor: (it) => it.deliveryStatus || '-' },
]

function onFilterChange(patch: Partial<AnalysisFilterState>) {
  filter.value = { ...filter.value, ...patch }
}

async function onStatusChange(row: Job51Item, status: string) {
  if (!row.jobId || row.deliveryStatus === status) return
  try {
    const res = await fetch(`${API_BASE}/api/51job/jobs/${row.jobId}/delivery-status`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ status }),
    })
    const data = await res.json()
    if (!data?.success) {
      console.error('update job51 delivery status failed', data?.message)
      await loadList(page.value, size.value, { silent: true })
      return
    }
    row.deliveryStatus = status
    if (detailJob.value?.jobId === row.jobId) detailJob.value.deliveryStatus = status
    await loadStats({ silent: true })
  } catch (e) {
    console.error('update job51 delivery status failed', e)
    await loadList(page.value, size.value, { silent: true })
  }
}
</script>

<template>
  <div class="space-y-6">
    <AnalysisPageHeader v-if="showHeader" platform="51job">
      <template #icon>
        <Icon icon="bi:bar-chart" :width="24" :height="24" />
      </template>
    </AnalysisPageHeader>

    <AnalysisKpiGrid
      :items="kpiItems"
      :loading="loadingStats"
      class="md:grid-cols-4 lg:grid-cols-4"
    />

    <AnalysisFilterPanel
      :filter="filter"
      :status-options="STATUS_OPTIONS"
      :show-reload="SHOW_RELOAD"
      :loading-list="loadingList"
      :reloading="reloading"
      :exporting="exporting"
      :salary-unit="salaryUnit"
      :location-options="locationOptions"
      @update:filter="onFilterChange"
      @apply="applyFilters"
      @reload="onReload"
      @export="exportCSV"
    />

    <AnalysisChartsGrid :charts="chartConfigs" :loading="loadingStats" />

    <AnalysisJobTable
      :total="total"
      :items="items"
      :columns="tableColumns"
      :row-key="(it) => it.jobId"
      :loading="loadingList"
      :page="page"
      :size="size"
      :total-pages="totalPages"
      :status-options="STATUS_OPTIONS"
      @page-change="(p) => loadList(p, size)"
      @size-change="(s) => loadList(1, s)"
      @row-click="detailJob = $event"
      @status-change="onStatusChange"
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
            <h3 class="font-semibold text-lg">{{ detailJob.jobName || '岗位详情' }}</h3>
            <p class="text-sm text-muted-foreground">{{ detailJob.companyName }}</p>
          </div>
          <Button variant="outline" size="sm" @click="detailJob = null">关闭</Button>
        </div>
        <div class="grid grid-cols-2 gap-x-4 gap-y-2 text-sm">
          <div><span class="text-muted-foreground">薪资：</span>{{ detailJob.salary || '-' }}</div>
          <div><span class="text-muted-foreground">地点：</span>{{ detailJob.location || '-' }}</div>
          <div><span class="text-muted-foreground">经验：</span>{{ detailJob.experience || '-' }}</div>
          <div><span class="text-muted-foreground">学历：</span>{{ detailJob.degree || '-' }}</div>
          <div><span class="text-muted-foreground">HR：</span>{{ detailJob.hrName || '-' }}</div>
          <div><span class="text-muted-foreground">状态：</span>{{ detailJob.deliveryStatus || '-' }}</div>
        </div>
      </div>
    </div>
  </div>
</template>
