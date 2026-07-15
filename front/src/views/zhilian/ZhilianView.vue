<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { Icon } from '@iconify/vue'
import { createSSEWithBackoff } from '@/lib/sse'
import { getApiBase, openPlatform } from '@/lib/platform'
import { usePlatformDeliveryStatus } from '@/composables/useRealtime'
import { parseKeywordsFromDb, serializeKeywordsForDb, parseSingleTokenFromDb } from '@/lib/jobConfig'
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
  experience?: string
  degree?: string
  jobType?: string
  companyType?: string
  companySize?: string
}

interface Option {
  name: string
  code: string
}

interface ZhilianOptions {
  city: Option[]
  salary: Option[]
  experience: Option[]
  degree: Option[]
  jobType: Option[]
  companyType: Option[]
  companySize: Option[]
}

const emptyOptions = (): ZhilianOptions => ({
  city: [],
  salary: [],
  experience: [],
  degree: [],
  jobType: [],
  companyType: [],
  companySize: [],
})

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

const config = ref<ZhilianConfig>({
  keywords: '',
  cityCode: '',
  salary: '0',
  experience: '',
  degree: '',
  jobType: '',
  companyType: '',
  companySize: '',
})
const options = ref<ZhilianOptions>(emptyOptions())
const loadingConfig = ref(true)

let sseClient: ReturnType<typeof createSSEWithBackoff> | null = null

function resolveCode(list: Option[] | undefined, raw?: string, fallback = ''): string {
  const token = parseSingleTokenFromDb(raw)
  if (!token || token === '不限' || token === '全部') return fallback
  const match = (list || []).find((o) => o.code === token || o.name === token)
  return match?.code ?? (fallback || token)
}

async function fetchAllData() {
  try {
    const res = await fetch(`${API}/api/zhilian/config`)
    const data = await res.json()
    const opts: ZhilianOptions = { ...emptyOptions(), ...(data.options || {}) }
    options.value = opts
    if (data.config) {
      config.value = {
        ...data.config,
        keywords: parseKeywordsFromDb(data.config.keywords),
        cityCode: resolveCode(opts.city, data.config.cityCode, '0'),
        salary: resolveCode(opts.salary, data.config.salary, '0'),
        experience: resolveCode(opts.experience, data.config.experience, ''),
        degree: resolveCode(opts.degree, data.config.degree, ''),
        jobType: resolveCode(opts.jobType, data.config.jobType, ''),
        companyType: resolveCode(opts.companyType, data.config.companyType, ''),
        companySize: resolveCode(opts.companySize, data.config.companySize, ''),
      }
    }
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
  setDelivering(false)
  try {
    const response = await fetch(`${API}/api/zhilian/stop`, { method: 'POST' })
    const data = await response.json()
    setDelivering(false)
    void refreshDeliveryStatus()
    if (!data.success) {
      console.warn('停止失败：', data.message)
    }
  } catch (error) {
    setDelivering(false)
    void refreshDeliveryStatus()
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

function toName(list: Option[], code?: string): string {
  const t = (code || '').trim()
  if (!t) return '不限'
  const match = list.find((o) => o.code === t || o.name === t)
  return match?.name || t
}

async function handleSaveConfig() {
  try {
    const payload = {
      ...config.value,
      keywords: serializeKeywordsForDb(config.value.keywords),
      cityCode: toName(options.value.city, config.value.cityCode),
      salary: toName(options.value.salary, config.value.salary),
      experience: toName(options.value.experience, config.value.experience),
      degree: toName(options.value.degree, config.value.degree),
      jobType: toName(options.value.jobType, config.value.jobType),
      companyType: toName(options.value.companyType, config.value.companyType),
      companySize: toName(options.value.companySize, config.value.companySize),
    }
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
          description="对齐智联官网搜索页筛选项：关键词、城市、薪资、经验、学历等"
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

            <ConfigField label="薪资范围" html-for="salary" hint="对应官网「薪资要求」">
              <Select
                id="salary"
                :model-value="config.salary || '0'"
                @update:model-value="config = { ...config, salary: $event }"
              >
                <option v-for="o in options.salary" :key="o.code || o.name" :value="o.code">
                  {{ o.name }}
                </option>
              </Select>
            </ConfigField>

            <ConfigField label="工作经验" html-for="experience" hint="对应官网「工作经验」">
              <Select
                id="experience"
                :model-value="config.experience || ''"
                @update:model-value="config = { ...config, experience: $event }"
              >
                <option v-for="o in options.experience" :key="o.code || o.name" :value="o.code">
                  {{ o.name }}
                </option>
              </Select>
            </ConfigField>

            <ConfigField label="学历要求" html-for="degree" hint="对应官网「学历要求」">
              <Select
                id="degree"
                :model-value="config.degree || ''"
                @update:model-value="config = { ...config, degree: $event }"
              >
                <option v-for="o in options.degree" :key="o.code || o.name" :value="o.code">
                  {{ o.name }}
                </option>
              </Select>
            </ConfigField>

            <ConfigField label="职位类型" html-for="jobType" hint="全职 / 兼职 / 实习 / 校园">
              <Select
                id="jobType"
                :model-value="config.jobType || ''"
                @update:model-value="config = { ...config, jobType: $event }"
              >
                <option v-for="o in options.jobType" :key="o.code || o.name" :value="o.code">
                  {{ o.name }}
                </option>
              </Select>
            </ConfigField>

            <ConfigField label="公司性质" html-for="companyType">
              <Select
                id="companyType"
                :model-value="config.companyType || ''"
                @update:model-value="config = { ...config, companyType: $event }"
              >
                <option v-for="o in options.companyType" :key="o.code || o.name" :value="o.code">
                  {{ o.name }}
                </option>
              </Select>
            </ConfigField>

            <ConfigField label="公司规模" html-for="companySize">
              <Select
                id="companySize"
                :model-value="config.companySize || ''"
                @update:model-value="config = { ...config, companySize: $event }"
              >
                <option v-for="o in options.companySize" :key="o.code || o.name" :value="o.code">
                  {{ o.name }}
                </option>
              </Select>
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
