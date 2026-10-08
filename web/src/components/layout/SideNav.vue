<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { Home, MonitorPlay, NotebookPen, Camera, Settings } from 'lucide-vue-next'

/**
 * 主导航侧栏（B25 导航重构，视觉对齐设计稿）：品牌区 + 内容导航 + 我的（学习轨迹 / 设置）
 */
const emit = defineEmits<{ navigate: []; 'open-profile': [] }>()

const route = useRoute()

const items = [
  { path: '/', label: '首页', icon: Home },
  { path: '/courses', label: '课程', icon: MonitorPlay },
  { path: '/notes', label: '笔记', icon: NotebookPen },
  { path: '/questions', label: '题目', icon: Camera },
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
      <p class="text-[18px] font-bold leading-5 tracking-tight text-gray-900">学迹</p>
      <p class="mt-0.5 text-[11px] text-gray-400">让学习更有轨迹</p>
    </div>

    <!-- 内容导航 -->
    <nav class="min-h-0 flex-1 overflow-y-auto px-3">
      <RouterLink
        v-for="item in items"
        :key="item.path"
        :to="item.path"
        class="mb-1 flex items-center gap-3 rounded-lg px-3.5 py-2.5 text-[14px] transition-colors"
        :class="isActive(item.path) ? 'bg-blue-50 font-medium text-blue-600' : 'text-gray-600 hover:bg-blue-50/60 hover:text-ink'"
        @click="$emit('navigate')"
      >
        <component :is="item.icon" :size="18" />
        {{ item.label }}
      </RouterLink>

      <!-- 我的 -->
      <div class="mt-4 border-t border-line pt-3">
        <p class="px-3.5 pb-1 text-[11px] text-gray-400">我的</p>
        <button
          class="flex w-full items-center gap-3 rounded-lg px-3.5 py-2.5 text-[14px] text-gray-600 transition-colors hover:bg-blue-50/60 hover:text-ink"
          @click="$emit('open-profile')"
        >
          <Settings :size="18" />
          设置
        </button>
      </div>
    </nav>
  </div>
</template>
