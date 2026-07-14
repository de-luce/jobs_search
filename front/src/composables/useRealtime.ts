import { ref, watch, onMounted, onUnmounted, type Ref } from 'vue'
import { createSSEWithBackoff } from '@/lib/sse'
import { getApiBase, type PlatformId } from '@/lib/platform'

const STATUS_PATH: Record<PlatformId, string> = {
  boss: '/api/boss/status',
  liepin: '/api/liepin/status',
  '51job': '/api/51job/status',
  zhilian: '/api/zhilian/status',
}

const SSE_PATH: Partial<Record<PlatformId, string>> = {
  boss: '/api/boss/stream',
  '51job': '/api/51job/stream',
}

export async function fetchPlatformRunning(platform: PlatformId): Promise<boolean> {
  const res = await fetch(`${getApiBase()}${STATUS_PATH[platform]}`)
  if (!res.ok) return false
  const data = await res.json()
  return Boolean(data.isRunning)
}

export function useVisibleInterval(callback: () => void, delayMs: Ref<number | null> | number | null, enabled = true) {
  let intervalId: ReturnType<typeof setInterval> | null = null

  const start = () => {
    stop()
    const delay = typeof delayMs === 'object' && delayMs !== null && 'value' in delayMs ? delayMs.value : delayMs
    if (!enabled || delay == null || delay <= 0) return

    const tick = () => {
      if (typeof document === 'undefined' || document.visibilityState === 'visible') {
        callback()
      }
    }
    intervalId = setInterval(tick, delay)
    const onVisibility = () => {
      if (document.visibilityState === 'visible') tick()
    }
    document.addEventListener('visibilitychange', onVisibility)
    return () => document.removeEventListener('visibilitychange', onVisibility)
  }

  let removeVisibility: (() => void) | undefined

  const stop = () => {
    if (intervalId) {
      clearInterval(intervalId)
      intervalId = null
    }
    removeVisibility?.()
    removeVisibility = undefined
  }

  onMounted(() => {
    removeVisibility = start()
  })

  if (typeof delayMs === 'object' && delayMs !== null && 'value' in delayMs) {
    watch(delayMs, () => {
      removeVisibility?.()
      removeVisibility = start()
    })
  }

  onUnmounted(stop)

  return { stop }
}

export function usePlatformDeliveryStatus(platform: PlatformId, pollMs = 2500) {
  const isRunning = ref(false)

  const refresh = async () => {
    try {
      isRunning.value = await fetchPlatformRunning(platform)
    } catch {
      // keep last state
    }
  }

  const setRunning = (running: boolean) => {
    isRunning.value = running
  }

  // 必须在 setup 同步阶段调用，不能放进 onMounted，否则轮询不会启动
  onMounted(() => {
    void refresh()
  })
  useVisibleInterval(() => void refresh(), pollMs, true)

  return { isRunning, refresh, setRunning }
}

type AnalysisRealtimeRefreshOptions = {
  platform: PlatformId
  onRefresh: () => void
  idleIntervalMs?: number
  activeIntervalMs?: number
  enabled?: boolean
}

export function useAnalysisRealtimeRefresh({
  platform,
  onRefresh,
  idleIntervalMs = 8000,
  activeIntervalMs = 3000,
  enabled = true,
}: AnalysisRealtimeRefreshOptions) {
  const { isRunning } = usePlatformDeliveryStatus(platform, 2500)

  const refresh = () => onRefresh()

  const interval = ref(idleIntervalMs)
  watch(isRunning, (running) => {
    interval.value = running ? activeIntervalMs : idleIntervalMs
  }, { immediate: true })

  if (enabled) {
    useVisibleInterval(refresh, interval, true)
  }

  let sseClient: ReturnType<typeof createSSEWithBackoff> | null = null

  onMounted(() => {
    if (!enabled) return
    const ssePath = SSE_PATH[platform]
    if (!ssePath || typeof EventSource === 'undefined') return

    let debounceTimer: ReturnType<typeof setTimeout> | null = null
    const trigger = () => {
      if (debounceTimer) clearTimeout(debounceTimer)
      debounceTimer = setTimeout(() => refresh(), 400)
    }

    sseClient = createSSEWithBackoff(`${getApiBase()}${ssePath}`, {
      listeners: [
        { name: 'progress', handler: trigger },
        { name: 'connected', handler: () => {} },
        { name: 'ping', handler: () => {} },
      ],
    })
  })

  onUnmounted(() => {
    sseClient?.close()
  })

  return { isRunning }
}
