<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { platformFromPath } from '@/lib/platformTheme'

const route = useRoute()

const accentClass = computed(() => {
  const platform = platformFromPath(route.path)
  if (!platform) return ''
  const map = {
    boss: 'accent-teal',
    liepin: 'accent-orange',
    '51job': 'accent-amber',
    zhilian: 'accent-sky',
  } as const
  return map[platform]
})
</script>

<template>
  <main :class="['ml-56 min-h-screen flex-1', accentClass]">
    <div class="mx-auto max-w-6xl px-6 py-6">
      <slot />
    </div>
  </main>
</template>
