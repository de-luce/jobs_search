<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { Icon } from '@iconify/vue'
import { createSSEWithBackoff } from '@/lib/sse'
import { getApiBase } from '@/lib/platform'
import { usePlatformDeliveryStatus } from '@/composables/useRealtime'
import {
  parseKeywordsFromDb,
  parseMultiTokensFromDb,
  parseSingleTokenFromDb,
} from '@/lib/jobConfig'
import Input from '@/components/ui/Input.vue'
import Select from '@/components/ui/Select.vue'
import PlatformPageHeader from '@/components/PlatformPageHeader.vue'
import PlatformTabs from '@/components/PlatformTabs.vue'
import PageLoading from '@/components/PageLoading.vue'
import PlatformActionBar from '@/components/PlatformActionBar.vue'
import PlatformDialogs from '@/components/PlatformDialogs.vue'
import PlatformInfoCard from '@/components/config/PlatformInfoCard.vue'
import ConfigSection from '@/components/config/ConfigSection.vue'
import ConfigField from '@/components/config/ConfigField.vue'
import MultiSelect from '@/components/config/MultiSelect.vue'
import BossAnalysisContent from '@/views/boss/BossAnalysisContent.vue'

interface BossConfig {
  id?: number
  debugger?: number
  waitTime?: number
  keywords?: string
  cityCode?: string
  industry?: string
  jobType?: string
  experience?: string
  degree?: string
  salary?: string
  scale?: string
  stage?: string
  enableAi?: number
  sendImgResume?: number
  filterDeadHr?: number
  deadStatus?: string
}

interface BossOption {
  id: number
  type: string
  name: string
  code: string
  sort_order?: number
  sortOrder?: number
}

interface BossOptions {
  city: BossOption[]
  industry: BossOption[]
  experience: BossOption[]
  jobType: BossOption[]
  salary: BossOption[]
  degree: BossOption[]
  scale: BossOption[]
  stage: BossOption[]
}

function toCodes(opts: BossOption[], items: string[]): string[] {
  const codeSet = new Set(opts.map((o) => o.code))
  return items
    .map((it) => {
      if (codeSet.has(it)) return it
      const byName = opts.find((o) => o.name === it)
      return byName ? byName.code : ''
    })
    .filter(Boolean)
}

function toBracketList(list: string[]): string {
  if (!list || list.length === 0) return ''
  // 去掉「不限」，避免与其它薪资并存时 URL 参数被整体清空
  const cleaned = list.filter((c) => c && c !== '0')
  if (cleaned.length === 0) return list.includes('0') ? '[0]' : ''
  return `[${cleaned.join(',')}]`
}

const API = getApiBase()

const config = ref<BossConfig>({
  keywords: '',
  cityCode: '',
  industry: '',
  jobType: '',
  experience: '',
  degree: '',
  salary: '',
  scale: '',
  stage: '',
  filterDeadHr: 0,
})

const keywordsDisplay = ref('')
const selectedIndustry = ref<string[]>([])
const selectedExperience = ref<string[]>([])
const selectedDegree = ref<string[]>([])
const selectedScale = ref<string[]>([])
const selectedStage = ref<string[]>([])
const selectedSalary = ref<string[]>([])

const options = ref<BossOptions>({
  city: [],
  industry: [],
  experience: [],
  jobType: [],
  salary: [],
  degree: [],
  scale: [],
  stage: [],
})

const loading = ref(true)
const isLoggedIn = ref(false)
const { isRunning: isDelivering, refresh: refreshDeliveryStatus, setRunning: setDelivering } =
  usePlatformDeliveryStatus('boss')
const checkingLogin = ref(true)
const showLogoutDialog = ref(false)
const showSaveDialog = ref(false)
const saveResult = ref<{ success: boolean; message: string } | null>(null)
const showLogoutResultDialog = ref(false)
const logoutResult = ref<{ success: boolean; message: string } | null>(null)

let sseClient: ReturnType<typeof createSSEWithBackoff> | null = null

