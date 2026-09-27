import { defineStore } from 'pinia'
import { ref } from 'vue'

/**
 * 布局 UI 状态：移动端 Sidebar 抽屉开关
 */
export const useUiStore = defineStore('ui', () => {
  const sidebarOpen = ref(false)

  function openSidebar() {
    sidebarOpen.value = true
  }

  function closeSidebar() {
    sidebarOpen.value = false
  }

  return { sidebarOpen, openSidebar, closeSidebar }
})
