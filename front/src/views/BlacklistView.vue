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

interface BlacklistItem {
  id: number
  type: string
  value: string
  createdAt?: string
  updatedAt?: string
}

const API = getApiBase()

const items = ref<BlacklistItem[]>([])
const newValue = ref('')
const loading = ref(true)
const saving = ref(false)
const deletingId = ref<number | null>(null)
const message = ref<{ type: 'ok' | 'err'; text: string } | null>(null)

async function fetchList() {
  try {
    loading.value = true
    const response = await fetch(`${API}/api/blacklist?type=company`, {
      method: 'GET',
      headers: { 'Content-Type': 'application/json' },
    })
    if (!response.ok) throw new Error('获取黑名单失败')
    const result = await response.json()
    if (result.success) {
      items.value = Array.isArray(result.data) ? result.data : []
    } else {
      throw new Error(result.message || '获取黑名单失败')
    }
  } catch (error) {
    console.error(error)
    message.value = { type: 'err', text: '获取黑名单失败，请检查后端服务' }
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void fetchList()
})

async function handleAdd() {
  const value = newValue.value.trim()
  if (!value) {
    message.value = { type: 'err', text: '请输入公司名称关键词' }
    return
  }
  try {
    saving.value = true
    message.value = null
    const response = await fetch(`${API}/api/blacklist`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ type: 'company', value }),
    })
    if (!response.ok) throw new Error('添加失败')
    const result = await response.json()
    if (!result.success) throw new Error(result.message || '添加失败')
    newValue.value = ''
    message.value = { type: 'ok', text: '已加入黑名单' }
    await fetchList()
  } catch (error) {
    console.error(error)
    message.value = { type: 'err', text: error instanceof Error ? error.message : '添加失败' }
  } finally {
    saving.value = false
  }
}

async function handleDelete(id: number) {
  try {
    deletingId.value = id
    message.value = null
    const response = await fetch(`${API}/api/blacklist/${id}`, {
      method: 'DELETE',
      headers: { 'Content-Type': 'application/json' },
    })
    if (!response.ok) throw new Error('删除失败')
    const result = await response.json()
    if (!result.success) throw new Error(result.message || '删除失败')
    message.value = { type: 'ok', text: '已删除' }
    await fetchList()
  } catch (error) {
    console.error(error)
    message.value = { type: 'err', text: error instanceof Error ? error.message : '删除失败' }
  } finally {
    deletingId.value = null
  }
}

function onEnterAdd(e: KeyboardEvent) {
  if (e.key === 'Enter') {
    e.preventDefault()
    void handleAdd()
  }
}
</script>

<template>
  <div class="space-y-6">
    <PageHeader title="全局黑名单" subtitle="公司名匹配词条则跳过投递（全平台生效，忽略大小写/空白/有限公司等后缀）" accent-bg-class="bg-slate-700">
      <template #icon>
        <AppIcon icon="bi:slash-circle" :size="20" class="text-white" />
      </template>
    </PageHeader>

    <Card class="animate-in fade-in slide-in-from-bottom-5 duration-700">
      <CardHeader>
        <CardTitle class="flex items-center gap-2">
          <AppIcon icon="bi:building-slash" class="text-primary" />
          公司名称黑名单
        </CardTitle>
        <CardDescription>
          投递前若岗位公司名与任一关键词匹配（忽略大小写、空白与「有限公司」等后缀，支持简称↔全称），则跳过该岗位。建议填品牌关键词，例如「外包」「字节」。
        </CardDescription>
      </CardHeader>
      <CardContent class="space-y-4">
        <div class="flex flex-col gap-3 sm:flex-row sm:items-end">
          <div class="flex-1 space-y-2">
            <Label html-for="blacklistValue">公司名关键词</Label>
            <Input
              id="blacklistValue"
              type="text"
              :model-value="newValue"
              placeholder="例如：外包、猎头、某某科技"
              @update:model-value="newValue = $event"
              @keydown="onEnterAdd"
            />
          </div>
          <Button size="sm" class="shrink-0" :disabled="saving" @click="handleAdd">
            <AppIcon icon="bi:plus-lg" class="mr-1" />
            {{ saving ? '添加中…' : '添加' }}
          </Button>
        </div>

        <p
          v-if="message"
          :class="[
            'text-sm',
            message.type === 'ok' ? 'text-emerald-600 dark:text-emerald-400' : 'text-red-600 dark:text-red-400',
          ]"
        >
          {{ message.text }}
        </p>
      </CardContent>
    </Card>

    <Card v-if="loading" class="border-blue-500/20 bg-blue-500/5">
      <CardContent class="pt-6">
        <p class="text-center text-sm text-muted-foreground">加载黑名单中...</p>
      </CardContent>
    </Card>

    <Card v-else class="animate-in fade-in slide-in-from-bottom-6 duration-700">
      <CardHeader>
        <CardTitle class="text-base">当前词条（{{ items.length }}）</CardTitle>
      </CardHeader>
      <CardContent>
        <div v-if="items.length === 0" class="py-8 text-center text-sm text-muted-foreground">
          暂无黑名单，添加后全平台投递时生效
        </div>
        <ul v-else class="divide-y divide-border">
          <li
            v-for="item in items"
            :key="item.id"
            class="flex items-center justify-between gap-3 py-3 first:pt-0 last:pb-0"
          >
            <div class="min-w-0">
              <p class="truncate font-medium text-foreground">{{ item.value }}</p>
              <p class="text-xs text-muted-foreground">LIKE 匹配 · type={{ item.type }}</p>
            </div>
            <Button
              variant="ghost"
              size="sm"
              class="shrink-0 text-red-600 hover:bg-red-500/10 hover:text-red-700"
              :disabled="deletingId === item.id"
              @click="handleDelete(item.id)"
            >
              <AppIcon icon="bi:trash" class="mr-1" />
              {{ deletingId === item.id ? '删除中…' : '删除' }}
            </Button>
          </li>
        </ul>
      </CardContent>
    </Card>
  </div>
</template>
