<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { Icon } from '@iconify/vue'
import { createSSEWithBackoff } from '@/lib/sse'
import { getApiBase, openPlatform } from '@/lib/platform'
import { usePlatformDeliveryStatus } from '@/composables/useRealtime'
import { parseKeywordsFromDb, serializeKeywordsForDb } from '@/lib/jobConfig'
import Input from '@/components/ui/Input.vue'
import Select from '@/components/ui/Select.vue'
import PlatformPageHeader from '@/components/PlatformPageHeader.vue'
import PlatformTabs from '@/components/PlatformTabs.vue'
import ConfigLoading from '@/components/config/ConfigLoading.vue'
import ZhilianAnalysisContent from '@/views/zhilian/ZhilianAnalysisContent.vue'
import PlatformActionBar from '@/components/PlatformActionBar.vue'
import PlatformDialogs from '@/components/PlatformDialogs.vue'
import PlatformInfoCard from '@/components/config/PlatformInfoCard.vue'
import ConfigSection from '@/components/config/ConfigSection.vue'
import ConfigField from '@/components/config/ConfigField.vue'

interface ZhilianConfig {
  id?: number
  keywords?: string
  cityCode?: string
  salary?: string
}

interface Option {
  name: string
  code: string
}

interface ZhilianOptions {
  city: Option[]
}

const API = getApiBase()
const isLoggedIn = ref(false)
const { isRunning: isDelivering, refresh: refreshDeliveryStatus, setRunning: setDelivering } =
  usePlatformDeliveryStatus('zhilian')
const checkingLogin = ref(true)
const showLogoutDialog = ref(false)
const showSaveDialog = ref(false)
const saveResult = ref<{ success: boolean; message: string } | null>(null)
const showLogoutResultDialog = ref(false)
const logoutResult = ref<{ success: boolean; message: string } | null>(null)

const config = ref<ZhilianConfig>({ keywords: '', cityCode: '', salary: '' })
const options = ref<ZhilianOptions>({ city: [] })
const loadingConfig = ref(true)

let sseClient: ReturnType<typeof createSSEWithBackoff> | null = null

async function fetchAllData() {
  try {
    const res = await fetch(`${API}/api/zhilian/config`)
    const data = await res.json()
    if (data.config) {
      const normalized = { ...data.config }
      normalized.keywords = parseKeywordsFromDb(data.config.keywords)
      config.value = normalized
    }
    if (data.options) options.value = data.options
  } catch (e) {
    console.error('[智联] 获取配置失败:', e)
  } finally {
    loadingConfig.value = false
  }
}

async function setupLoginSSE() {
  let cancelled = false

  try {
    checkingLogin.value = true
    const result = await openPlatform('zhilian', API)
    if (cancelled) return
    isLoggedIn.value = result.isLoggedIn ?? false
    await fetchAllData()
  } catch (error) {
    if (!cancelled) {
      console.error('[智联招聘] 打开平台失败:', error)
      checkingLogin.value = false
      loadingConfig.value = false
    }
    return
  }

  if (typeof window === 'undefined' || typeof EventSource === 'undefined') {
    console.warn('[智联招聘] EventSource 不可用，无法连接SSE')
    if (!cancelled) checkingLogin.value = false
    return
  }

  sseClient = createSSEWithBackoff(`${API}/api/jobs/login-status/stream`, {
    onOpen: () => console.log('[智联招聘 SSE] 连接已打开'),
    onError: (e, attempt, delay) => {
      console.warn(`[智联招聘 SSE] 连接错误，第${attempt}次重连，延迟 ${delay}ms`, e)
      checkingLogin.value = false
    },
    listeners: [
      {
        name: 'connected',
        handler: (event) => {
          try {
            const data = JSON.parse(event.data)
            isLoggedIn.value = data.zhilianLoggedIn || false
            checkingLogin.value = false
          } catch (error) {
            console.error('[智联招聘 SSE] 解析连接消息失败:', error)
          }
        },
      },
      {
        name: 'login-status',
        handler: (event) => {
          try {
            const data = JSON.parse(event.data)
            if (data.platform === 'zhilian') {
              isLoggedIn.value = data.isLoggedIn
              checkingLogin.value = false
            }
          } catch (error) {
            console.error('[智联招聘 SSE] 解析登录状态消息失败:', error)
          }
        },
      },
      { name: 'ping', handler: () => {} },
    ],
  })
}

onMounted(() => {
  void setupLoginSSE()
})

onUnmounted(() => {
  sseClient?.close()
})

async function handleStartDelivery() {
  try {
    const response = await fetch(`${API}/api/zhilian/start`, { method: 'POST' })
    const data = await response.json()
    if (data.success) {
      setDelivering(true)
      void refreshDeliveryStatus()
    } else {
      console.warn('启动失败：', data.message)
    }
  } catch (error) {
    console.error('启动投递失败：', error)
  }
}

