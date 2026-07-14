import { ref, watch, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { getApiBase, openManagementPage, openPlatform } from '@/lib/platform'
import { platformFromPath } from '@/lib/platformTheme'

export function useCdpSession() {
  const route = useRoute()
  const cdpReady = ref(false)
  const cdpHint = ref('')
  let syncing = false
  let lastPlatformOpened: string | null = null

  async function syncCdp() {
    if (syncing || typeof window === 'undefined') return
    syncing = true
    try {
      const result = await openManagementPage(window.location.href, getApiBase())
      cdpReady.value = Boolean(result.success)
      if (result.success) {
        cdpHint.value = '请在自动化浏览器窗口中操作（与管理页、招聘站同一 Chrome）'
      }

      const platform = platformFromPath(route.path)
      // 同平台内路由切换（如 /boss → /boss/analysis）不再重复 openPlatform，避免触发登录页引导
      if (platform) {
        if (platform !== lastPlatformOpened) {
          const platformResult = await openPlatform(platform, getApiBase(), { focus: false })
          lastPlatformOpened = platform
          if (!platformResult.isLoggedIn) {
            cdpHint.value = '未登录：请切换到招聘站点标签页完成登录'
          }
        }
      } else {
        lastPlatformOpened = null
      }
    } catch (error) {
      console.warn('[CDP] 同步自动化浏览器失败:', error)
      cdpHint.value = ''
    } finally {
      syncing = false
    }
  }

  onMounted(() => {
    void syncCdp()
  })

  watch(
    () => route.path,
    () => {
      void syncCdp()
    }
  )

  return { cdpReady, cdpHint, syncCdp }
}
