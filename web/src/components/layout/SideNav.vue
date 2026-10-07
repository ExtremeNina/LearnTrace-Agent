<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { Home, MonitorPlay, NotebookPen, Camera, Settings, Sprout } from 'lucide-vue-next'
import { getHomeOverview } from '../../api/home'

/**
 * 主导航侧栏（B25 导航重构，视觉对齐设计稿）：品牌区 + 内容导航 + 我的（学习轨迹 / 设置）+ 本周学习目标卡
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

// 本周学习目标卡（只读版：本周复习 / 新增笔记）
const weekReviewed = ref(0)
const weekNewNotes = ref(0)

onMounted(async () => {
  try {
    const overview = await getHomeOverview()
    weekReviewed.value = overview.week.reviewed
    weekNewNotes.value = overview.week.newNotes
  } catch {
    // 目标卡数据加载失败静默
  }
})
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

    <!-- 本周学习目标（默认目标：每周复习 15 次，目标设置功能后置） -->
    <div class="shrink-0 px-3 pb-4">
      <div class="rounded-lg border border-gray-100 bg-white p-4 shadow-sm">
        <p class="flex items-center gap-1.5 text-[13px] font-semibold text-gray-900">
          <Sprout :size="15" class="text-emerald-500" />
          本周学习目标
        </p>
        <p class="mt-1.5 text-[12px] text-ink-2">
          复习 <span class="font-semibold text-ink">{{ weekReviewed }}</span> / 15 次 · 新增笔记
          <span class="font-semibold text-ink">{{ weekNewNotes }}</span> 篇
        </p>
        <div class="mt-2 h-1.5 overflow-hidden rounded-full bg-gray-100">
          <div
            class="h-full rounded-full bg-gradient-to-r from-emerald-400 to-blue-400 transition-all"
            :style="{ width: Math.min(100, Math.round((weekReviewed / 15) * 100)) + '%' }"
          />
        </div>
      </div>
    </div>
  </div>
</template>
