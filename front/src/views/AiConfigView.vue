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
import Label from '@/components/ui/Label.vue'
import Textarea from '@/components/ui/Textarea.vue'
import PageHeader from '@/components/PageHeader.vue'

const API = getApiBase()

const aiConfig = ref({
  introduce: '',
  prompt: '',
})

const loading = ref(false)
const enableAi = ref(0)

onMounted(() => {
  void fetchAiConfig()
  void fetchEnableAi()
})

async function fetchAiConfig() {
  try {
    const response = await fetch(`${API}/api/ai/config`, {
      method: 'GET',
      headers: { 'Content-Type': 'application/json' },
    })

    if (!response.ok) throw new Error(`HTTP error! status: ${response.status}`)

    const result = await response.json()
    if (result.success && result.data) {
      aiConfig.value = {
        introduce: result.data.introduce || '',
        prompt: result.data.prompt || '',
      }
    }
  } catch (error) {
    console.error('加载AI配置失败:', error)
    console.log('使用默认配置')
  }
}

async function fetchEnableAi() {
  try {
    const response = await fetch(`${API}/api/boss/config`, {
      method: 'GET',
      headers: { 'Content-Type': 'application/json' },
    })

    if (!response.ok) throw new Error(`HTTP error! status: ${response.status}`)

    const result = await response.json()
    const raw = result?.config?.enableAi
    const val = String(raw ?? '').trim().toLowerCase()
    enableAi.value = val === '1' || val === 'true' || val === 'on' ? 1 : Number(raw) === 1 ? 1 : 0
  } catch (e) {
    console.error('加载enable_ai失败:', e)
  }
}

async function toggleEnableAi() {
  try {
    const next = enableAi.value ? 0 : 1
    enableAi.value = next
    const response = await fetch(`${API}/api/boss/config`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ enableAi: next }),
    })
    if (!response.ok) throw new Error(`HTTP error! status: ${response.status}`)
  } catch (e) {
    console.error('更新enable_ai失败:', e)
    enableAi.value = enableAi.value ? 0 : 1
    alert('切换失败，请检查后端服务连接')
  }
}

async function handleSave() {
  loading.value = true
  try {
    const response = await fetch(`${API}/api/ai/config`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(aiConfig.value),
    })

    const result = await response.json()

    if (result.success) {
      alert('AI配置已保存！')
    } else {
      alert('保存失败: ' + result.message)
    }
  } catch (error) {
    console.error('保存AI配置失败:', error)
    alert('保存失败，请检查服务器连接！')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="space-y-6">
    <PageHeader title="AI 配置" subtitle="技能介绍与提示词" accent-bg-class="bg-violet-600">
      <template #icon>
        <AppIcon icon="bi:stars" :size="20" class="text-white" />
      </template>
      <template #actions>
        <Button size="sm" :disabled="loading" @click="handleSave">
          <AppIcon icon="bi:save" class="mr-1" /> 保存
        </Button>
      </template>
    </PageHeader>

    <div class="space-y-6">
      <Card class="animate-in fade-in slide-in-from-bottom-5 duration-700">
        <CardHeader class="flex items-start gap-4">
          <div class="min-w-0 space-y-2">
            <CardTitle class="flex items-center gap-2">
              <AppIcon icon="bi:stars" class="text-primary" />
              AI配置
            </CardTitle>
            <CardDescription>配置AI相关的技能介绍和提示词，用于生成个性化求职内容</CardDescription>
          </div>
          <div>
            <button
              type="button"
              aria-label="AI启用开关"
              :class="[
                'relative inline-flex h-7 w-14 rounded-full transition-colors focus:outline-none focus:ring-2 focus:ring-emerald-400/40 border border-white/30 shadow-[inset_0_1px_0_rgba(255,255,255,.25)]',
                enableAi ? 'bg-emerald-500/80 hover:bg-emerald-500' : 'bg-white/10 hover:bg-white/15',
              ]"
              @click="toggleEnableAi"
            >
              <span
                :class="[
                  'absolute top-1 left-1 h-5 w-5 rounded-full bg-white shadow transition-transform',
                  enableAi ? 'translate-x-7' : 'translate-x-0',
                ]"
              />
            </button>
          </div>
        </CardHeader>
        <CardContent>
          <div class="space-y-6">
            <div class="space-y-2">
              <Label html-for="introduce">技能介绍</Label>
              <Textarea
                id="introduce"
                :model-value="aiConfig.introduce"
                placeholder="请输入您的技能介绍，例如：我熟练使用Java、Python等语言进行开发..."
                class="min-h-[150px] resize-y"
                @update:model-value="aiConfig = { ...aiConfig, introduce: $event }"
              />
              <p class="text-xs text-muted-foreground">
                详细描述您的技能、经验和专业背景，AI将使用这些信息生成个性化的求职文本
              </p>
            </div>

            <div class="space-y-2">
              <Label html-for="prompt">AI提示词</Label>
              <Textarea
                id="prompt"
                :model-value="aiConfig.prompt"
                placeholder="请输入AI提示词模板，例如：我目前在找工作，%s，我期望的岗位方向是【%s】..."
                class="min-h-[150px] resize-y"
                @update:model-value="aiConfig = { ...aiConfig, prompt: $event }"
              />
              <p class="text-xs text-muted-foreground">
                AI使用的提示词模板，支持使用 %s 作为占位符，用于动态插入内容
              </p>
            </div>
          </div>
        </CardContent>
      </Card>

      <Card class="border-primary/20 bg-primary/5">
        <CardContent class="pt-6">
          <div class="flex gap-3">
            <AppIcon icon="bi:info-circle" class="h-5 w-5 text-primary flex-shrink-0 mt-0.5" />
            <div>
              <p class="text-sm text-foreground mb-2">
                <strong class="font-semibold">使用说明：</strong>
              </p>
              <ul class="text-sm text-muted-foreground space-y-2">
                <li class="flex items-start gap-2">
                  <span class="text-primary mt-0.5">•</span>
                  <span><strong>技能介绍：</strong>用于AI了解您的专业技能、工作经验和技术背景，是生成个性化内容的基础</span>
                </li>
                <li class="flex items-start gap-2">
                  <span class="text-primary mt-0.5">•</span>
                  <span><strong>AI提示词：</strong>定义AI生成内容的模板和风格，支持使用 <code class="bg-muted px-1 py-0.5 rounded text-xs">%s</code> 作为占位符</span>
                </li>
                <li class="flex items-start gap-2">
                  <span class="text-primary mt-0.5">•</span>
                  <span><strong>效果：</strong>配置保存后，AI将在自动投递时使用这些信息生成匹配度高的求职沟通内容</span>
                </li>
                <li class="flex items-start gap-2">
                  <span class="text-primary mt-0.5">•</span>
                  <span><strong>提示：</strong>建议定期更新技能介绍以反映最新的技能和经验，提高匹配成功率</span>
                </li>
              </ul>
            </div>
          </div>
        </CardContent>
      </Card>
    </div>
  </div>
</template>
