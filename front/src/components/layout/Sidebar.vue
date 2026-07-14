<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRoute, RouterLink } from 'vue-router'
import AppIcon from '@/components/AppIcon.vue'
import { getApiBase } from '@/lib/platform'
import { getPlatformTheme } from '@/lib/platformTheme'
import { useAppTheme } from '@/composables/useTheme'

const route = useRoute()
const { isDark, toggleTheme } = useAppTheme()
const health = ref<'up' | 'degraded' | 'down' | 'unknown'>('unknown')

const envGroup = [
  { href: '/env-config', icon: 'bi:gear', label: '环境配置' },
  { href: '/ai-config', icon: 'bi:stars', label: 'AI 配置' },
  { href: '/blacklist', icon: 'bi:slash-circle', label: '全局黑名单' },
]

const platformGroup = [
  { href: '/boss', platform: 'boss' as const },
  { href: '/liepin', platform: 'liepin' as const },
  { href: '/51job', platform: '51job' as const },
  { href: '/zhilian', platform: 'zhilian' as const },
]

const healthColor = computed(() =>
  health.value === 'up'
    ? 'bg-emerald-500'
    : health.value === 'degraded'
      ? 'bg-amber-500'
      : health.value === 'down'
        ? 'bg-red-500'
        : 'bg-muted-foreground/40'
)

const healthText = computed(() =>
  health.value === 'up'
    ? '服务正常'
    : health.value === 'degraded'
      ? '服务降级'
      : health.value === 'down'
        ? '服务异常'
        : '未连接'
)

let interval: ReturnType<typeof setInterval> | null = null

async function checkHealth() {
  const baseUrl = getApiBase()
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), 3000)
  try {
    let res = await fetch(`${baseUrl}/api/health`, { signal: controller.signal })
    if (res.status === 404) {
      res = await fetch(`${baseUrl}/actuator/health`, { signal: controller.signal })
    }
    if (!res.ok) throw new Error(`status ${res.status}`)
    const data = await res.json()
    const statusRaw = (data.status || data.state || '').toString().toUpperCase()
    if (statusRaw === 'UP' || statusRaw === 'HEALTHY') health.value = 'up'
    else if (statusRaw === 'DEGRADED' || statusRaw === 'WARN') health.value = 'degraded'
    else health.value = 'down'
  } catch {
    health.value = 'unknown'
  } finally {
    clearTimeout(timeout)
  }
}

const isActive = (href: string) => route.path === href || route.path.startsWith(`${href}/`)

onMounted(() => {
  void checkHealth()
  interval = setInterval(checkHealth, 30000)
})

onUnmounted(() => {
  if (interval) clearInterval(interval)
})
</script>

<template>
  <aside class="fixed left-0 top-0 z-50 flex h-full w-56 flex-col border-r border-border bg-card">
    <div class="border-b border-border px-4 py-5">
      <div class="flex items-center gap-2.5">
        <span class="text-xl">🍀</span>
        <div>
          <h1 class="text-base font-semibold tracking-tight">Get Jobs</h1>
          <p class="text-xs text-muted-foreground">求职配置中心</p>
        </div>
      </div>
      <div class="mt-3 flex items-center gap-2 text-xs text-muted-foreground">
        <span :class="['h-2 w-2 rounded-full', healthColor]" />
        <span>{{ healthText }}</span>
      </div>
    </div>

    <nav class="flex-1 space-y-6 overflow-y-auto p-3">
      <div>
        <p class="px-2 pb-1 text-[11px] font-medium uppercase tracking-wide text-muted-foreground">系统</p>
        <div class="space-y-0.5">
          <RouterLink
            v-for="item in envGroup"
            :key="item.href"
            :to="item.href"
            :class="[
              'flex items-center gap-2.5 rounded-md px-2.5 py-2 text-sm transition-colors',
              isActive(item.href)
                ? 'bg-primary/10 text-primary font-medium'
                : 'text-foreground/80 hover:bg-muted hover:text-foreground',
            ]"
          >
            <AppIcon :icon="item.icon" :size="17" />
            <span>{{ item.label }}</span>
          </RouterLink>
        </div>
      </div>

      <div>
        <p class="px-2 pb-1 text-[11px] font-medium uppercase tracking-wide text-muted-foreground">平台</p>
        <div class="space-y-0.5">
          <RouterLink
            v-for="item in platformGroup"
            :key="item.href"
            :to="item.href"
            :class="[
              'flex items-center gap-2.5 rounded-md px-2.5 py-2 text-sm transition-colors',
              isActive(item.href)
                ? `bg-primary/10 font-medium ${getPlatformTheme(item.platform).accentTextClass}`
                : 'text-foreground/80 hover:bg-muted hover:text-foreground',
            ]"
          >
            <AppIcon
              :icon="getPlatformTheme(item.platform).icon"
              :size="17"
              :class="isActive(item.href) ? getPlatformTheme(item.platform).accentTextClass : ''"
            />
            <span>{{ getPlatformTheme(item.platform).label }}</span>
          </RouterLink>
        </div>
      </div>
    </nav>

    <div class="border-t border-border p-3 space-y-2">
      <button
        type="button"
        class="flex w-full items-center justify-center gap-2 rounded-md border border-border px-3 py-2 text-sm hover:bg-muted"
        @click="toggleTheme()"
      >
        <AppIcon :icon="isDark ? 'bi:sun' : 'bi:moon'" :size="16" />
        <span>{{ isDark ? '浅色' : '深色' }}</span>
      </button>
      <p class="text-center text-[11px] text-muted-foreground">v1.0.0</p>
    </div>
  </aside>
</template>
