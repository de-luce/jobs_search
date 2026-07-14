import type { PlatformId } from './platform'

export type PlatformTheme = {
  label: string
  shortLabel: string
  icon: string
  accentName: 'teal' | 'orange' | 'amber' | 'sky'
  accentBgClass: string
  accentTextClass: string
  sectionIconClass: string
}

export const PLATFORM_THEME: Record<PlatformId, PlatformTheme> = {
  boss: {
    label: 'Boss直聘',
    shortLabel: 'Boss',
    icon: 'bi:briefcase',
    accentName: 'teal',
    accentBgClass: 'bg-teal-500',
    accentTextClass: 'text-teal-600',
    sectionIconClass: 'text-teal-600 dark:text-teal-400',
  },
  liepin: {
    label: '猎聘',
    shortLabel: '猎聘',
    icon: 'bi:search',
    accentName: 'orange',
    accentBgClass: 'bg-orange-500',
    accentTextClass: 'text-orange-600',
    sectionIconClass: 'text-orange-600 dark:text-orange-400',
  },
  '51job': {
    label: '51job',
    shortLabel: '51job',
    icon: 'bi:clipboard',
    accentName: 'amber',
    accentBgClass: 'bg-amber-500',
    accentTextClass: 'text-amber-600',
    sectionIconClass: 'text-amber-600 dark:text-amber-400',
  },
  zhilian: {
    label: '智联招聘',
    shortLabel: '智联',
    icon: 'bi:building',
    accentName: 'sky',
    accentBgClass: 'bg-sky-500',
    accentTextClass: 'text-sky-600',
    sectionIconClass: 'text-sky-600 dark:text-sky-400',
  },
}

export function getPlatformTheme(platform: PlatformId): PlatformTheme {
  return PLATFORM_THEME[platform]
}

export function platformFromPath(path: string): PlatformId | null {
  if (path.startsWith('/boss')) return 'boss'
  if (path.startsWith('/liepin')) return 'liepin'
  if (path.startsWith('/51job')) return '51job'
  if (path.startsWith('/zhilian')) return 'zhilian'
  return null
}
