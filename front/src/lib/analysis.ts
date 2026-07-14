import type { PlatformId } from './platform'

export type NameValue = { name: string; value: number }
export type BucketValue = { bucket: string; value: number }

export type AnalysisKpi = {
  total: number
  delivered: number
  pending: number
  filtered?: number
  failed?: number
  avgMonthlyK?: number | null
}

export type AnalysisCharts = {
  byStatus?: NameValue[]
  byCity?: NameValue[]
  byIndustry?: NameValue[]
  byCompany?: NameValue[]
  byExperience?: NameValue[]
  byDegree?: NameValue[]
  salaryBuckets?: BucketValue[]
}

export type StatsResponse = {
  kpi: AnalysisKpi
  charts: AnalysisCharts
}

export type PagedResult<T> = {
  items: T[]
  total: number
  page: number
  size: number
}

export type AnalysisFilterState = {
  statuses: string[]
  location: string
  experience: string
  degree: string
  minK: string
  maxK: string
  keyword: string
  filterHeadhunter: boolean
}

export const DEFAULT_FILTER: AnalysisFilterState = {
  statuses: [],
  location: '',
  experience: '',
  degree: '',
  minK: '',
  maxK: '',
  keyword: '',
  filterHeadhunter: false,
}

export function buildQuery(params: Record<string, string | undefined | null>) {
  const q = new URLSearchParams()
  Object.entries(params).forEach(([k, v]) => {
    if (v != null && String(v).trim() !== '') q.set(k, String(v))
  })
  return q
}

function toOptionalNumberParam(raw?: string): string | undefined {
  if (raw == null || String(raw).trim() === '') return undefined
  const n = Number(raw)
  if (Number.isNaN(n)) return undefined
  return String(n)
}

export function filterToParams(filter: AnalysisFilterState) {
  return {
    statuses: filter.statuses.length ? filter.statuses.join(',') : undefined,
    location: filter.location || undefined,
    experience: filter.experience || undefined,
    degree: filter.degree || undefined,
    minK: toOptionalNumberParam(filter.minK),
    maxK: toOptionalNumberParam(filter.maxK),
    keyword: filter.keyword || undefined,
    filterHeadhunter: filter.filterHeadhunter ? 'true' : undefined,
  }
}

export function formatDateOnly(s?: string) {
  if (!s) return '-'
  const d = new Date(s)
  if (!isNaN(d.getTime())) return d.toISOString().slice(0, 10)
  return s.slice(0, 10)
}

export function exportCsv(filename: string, header: string[], rows: string[][]) {
  const csv = [header, ...rows]
    .map((r) =>
      r
        .map((v) => (String(v).includes(',') ? `"${String(v).replace(/"/g, '""')}"` : String(v)))
        .join(',')
    )
    .join('\n')
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}

/** 各平台投递分析状态选项 */
export const PLATFORM_STATUS_OPTIONS: Record<PlatformId, string[]> = {
  boss: ['未投递', '已投递', '已过滤', '投递失败'],
  liepin: ['未投递', '已投递', '已过滤', '投递失败'],
  '51job': ['未投递', '已投递', '已过滤', '投递失败'],
  zhilian: ['未投递', '已投递', '已过滤', '投递失败'],
}

/** 各平台是否支持猎头过滤 */
export const PLATFORM_HEADHUNTER_FILTER: Record<PlatformId, boolean> = {
  boss: true,
  liepin: false,
  '51job': false,
  zhilian: false,
}

/** 各平台是否支持 reload */
export const PLATFORM_RELOAD: Record<PlatformId, boolean> = {
  boss: true,
  liepin: false,
  '51job': true,
  zhilian: false,
}
