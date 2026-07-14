<script setup lang="ts">
import { ref, watch, onMounted, onUnmounted, nextTick } from 'vue'
import { CATEGORY_COLORS } from '@/components/analysis/chartHelpers'
import {
  Chart,
  BarController,
  BarElement,
  CategoryScale,
  DoughnutController,
  ArcElement,
  Legend,
  LineController,
  LineElement,
  LinearScale,
  PieController,
  PointElement,
  Title,
  Tooltip,
} from 'chart.js'

Chart.register(
  CategoryScale,
  LinearScale,
  PointElement,
  LineElement,
  BarElement,
  ArcElement,
  BarController,
  LineController,
  PieController,
  DoughnutController,
  Title,
  Tooltip,
  Legend
)

const props = withDefaults(
  defineProps<{
    type: 'pie' | 'bar' | 'line'
    labels: string[]
    data: number[]
    title?: string
    color?: string
    colors?: string[]
    emptyText?: string
  }>(),
  {
    color: '#3b82f6',
    emptyText: '暂无数据',
  }
)

const canvasRef = ref<HTMLCanvasElement | null>(null)
let chartInstance: Chart | null = null

function destroyChart() {
  chartInstance?.destroy()
  chartInstance = null
}

function renderChart() {
  const canvas = canvasRef.value
  const ctx = canvas?.getContext('2d')
  if (!ctx) return

  destroyChart()

  if (!props.labels.length || !props.data.length || props.data.every((v) => v === 0)) {
    return
  }

  const pieColors = (props.colors?.length ? props.colors : CATEGORY_COLORS).slice(0, props.labels.length)
  const backgroundColor =
    props.type === 'pie'
      ? pieColors
      : props.type === 'bar' && props.colors?.length
        ? props.colors.slice(0, props.data.length)
        : props.color

  const dataset: Record<string, unknown> = {
    label: props.title || '',
    data: props.data,
    backgroundColor,
    borderWidth: props.type === 'pie' ? 0 : 1,
  }

  if (props.type === 'line') {
    dataset.fill = false
    dataset.borderColor = props.color
    dataset.pointBackgroundColor = props.color
    dataset.pointRadius = 3
    dataset.tension = 0.2
  }

  chartInstance = new Chart(ctx, {
    type: props.type,
    data: { labels: props.labels, datasets: [dataset as never] },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      animation: false,
      plugins: {
        legend: { display: props.type === 'pie', labels: { boxWidth: 12, font: { size: 11 } } },
        title: { display: false },
      },
      scales:
        props.type !== 'pie'
          ? {
              x: { ticks: { autoSkip: true, maxRotation: 0, font: { size: 11 } } },
              y: { beginAtZero: true, ticks: { precision: 0, font: { size: 11 } } },
            }
          : undefined,
    },
  })
}

const isEmpty = () =>
  !props.labels.length || !props.data.length || props.data.every((v) => v === 0)

watch(
  () => [props.type, props.labels, props.data, props.title, props.color, props.colors],
  async () => {
    await nextTick()
    renderChart()
  },
  { deep: true }
)

onMounted(async () => {
  await nextTick()
  renderChart()
})

onUnmounted(destroyChart)
</script>

<template>
  <div
    v-if="isEmpty()"
    class="h-56 flex items-center justify-center rounded-lg border border-dashed text-sm text-muted-foreground"
  >
    {{ emptyText }}
  </div>
  <canvas v-else ref="canvasRef" class="w-full h-56" />
</template>
