export type KpiHighlight = 'default' | 'delivered' | 'pending' | 'filtered' | 'failed' | 'salary'

export type KpiItem = {
  label: string
  value: string | number
  highlight?: KpiHighlight
}

export const HIGHLIGHT_CLASS: Record<KpiHighlight, string> = {
  default: 'border-border',
  delivered: 'border-green-300/70 bg-green-50 dark:bg-green-950/40',
  pending: 'border-amber-300/70 bg-amber-50 dark:bg-amber-950/40',
  filtered: 'border-pink-300/70 bg-pink-50 dark:bg-pink-950/40',
  failed: 'border-red-300/70 bg-red-50 dark:bg-red-950/40',
  salary: 'border-sky-300/70 bg-sky-50 dark:bg-sky-950/40',
}

export const VALUE_CLASS: Record<KpiHighlight, string> = {
  default: 'text-foreground',
  delivered: 'text-green-700 dark:text-green-300',
  pending: 'text-amber-700 dark:text-amber-300',
  filtered: 'text-pink-700 dark:text-pink-300',
  failed: 'text-red-700 dark:text-red-300',
  salary: 'text-sky-700 dark:text-sky-300',
}

export function formatAvgSalaryK(value?: number | null) {
  if (value == null || Number.isNaN(value)) return '—'
  return String(Math.round(value * 10) / 10)
}

export function deliveryStatusClass(status?: string) {
  const v = (status || '').trim()
  if (v.includes('已投递')) {
    return 'px-2 py-0.5 rounded-full text-xs font-medium bg-green-100 text-green-700 dark:bg-green-900/40 dark:text-green-300'
  }
  if (v.includes('未投递') || v === '未投递') {
    return 'px-2 py-0.5 rounded-full text-xs font-medium bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-200'
  }
  if (v.includes('已过滤')) {
    return 'px-2 py-0.5 rounded-full text-xs font-medium bg-pink-100 text-pink-700 dark:bg-pink-900/40 dark:text-pink-300'
  }
  if (v.includes('失败')) {
    return 'px-2 py-0.5 rounded-full text-xs font-medium bg-red-100 text-red-700 dark:bg-red-900/40 dark:text-red-300'
  }
  return 'px-2 py-0.5 rounded-full text-xs font-medium bg-muted text-muted-foreground'
}

export function statusFilterClass(status: string, checked: boolean) {
  if (!checked) {
    return 'inline-flex items-center gap-1.5 text-sm px-2.5 py-1 rounded-md border border-transparent hover:bg-muted/60'
  }
  if (status === '已投递') {
    return 'inline-flex items-center gap-1.5 text-sm px-2.5 py-1 rounded-md border border-green-300 bg-green-50 text-green-800 dark:bg-green-950/40 dark:text-green-200 dark:border-green-700'
  }
  if (status === '未投递') {
    return 'inline-flex items-center gap-1.5 text-sm px-2.5 py-1 rounded-md border border-amber-300 bg-amber-50 text-amber-800 dark:bg-amber-950/40 dark:text-amber-200 dark:border-amber-700'
  }
  if (status === '已过滤') {
    return 'inline-flex items-center gap-1.5 text-sm px-2.5 py-1 rounded-md border border-pink-300 bg-pink-50 text-pink-800 dark:bg-pink-950/40 dark:text-pink-200 dark:border-pink-700'
  }
  if (status === '投递失败') {
    return 'inline-flex items-center gap-1.5 text-sm px-2.5 py-1 rounded-md border border-red-300 bg-red-50 text-red-800 dark:bg-red-950/40 dark:text-red-200 dark:border-red-700'
  }
  return 'inline-flex items-center gap-1.5 text-sm px-2.5 py-1 rounded-md border bg-muted'
}
