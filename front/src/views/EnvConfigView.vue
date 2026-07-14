<script setup lang="ts">
import { ref, onMounted } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import { getApiBase } from '@/lib/platform'
import Button from '@/components/ui/Button.vue'
import Card from '@/components/ui/Card.vue'
import CardHeader from '@/components/ui/CardHeader.vue'
import CardTitle from '@/components/ui/CardTitle.vue'
import CardDescription from '@/components/ui/CardDescription.vue'
import CardContent from '@/components/ui/CardContent.vue'
import Input from '@/components/ui/Input.vue'
import Label from '@/components/ui/Label.vue'
import PageHeader from '@/components/PageHeader.vue'

const API = getApiBase()

const envConfig = ref({
  hookUrl: '',
  baseUrl: '',
  apiKey: '',
  model: '',
  botIsSend: 0,
})

const showApiKey = ref(false)
const loading = ref(true)
const saving = ref(false)
const showSaveDialog = ref(false)
const saveResult = ref<{ success: boolean; message: string } | null>(null)

async function fetchConfig() {
  try {
    loading.value = true
    const response = await fetch(`${API}/api/config`, {
      method: 'GET',
      headers: { 'Content-Type': 'application/json' },
    })

    if (!response.ok) throw new Error('获取配置失败')

    const result = await response.json()

    if (result.success && result.data) {
      envConfig.value = {
        hookUrl: result.data.HOOK_URL || '',
        baseUrl: result.data.BASE_URL || '',
        apiKey: result.data.API_KEY || '',
        model: result.data.MODEL || '',
        botIsSend: (() => {
          const raw = result.data.BOT_IS_SEND
          const val = String(raw ?? '').trim().toLowerCase()
          return val === '1' || val === 'true' ? 1 : 0
        })(),
      }
    }
  } catch (error) {
    console.error('获取配置失败:', error)
    alert('获取配置失败，请检查后端服务是否正常运行')
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void fetchConfig()
})

async function handleSave(silent = false) {
  try {
    saving.value = true

    const configMap = {
      HOOK_URL: envConfig.value.hookUrl,
      BASE_URL: envConfig.value.baseUrl,
      API_KEY: envConfig.value.apiKey,
      MODEL: envConfig.value.model,
      BOT_IS_SEND: String(envConfig.value.botIsSend ?? 0),
    }

    const response = await fetch(`${API}/api/config`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(configMap),
    })

    if (!response.ok) throw new Error('保存配置失败')

    const result = await response.json()

    if (result.success) {
      if (!silent) {
        saveResult.value = { success: true, message: '保存成功' }
        showSaveDialog.value = true
      }
    } else {
      throw new Error(result.message || '保存配置失败')
    }
  } catch (error) {
    console.error('保存配置失败:', error)
    if (!silent) {
      saveResult.value = { success: false, message: '保存配置失败：网络或服务异常。' }
      showSaveDialog.value = true
    }
  } finally {
    saving.value = false
  }
}

function toggleBotIsSend() {
  envConfig.value = { ...envConfig.value, botIsSend: envConfig.value.botIsSend ? 0 : 1 }
}
</script>

