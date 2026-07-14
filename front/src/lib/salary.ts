export type SalaryInfo = {
  minK: number
  maxK: number
  months: number
  medianK: number
  annualTotal: number
}

/** 各平台岗位薪资展示习惯（内部计算仍统一为月薪 K） */
export type SalaryDisplayUnit = 'K' | '万' | '万/年'

/**
 * 解析薪资字符串并换算为「月薪 K」（1K = 1000 元/月）。
 * 支持：20-40K、1.5-2万、1.5-2万·13薪、25-41万/年、9000-16000元、6-8千
 */
export function parseSalary(raw?: string): SalaryInfo | undefined {
  if (!raw) return undefined
  let s = raw.trim()
  if (!s || s.includes('面议')) return undefined
  s = s.replace(/\s+/g, '')
  const original = s
  const lower = s.toLowerCase()

  let months = 12
  const monthsMatch = lower.match(/[·.\-]?([0-9]{1,2})薪/)
  if (monthsMatch && monthsMatch.index != null) {
    months = Number(monthsMatch[1]) || 12
    s = s.slice(0, monthsMatch.index)
  }

  const range = s.match(/(\d+(?:\.\d+)?)\s*[-~～—]+\s*(\d+(?:\.\d+)?)/)
  const single = s.match(/(\d+(?:\.\d+)?)/)
  let a: number | undefined
  let b: number | undefined
  if (range) {
    a = Number(range[1])
    b = Number(range[2])
  } else if (single) {
    a = Number(single[1])
    b = a
  }
  if (a == null || b == null || Number.isNaN(a) || Number.isNaN(b)) return undefined

  const min = Math.min(a, b)
  const max = Math.max(a, b)
  const factor = resolveFactorToMonthlyK(lower, original)
  const minK = min * factor
  const maxK = max * factor
  const medianK = (minK + maxK) / 2
  return {
    minK,
    maxK,
    months,
    medianK,
    annualTotal: Math.round(medianK * 1000 * months),
  }
}

function resolveFactorToMonthlyK(normalizedLower: string, original: string): number {
  const s = normalizedLower
  if (s.includes('k')) return 1
  if (s.includes('元/天') || s.includes('/天')) return 22 / 1000
  const annual = s.includes('/年') || s.includes('年薪') || (s.includes('万') && s.includes('年') && !s.includes('/月'))
  if (s.includes('万')) return annual ? 10 / 12 : 10
  if (s.includes('千')) return 1
  if (s.includes('元')) return annual ? 1 / 1000 / 12 : 1 / 1000

  const range = s.match(/(\d+(?:\.\d+)?)\s*[-~]+\s*(\d+(?:\.\d+)?)/)
  if (range) {
    const sample = Math.max(Number(range[1]), Number(range[2]))
    if (sample >= 100) return 1 / 1000
  }
  if (!original.toLowerCase().includes('k') && !original.includes('千')) {
    const m = s.match(/(\d+(?:\.\d+)?)/)
    if (m) {
      const v = Number(m[1])
      if (v > 0 && v < 100) return 10
    }
  }
  return 1
}

/** 将 UI 上的薪资单位数值换算为后端 minK/maxK（月薪 K） */
export function displaySalaryToMonthlyK(value: number, unit: SalaryDisplayUnit): number {
  if (unit === 'K') return value
  if (unit === '万') return value * 10
  // 万/年 → 月薪 K
  return (value * 10) / 12
}

/** 月薪 K → UI 展示数值 */
export function monthlyKToDisplay(k: number, unit: SalaryDisplayUnit): number {
  if (unit === 'K') return k
  if (unit === '万') return k / 10
  return (k * 12) / 10
}

export function formatSalaryByUnit(k: number | null | undefined, unit: SalaryDisplayUnit, digits = 1): string {
  if (k == null || Number.isNaN(k)) return '-'
  const v = monthlyKToDisplay(k, unit)
  return `${v.toFixed(digits)}${unit === 'K' ? 'K' : unit}`
}
