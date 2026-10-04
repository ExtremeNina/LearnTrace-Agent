<script setup lang="ts">
import { onMounted, onUnmounted } from 'vue'
import { ChevronsRight, Menu } from 'lucide-vue-next'
import { useUiStore } from '../stores/ui'
import { useUserStore } from '../stores/user'
import { useToastStore } from '../stores/toast'
import * as reviewApi from '../api/review'
import IconRail from '../components/layout/IconRail.vue'
import SidebarContent from '../components/layout/SidebarContent.vue'
import ToastHost from '../components/ToastHost.vue'

const ui = useUiStore()
const userStore = useUserStore()
const toast = useToastStore()

onMounted(async () => {
  // 个人页面数据 + 网课任务状态轮询（任务完成 / 失败右上角通知）
  userStore.loadMe()
  toast.startTaskWatcher()
  // 主动复习提醒：有到期卡且当天未提醒过时轻推一次（受个人页面的任务通知开关控制）
  await remindReview()
})

onUnmounted(() => {
  toast.stopTaskWatcher()
  stopResize()
})

/** 拖拽侧栏右边界调整宽度（200~480px，持久化） */
function startResize(e: MouseEvent) {
  e.preventDefault()
  const startX = e.clientX
  const startWidth = ui.sidebarWidth
  const onMove = (ev: MouseEvent) => {
    ui.setSidebarWidth(startWidth + (ev.clientX - startX))
  }
  const onUp = () => {
    window.removeEventListener('mousemove', onMove)
    window.removeEventListener('mouseup', onUp)
    document.body.style.cursor = ''
    document.body.style.userSelect = ''
  }
  document.body.style.cursor = 'col-resize'
  document.body.style.userSelect = 'none'
  window.addEventListener('mousemove', onMove)
  window.addEventListener('mouseup', onUp)
}

function stopResize() {
  document.body.style.cursor = ''
  document.body.style.userSelect = ''
}

async function remindReview() {
  try {
    const today = new Date().toISOString().slice(0, 10)
    if (localStorage.getItem('xj_review_reminded') === today) {
      return
    }
    const user = await userStore.loadMe()
    if (user?.notifyTaskEnabled === false) {
      return
    }
    const stats = await reviewApi.getReviewStats()
    if (stats.dueCount > 0) {
      localStorage.setItem('xj_review_reminded', today)
      toast.push(`有 ${stats.dueCount} 张卡片该复习了，要现在开始吗？`)
    }
  } catch {
    // 提醒失败静默
  }
}
</script>

<template>
  <div class="flex h-full overflow-hidden bg-surface text-ink">
    <!-- 图标栏：桌面端 -->
    <IconRail class="hidden md:flex" />

    <!-- Sidebar：桌面端（可收缩，双模式） -->
    <aside
      v-if="!ui.sidebarCollapsed"
      class="panel-gradient relative hidden shrink-0 flex-col border-r border-line md:flex"
      :style="{ width: ui.sidebarWidth + 'px' }"
    >
      <button
        class="absolute right-2 top-3.5 z-10 flex h-7 w-7 items-center justify-center rounded-lg text-ink-2 hover:bg-line/60 hover:text-ink"
        title="收起侧栏"
        @click="ui.toggleSidebarCollapsed()"
      >
        <ChevronsRight :size="16" />
      </button>
      <SidebarContent :mode="ui.sidebarMode" collapsible />
      <!-- 分隔竖线 + 拖拽调宽手柄 -->
      <div
        class="absolute inset-y-0 right-0 z-10 w-[3px] cursor-col-resize bg-line transition-colors hover:bg-primary"
        title="拖拽调整宽度"
        @mousedown="startResize"
      ></div>
    </aside>
    <!-- 收缩后的展开入口（悬停桌面图标栏旁） -->
    <button
      v-if="ui.sidebarCollapsed"
      class="panel-gradient hidden h-full w-2 shrink-0 border-r border-line md:block hover:w-14 transition-all group relative"
      title="展开侧栏"
      @click="ui.showSidebar(ui.sidebarMode)"
    >
      <ChevronsRight :size="16" class="absolute left-1/2 top-6 -translate-x-1/2 rotate-180 text-ink-2 opacity-0 transition-opacity group-hover:opacity-100" />
    </button>

    <!-- Sidebar：移动端抽屉（带模式切换） -->
    <Transition name="fade">
      <div
        v-if="ui.sidebarOpen"
        class="fixed inset-0 z-40 bg-black/30 md:hidden"
        @click="ui.closeSidebar()"
      />
    </Transition>
    <Transition name="slide">
      <aside
        v-if="ui.sidebarOpen"
        class="panel-gradient fixed inset-y-0 left-0 z-50 w-64 flex-col border-r border-line md:hidden"
      >
        <div class="flex shrink-0 gap-1 px-3 pt-3">
          <button
            class="flex-1 rounded-xl py-2 text-[13px] transition-colors"
            :class="ui.sidebarMode === 'chat' ? 'bg-line/70 font-medium text-ink' : 'text-ink-2'"
            @click="ui.showSidebar('chat')"
          >
            对话
          </button>
          <button
            class="flex-1 rounded-xl py-2 text-[13px] transition-colors"
            :class="ui.sidebarMode === 'assets' ? 'bg-line/70 font-medium text-ink' : 'text-ink-2'"
            @click="ui.showSidebar('assets')"
          >
            学习资产
          </button>
          <button
            class="flex-1 rounded-xl py-2 text-[13px] transition-colors"
            :class="ui.sidebarMode === 'tasks' ? 'bg-line/70 font-medium text-ink' : 'text-ink-2'"
            @click="ui.showSidebar('tasks')"
          >
            任务
          </button>
        </div>
        <div class="min-h-0 flex-1">
          <SidebarContent :mode="ui.sidebarMode" @navigate="ui.closeSidebar()" />
        </div>
      </aside>
    </Transition>

    <!-- 主区 -->
    <div class="flex min-w-0 flex-1 flex-col">
      <!-- 移动端顶栏 -->
      <header class="flex h-12 shrink-0 items-center gap-3 border-b border-line bg-panel px-3 md:hidden">
        <button
          class="flex h-8 w-8 items-center justify-center rounded-lg text-ink-2 hover:bg-line/60 hover:text-ink"
          @click="ui.openSidebar()"
        >
          <Menu :size="20" />
        </button>
        <span class="text-[14px] font-medium">学迹</span>
      </header>

      <main class="min-h-0 flex-1">
        <router-view v-slot="{ Component }">
          <!-- 笔记整理页跨页保留状态（上次打开的笔记 / 树展开） -->
          <KeepAlive include="NotesView">
            <component :is="Component" />
          </KeepAlive>
        </router-view>
      </main>
    </div>

    <!-- 全局轻提示：任务完成 / 失败通知 -->
    <ToastHost />
  </div>
</template>

<style scoped>
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.15s ease;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
.slide-enter-active,
.slide-leave-active {
  transition: transform 0.2s ease;
}
.slide-enter-from,
.slide-leave-to {
  transform: translateX(-100%);
}
</style>
