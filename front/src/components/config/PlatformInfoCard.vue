<script setup lang="ts">
import ConfigSection from '@/components/config/ConfigSection.vue'
import { getPlatformTheme } from '@/lib/platformTheme'
import type { PlatformId } from '@/lib/platform'

const props = defineProps<{
  platform: PlatformId
}>()

const theme = getPlatformTheme(props.platform)
</script>

<template>
  <ConfigSection
    :platform="platform"
    :title="`${theme.label}平台说明`"
    description="登录与投递操作提示"
  >
    <template #icon>
      <slot name="icon" />
    </template>
    <div class="space-y-3">
      <p class="text-sm text-muted-foreground">
        请在浏览器标签页中登录 {{ theme.label }} 平台，登录成功后系统会自动检测登录状态。
      </p>
      <p class="text-sm text-muted-foreground">
        登录成功后，点击「开始投递」按钮启动自动投递任务。
      </p>
      <p class="text-sm text-muted-foreground">
        点击「保存配置」按钮可手动保存当前登录相关信息到数据库。
      </p>
    </div>
  </ConfigSection>
</template>
