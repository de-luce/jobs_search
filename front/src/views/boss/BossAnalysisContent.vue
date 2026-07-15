<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { Icon } from '@iconify/vue'
import Button from '@/components/ui/Button.vue'
import Label from '@/components/ui/Label.vue'
import Textarea from '@/components/ui/Textarea.vue'
import AnalysisPageHeader from '@/components/analysis/AnalysisPageHeader.vue'
import AnalysisKpiGrid from '@/components/analysis/AnalysisKpiGrid.vue'
import { type KpiItem } from '@/components/analysis/kpiHelpers'
import AnalysisFilterPanel from '@/components/analysis/AnalysisFilterPanel.vue'
import AnalysisChartsGrid from '@/components/analysis/AnalysisChartsGrid.vue'
import { buildChartConfigs } from '@/lib/chartConfigs'
import AnalysisJobTable, { type TableColumn } from '@/components/analysis/AnalysisJobTable.vue'
import { formatSalaryByUnit, parseSalary } from '@/lib/salary'
import { useAnalysisRealtimeRefresh } from '@/composables/useRealtime'
import { getApiBase } from '@/lib/platform'
import {
  buildQuery,
  filterToParams,
  exportCsv,
  DEFAULT_FILTER,
  PLATFORM_STATUS_OPTIONS,
  PLATFORM_HEADHUNTER_FILTER,
  PLATFORM_RELOAD,
  PLATFORM_SALARY_UNIT,
  toFilterOptions,
  salaryKpiLabel,
  type StatsResponse,
  type AnalysisFilterState,
  type AnalysisFilterOption,
  type PagedResult,
} from '@/lib/analysis'

type BossJob = {
  id: number
  companyName?: string
  jobName?: string
  salary?: string
  location?: string
  experience?: string
  degree?: string
  hrName?: string
  hrPosition?: string
  hrActiveStatus?: string
  deliveryStatus?: string
  jobUrl?: string
  recruitmentStatus?: string
  companyAddress?: string
  industry?: string
  introduce?: string
  financingStage?: string
  companyScale?: string
  jobDescription?: string
  createdAt?: string
}

withDefaults(defineProps<{ showHeader?: boolean }>(), { showHeader: false })

const API = getApiBase()

