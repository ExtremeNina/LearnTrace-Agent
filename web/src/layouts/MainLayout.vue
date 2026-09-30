<script setup lang="ts">
import { Menu } from 'lucide-vue-next'
import { useUiStore } from '../stores/ui'
import IconRail from '../components/layout/IconRail.vue'
import SidebarContent from '../components/layout/SidebarContent.vue'

const ui = useUiStore()
</script>

<template>
  <div class="flex h-full overflow-hidden bg-white text-ink">
    <!-- 图标栏：桌面端 -->
    <IconRail class="hidden md:flex" />

    <!-- Sidebar：桌面端常驻 -->
    <aside class="panel-gradient hidden w-60 shrink-0 flex-col border-r border-line md:flex">
      <SidebarContent />
    </aside>
    <!-- Sidebar：移动端抽屉 -->
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
        <SidebarContent @navigate="ui.closeSidebar()" />
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
