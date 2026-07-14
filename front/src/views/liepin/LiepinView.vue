<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { Icon } from '@iconify/vue'
import { createSSEWithBackoff } from '@/lib/sse'
import { getApiBase, openPlatform } from '@/lib/platform'
import { parseKeywordsFromDb, serializeKeywordsForDb } from '@/lib/jobConfig'
import { usePlatformDeliveryStatus } from '@/composables/useRealtime'
import Input from '@/components/ui/Input.vue'
import Select from '@/components/ui/Select.vue'
import PlatformPageHeader from '@/components/PlatformPageHeader.vue'
import PlatformTabs from '@/components/PlatformTabs.vue'
import PageLoading from '@/components/PageLoading.vue'
import ConfigToggleLink from '@/components/config/ConfigToggleLink.vue'
import LiepinAnalysisContent from './LiepinAnalysisContent.vue'
import PlatformActionBar from '@/components/PlatformActionBar.vue'
import PlatformDialogs from '@/components/PlatformDialogs.vue'
import ConfigSection from '@/components/config/ConfigSection.vue'
import ConfigField from '@/components/config/ConfigField.vue'
import PlatformInfoCard from '@/components/config/PlatformInfoCard.vue'

interface LiepinConfig {
  id?: number
  keywords?: string
  city?: string
  salaryCode?: string
  pubTime?: string
  workYearCode?: string
  eduLevel?: string
  jobKind?: string
  compScale?: string
  compStage?: string
  compKind?: string
}

interface LiepinOption {
  id: number
  type: string
  name: string
  code: string
}

interface LiepinOptions {
  city: LiepinOption[]
  salary: LiepinOption[]
  pubTime: LiepinOption[]
  experience: LiepinOption[]
  degree: LiepinOption[]
  jobType: LiepinOption[]
  scale: LiepinOption[]
  stage: LiepinOption[]
  compKind: LiepinOption[]
}

const CITY_ORDER = [
  '全国', '北京', '上海', '广州', '深圳',
  '杭州', '成都', '南京', '武汉', '苏州', '重庆', '天津',
  '长沙', '青岛', '宁波', '无锡', '西安', '郑州', '合肥', '厦门', '东莞',
  '济南', '福州', '佛山', '昆明', '大连', '沈阳', '常州', '哈尔滨', '南昌', '泉州',
  '南通', '烟台', '温州', '贵阳', '南宁', '石家庄', '长春', '嘉兴', '珠海', '太原',
  '绍兴', '金华', '潍坊', '徐州', '惠州', '台州', '扬州', '中山', '乌鲁木齐', '兰州',
  '海口', '呼和浩特', '银川',
]

function sortLiepinCities(cities: LiepinOption[]): LiepinOption[] {
  const orderMap = new Map<string, number>(CITY_ORDER.map((name, index) => [name, index]))
  return [...cities].sort((a, b) => {
    const ai = orderMap.get(a.name)
    const bi = orderMap.get(b.name)
    if (ai != null && bi != null) return ai - bi
    if (ai != null) return -1
    if (bi != null) return 1
    return a.name.localeCompare(b.name, 'zh-CN')
  })
}

function normalizeLiepinOptions(raw?: Partial<LiepinOptions>): LiepinOptions {
  return {
    city: sortLiepinCities(raw?.city ?? []),
    salary: raw?.salary ?? [],
    pubTime: raw?.pubTime ?? [],
    experience: raw?.experience ?? [],
    degree: raw?.degree ?? [],
    jobType: raw?.jobType ?? [],
    scale: raw?.scale ?? [],
    stage: raw?.stage ?? [],
    compKind: raw?.compKind ?? [],
  }
}

const API = getApiBase()

const config = ref<LiepinConfig>({
  keywords: '',
  city: '',
  salaryCode: '',
  pubTime: '',
  workYearCode: '',
  eduLevel: '',
  jobKind: '',
  compScale: '',
  compStage: '',
  compKind: '',
})

const options = ref<LiepinOptions>(normalizeLiepinOptions())
const useCustomSalary = ref(false)
const loading = ref(true)
const showSaveDialog = ref(false)
const saveResult = ref<{ success: boolean; message: string } | null>(null)
const isCustomCity = ref(false)
const isLoggedIn = ref(false)
const { isRunning: isDelivering, refresh: refreshDeliveryStatus, setRunning: setDelivering } =
  usePlatformDeliveryStatus('liepin')
