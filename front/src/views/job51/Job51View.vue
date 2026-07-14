<script setup lang="ts">
import { ref, onMounted, onUnmounted, watch } from 'vue'
import { Icon } from '@iconify/vue'
import { createSSEWithBackoff } from '@/lib/sse'
import { getApiBase, openPlatform } from '@/lib/platform'
import {
  parseKeywordsFromDb,
  serializeKeywordsForDb,
  parseSingleTokenFromDb,
  parseMultiTokensFromDb,
} from '@/lib/jobConfig'
import { usePlatformDeliveryStatus } from '@/composables/useRealtime'
import Input from '@/components/ui/Input.vue'
import Select from '@/components/ui/Select.vue'
import PlatformPageHeader from '@/components/PlatformPageHeader.vue'
import PlatformTabs from '@/components/PlatformTabs.vue'
import Job51AnalysisContent from '@/views/job51/Job51AnalysisContent.vue'
import PlatformActionBar from '@/components/PlatformActionBar.vue'
import PlatformDialogs from '@/components/PlatformDialogs.vue'
import ConfigSection from '@/components/config/ConfigSection.vue'
import ConfigField from '@/components/config/ConfigField.vue'
import PlatformInfoCard from '@/components/config/PlatformInfoCard.vue'
import ConfigToggleLink from '@/components/config/ConfigToggleLink.vue'
import ConfigLoading from '@/components/config/ConfigLoading.vue'
import MultiSelect from '@/components/config/MultiSelect.vue'

interface Job51Config {
  id?: number
  keywords?: string
  jobArea?: string
  salary?: string
}

interface Job51Option { name: string; code: string }
interface Job51Options { jobArea: Job51Option[]; salary: Job51Option[] }

const MAX_SALARY_SELECTIONS = 5

const API = getApiBase()
const isLoggedIn = ref(false)
const { isRunning: isDelivering, refresh: refreshDeliveryStatus, setRunning: setDelivering } =
  usePlatformDeliveryStatus('51job')
const checkingLogin = ref(true)
const showLogoutDialog = ref(false)
const showSaveDialog = ref(false)
const saveResult = ref<{ success: boolean; message: string } | null>(null)
const showLogoutResultDialog = ref(false)
const logoutResult = ref<{ success: boolean; message: string } | null>(null)

const config = ref<Job51Config>({ keywords: '', jobArea: '', salary: '' })
const options = ref<Job51Options>({ jobArea: [], salary: [] })
const loadingConfig = ref(true)
const isCustomArea = ref(false)
const backendAvailable = ref(false)
const cookieSavedAfterLogin = ref(false)
const selectedSalaries = ref<string[]>([])
const deliveryMessage = ref('')

let sseClient: ReturnType<typeof createSSEWithBackoff> | null = null
let progressSseClient: ReturnType<typeof createSSEWithBackoff> | null = null

async function fetchAllData() {
  try {
    const res = await fetch(`${API}/api/51job/config`)
    if (!res.ok) {
      console.warn(`[51job] 获取配置失败: ${res.status}`)
      config.value = { keywords: '', jobArea: '', salary: '' }
      options.value = { jobArea: [], salary: [] }
      return
    }
    const data = await res.json()
    if (data.config || data.options) {
      const opts: Job51Options = data.options || { jobArea: [], salary: [] }
      const conf: Job51Config = data.config || { keywords: '', jobArea: '', salary: '' }

      const normalizedKeywords = parseKeywordsFromDb(conf.keywords)
      const rawArea = parseSingleTokenFromDb(conf.jobArea)
      const rawSalaries = parseMultiTokensFromDb(conf.salary)

      const areaList = opts.jobArea || []
      const salaryList = opts.salary || []

      const matchArea = areaList.find((o) => o.code === rawArea || o.name === rawArea)

      const salaryCodes = rawSalaries
        .map((raw) => {
          const match = salaryList.find((o) => o.code === raw || o.name === raw)
          return match?.code || raw
        })
        .filter(Boolean)
        .slice(0, MAX_SALARY_SELECTIONS)

      const areaCode =
        matchArea?.code ||
        areaList.find((o) => o.name === '不限')?.code ||
        areaList.find((o) => o.code === '0')?.code ||
        ''

      options.value = opts
      config.value = { ...conf, keywords: normalizedKeywords, jobArea: areaCode, salary: JSON.stringify(salaryCodes) }
      selectedSalaries.value = salaryCodes
      isCustomArea.value = false
    }
  } catch (e) {
    console.warn('[51job] 获取配置异常（可能后端未启动）:', e)
  } finally {
    loadingConfig.value = false
  }
}

