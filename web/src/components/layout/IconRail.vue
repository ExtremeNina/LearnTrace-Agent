<script setup lang="ts">
import { House, History, CircleHelp, Settings } from 'lucide-vue-next'
import { useRoute } from 'vue-router'

const route = useRoute()
</script>

<template>
  <!-- 最左全局图标栏（PRD §5：桌面端显示，移动端隐藏并入抽屉） -->
  <nav class="panel-gradient w-14 shrink-0 flex-col items-center justify-between py-4 md:flex">
    <div class="flex flex-col items-center gap-2">
      <RouterLink
        to="/"
        class="flex h-10 w-10 items-center justify-center rounded-xl transition-colors"
        :class="route.path === '/' ? 'bg-ink text-white' : 'text-ink hover:bg-line/60'"
        title="首页 / Agent"
      >
        <House :size="20" />
      </RouterLink>
      <RouterLink
        to="/history"
        class="flex h-10 w-10 items-center justify-center rounded-xl transition-colors"
        :class="route.path === '/history' ? 'bg-ink text-white' : 'text-ink hover:bg-line/60'"
        title="会话历史"
      >
        <History :size="20" />
      </RouterLink>
    </div>
    <div class="flex flex-col items-center gap-2">
      <button
        class="flex h-10 w-10 items-center justify-center rounded-xl text-ink hover:bg-line/60"
        title="帮助"
      >
        <CircleHelp :size="20" />
      </button>
      <button
        class="flex h-10 w-10 items-center justify-center rounded-xl text-ink hover:bg-line/60"
        title="设置"
      >
        <Settings :size="20" />
      </button>
    </div>
  </nav>
</template>