const checkingLogin = ref(true)
const showLogoutDialog = ref(false)
const showLogoutResultDialog = ref(false)
const logoutResult = ref<{ success: boolean; message: string } | null>(null)

let sseClient: ReturnType<typeof createSSEWithBackoff> | null = null

const fetchAllData = async () => {
  try {
    const response = await fetch(`${API}/api/liepin/config`)
    const data = await response.json()

    if (data.config) {
      const normalized = { ...data.config } as LiepinConfig
      normalized.keywords = parseKeywordsFromDb(data.config.keywords)
      if (data.config.salaryCode && String(data.config.salaryCode).includes('$')) {
        useCustomSalary.value = true
      } else {
        const preset = (data.options?.salary || []).some(
          (s: LiepinOption) => s.code === data.config.salaryCode || s.name === data.config.salaryCode
        )
        useCustomSalary.value = !preset && !!data.config.salaryCode
      }
      config.value = normalized
      if (data.options?.city && data.config.city) {
        const cityExists = data.options.city.some(
          (c: LiepinOption) => c.name === data.config.city || c.code === data.config.city
        )
        isCustomCity.value = !cityExists
      }
    }
    if (data.options) {
      options.value = normalizeLiepinOptions(data.options)
    }
  } catch (error) {
    console.error('Failed to fetch liepin data:', error)
  } finally {
    loading.value = false
  }
}

let cancelled = false