async function fetchAllData() {
  try {
    const response = await fetch(`${API}/api/boss/config`)
    const data = await response.json()

    if (data.config) {
      config.value = {
        ...data.config,
        cityCode: parseSingleTokenFromDb(data.config.cityCode),
        jobType: parseSingleTokenFromDb(data.config.jobType),
      }
      keywordsDisplay.value = parseKeywordsFromDb(data.config.keywords)
      selectedIndustry.value = parseMultiTokensFromDb(data.config.industry)
      selectedExperience.value = parseMultiTokensFromDb(data.config.experience)
      selectedDegree.value = parseMultiTokensFromDb(data.config.degree)
      selectedScale.value = parseMultiTokensFromDb(data.config.scale)
      selectedStage.value = parseMultiTokensFromDb(data.config.stage)
      selectedSalary.value = parseMultiTokensFromDb(data.config.salary)
    }

    if (data.options) {
      const cityList = data.options.city || []
      const cityHasOrder = cityList.some((o: BossOption) => o.sortOrder != null || o.sort_order != null)
      let sortedCity = cityHasOrder
        ? [...cityList]
            .map((o: BossOption, idx: number) => ({ o, idx }))
            .sort((a, b) => {
              const ar = a.o.sortOrder ?? a.o.sort_order
              const br = b.o.sortOrder ?? b.o.sort_order
              if (ar == null && br == null) return a.idx - b.idx
              if (ar == null) return 1
              if (br == null) return -1
              if (ar !== br) return ar - br
              return a.idx - b.idx
            })
            .map(({ o }) => o)
        : cityList

      const hasUnlimitedCity = sortedCity.some((c: BossOption) => c.code === '0' || c.name === '不限')
      if (!hasUnlimitedCity) {
        sortedCity = [{ id: -1, type: 'city', name: '不限', code: '0', sortOrder: 0 }, ...sortedCity]
      }

      const industryList = data.options.industry || []
      const industryHasOrder = industryList.some((o: BossOption) => o.sortOrder != null || o.sort_order != null)
      const sortedIndustry = industryHasOrder
        ? [...industryList]
            .map((o: BossOption, idx: number) => ({ o, idx }))
            .sort((a, b) => {
              const ar = a.o.sortOrder ?? a.o.sort_order
              const br = b.o.sortOrder ?? b.o.sort_order
              if (ar == null && br == null) return a.idx - b.idx
              if (ar == null) return 1
              if (br == null) return -1
              if (ar !== br) return ar - br
              return a.idx - b.idx
            })
            .map(({ o }) => o)
        : industryList

      options.value = {
        ...data.options,
        city: sortedCity,
        industry: sortedIndustry,
      }

      const currentCityRaw = data.config?.cityCode || ''
      const currentCityHead = parseSingleTokenFromDb(currentCityRaw)
      const cityMatchByCode = sortedCity.find((c: BossOption) => c.code === currentCityHead)
      const cityMatchByName = sortedCity.find((c: BossOption) => c.name === currentCityHead)
      const normalizedCityCode = cityMatchByCode
        ? cityMatchByCode.code
        : cityMatchByName
          ? cityMatchByName.code
          : '0'
      config.value = { ...config.value, cityCode: normalizedCityCode }

      const currentJobTypeRaw = data.config?.jobType || ''
      const currentJobTypeHead = parseSingleTokenFromDb(currentJobTypeRaw)
      const jobTypeMatchByCode = (data.options.jobType || []).find(
        (t: BossOption) => t.code === currentJobTypeHead
      )
      const jobTypeMatchByName = (data.options.jobType || []).find(
        (t: BossOption) => t.name === currentJobTypeHead
      )
      const normalizedJobType = jobTypeMatchByCode
        ? jobTypeMatchByCode.code
        : jobTypeMatchByName
          ? jobTypeMatchByName.code
          : ''
      config.value = { ...config.value, jobType: normalizedJobType }

      config.value = {
        ...config.value,
        filterDeadHr: data.config?.filterDeadHr ?? 0,
        sendImgResume: data.config?.sendImgResume ?? 0,
      }

      selectedIndustry.value = toCodes(data.options.industry || [], parseMultiTokensFromDb(data.config?.industry))
      selectedExperience.value = toCodes(data.options.experience || [], parseMultiTokensFromDb(data.config?.experience))
      selectedDegree.value = toCodes(data.options.degree || [], parseMultiTokensFromDb(data.config?.degree))
      selectedScale.value = toCodes(data.options.scale || [], parseMultiTokensFromDb(data.config?.scale))
      selectedStage.value = toCodes(data.options.stage || [], parseMultiTokensFromDb(data.config?.stage))
      selectedSalary.value = toCodes(data.options.salary || [], parseMultiTokensFromDb(data.config?.salary))
    }
  } catch (error) {
    console.error('Failed to fetch data:', error)
  } finally {
    loading.value = false
  }
}

