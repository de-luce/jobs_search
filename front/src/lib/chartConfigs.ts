import type { AnalysisCharts } from '@/lib/analysis'
import { PLATFORM_SALARY_UNIT } from '@/lib/analysis'
import { CATEGORY_COLORS } from '@/lib/chartColors'
import type { PlatformId } from '@/lib/platform'
import type { SalaryDisplayUnit } from '@/lib/salary'

export type ChartConfig = {
  key: string
  title: string
  type: 'pie' | 'bar' | 'line'
  labels: string[]
  data: number[]
  colors?: string[]
  color?: string
  icon?: string
}

function remapSalaryBucketLabel(bucket: string, unit: SalaryDisplayUnit): string {
  if (unit === 'K') return bucket
  const convert = (n: number) => {
    if (unit === '万') return String(Math.round((n / 10) * 10) / 10)
    return String(Math.round(((n * 12) / 10) * 10) / 10)
  }
  const range = bucket.match(/^(\d+(?:\.\d+)?)-(\d+(?:\.\d+)?)K$/i)
  if (range) return `${convert(Number(range[1]))}-${convert(Number(range[2]))}${unit}`
  const ge = bucket.match(/^>=\s*(\d+(?:\.\d+)?)K$/i)
  if (ge) return `>=${convert(Number(ge[1]))}${unit}`
  return bucket.replace(/K/gi, unit)
}

export function buildChartConfigs(stats: AnalysisCharts | undefined, platform: string): ChartConfig[] {
  const c = stats || {}
  const unit = PLATFORM_SALARY_UNIT[platform as PlatformId] || 'K'
  const base: ChartConfig[] = [
    {
      key: 'byStatus',
      title: '投递状态',
      type: 'pie',
      labels: c.byStatus?.map((x) => x.name) || [],
      data: c.byStatus?.map((x) => x.value) || [],
      icon: 'bi:pie-chart',
    },
  ]

  if (c.byCity?.length) {
    base.push({
      key: 'byCity',
      title: '城市分布',
      type: 'bar',
      labels: c.byCity.map((x) => x.name),
      data: c.byCity.map((x) => x.value),
      colors: CATEGORY_COLORS,
      icon: 'bi:bar-chart',
    })
  }

  if (c.byIndustry?.length) {
    base.push({
      key: 'byIndustry',
      title: platform === 'boss' ? '行业 TOP10' : '行业分布',
      type: 'bar',
      labels: c.byIndustry.map((x) => x.name),
      data: c.byIndustry.map((x) => x.value),
      colors: CATEGORY_COLORS,
      icon: 'bi:bar-chart',
    })
  }

  if (c.byCompany?.length) {
    base.push({
      key: 'byCompany',
      title: '公司 TOP10',
      type: 'bar',
      labels: c.byCompany.map((x) => x.name),
      data: c.byCompany.map((x) => x.value),
      colors: CATEGORY_COLORS,
      icon: 'bi:bar-chart',
    })
  }

  if (c.byExperience?.length) {
    base.push({
      key: 'byExperience',
      title: '经验分布',
      type: 'bar',
      labels: c.byExperience.map((x) => x.name),
      data: c.byExperience.map((x) => x.value),
      colors: CATEGORY_COLORS,
      icon: 'bi:bar-chart',
    })
  }

  if (c.byDegree?.length) {
    base.push({
      key: 'byDegree',
      title: '学历分布',
      type: 'bar',
      labels: c.byDegree.map((x) => x.name),
      data: c.byDegree.map((x) => x.value),
      colors: CATEGORY_COLORS,
      icon: 'bi:bar-chart',
    })
  }

  if (c.salaryBuckets?.length) {
    base.push({
      key: 'salaryBuckets',
      title: `薪资区间（${unit}）`,
      type: platform === 'boss' || platform === '51job' ? 'line' : 'bar',
      labels: c.salaryBuckets.map((x) => remapSalaryBucketLabel(x.bucket, unit)),
      data: c.salaryBuckets.map((x) => x.value),
      colors: platform === 'boss' || platform === '51job' ? undefined : CATEGORY_COLORS,
      color: platform === 'boss' || platform === '51job' ? '#ef4444' : undefined,
      icon: platform === 'boss' || platform === '51job' ? 'bi:line-chart' : 'bi:bar-chart',
    })
  }

  return base
}
