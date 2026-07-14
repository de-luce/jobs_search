<script setup lang="ts">
import { Icon } from '@iconify/vue'
import Button from '@/components/ui/Button.vue'
import Card from '@/components/ui/Card.vue'
import CardHeader from '@/components/ui/CardHeader.vue'
import CardTitle from '@/components/ui/CardTitle.vue'
import CardContent from '@/components/ui/CardContent.vue'

type DialogResult = { success: boolean; message: string } | null

defineProps<{
  showLogoutDialog: boolean
  logoutResult: DialogResult
  showLogoutResultDialog: boolean
  saveResult: DialogResult
  showSaveDialog: boolean
}>()

const emit = defineEmits<{
  'logout-dialog-close': []
  'logout-confirm': []
  'logout-result-close': []
  'save-dialog-close': []
}>()

async function handleLogoutConfirm() {
  emit('logout-confirm')
  emit('logout-dialog-close')
}
</script>

<template>
  <div v-if="showLogoutDialog" class="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
    <Card class="bg-background rounded-2xl shadow-2xl w-[92%] max-w-sm border">
      <CardHeader class="pb-2">
        <CardTitle class="text-lg flex items-center gap-2">
          <Icon icon="bi:box-arrow-right" class="text-red-500" /> 确认退出登录
        </CardTitle>
      </CardHeader>
      <CardContent>
        <p class="text-sm text-muted-foreground mb-4">退出后将清除 Cookie 并切换为未登录状态。</p>
        <div class="flex justify-end gap-2">
          <Button variant="ghost" class="rounded-full px-4" @click="emit('logout-dialog-close')">
            取消
          </Button>
          <Button
            class="rounded-full bg-gradient-to-r from-red-500 to-rose-600 text-white px-4"
            @click="handleLogoutConfirm"
          >
            确认退出
          </Button>
        </div>
      </CardContent>
    </Card>
  </div>

  <div v-if="showLogoutResultDialog && logoutResult" class="fixed inset-0 z-50 flex items-center justify-center bg-black/30">
    <Card class="bg-background rounded-2xl shadow-2xl w-[92%] max-w-sm border">
      <CardHeader class="pb-2">
        <CardTitle class="text-lg flex items-center gap-2">
          <Icon
            icon="bi:box-arrow-right"
            :class="logoutResult.success ? 'text-green-500' : 'text-red-500'"
          />
          {{ logoutResult.success ? '退出登录成功' : '退出登录失败' }}
        </CardTitle>
      </CardHeader>
      <CardContent>
        <p class="text-sm text-muted-foreground mb-4">{{ logoutResult.message }}</p>
        <Button
          :class="`rounded-full px-4 ${logoutResult.success ? 'bg-green-500' : 'bg-red-500'} text-white`"
          @click="emit('logout-result-close')"
        >
          知道了
        </Button>
      </CardContent>
    </Card>
  </div>

  <div v-if="showSaveDialog && saveResult" class="fixed inset-0 z-50 flex items-center justify-center bg-black/30">
    <Card class="bg-background rounded-2xl shadow-2xl w-[92%] max-w-sm border">
      <CardHeader class="pb-2">
        <CardTitle class="text-lg flex items-center gap-2">
          <Icon icon="bi:save" :class="saveResult.success ? 'text-green-500' : 'text-red-500'" />
          {{ saveResult.success ? '保存成功' : '保存失败' }}
        </CardTitle>
      </CardHeader>
      <CardContent>
        <p class="text-sm text-muted-foreground mb-4">{{ saveResult.message }}</p>
        <Button
          :class="`rounded-full px-4 ${saveResult.success ? 'bg-green-500' : 'bg-red-500'} text-white`"
          @click="emit('save-dialog-close')"
        >
          知道了
        </Button>
      </CardContent>
    </Card>
  </div>
</template>
