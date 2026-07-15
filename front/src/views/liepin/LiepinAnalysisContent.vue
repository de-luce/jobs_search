<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { Icon } from '@iconify/vue'
import Button from '@/components/ui/Button.vue'
import AnalysisPageHeader from '@/components/analysis/AnalysisPageHeader.vue'
import AnalysisKpiGrid from '@/components/analysis/AnalysisKpiGrid.vue'
import AnalysisFilterPanel from '@/components/analysis/AnalysisFilterPanel.vue'
import AnalysisChartsGrid from '@/components/analysis/AnalysisChartsGrid.vue'
import AnalysisJobTable, { type TableColumn } from '@/components/analysis/AnalysisJobTable.vue'
import {
  buildQuery,
  DEFAULT_FILTER,
  exportCsv,
  filterToParams,
  formatDateOnly,
  PLATFORM_HEADHUNTER_FILTER,
  PLATFORM_RELOAD,
  PLATFORM_STATUS_OPTIONS,
  PLATFORM_SALARY_UNIT,
  toFilterOptions,
  salaryKpiLabel,
  type AnalysisFilterState,
  type AnalysisFilterOption,
  type PagedResult,
  type StatsResponse,
} from '@/lib/analysis'
import { updateDeliveryStatus } from '@/lib/deliveryStatusApi'
import { buildChartConfigs } from '@/lib/chartConfigs'
import { deliveryStatusClass, type KpiItem } from '@/lib/analysisKpi'
import { getApiBase } from '@/lib/platform'
import { formatSalaryByUnit, parseSalary } from '@/lib/salary'
import { useAnalysisRealtimeRefresh } from '@/composables/useRealtime'

type LiepinJob = {
  jobId: number
  compName?: string
  compIndustry?: string
  jobTitle?: string
  jobSalaryText?: string
  jobArea?: string
  jobEduReq?: string
  jobExpReq?: string
  hrName?: string
  deliveryStatus?: string
  jobLink?: string
  createTime?: string
}

withDefaults(
  defineProps<{
    showHeader?: boolean
  }>(),
  { showHeader: false }
)

const API = getApiBase()

