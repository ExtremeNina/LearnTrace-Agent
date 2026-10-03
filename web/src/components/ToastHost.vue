<script setup lang="ts">
import { CheckCircle2, XCircle, X } from 'lucide-vue-next'
import { useToastStore } from '../stores/toast'

/**
 * 全局轻提示宿主（右上角堆叠）：任务完成 / 失败通知与操作反馈
 */
const toast = useToastStore()
</script>

<template>
  <div class="pointer-events-none fixed right-4 top-4 z-[80] flex w-80 flex-col gap-2">
    <TransitionGroup name="toast">
      <div
        v-for="t in toast.toasts"
        :key="t.id"
        class="pointer-events-auto flex items-start gap-2.5 rounded-2xl border border-line bg-surface p-3.5 shadow-lg"
      >
        <XCircle v-if="t.type === 'error'" :size="17" class="mt-0.5 shrink-0 text-red-500" />
        <CheckCircle2 v-else :size="17" class="mt-0.5 shrink-0 text-green-600" />
        <p class="min-w-0 flex-1 text-[13px] leading-5 text-ink">{{ t.text }}</p>
        <button
          class="flex h-6 w-6 shrink-0 items-center justify-center rounded-lg text-ink-2 hover:bg-line/60 hover:text-ink"
          title="关闭"
          @click="toast.dismiss(t.id)"
        >
          <X :size="13" />
        </button>
      </div>
    </TransitionGroup>
  </div>
</template>

<style scoped>
.toast-enter-active,
.toast-leave-active {
  transition: all 0.2s ease;
}
.toast-enter-from,
.toast-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}
</style>
