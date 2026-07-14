<script setup lang="ts">
import AppIcon from '@/components/AppIcon.vue'
import Button from '@/components/ui/Button.vue'
import { focusPlatformLogin } from '@/lib/platform'

defineProps<{
  platformLabel: string
  checkingLogin: boolean
  isLoggedIn: boolean
  isDelivering: boolean
  platform?: 'boss' | 'liepin' | '51job' | 'zhilian'
}>()

const emit = defineEmits<{
  start: []
  stop: []
  logout: []
  save: []
}>()
</script>

<template>
  <div class="flex flex-wrap items-center gap-2">
    <Button v-if="checkingLogin" size="sm" variant="outline" disabled>
      <AppIcon icon="bi:hourglass-split" class="mr-1" /> 检查登录…
    </Button>
    <template v-else-if="!isLoggedIn">
      <Button size="sm" variant="outline" disabled>请先登录 {{ platformLabel }}</Button>
      <Button
        v-if="platform"
        size="sm"
        variant="secondary"
        @click="focusPlatformLogin(platform)"
      >
        <AppIcon icon="bi:box-arrow-up-right" class="mr-1" /> 打开登录页
      </Button>
    </template>
    <Button v-else-if="isDelivering" size="sm" variant="destructive" @click="emit('stop')">
      <AppIcon icon="bi:stop-fill" class="mr-1" /> 结束投递
    </Button>
    <Button v-else size="sm" @click="emit('start')">
      <AppIcon icon="bi:play-fill" class="mr-1" /> 开始投递
    </Button>
    <Button size="sm" variant="outline" @click="emit('logout')">
      <AppIcon icon="bi:box-arrow-right" class="mr-1" /> 退出
    </Button>
    <Button size="sm" variant="secondary" @click="emit('save')">
      <AppIcon icon="bi:save" class="mr-1" /> 保存
    </Button>
  </div>
</template>
