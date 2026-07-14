import { useDark, useToggle } from '@vueuse/core'

export function useAppTheme() {
  const isDark = useDark({
    selector: 'html',
    attribute: 'class',
    valueDark: 'dark',
    valueLight: '',
    storageKey: 'jobs_search-theme',
  })
  const toggleTheme = useToggle(isDark)
  return { isDark, toggleTheme }
}