async function setupLoginSSE() {
  if (!backendAvailable.value) {
    checkingLogin.value = false
    return
  }

  let cancelled = false

  try {
    checkingLogin.value = true
    const result = await openPlatform('51job', API)
    if (cancelled) return
    isLoggedIn.value = result.isLoggedIn ?? false
  } catch (error) {
    if (!cancelled) {
      console.error('[51job] 打开平台失败:', error)
      checkingLogin.value = false
    }
    return
  }

  if (typeof window === 'undefined' || typeof EventSource === 'undefined') {
    console.warn('[51job] EventSource 不可用，无法连接SSE')
    if (!cancelled) checkingLogin.value = false
    return
  }

  sseClient = createSSEWithBackoff(`${API}/api/jobs/login-status/stream`, {
    onOpen: () => console.log('[51job SSE] ✅ 连接已打开'),
    onError: (e, attempt, delay) => {
      console.warn(`[51job SSE] 连接错误，准备第${attempt}次重连，延迟 ${delay}ms`, e)
      checkingLogin.value = false
    },
    listeners: [
      {
        name: 'connected',
        handler: (event) => {
          try {
            const data = JSON.parse(event.data)
            isLoggedIn.value = data.job51LoggedIn || false
            if (data.job51LoggedIn && !cookieSavedAfterLogin.value) {
              fetch(`${API}/api/cookie/save?platform=51job`, { method: 'POST' }).catch(() => {})
              cookieSavedAfterLogin.value = true
            }
            checkingLogin.value = false
          } catch (error) {
            console.error('[51job SSE] ❌ 解析连接消息失败:', error)
          }
        },
      },
      {
        name: 'login-status',
        handler: (event) => {
          try {
            const data = JSON.parse(event.data)
            if (data.platform === '51job') {
              isLoggedIn.value = data.isLoggedIn
              if (data.isLoggedIn && !cookieSavedAfterLogin.value) {
                fetch(`${API}/api/cookie/save?platform=51job`, { method: 'POST' }).catch(() => {})
                cookieSavedAfterLogin.value = true
              }
              checkingLogin.value = false
            }
          } catch (error) {
            console.error('[51job SSE] ❌ 解析登录状态消息失败:', error)
          }
        },
      },
      { name: 'ping', handler: () => {} },
    ],
  })

  return () => { cancelled = true }
}

onMounted(async () => {
  try {
    const res = await fetch(`${API}/api/51job/config`, { method: 'GET' })
    const ok = !!res && res.ok
    backendAvailable.value = ok
    if (ok) {
      await fetchAllData()
      await refreshDeliveryStatus()
      if (isDelivering.value) {
        setupDeliveryProgressSSE()
      }
    } else {
      loadingConfig.value = false
    }
  } catch {
    backendAvailable.value = false
    loadingConfig.value = false
  }
})

onUnmounted(() => {
  sseClient?.close()
  progressSseClient?.close()
})

function setupDeliveryProgressSSE() {
  if (typeof window === 'undefined' || typeof EventSource === 'undefined') return

  progressSseClient?.close()
  progressSseClient = createSSEWithBackoff(`${API}/api/51job/stream`, {
    onError: () => {},
    listeners: [
      {
        name: 'progress',
        handler: (event) => {
          try {
            const data = JSON.parse(event.data)
            if (data.message) deliveryMessage.value = data.message
          } catch {
            // ignore
          }
        },
      },
      { name: 'connected', handler: () => {} },
      { name: 'ping', handler: () => {} },
    ],
  })
}

watch(isDelivering, (running) => {
  if (running) {
    deliveryMessage.value = '投递任务已启动，等待浏览器加载搜索结果...'
    setupDeliveryProgressSSE()
    return
  }
  progressSseClient?.close()
  progressSseClient = null
})

watch([backendAvailable, cookieSavedAfterLogin], () => {
  sseClient?.close()
  void setupLoginSSE()
}, { immediate: true })

async function handleStartDelivery() {
  try {
    deliveryMessage.value = '正在启动投递任务...'
    setupDeliveryProgressSSE()
    const response = await fetch(`${API}/api/51job/start`, { method: 'POST' })
    const data = await response.json()
    if (data.success) {
      setDelivering(true)
      void refreshDeliveryStatus()
    } else {
      deliveryMessage.value = data.message || '启动失败'
      console.warn('[51job] 启动失败：', data.message)
    }
  } catch (error) {
    deliveryMessage.value = '启动投递失败，请检查后端是否运行'
    console.error('[51job] 启动投递失败：', error)
  }
}

async function handleStopDelivery() {
  setDelivering(false)
  try {
    const response = await fetch(`${API}/api/51job/stop`, { method: 'POST' })
    if (!response.ok) {
      console.warn('[51job] 停止投递请求失败，状态码:', response.status)
    }
    const data = await response.json()
    setDelivering(false)
    deliveryMessage.value = data.message || '投递任务已停止'
    void refreshDeliveryStatus()
  } catch (error) {
    setDelivering(false)
    void refreshDeliveryStatus()
    console.error('[51job] 停止投递请求异常:', error)
  }
}

