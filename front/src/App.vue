<script setup lang="ts">
import { RouterView } from 'vue-router'
import Sidebar from '@/components/layout/Sidebar.vue'
import ContentArea from '@/components/layout/ContentArea.vue'
import { useCdpSession } from '@/composables/useCdpSession'

const { cdpHint } = useCdpSession()
</script>

<template>
  <div class="flex min-h-screen bg-muted/30">
    <Sidebar />
    <ContentArea>
      <div
        v-if="cdpHint"
        class="mb-4 rounded-lg border border-border bg-card px-4 py-2.5 text-sm text-muted-foreground"
      >
        {{ cdpHint }}
      </div>
      <RouterView />
    </ContentArea>
  </div>
</template>