const stats = ref<StatsResponse | null>(null)
const loadingStats = ref(true)
const items = ref<BossJob[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const filter = ref<AnalysisFilterState>({ ...DEFAULT_FILTER })
const loadingList = ref(false)
const reloading = ref(false)
const exporting = ref(false)
const detailJob = ref<BossJob | null>(null)
const salaryUnit = PLATFORM_SALARY_UNIT.boss
const locationOptions = ref<AnalysisFilterOption[]>([])
const experienceOptions = ref<AnalysisFilterOption[]>([])
const degreeOptions = ref<AnalysisFilterOption[]>([])
const industryOptions = ref<AnalysisFilterOption[]>([])
const scaleOptions = ref<AnalysisFilterOption[]>([])
const stageOptions = ref<AnalysisFilterOption[]>([])

const filterParams = computed(() => filterToParams(filter.value, 'boss'))

async function loadFilterOptions() {
  try {
    const res = await fetch(`${API}/api/boss/config`)
    const data = await res.json()
    const opts = data.options || {}
    locationOptions.value = toFilterOptions(opts.city)
    experienceOptions.value = toFilterOptions(opts.experience)
    degreeOptions.value = toFilterOptions(opts.degree)
    industryOptions.value = toFilterOptions(opts.industry)
    scaleOptions.value = toFilterOptions(opts.scale)
    stageOptions.value = toFilterOptions(opts.stage)
  } catch (e) {
    console.error('fetch boss filter options failed', e)
  }
}

function filterHeadhunterRows(rows: BossJob[]) {
  return rows.filter((it) => {
    const hp = (it.hrPosition || '').toLowerCase()
    return !(hp.includes('猎头') || hp.includes('獵頭'))
  })
}

async function loadStats(opts?: { silent?: boolean }) {
  try {
    if (!opts?.silent) loadingStats.value = true
    const res = await fetch(`${API}/api/boss/stats?${buildQuery(filterParams.value)}`)
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
    const res = await fetch(`${API}/api/boss/list?${q}`)
    const data: PagedResult<BossJob> = await res.json()
    let rows = data.items || []
    if (filter.value.filterHeadhunter) {
      rows = filterHeadhunterRows(rows)
    }
    items.value = rows
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
  void loadFilterOptions()
  void loadList(1, size.value)
  void loadStats()
})

useAnalysisRealtimeRefresh({ platform: 'boss', onRefresh: refreshAll })

async function applyFilters() {
  await loadList(1, size.value)
  await loadStats()
}

async function onReload() {
  try {
    reloading.value = true
    await fetch(`${API}/api/boss/reload`)
    await loadList(1, size.value)
    await loadStats()
  } finally {
    reloading.value = false
  }
}

async function onExport() {
  try {
    exporting.value = true
    const pageSize = 1000
    let currentPage = 1
    let all: BossJob[] = []
    let totalCount = 0
    while (true) {
      const q = buildQuery({ ...filterParams.value, page: String(currentPage), size: String(pageSize) })
      const res = await fetch(`${API}/api/boss/list?${q}`)
      const data: PagedResult<BossJob> = await res.json()
      let chunk = data.items || []
      if (filter.value.filterHeadhunter) {
        chunk = filterHeadhunterRows(chunk)
      }
      if (currentPage === 1) totalCount = data.total || chunk.length
      all = all.concat(chunk)
      if (all.length >= totalCount || chunk.length === 0) break
      currentPage += 1
    }
    const header = ['公司名称', '岗位名称', '薪资', '地点', '经验', '学历', 'HR', '投递状态', '链接', '创建时间']
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
      it.createdAt || '',
    ])
    exportCsv(`boss_jobs_${new Date().toISOString().slice(0, 10)}.csv`, header, rows)
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
  { label: '失败', value: kpi.value?.failed ?? 0, highlight: 'failed' },
  { label: salaryKpiLabel('boss'), value: avgSalaryDisplay.value, highlight: 'salary' },
])

const chartConfigs = computed(() =>
  buildChartConfigs(stats.value?.charts, 'boss').filter((c) => c.key !== 'byCity')
)

const columns: TableColumn<BossJob>[] = [
  {
    key: 'company',
    header: '公司',
    className: 'max-w-[140px] truncate',
    type: 'company-link',
    accessor: 'companyName',
    hrefAccessor: 'jobUrl',
  },
  {
    key: 'job',
    header: '岗位',
    className: 'max-w-[180px] truncate',
    accessor: 'jobName',
  },
  {
    key: 'salary',
    header: '薪资',
    className: 'whitespace-nowrap',
    accessor: 'salary',
  },
  {
    key: 'location',
    header: '地点',
    className: 'whitespace-nowrap',
    accessor: 'location',
  },
  {
    key: 'hr',
    header: 'HR',
    className: 'max-w-[100px] truncate',
    accessor: 'hrName',
  },
  {
    key: 'status',
    header: '状态',
    type: 'status',
    accessor: 'deliveryStatus',
  },
  {
    key: 'time',
    header: '时间',
    className: 'whitespace-nowrap',
    type: 'date',
    accessor: 'createdAt',
  },
]

function onFilterPatch(patch: Partial<AnalysisFilterState>) {
  filter.value = { ...filter.value, ...patch }
}