async function triggerLogout() {
  try {
    const response = await fetch(`${API}/api/51job/logout`, { method: 'POST' })
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
    const toBracketListString = (v?: string, type?: 'jobArea' | 'salary') => {
      const t = (v || '').trim()
      if (!t) return '[]'
      if (type === 'jobArea') {
        const match = (options.value.jobArea || []).find((o) => o.code === t || o.name === t)
        const name = match?.name || t
        return `["${name.replace(/"/g, '\\"')}"]`
      }
      if (type === 'salary') {
        const names = selectedSalaries.value
          .map((code) => {
            const match = (options.value.salary || []).find((o) => o.code === code)
            return match?.name || ''
          })
          .filter(Boolean)
        return names.length > 0 ? JSON.stringify(names) : '[]'
      }
      return `["${t.replace(/"/g, '\\"')}"]`
    }
    const payload = {
      ...config.value,
      keywords: serializeKeywordsForDb(config.value.keywords),
      jobArea: toBracketListString(config.value.jobArea, 'jobArea'),
      salary: toBracketListString(config.value.salary, 'salary'),
    }
    const response = await fetch(`${API}/api/51job/config`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    })
    if (response.ok) {
      try {
        await fetch(`${API}/api/cookie/save?platform=51job`, { method: 'POST' })
      } catch (e) {
        console.warn('[51job] 保存 Cookie 失败:', e)
      }
      await fetchAllData()
      saveResult.value = { success: true, message: '保存成功，配置与Cookie已更新。' }
    } else {
      saveResult.value = { success: false, message: '保存失败：后端返回异常状态。' }
    }
    showSaveDialog.value = true
  } catch (error) {
    console.error('[51job] 保存配置失败:', error)
    saveResult.value = { success: false, message: '保存失败：网络或服务异常。' }
    showSaveDialog.value = true
  }
}

function handleSalaryChange(codes: string[]) {
  selectedSalaries.value = codes
  config.value = { ...config.value, salary: JSON.stringify(codes) }
}

function toggleCustomArea() {
  isCustomArea.value = !isCustomArea.value
  if (!isCustomArea.value) {
    config.value = { ...config.value, jobArea: '' }
  }
}
</script>

<template>
  <div class="space-y-6">
    <PlatformPageHeader platform="51job">
      <template #icon>
        <Icon icon="bi:briefcase" class="text-2xl" />
      </template>
      <template #actions>
        <PlatformActionBar
          platform="51job"
          platform-label="51job"
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
        <p
          v-if="deliveryMessage"
          class="rounded-lg border border-amber-200/60 bg-amber-50/80 px-4 py-3 text-sm text-amber-900"
        >
          {{ deliveryMessage }}
        </p>

        <PlatformInfoCard platform="51job">
          <template #icon>
            <Icon icon="bi:briefcase" />
          </template>
        </PlatformInfoCard>

        <ConfigSection platform="51job" title="搜索配置" description="设置职位搜索关键词、目标城市和薪资范围">
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

            <ConfigField
              label="工作城市"
              :hint="isCustomArea ? '手动输入城市码（例如：410代表北京）' : '从列表选择城市，或点击「手动输入」自定义'"
            >
              <template #label-extra>
                <ConfigToggleLink @click="toggleCustomArea">
                  {{ isCustomArea ? '从列表选择' : '手动输入' }}
                </ConfigToggleLink>
              </template>
              <Input
                v-if="isCustomArea"
                placeholder="请输入城市码，例如：410"
                :model-value="config.jobArea || ''"
                @update:model-value="config = { ...config, jobArea: $event }"
              />
              <Select
                v-else
                :model-value="config.jobArea || ''"
                @update:model-value="config = { ...config, jobArea: $event }"
              >
                <option value="">请选择城市</option>
                <option v-for="o in options.jobArea" :key="o.code" :value="o.code">
                  {{ o.name }}
                </option>
              </Select>
            </ConfigField>

            <ConfigField
              label="薪资范围"
              :hint="`最多选择 ${MAX_SALARY_SELECTIONS} 个档位，已选 ${selectedSalaries.length}/${MAX_SALARY_SELECTIONS}`"
            >
              <MultiSelect
                :options="options.salary"
                :selected="selectedSalaries"
                placeholder="请选择薪资范围"
                :max-selections="MAX_SALARY_SELECTIONS"
                @update:selected="handleSalaryChange"
              />
            </ConfigField>
          </div>
        </ConfigSection>
      </template>
      <template #analytics>
        <Job51AnalysisContent />
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