const stats = ref<StatsResponse | null>(null)
const loadingStats = ref(true)
const items = ref<LiepinJob[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const filter = ref<AnalysisFilterState>({ ...DEFAULT_FILTER })
const loadingList = ref(false)
const exporting = ref(false)
const detailJob = ref<LiepinJob | null>(null)
const salaryUnit = PLATFORM_SALARY_UNIT.liepin
const locationOptions = ref<AnalysisFilterOption[]>([])
const experienceOptions = ref<AnalysisFilterOption[]>([])
const degreeOptions = ref<AnalysisFilterOption[]>([])
const scaleOptions = ref<AnalysisFilterOption[]>([])

const filterParams = computed(() => filterToParams(filter.value, 'liepin'))

async function loadFilterOptions() {
  try {
    const res = await fetch(`${API}/api/liepin/config`)
    const data = await res.json()
    const opts = data.options || {}
    locationOptions.value = toFilterOptions(opts.city)
    experienceOptions.value = toFilterOptions(opts.experience)
    degreeOptions.value = toFilterOptions(opts.degree)
    scaleOptions.value = toFilterOptions(opts.scale)
  } catch (e) {
    console.error('fetch liepin filter options failed', e)
  }
}

function liepinDeliveryStatus(status?: string) {
  return status || '未投递'
}

const loadStats = async (opts?: { silent?: boolean }) => {
  try {
    if (!opts?.silent) loadingStats.value = true
    const res = await fetch(`${API}/api/liepin/stats?${buildQuery(filterParams.value)}`)
    stats.value = await res.json()
  } catch (e) {
    console.error('fetch stats failed', e)
  } finally {
    if (!opts?.silent) loadingStats.value = false
  }
}

const loadList = async (toPage = page.value, toSize = size.value, opts?: { silent?: boolean }) => {
  try {
    if (!opts?.silent) loadingList.value = true
    const q = buildQuery({ ...filterParams.value, page: String(toPage), size: String(toSize) })
    const res = await fetch(`${API}/api/liepin/list?${q}`)
    const data: PagedResult<LiepinJob> = await res.json()
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

const refreshAll = () => {
  void loadStats({ silent: true })
  void loadList(page.value, size.value, { silent: true })
}

onMounted(() => {
  void loadFilterOptions()
  void loadList(1, size.value)
  void loadStats()
})

useAnalysisRealtimeRefresh({ platform: 'liepin', onRefresh: refreshAll })

const applyFilter = () => {
  void loadList(1, size.value)
  void loadStats()
}

function onFilterPatch(patch: Partial<AnalysisFilterState>) {
  filter.value = { ...filter.value, ...patch }
}

const exportCSV = async () => {
  try {
    exporting.value = true
    const pageSize = 1000
    let currentPage = 1
    let all: LiepinJob[] = []
    let totalCount = 0
    while (true) {
      const q = buildQuery({ ...filterParams.value, page: String(currentPage), size: String(pageSize) })
      const res = await fetch(`${API}/api/liepin/list?${q}`)
      const data: PagedResult<LiepinJob> = await res.json()
      const chunk = data.items || []
      if (currentPage === 1) totalCount = data.total || chunk.length
      all = all.concat(chunk)
      if (all.length >= totalCount || chunk.length === 0) break
      currentPage += 1
    }
    const header = ['公司', '岗位', '薪资', '城市', '经验', '学历', 'HR', '状态', '链接', '创建时间']
    const rows = all.map((it) => [
      it.compName || '',
      it.jobTitle || '',
      it.jobSalaryText || '',
      it.jobArea || '',
      it.jobExpReq || '',
      it.jobEduReq || '',
      it.hrName || '',
      liepinDeliveryStatus(it.deliveryStatus),
      it.jobLink || '',
      it.createTime || '',
    ])
    exportCsv(`liepin_jobs_${new Date().toISOString().slice(0, 10)}.csv`, header, rows)
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
    const info = parseSalary(it.jobSalaryText)
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
  { label: '失败', value: kpi.value?.failed ?? 0, highlight: 'failed' },
  { label: salaryKpiLabel('liepin'), value: avgSalaryDisplay.value, highlight: 'salary' },
])

const chartConfigs = computed(() => buildChartConfigs(stats.value?.charts, 'liepin'))

const columns = computed<TableColumn<LiepinJob>[]>(() => [
  {
    key: 'compName',
    header: '公司',
    className: 'max-w-[160px] truncate',
    type: 'company-link',
    accessor: (it) => it.compName || '-',
    hrefAccessor: (it) => it.jobLink || '',
  },
  { key: 'jobTitle', header: '岗位', className: 'max-w-[200px] truncate', accessor: (it) => it.jobTitle || '-' },
  { key: 'salary', header: '薪资', className: 'whitespace-nowrap', accessor: (it) => it.jobSalaryText || '-' },
  { key: 'city', header: '城市', className: 'whitespace-nowrap', accessor: (it) => it.jobArea || '-' },
  {
    key: 'status',
    header: '状态',
    type: 'status',
    accessor: (it) => liepinDeliveryStatus(it.deliveryStatus),
  },
  {
    key: 'time',
    header: '时间',
    className: 'whitespace-nowrap',
    type: 'date',
    accessor: (it) => it.createTime,
  },
])

async function onStatusChange(row: LiepinJob, status: string) {
  const current = liepinDeliveryStatus(row.deliveryStatus)
  if (row.jobId == null || current === status) return
  const result = await updateDeliveryStatus(
    `${API}/api/liepin/jobs/${row.jobId}/delivery-status`,
    status
  )
  if (!result.ok) {
    console.error('update liepin delivery status failed', result.message)
    window.alert(result.message)
    await loadList(page.value, size.value, { silent: true })
    return
  }
  row.deliveryStatus = status
  if (detailJob.value?.jobId === row.jobId) detailJob.value.deliveryStatus = status
  await loadStats({ silent: true })
}
</script>

<template>
  <div class="space-y-6">
    <AnalysisPageHeader v-if="showHeader" platform="liepin">
      <template #icon>
        <Icon icon="bi:bar-chart" class="text-2xl" />
      </template>
    </AnalysisPageHeader>

    <AnalysisKpiGrid :items="kpiItems" :loading="loadingStats" />

    <AnalysisFilterPanel
      :filter="filter"
      :status-options="PLATFORM_STATUS_OPTIONS.liepin"
      :show-headhunter-filter="PLATFORM_HEADHUNTER_FILTER.liepin"
      :show-reload="PLATFORM_RELOAD.liepin"
      :loading-list="loadingList"
      :exporting="exporting"
      :salary-unit="salaryUnit"
      :location-options="locationOptions"
      :experience-options="experienceOptions"
      :degree-options="degreeOptions"
      :scale-options="scaleOptions"
      experience-field-type="select"
      @update:filter="onFilterPatch"
      @apply="applyFilter"
      @export="exportCSV"
    />

    <AnalysisChartsGrid :charts="chartConfigs" :loading="loadingStats" />

    <AnalysisJobTable
      :total="total"
      :items="items"
      :columns="columns"
      :row-key="(row) => row.jobId"
      :loading="loadingList"
      :page="page"
      :size="size"
      :total-pages="totalPages"
      :status-options="PLATFORM_STATUS_OPTIONS.liepin"
      @page-change="(p) => loadList(p, size)"
      @size-change="(s) => loadList(1, s)"
      @row-click="(row) => (detailJob = row)"
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
        <h3 class="font-semibold text-lg mb-1">{{ detailJob.jobTitle }}</h3>
        <p class="text-sm text-muted-foreground mb-4">{{ detailJob.compName }}</p>
        <div class="grid grid-cols-2 gap-2 text-sm mb-4">
          <div>薪资：{{ detailJob.jobSalaryText || '-' }}</div>
          <div>城市：{{ detailJob.jobArea || '-' }}</div>
          <div>经验：{{ detailJob.jobExpReq || '-' }}</div>
          <div>学历：{{ detailJob.jobEduReq || '-' }}</div>
          <div>HR：{{ detailJob.hrName || '-' }}</div>
          <div>行业：{{ detailJob.compIndustry || '-' }}</div>
          <div>
            状态：
            <span :class="deliveryStatusClass(liepinDeliveryStatus(detailJob.deliveryStatus))">
              {{ liepinDeliveryStatus(detailJob.deliveryStatus) }}
            </span>
          </div>
          <div>创建时间：{{ formatDateOnly(detailJob.createTime) || '-' }}</div>
        </div>
        <Button variant="outline" size="sm" class="mt-4" @click="detailJob = null">
          关闭
        </Button>
      </div>
    </div>
  </div>
</template>