async function handleSave(silent = false, overrides?: Partial<BossConfig>) {
  try {
    const payload: BossConfig = {
      ...config.value,
      ...(overrides || {}),
      keywords: keywordsDisplay.value,
      industry: toBracketList(selectedIndustry.value),
      experience: toBracketList(selectedExperience.value),
      degree: toBracketList(selectedDegree.value),
      scale: toBracketList(selectedScale.value),
      stage: toBracketList(selectedStage.value),
      salary: toBracketList(selectedSalary.value),
    }
    const response = await fetch(`${API}/api/boss/config`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    })

    if (response.ok) {
      try {
        await fetch(`${API}/api/cookie/save?platform=boss`, { method: 'POST' })
      } catch (e) {
        console.warn('保存 Cookie 失败（Boss）:', e)
      }

      await fetchAllData()
      if (!silent) {
        saveResult.value = { success: true, message: '保存成功，配置与Cookie已更新。' }
        showSaveDialog.value = true
      }
    } else {
      console.warn('保存失败：后端返回非 2xx 状态')
      if (!silent) {
        saveResult.value = { success: false, message: '保存失败：后端返回异常状态。' }
        showSaveDialog.value = true
      }
    }
  } catch (error) {
    console.error('Failed to save config:', error)
    if (!silent) {
      saveResult.value = { success: false, message: '保存失败：网络或服务异常。' }
      showSaveDialog.value = true
    }
  }
}

async function handleStartDelivery() {
  try {
    const response = await fetch(`${API}/api/boss/start`, { method: 'POST' })
    const data = await response.json()
    if (data.success) {
      setDelivering(true)
      void refreshDeliveryStatus()
    } else {
      console.warn('启动失败：', data.message)
    }
  } catch (error) {
    console.error('Failed to start delivery:', error)
  }
}

async function handleStopDelivery() {
  setDelivering(false)
  try {
    const response = await fetch(`${API}/api/boss/stop`, { method: 'POST' })
    const data = await response.json()
    setDelivering(false)
    void refreshDeliveryStatus()
    if (!data.success) {
      console.warn('停止失败：', data.message)
    }
  } catch (error) {
    setDelivering(false)
    void refreshDeliveryStatus()
    console.error('Failed to stop delivery:', error)
  }
}

async function triggerLogout() {
  try {
    const response = await fetch(`${API}/api/boss/logout`, { method: 'POST' })
    const data = await response.json()
    if (data.success) {
      isLoggedIn.value = false
      logoutResult.value = { success: true, message: '已退出登录，Cookie已清空。' }
      showLogoutResultDialog.value = true
    } else {
      logoutResult.value = {
        success: false,
        message: `退出登录失败：${data.message || '服务返回异常。'}`,
      }
      showLogoutResultDialog.value = true
    }
  } catch {
    logoutResult.value = { success: false, message: '退出登录失败：网络或服务异常。' }
    showLogoutResultDialog.value = true
  }
}

let cancelled = false

onMounted(async () => {
  try {
    checkingLogin.value = true
    await fetchAllData()
  } catch (error) {
    if (!cancelled) {
      console.error('[Boss] 加载配置失败:', error)
      checkingLogin.value = false
    }
    return
  }

  if (typeof EventSource === 'undefined') {
    console.warn('EventSource 不可用，无法连接SSE')
    if (!cancelled) checkingLogin.value = false
    return
  }

  sseClient = createSSEWithBackoff(`${API}/api/jobs/login-status/stream`, {
    onOpen: () => {
      console.log('[SSE] 连接已打开')
    },
    onError: (e, attempt, delay) => {
      console.warn(`[SSE] 连接错误，准备第${attempt}次重连，延迟 ${delay}ms`, e)
      checkingLogin.value = false
    },
    listeners: [
      {
        name: 'connected',
        handler: (event) => {
          try {
            const data = JSON.parse(event.data)
            isLoggedIn.value = data.bossLoggedIn || false
            checkingLogin.value = false
          } catch (error) {
            console.error('[SSE] 解析连接消息失败:', error)
          }
        },
      },
      {
        name: 'login-status',
        handler: (event) => {
          try {
            const data = JSON.parse(event.data)
            if (data.platform === 'boss') {
              isLoggedIn.value = data.isLoggedIn
              checkingLogin.value = false
            }
          } catch (error) {
            console.error('[SSE] 解析登录状态消息失败:', error)
          }
        },
      },
      { name: 'ping', handler: () => {} },
    ],
  })
})

onUnmounted(() => {
  cancelled = true
  sseClient?.close()
})
</script>

