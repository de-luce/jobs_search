export type PlatformId = 'boss' | 'liepin' | '51job' | 'zhilian'

export type OpenPlatformResult = {
  success?: boolean
  platform?: string
  activePlatform?: string
  isLoggedIn?: boolean
  browserReady?: boolean
  focused?: boolean
  message?: string
}

export type OpenManagementResult = {
  success?: boolean
  url?: string
  cdpPort?: number
  message?: string
}

export function getApiBase() {
  return import.meta.env.VITE_API_BASE_URL || 'http://localhost:8888'
}

export async function openManagementPage(
  url: string = window.location.href,
  baseUrl: string = getApiBase()
): Promise<OpenManagementResult> {
  const res = await fetch(`${baseUrl}/api/playwright/management/open`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ url }),
  })
  const data = (await res.json().catch(() => ({}))) as OpenManagementResult
  if (!res.ok) {
    throw new Error(data.message || '打开管理页失败')
  }
  return data
}

export async function openPlatform(
  platform: PlatformId,
  baseUrl: string = getApiBase(),
  options?: { focus?: boolean; forceLogin?: boolean }
): Promise<OpenPlatformResult> {
  const focus = options?.focus ?? false
  const forceLogin = options?.forceLogin ?? false
  const res = await fetch(
    `${baseUrl}/api/playwright/platform/${platform}/open?focus=${focus ? 'true' : 'false'}&forceLogin=${forceLogin ? 'true' : 'false'}`,
    { method: 'POST' }
  )
  const data = (await res.json().catch(() => ({}))) as OpenPlatformResult
  if (!res.ok) {
    throw new Error(data.message || `打开平台 ${platform} 失败`)
  }
  return data
}

export async function focusPlatformLogin(platform: PlatformId, baseUrl: string = getApiBase()) {
  return openPlatform(platform, baseUrl, { focus: true, forceLogin: true })
}