async function onStatusChange(row: BossJob, status: string) {
  if (!row.id || row.deliveryStatus === status) return
  try {
    const res = await fetch(`${API}/api/boss/jobs/${row.id}/delivery-status`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ status }),
    })
    const data = await res.json()
    if (!data?.success) {
      console.error('update boss delivery status failed', data?.message)
      await loadList(page.value, size.value, { silent: true })
      return
    }
    row.deliveryStatus = status
    if (detailJob.value?.id === row.id) detailJob.value.deliveryStatus = status
    await loadStats({ silent: true })
  } catch (e) {
    console.error('update boss delivery status failed', e)
    await loadList(page.value, size.value, { silent: true })
  }
}

const detailFields: [string, keyof BossJob][] = [
  ['薪资', 'salary'],
  ['地点', 'location'],
  ['经验', 'experience'],
  ['学历', 'degree'],
  ['HR', 'hrName'],
  ['HR职位', 'hrPosition'],
  ['HR活跃', 'hrActiveStatus'],
  ['投递状态', 'deliveryStatus'],
  ['行业', 'industry'],
  ['规模', 'companyScale'],
  ['融资', 'financingStage'],
  ['地址', 'companyAddress'],
]
</script>

<template>
  <div class="space-y-6">
    <AnalysisPageHeader v-if="showHeader" platform="boss">
      <template #icon>
        <Icon icon="bi:bar-chart" class="text-2xl" />
      </template>
    </AnalysisPageHeader>

    <AnalysisKpiGrid :items="kpiItems" :loading="loadingStats" />

    <AnalysisFilterPanel
      :filter="filter"
      :status-options="PLATFORM_STATUS_OPTIONS.boss"
      :show-headhunter-filter="PLATFORM_HEADHUNTER_FILTER.boss"
      :show-reload="PLATFORM_RELOAD.boss"
      :loading-list="loadingList"
      :reloading="reloading"
      :exporting="exporting"
      :salary-unit="salaryUnit"
      :location-options="locationOptions"
      :experience-options="experienceOptions"
      :degree-options="degreeOptions"
      :industry-options="industryOptions"
      :scale-options="scaleOptions"
      :stage-options="stageOptions"
      experience-field-type="select"
      @update:filter="onFilterPatch"
      @apply="applyFilters"
      @reload="onReload"
      @export="onExport"
    />

    <AnalysisChartsGrid :charts="chartConfigs" :loading="loadingStats" />

    <AnalysisJobTable
      :total="total"
      :items="items"
      :columns="columns"
      :row-key="(row) => row.id"
      :loading="loadingList"
      :page="page"
      :size="size"
      :total-pages="totalPages"
      :status-options="PLATFORM_STATUS_OPTIONS.boss"
      @page-change="loadList($event, size)"
      @size-change="loadList(1, $event)"
      @row-click="detailJob = $event"
      @status-change="onStatusChange"
    />

    <div
      v-if="detailJob"
      class="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
      @click="detailJob = null"
    >
      <div
        class="bg-background rounded-lg border shadow-lg w-full max-w-2xl max-h-[85vh] overflow-y-auto p-5"
        @click.stop
      >
        <div class="flex items-start justify-between gap-3 mb-4">
          <div>
            <h3 class="font-semibold text-lg">{{ detailJob.jobName || '岗位详情' }}</h3>
            <p class="text-sm text-muted-foreground">{{ detailJob.companyName }}</p>
          </div>
          <Button variant="outline" size="sm" @click="detailJob = null">关闭</Button>
        </div>
        <div class="grid grid-cols-2 gap-x-4 gap-y-2 text-sm mb-4">
          <div v-for="[label, key] in detailFields" :key="label">
            <span class="text-muted-foreground">{{ label }}：</span>
            {{ detailJob[key] || '-' }}
          </div>
        </div>
        <Label class="text-xs text-muted-foreground">岗位描述</Label>
        <Textarea
          :model-value="detailJob.jobDescription || ''"
          readonly
          class="w-full h-32 mt-1 text-sm bg-muted/30"
          :rows="5"
        />
      </div>
    </div>
  </div>
</template>
