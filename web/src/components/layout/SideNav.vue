<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { Home, MonitorPlay, NotebookPen, Camera, ListChecks, Settings } from 'lucide-vue-next'

/** 主导航侧栏（B25 导航重构）：品牌区 + 内容导航 + 底部我的区；桌面常驻、移动端抽屉复用 */
const emit = defineEmits<{ navigate: []; 'open-profile': [] }>()

const route = useRoute()

const items = [
  { path: '/', label: '首页', icon: Home },
  { path: '/courses', label: '课程', icon: MonitorPlay },
  { path: '/notes', label: '笔记', icon: NotebookPen },
  { path: '/questions', label: '题目', icon: Camera },
  { path: '/quiz', label: '练习测验', icon: ListChecks },
]

const activePath = computed(() => route.path)

function isActive(path: string): boolean {
  return path === '/' ? activePath.value === '/' : activePath.value.startsWith(path)
}
</script>

<template>
  <div class="flex h-full min-h-0 flex-col">
    <!-- 品牌区 -->
    <div class="px-5 pb-4 pt-5">
      <p class="text-[20px] font-semibold tracking-tight text-ink">学迹</p>
      <p class="mt-0.5 text-[11px] text-ink-2">让学习更有轨迹</p>
    </div>

    <!-- 内容导航 -->
    <nav class="min-h-0 flex-1 overflow-y-auto px-3">
      <RouterLink
        v-for="item in items"
        :key="item.path"
        :to="item.path"
        class="mb-0.5 flex items-center gap-3 rounded-2xl px-3.5 py-2.5 text-[14px] transition-colors"
        :class="isActive(item.path) ? 'bg-line/60 font-medium text-ink' : 'text-ink-2 hover:bg-line/50 hover:text-ink'"
        @click="$emit('navigate')"
      >
        <component :is="item.icon" :size="18" />
        {{ item.label }}
      </RouterLink>
    </nav>

    <!-- 我的 -->
    <div class="shrink-0 border-t border-line px-3 py-3">
      <button
        class="flex w-full items-center gap-3 rounded-2xl px-3.5 py-2.5 text-[14px] text-ink-2 transition-colors hover:bg-line/50 hover:text-ink"
        @click="$emit('open-profile')"
      >
        <Settings :size="18" />
        设置
        <span class="ml-auto text-[11px] text-ink-2/70">Ctrl+,</span>
      </button>
    </div>
  </div>
</template>