<template>
  <div class="space-y-6">
    <PageHeader title="环境变量配置" subtitle="Webhook、API Key 等运行参数" accent-bg-class="bg-slate-600">
      <template #icon>
        <AppIcon icon="bi:gear" :size="20" class="text-white" />
      </template>
      <template #actions>
        <Button size="sm" :disabled="saving" @click="handleSave(false)">
          <AppIcon icon="bi:save" class="mr-1" /> 保存
        </Button>
      </template>
    </PageHeader>

    <Card v-if="loading" class="border-blue-500/20 bg-blue-500/5">
      <CardContent class="pt-6">
        <p class="text-center text-sm text-muted-foreground">加载配置中...</p>
      </CardContent>
    </Card>

    <div class="space-y-6">
      <Card class="animate-in fade-in slide-in-from-bottom-5 duration-700">
        <CardHeader class="flex items-start gap-4">
          <div class="min-w-0 space-y-2">
            <CardTitle class="flex items-center gap-2">
              <AppIcon icon="bi:box-arrow-up-right" class="text-primary" />
              企业微信 Webhook
            </CardTitle>
            <CardDescription>配置企业微信群机器人，用于接收通知消息</CardDescription>
          </div>
          <div>
            <button
              type="button"
              aria-label="企业微信发送开关"
              :class="[
                'relative inline-flex h-7 w-14 rounded-full transition-colors focus:outline-none focus:ring-2 focus:ring-emerald-400/40 border border-white/30 shadow-[inset_0_1px_0_rgba(255,255,255,.25)]',
                envConfig.botIsSend ? 'bg-emerald-500/80 hover:bg-emerald-500' : 'bg-white/10 hover:bg-white/15',
              ]"
              @click="toggleBotIsSend"
            >
              <span
                :class="[
                  'absolute top-1 left-1 h-5 w-5 rounded-full bg-white shadow transition-transform',
                  envConfig.botIsSend ? 'translate-x-7' : 'translate-x-0',
                ]"
              />
            </button>
          </div>
        </CardHeader>
        <CardContent>
          <div class="space-y-2">
            <Label html-for="hookUrl">Webhook URL</Label>
            <Input
              id="hookUrl"
              type="text"
              :model-value="envConfig.hookUrl"
              placeholder="https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=your_key"
              @update:model-value="envConfig = { ...envConfig, hookUrl: $event }"
            />
            <p class="text-xs text-muted-foreground">
              企业微信群机器人webhook地址，用于接收通知消息
            </p>
          </div>
        </CardContent>
      </Card>

      <Card class="animate-in fade-in slide-in-from-bottom-6 duration-700">
        <CardHeader>
          <CardTitle class="flex items-center gap-2">
            <AppIcon icon="bi:braces" class="text-primary" />
            API 配置
          </CardTitle>
          <CardDescription>配置 API 服务器地址和使用的 AI 模型</CardDescription>
        </CardHeader>
        <CardContent>
          <div class="grid grid-cols-1 md:grid-cols-2 gap-6">
            <div class="space-y-2">
              <Label html-for="baseUrl">API Base URL</Label>
              <Input
                id="baseUrl"
                type="text"
                :model-value="envConfig.baseUrl"
                placeholder="https://api.ruyun.fun"
                @update:model-value="envConfig = { ...envConfig, baseUrl: $event }"
              />
              <p class="text-xs text-muted-foreground">API服务器地址</p>
            </div>
            <div class="space-y-2">
              <Label html-for="model">AI模型</Label>
              <Input
                id="model"
                type="text"
                :model-value="envConfig.model"
                placeholder="gpt-5-nano-2025-08-07"
                @update:model-value="envConfig = { ...envConfig, model: $event }"
              />
              <p class="text-xs text-muted-foreground">使用的AI模型名称</p>
            </div>
          </div>
        </CardContent>
      </Card>

      <Card class="animate-in fade-in slide-in-from-bottom-7 duration-700">
        <CardHeader>
          <CardTitle class="flex items-center gap-2">
            <AppIcon icon="bi:key" class="text-primary" />
            API 密钥
          </CardTitle>
          <CardDescription>配置 API 访问密钥，请妥善保管</CardDescription>
        </CardHeader>
        <CardContent>
          <div class="space-y-2">
            <Label html-for="apiKey">API Key</Label>
            <div class="relative">
              <Input
                id="apiKey"
                :type="showApiKey ? 'text' : 'password'"
                :model-value="envConfig.apiKey"
                placeholder="sk-xxxxxxxxxxxxxxxxx"
                @update:model-value="envConfig = { ...envConfig, apiKey: $event }"
              />
              <Button
                variant="ghost"
                size="sm"
                type="button"
                class="absolute right-1 top-1/2 -translate-y-1/2 h-7"
                @click="showApiKey = !showApiKey"
              >
                {{ showApiKey ? '隐藏' : '显示' }}
              </Button>
            </div>
            <p class="text-xs text-muted-foreground">🔐 API密钥将被安全存储，请妥善保管</p>
          </div>
        </CardContent>
      </Card>

      <Card class="border-primary/20 bg-primary/5">
        <CardContent class="pt-6">
          <div class="flex gap-3">
            <AppIcon icon="bi:info-circle" class="h-5 w-5 text-primary flex-shrink-0 mt-0.5" />
            <div>
              <p class="text-sm text-foreground">
                <strong class="font-semibold">提示：</strong> 这些环境变量将保存到
                <code class="bg-primary/10 px-2 py-0.5 rounded text-primary font-mono text-xs">.env</code>
                文件中。请勿将包含敏感信息的 .env 文件提交到版本控制系统。
              </p>
            </div>
          </div>
        </CardContent>
      </Card>

      <div
        v-if="showSaveDialog && saveResult"
        class="fixed inset-0 z-50 flex items-center justify-center bg-black/30"
        role="dialog"
        aria-modal="true"
      >
        <div class="bg-white dark:bg-neutral-900 rounded-2xl shadow-2xl w-[92%] max-w-sm border border-gray-200 dark:border-neutral-800">
          <Card class="border-0">
            <CardHeader class="pb-2">
              <CardTitle class="text-lg flex items-center gap-2">
                <AppIcon
                  icon="bi:save"
                  :class="saveResult.success ? 'text-green-500' : 'text-red-500'"
                />
                {{ saveResult.success ? '保存成功' : '保存失败' }}
              </CardTitle>
            </CardHeader>
            <CardContent class="pt-0">
              <p class="text-sm text-muted-foreground mb-4">{{ saveResult.message }}</p>
              <div class="flex justify-end gap-2">
                <Button
                  :class="`rounded-full px-4 ${saveResult.success ? 'bg-gradient-to-r from-green-500 to-emerald-600 hover:from-green-600 hover:to-emerald-700 text-white' : 'bg-gradient-to-r from-red-500 to-rose-600 hover:from-red-600 hover:to-rose-700 text-white'}`"
                  @click="showSaveDialog = false"
                >
                  知道了
                </Button>
              </div>
            </CardContent>
          </Card>
        </div>
      </div>
    </div>
  </div>
</template>