onMounted(async () => {
  try {
    checkingLogin.value = true
    const result = await openPlatform('liepin', API)
    if (cancelled) return
    isLoggedIn.value = result.isLoggedIn ?? false
    void fetchAllData()
  } catch (error) {
    if (!cancelled) {
      console.error('[猎聘] 打开平台失败:', error)
      checkingLogin.value = false
    }
    return
  }

  if (typeof window === 'undefined' || typeof EventSource === 'undefined') {
    console.warn('EventSource 不可用，无法连接SSE')
    if (!cancelled) checkingLogin.value = false
    return
  }

  sseClient = createSSEWithBackoff(`${API}/api/jobs/login-status/stream`, {
    onOpen: () => {
      console.log('[SSE] 连接已打开')
    },
    onError: (_e, attempt, delay) => {
      console.warn(`[SSE] 连接错误，准备第${attempt}次重连，延迟 ${delay}ms`, _e)
      checkingLogin.value = false
    },
    listeners: [
      {
        name: 'connected',
        handler: (event) => {
          try {
            const data = JSON.parse(event.data)
            isLoggedIn.value = data.liepinLoggedIn || false
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
            if (data.platform === 'liepin') {
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

const handleSave = async () => {
  try {
    const payload = { ...config.value, keywords: serializeKeywordsForDb(config.value.keywords) }
    const response = await fetch(`${API}/api/liepin/config`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    })

    if (response.ok) {
      try {
        await fetch(`${API}/api/cookie/save?platform=liepin`, { method: 'POST' })
      } catch (e) {
        console.warn('保存 Cookie 失败（Liepin）:', e)
      }
      void fetchAllData()
      saveResult.value = { success: true, message: '保存成功，配置与Cookie已更新。' }
      showSaveDialog.value = true
    } else {
      saveResult.value = { success: false, message: '保存失败：后端返回异常状态。' }
      showSaveDialog.value = true
    }
  } catch (error) {
    console.error('Failed to save config:', error)
    saveResult.value = { success: false, message: '保存失败：网络或服务异常。' }
    showSaveDialog.value = true
  }
}

const handleStartDelivery = async () => {
  try {
    const response = await fetch(`${API}/api/liepin/start`, { method: 'POST' })
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

const handleStopDelivery = async () => {
  try {
    const response = await fetch(`${API}/api/liepin/stop`, { method: 'POST' })
    const data = await response.json()
    if (data.success) {
      setDelivering(false)
      void refreshDeliveryStatus()
    } else {
      console.warn('停止失败：', data.message)
    }
  } catch (error) {
    console.error('Failed to stop delivery:', error)
  }
}

const triggerLogout = async () => {
  try {
    const response = await fetch(`${API}/api/liepin/logout`, { method: 'POST' })
    const data = await response.json()
    if (data.success) {
      isLoggedIn.value = false
      logoutResult.value = { success: true, message: '已退出登录，Cookie已清空。' }
      showLogoutResultDialog.value = true
    } else {
      logoutResult.value = { success: false, message: `退出登录失败：${data.message || '服务返回异常。'}` }
      showLogoutResultDialog.value = true
    }
  } catch (error) {
    console.error('Failed to logout:', error)
    logoutResult.value = { success: false, message: '退出登录失败：网络或服务异常。' }
    showLogoutResultDialog.value = true
  }
}

const toggleCustomCity = () => {
  isCustomCity.value = !isCustomCity.value
  if (!isCustomCity.value) {
    config.value = { ...config.value, city: '' }
  }
}

const toggleCustomSalary = () => {
  useCustomSalary.value = !useCustomSalary.value
  if (!useCustomSalary.value) {
    config.value = { ...config.value, salaryCode: '' }
  }
}

const updateConfig = (patch: Partial<LiepinConfig>) => {
  config.value = { ...config.value, ...patch }
}
</script>

<template>
  <PageLoading v-if="loading" />

  <div v-else class="space-y-6">
    <PlatformPageHeader platform="liepin">
      <template #icon>
        <Icon icon="bi:search" class="text-2xl" />
      </template>
      <template #actions>
        <PlatformActionBar
          platform="liepin"
          platform-label="猎聘"
          :checking-login="checkingLogin"
          :is-logged-in="isLoggedIn"
          :is-delivering="isDelivering"
          @start="handleStartDelivery"
          @stop="handleStopDelivery"
          @logout="showLogoutDialog = true"
          @save="handleSave"
        />
      </template>
    </PlatformPageHeader>

    <PlatformTabs>
      <template #config>
        <PlatformInfoCard platform="liepin">
          <template #icon>
            <Icon icon="bi:briefcase" />
          </template>
        </PlatformInfoCard>

        <ConfigSection
          platform="liepin"
          title="搜索配置"
          description="设置职位搜索关键词和筛选条件"
        >
          <template #icon>
            <Icon icon="bi:search" />
          </template>
          <div class="grid grid-cols-1 md:grid-cols-2 gap-6">
            <ConfigField
              label="搜索关键词"
              html-for="keywords"
              hint="关键词可多选，使用英文逗号分隔，例如：大模型, Python, Golang"
            >
              <Input
                id="keywords"
                :model-value="config.keywords || ''"
                placeholder="例如：大模型, Python, Golang"
                @update:model-value="updateConfig({ keywords: $event })"
              />
            </ConfigField>

            <ConfigField
              label="工作城市"
              html-for="city"
              :hint="isCustomCity ? '手动输入城市码（例如：410代表北京）' : '从列表选择城市，或切换为手动输入'"
            >
              <template #label-extra>
                <ConfigToggleLink @click="toggleCustomCity">
                  {{ isCustomCity ? '从列表选择' : '手动输入' }}
                </ConfigToggleLink>
              </template>
              <Input
                v-if="isCustomCity"
                id="city"
                :model-value="config.city || ''"
                placeholder="请输入城市码，例如：410"
                @update:model-value="updateConfig({ city: $event })"
              />
              <Select
                v-else
                id="city"
                :model-value="config.city || ''"
                @update:model-value="updateConfig({ city: $event })"
              >
                <option value="">请选择城市</option>
                <option v-for="city in options.city" :key="city.id" :value="city.name">
                  {{ city.name }}
                </option>
              </Select>
            </ConfigField>
          </div>
        </ConfigSection>

        <ConfigSection
          platform="liepin"
          title="薪资筛选"
          description="设置期望薪资范围（与猎聘官网一致）"
          :delay="6"
        >
          <template #icon>
            <Icon icon="bi:money" />
          </template>
          <div class="grid grid-cols-1 md:grid-cols-2 gap-6">
            <ConfigField
              label="年薪档位"
              html-for="salaryPreset"
              hint="选择猎聘官网年薪档位，或切换为自定义（自定义会写入 salaryCode，如 18$30）"
            >
              <template #label-extra>
                <ConfigToggleLink @click="toggleCustomSalary">
                  {{ useCustomSalary ? '使用档位' : '自定义年薪' }}
                </ConfigToggleLink>
              </template>
              <Input
                v-if="useCustomSalary"
                id="salaryCustom"
                :model-value="config.salaryCode || ''"
                placeholder="例如：16$30（表示16万-30万/年）"
                @update:model-value="updateConfig({ salaryCode: $event })"
              />
              <Select
                v-else
                id="salaryPreset"
                :model-value="config.salaryCode || ''"
                @update:model-value="updateConfig({ salaryCode: $event })"
              >
                <option value="">不限</option>
                <option v-for="item in options.salary" :key="item.id" :value="item.code">
                  {{ item.name }}
                </option>
              </Select>
            </ConfigField>

            <ConfigField label="招聘者活跃" html-for="pubTime">
              <Select
                id="pubTime"
                :model-value="config.pubTime || ''"
                @update:model-value="updateConfig({ pubTime: $event })"
              >
                <option value="">不限</option>
                <option
                  v-for="item in options.pubTime.filter((i) => i.code !== '')"
                  :key="item.id"
                  :value="item.code"
                >
                  {{ item.name }}
                </option>
              </Select>
            </ConfigField>

            <ConfigField label="工作经验" html-for="workYearCode">
              <Select
                id="workYearCode"
                :model-value="config.workYearCode || ''"
                @update:model-value="updateConfig({ workYearCode: $event })"
              >
                <option value="">不限</option>
                <option
                  v-for="item in options.experience.filter((i) => i.code !== '')"
                  :key="item.id"
                  :value="item.code"
                >
                  {{ item.name }}
                </option>
              </Select>
            </ConfigField>

            <ConfigField label="学历要求" html-for="eduLevel">
              <Select
                id="eduLevel"
                :model-value="config.eduLevel || ''"
                @update:model-value="updateConfig({ eduLevel: $event })"
              >
                <option value="">不限</option>
                <option
                  v-for="item in options.degree.filter((i) => i.code !== '')"
                  :key="item.id"
                  :value="item.code"
                >
                  {{ item.name }}
                </option>
              </Select>
            </ConfigField>

            <ConfigField label="职位类型" html-for="jobKind">
              <Select
                id="jobKind"
                :model-value="config.jobKind || ''"
                @update:model-value="updateConfig({ jobKind: $event })"
              >
                <option value="">不限</option>
                <option
                  v-for="item in options.jobType.filter((i) => i.code !== '')"
                  :key="item.id"
                  :value="item.code"
                >
                  {{ item.name }}
                </option>
              </Select>
            </ConfigField>
          </div>
        </ConfigSection>

        <ConfigSection
          platform="liepin"
          title="企业要求"
          description="设置目标企业的规模、融资阶段和性质"
          :delay="7"
        >
          <template #icon>
            <Icon icon="bi:building" />
          </template>
          <div class="grid grid-cols-1 md:grid-cols-2 gap-6">
            <ConfigField label="企业规模" html-for="compScale">
              <Select
                id="compScale"
                :model-value="config.compScale || ''"
                @update:model-value="updateConfig({ compScale: $event })"
              >
                <option value="">不限</option>
                <option
                  v-for="item in options.scale.filter((i) => i.code !== '')"
                  :key="item.id"
                  :value="item.code"
                >
                  {{ item.name }}
                </option>
              </Select>
            </ConfigField>

            <ConfigField label="融资阶段" html-for="compStage">
              <Select
                id="compStage"
                :model-value="config.compStage || ''"
                @update:model-value="updateConfig({ compStage: $event })"
              >
                <option value="">不限</option>
                <option
                  v-for="item in options.stage.filter((i) => i.code !== '')"
                  :key="item.id"
                  :value="item.code"
                >
                  {{ item.name }}
                </option>
              </Select>
            </ConfigField>

            <ConfigField label="企业性质" html-for="compKind">
              <Select
                id="compKind"
                :model-value="config.compKind || ''"
                @update:model-value="updateConfig({ compKind: $event })"
              >
                <option value="">不限</option>
                <option
                  v-for="item in options.compKind.filter((i) => i.code !== '')"
                  :key="item.id"
                  :value="item.code"
                >
                  {{ item.name }}
                </option>
              </Select>
            </ConfigField>
          </div>
        </ConfigSection>
      </template>

      <template #analytics>
        <LiepinAnalysisContent />
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