async function handleStopDelivery() {
  try {
    const response = await fetch(`${API}/api/zhilian/stop`, { method: 'POST' })
    const data = await response.json()
    if (data.success) {
      setDelivering(false)
      void refreshDeliveryStatus()
    } else {
      console.warn('停止失败：', data.message)
    }
  } catch (error) {
    console.error('停止投递失败：', error)
  }
}

async function triggerLogout() {
  try {
    const response = await fetch(`${API}/api/zhilian/logout`, { method: 'POST' })
    const data = await response.json()
    isLoggedIn.value = false
    logoutResult.value = {
      success: data.success,
      message: data.success ? '已退出登录，Cookie已清空。' : data.message,
    }
    showLogoutResultDialog.value = true
  } catch {
    logoutResult.value = { success: false, message: '退出登录失败：网络或服务异常。' }
    showLogoutResultDialog.value = true
  }
}

async function handleSaveConfig() {
  try {
    const payload = { ...config.value, keywords: serializeKeywordsForDb(config.value.keywords) }
    const response = await fetch(`${API}/api/zhilian/config`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    })
    if (response.ok) {
      try {
        await fetch(`${API}/api/cookie/save?platform=zhilian`, { method: 'POST' })
      } catch {}
      await fetchAllData()
      saveResult.value = { success: true, message: '保存成功，配置已更新。' }
    } else {
      saveResult.value = { success: false, message: '保存失败：后端返回异常状态。' }
    }
    showSaveDialog.value = true
  } catch (error) {
    console.error('[智联] 保存配置失败:', error)
    saveResult.value = { success: false, message: '保存失败：网络或服务异常。' }
    showSaveDialog.value = true
  }
}
</script>

<template>
  <div class="space-y-6">
    <PlatformPageHeader platform="zhilian">
      <template #icon>
        <Icon icon="bi:briefcase" class="text-2xl" />
      </template>
      <template #actions>
        <PlatformActionBar
          platform="zhilian"
          platform-label="智联招聘"
          :checking-login="checkingLogin"
          :is-logged-in="isLoggedIn"
          :is-delivering="isDelivering"
          @start="handleStartDelivery"
          @stop="handleStopDelivery"
          @logout="showLogoutDialog = true"
          @save="handleSaveConfig"
        />
      </template>
    </PlatformPageHeader>

    <PlatformTabs>
      <template #config>
        <PlatformInfoCard platform="zhilian">
          <template #icon>
            <Icon icon="bi:briefcase" />
          </template>
        </PlatformInfoCard>

        <ConfigSection
          platform="zhilian"
          title="搜索配置"
          description="设置职位搜索关键词、目标城市和薪资范围"
          :delay="6"
        >
          <template #icon>
            <Icon icon="bi:search" />
          </template>
          <ConfigLoading v-if="loadingConfig" />
          <div v-else class="grid grid-cols-1 md:grid-cols-2 gap-6">
            <ConfigField
              label="搜索关键词"
              html-for="keywords"
              hint="多个关键词用英文逗号分隔，如：Java, 后端, Spring"
            >
              <Input
                id="keywords"
                placeholder="如：Java, 后端, Spring"
                :model-value="config.keywords || ''"
                @update:model-value="config = { ...config, keywords: $event }"
              />
            </ConfigField>

            <ConfigField label="城市" html-for="cityCode" hint="目标工作城市">
              <Select
                id="cityCode"
                :model-value="config.cityCode || ''"
                @update:model-value="config = { ...config, cityCode: $event }"
              >
                <option v-for="o in options.city" :key="o.code" :value="o.code">
                  {{ o.name }}
                </option>
              </Select>
            </ConfigField>

            <ConfigField
              label="薪资范围"
              html-for="salary"
              hint="最低和最高工资，用逗号分割，如：12000, 20000 或 不限"
            >
              <Input
                id="salary"
                placeholder="如：12000, 20000 或 不限"
                :model-value="config.salary || ''"
                @update:model-value="config = { ...config, salary: $event }"
              />
            </ConfigField>
          </div>
        </ConfigSection>
      </template>
      <template #analytics>
        <ZhilianAnalysisContent />
      </template>
    </PlatformTabs>

    <PlatformDialogs
      :show-logout-dialog="showLogoutDialog"
      :logout-result="logoutResult"
      :show-logout-result-dialog="showLogoutResultDialog"
      :save-result="saveResult"
      :show-save-dialog="showSaveDialog"
      @logout-dialog-close="showLogoutDialog = false"
      @logout-confirm="triggerLogout"
      @logout-result-close="showLogoutResultDialog = false"
      @save-dialog-close="showSaveDialog = false"
    />
  </div>
</template>
