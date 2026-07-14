<script setup lang="ts">
import { ref, watch, onMounted, onUnmounted } from 'vue'

export type SelectOption = {
  id?: number | string
  code: string
  name: string
}

const props = defineProps<{
  options: SelectOption[]
  selected: string[]
  placeholder?: string
  maxSelections?: number
}>()

const emit = defineEmits<{
  'update:selected': [value: string[]]
  close: []
}>()

const open = ref(false)
const wrapperRef = ref<HTMLDivElement | null>(null)
const buttonRef = ref<HTMLButtonElement | null>(null)
const dropdownRef = ref<HTMLDivElement | null>(null)
const dropdownPosition = ref({ top: 0, left: 0, width: 0 })

function updatePosition() {
  if (buttonRef.value) {
    const rect = buttonRef.value.getBoundingClientRect()
    dropdownPosition.value = { top: rect.bottom + 8, left: rect.left, width: rect.width }
  }
}

watch(open, (isOpen) => {
  if (!isOpen) return
  updatePosition()
  const handleUpdate = () => updatePosition()
  window.addEventListener('scroll', handleUpdate, true)
  window.addEventListener('resize', handleUpdate)
  onUnmounted(() => {
    window.removeEventListener('scroll', handleUpdate, true)
    window.removeEventListener('resize', handleUpdate)
  })
})

function handleOutsideClick(e: MouseEvent) {
  if (!open.value) return
  const target = e.target as Node
  if (!wrapperRef.value?.contains(target) && !dropdownRef.value?.contains(target)) {
    open.value = false
    emit('close')
  }
}

function handleEscape(e: KeyboardEvent) {
  if (e.key === 'Escape') {
    open.value = false
    emit('close')
  }
}

onMounted(() => {
  document.addEventListener('mousedown', handleOutsideClick)
  document.addEventListener('keydown', handleEscape)
})

onUnmounted(() => {
  document.removeEventListener('mousedown', handleOutsideClick)
  document.removeEventListener('keydown', handleEscape)
})

function toggle(code: string) {
  if (props.selected.includes(code)) {
    emit('update:selected', props.selected.filter((c) => c !== code))
  } else if (!props.maxSelections || props.selected.length < props.maxSelections) {
    emit('update:selected', [...props.selected, code])
  }
}

function toggleOpen() {
  open.value = !open.value
  if (!open.value) emit('close')
}

const selectedNames = () =>
  props.options.filter((o) => props.selected.includes(o.code)).map((o) => o.name)
</script>

<template>
  <div ref="wrapperRef" class="relative">
    <button
      ref="buttonRef"
      type="button"
      class="flex h-10 w-full items-center justify-between rounded-md border border-input bg-background px-3 py-2 text-sm shadow-sm transition-colors hover:bg-accent/50 focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2"
      @click="toggleOpen"
    >
      <span class="truncate text-sm">
        {{ selectedNames().length > 0 ? selectedNames().join('，') : placeholder || '请选择' }}
      </span>
      <span :class="['ml-2 text-xs text-muted-foreground transition-transform', open ? 'rotate-180' : '']">
        ▼
      </span>
    </button>
    <Teleport to="body">
      <div
        v-if="open"
        ref="dropdownRef"
        class="fixed z-[9999] max-h-60 overflow-y-auto rounded-md border bg-popover p-2 shadow-lg"
        :style="{
          top: `${dropdownPosition.top}px`,
          left: `${dropdownPosition.left}px`,
          width: `${dropdownPosition.width}px`,
        }"
      >
        <div
          v-for="opt in options"
          :key="opt.code"
          :class="[
            'flex items-center gap-2 rounded-md px-3 py-2 text-sm cursor-pointer transition-colors',
            !selected.includes(opt.code) && maxSelections && selected.length >= maxSelections
              ? 'opacity-50 cursor-not-allowed'
              : 'hover:bg-accent',
            selected.includes(opt.code) ? 'bg-accent/60' : '',
          ]"
          @click="
            !(
              !selected.includes(opt.code) &&
              maxSelections &&
              selected.length >= maxSelections
            ) && toggle(opt.code)
          "
        >
          <span
            :class="[
              'inline-flex h-4 w-4 items-center justify-center rounded border',
              selected.includes(opt.code) ? 'bg-primary border-primary' : 'border-input',
            ]"
          >
            <svg
              v-if="selected.includes(opt.code)"
              class="w-3 h-3 text-primary-foreground"
              fill="none"
              viewBox="0 0 24 24"
              stroke="currentColor"
            >
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="3" d="M5 13l4 4L19 7" />
            </svg>
          </span>
          <span class="truncate">{{ opt.name }}</span>
        </div>
      </div>
    </Teleport>
  </div>
</template>
