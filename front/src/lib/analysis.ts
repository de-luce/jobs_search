import type { PlatformId } from './platform'
import { displaySalaryToMonthlyK, type SalaryDisplayUnit } from './salary'

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

export type AnalysisFilterOption = { name: string; code?: string }

export type AnalysisFilterState = {
  statuses: string[]
  location: string
  experience: string
  degree: string
  /** UI 薪资下限（按平台单位：K / 万 / 万/年） */
  minK: string
  maxK: string
  keyword: string
  filterHeadhunter: boolean
  industry: string
  scale: string
  stage: string
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
  industry: '',
  scale: '',
  stage: '',
}

/** 分析页薪资筛选/KPI 展示单位（与各站岗位原文习惯对齐） */
export const PLATFORM_SALARY_UNIT: Record<PlatformId, SalaryDisplayUnit> = {
  boss: 'K',
  '51job': '万',
  zhilian: '万',
  liepin: '万/年',
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

/** 将筛选表单转为 API 参数；薪资按平台单位换算为后端月薪 minK/maxK */
export function filterToParams(filter: AnalysisFilterState, platform?: PlatformId) {
  const unit = platform ? PLATFORM_SALARY_UNIT[platform] : 'K'
  const minRaw = toOptionalNumberParam(filter.minK)
  const maxRaw = toOptionalNumberParam(filter.maxK)
  const minK =
    minRaw == null ? undefined : String(displaySalaryToMonthlyK(Number(minRaw), unit))
  const maxK =
    maxRaw == null ? undefined : String(displaySalaryToMonthlyK(Number(maxRaw), unit))

  return {
    statuses: filter.statuses.length ? filter.statuses.join(',') : undefined,
    location: filter.location || undefined,
    experience: filter.experience || undefined,
    degree: filter.degree || undefined,
    minK,
    maxK,
    keyword: filter.keyword || undefined,
    filterHeadhunter: filter.filterHeadhunter ? 'true' : undefined,
    industry: filter.industry || undefined,
    scale: filter.scale || undefined,
    stage: filter.stage || undefined,
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

/** 将配置选项转为分析筛选下拉 */
export function toFilterOptions(
  list?: Array<{ name?: string; code?: string } | null>
): AnalysisFilterOption[] {
  return (list || [])
    .filter((o): o is { name: string; code?: string } => !!o?.name && o.name !== '不限')
    .map((o) => ({ name: o.name, code: o.code }))
}

export function salaryKpiLabel(platform: PlatformId): string {
  const unit = PLATFORM_SALARY_UNIT[platform]
  return `均薪(${unit})`
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