<template>
  <PageLoading v-if="loading" />

  <div v-else class="space-y-6">
    <PlatformPageHeader platform="boss">
      <template #icon>
        <Icon icon="bi:briefcase" class="text-2xl" />
      </template>
      <template #actions>
        <PlatformActionBar
          platform="boss"
          platform-label="Boss"
          :checking-login="checkingLogin"
          :is-logged-in="isLoggedIn"
          :is-delivering="isDelivering"
          @start="handleStartDelivery"
          @stop="handleStopDelivery"
          @logout="showLogoutDialog = true"
          @save="handleSave(false)"
        />
      </template>
    </PlatformPageHeader>

    <PlatformTabs>
      <template #config>
        <PlatformInfoCard platform="boss">
          <template #icon>
            <Icon icon="bi:briefcase" />
          </template>
        </PlatformInfoCard>

        <ConfigSection platform="boss" title="搜索配置" description="设置职位搜索关键词和目标城市">
          <template #icon>
            <Icon icon="bi:search" />
          </template>
          <div class="grid grid-cols-1 md:grid-cols-3 gap-6">
            <ConfigField label="搜索关键词" html-for="keywords" hint="职位搜索的关键词">
              <Input id="keywords" v-model="keywordsDisplay" placeholder="例如：Java开发工程师" />
            </ConfigField>

            <ConfigField label="工作城市" html-for="city" hint="目标工作城市（按设定顺序显示）">
              <Select id="city" v-model="config.cityCode">
                <option v-for="city in options.city" :key="city.id" :value="city.code">
                  {{ city.name }}
                </option>
              </Select>
            </ConfigField>

            <ConfigField label="职位类型" html-for="jobType" hint="选择职位类型">
              <Select id="jobType" v-model="config.jobType">
                <option v-for="type in options.jobType" :key="type.id" :value="type.code">
                  {{ type.name }}
                </option>
              </Select>
            </ConfigField>

            <ConfigField label="公司行业" hint="可多选">
              <MultiSelect
                :options="options.industry"
                :selected="selectedIndustry"
                placeholder="选择公司行业"
                @update:selected="selectedIndustry = $event"
              />
            </ConfigField>

            <ConfigField
              label="HR活跃过滤"
              html-for="filterDeadHr"
              hint="开启后将过滤活跃状态包含「年」的HR，但仍保存数据。"
            >
              <Select
                id="filterDeadHr"
                :model-value="String(config.filterDeadHr ?? 0)"
                @update:model-value="config.filterDeadHr = Number($event)"
              >
                <option value="0">关闭</option>
                <option value="1">开启</option>
              </Select>
            </ConfigField>
          </div>
        </ConfigSection>

        <ConfigSection
          platform="boss"
          title="薪资与经验要求"
          description="设置薪资待遇和工作经验要求"
        >
          <template #icon>
            <Icon icon="bi:cash-stack" />
          </template>
          <div class="grid grid-cols-1 md:grid-cols-2 gap-6">
            <ConfigField label="薪资待遇" hint="选项来源：字典表 type=salary（可多选）">
              <MultiSelect
                :options="options.salary"
                :selected="selectedSalary"
                placeholder="选择薪资待遇"
                @update:selected="selectedSalary = $event"
              />
            </ConfigField>
            <ConfigField label="工作经验">
              <MultiSelect
                :options="options.experience"
                :selected="selectedExperience"
                placeholder="选择工作经验"
                @update:selected="selectedExperience = $event"
              />
            </ConfigField>
          </div>
        </ConfigSection>

        <ConfigSection platform="boss" title="公司要求" description="设置目标公司的规模和融资阶段">
          <template #icon>
            <Icon icon="bi:building" />
          </template>
          <div class="grid grid-cols-1 md:grid-cols-3 gap-6">
            <ConfigField label="学历要求">
              <MultiSelect
                :options="options.degree"
                :selected="selectedDegree"
                placeholder="选择学历要求"
                @update:selected="selectedDegree = $event"
              />
            </ConfigField>
            <ConfigField label="公司规模">
              <MultiSelect
                :options="options.scale"
                :selected="selectedScale"
                placeholder="选择公司规模"
                @update:selected="selectedScale = $event"
              />
            </ConfigField>
            <ConfigField label="融资阶段">
              <MultiSelect
                :options="options.stage"
                :selected="selectedStage"
                placeholder="选择融资阶段"
                @update:selected="selectedStage = $event"
              />
            </ConfigField>
          </div>
        </ConfigSection>

        <ConfigSection
          platform="boss"
          title="投递设置"
          description="招呼语由 Boss 官网自动发送（与猎聘一致）"
        >
          <template #icon>
            <Icon icon="bi:send" />
          </template>
          <p class="text-sm text-muted-foreground mb-4">
            请在 Boss 直聘网页端「我的 → 设置 → 招呼语」配置默认招呼语。点击「立即沟通」后，Boss 会自动发送，本应用无需填写或点击发送。
            若已在 AI 配置页开启 AI 生成，将额外尝试发送个性化招呼语。
          </p>
          <div class="grid grid-cols-1 md:grid-cols-2 gap-6">
            <ConfigField
              label="发送图片简历"
              html-for="sendImgResume"
              hint="开启后自动发送图片简历，需将 resume.jpg 放在 src/main/resources/ 目录"
            >
              <Select
                id="sendImgResume"
                :model-value="String(config.sendImgResume ?? 0)"
                @update:model-value="config.sendImgResume = Number($event)"
              >
                <option value="0">关闭</option>
                <option value="1">开启</option>
              </Select>
            </ConfigField>
          </div>
        </ConfigSection>
      </template>

      <template #analytics>
        <BossAnalysisContent />
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
