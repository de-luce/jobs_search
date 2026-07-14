<script setup lang="ts">
import { ref } from 'vue'
import { cn } from '@/lib/utils'

const props = withDefaults(
  defineProps<{
    defaultTab?: 'config' | 'analytics'
  }>(),
  { defaultTab: 'config' }
)

const activeTab = ref(props.defaultTab)
</script>

<template>
  <div class="w-full">
    <div class="inline-flex h-9 w-full max-w-md rounded-lg border border-border bg-muted/40 p-1">
      <button
        type="button"
        :class="cn(
          'flex-1 rounded-md text-sm font-medium transition-colors',
          activeTab === 'config' ? 'bg-background text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground'
        )"
        @click="activeTab = 'config'"
      >
        平台配置
      </button>
      <button
        type="button"
        :class="cn(
          'flex-1 rounded-md text-sm font-medium transition-colors',
          activeTab === 'analytics' ? 'bg-background text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground'
        )"
        @click="activeTab = 'analytics'"
      >
        投递分析
      </button>
    </div>

    <div v-show="activeTab === 'config'" class="mt-5 space-y-5">
      <slot name="config" />
    </div>
    <div v-show="activeTab === 'analytics'" class="mt-5 space-y-5">
      <slot name="analytics" />
    </div>
  </div>
</template>
