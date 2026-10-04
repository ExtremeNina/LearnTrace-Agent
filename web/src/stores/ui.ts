import { defineStore } from 'pinia'
import { ref } from 'vue'

/**
 * 布局 UI 状态：移动端 Sidebar 抽屉开关、桌面端侧栏模式（对话 / 学习资产）与收缩状态
 */
export type SidebarMode = 'chat' | 'assets'

const MODE_KEY = 'xj_sidebar_mode'
const COLLAPSED_KEY = 'xj_sidebar_collapsed'

export const useUiStore = defineStore('ui', () => {
  const sidebarOpen = ref(false)

  /** 侧栏当前模式：chat = 新对话与会话历史 / assets = 学习资产（网课、拍照、笔记） */
  const sidebarMode = ref<SidebarMode>((localStorage.getItem(MODE_KEY) as SidebarMode) || 'chat')

  /** 桌面端侧栏是否收缩（移动端抽屉不受影响） */
  const sidebarCollapsed = ref(localStorage.getItem(COLLAPSED_KEY) === '1')

  function openSidebar() {
    sidebarOpen.value = true
  }

  function closeSidebar() {
    sidebarOpen.value = false
  }

  /** 切换侧栏模式并展开（同时持久化，刷新后保持） */
  function showSidebar(mode: SidebarMode) {
    sidebarMode.value = mode
    sidebarCollapsed.value = false
    localStorage.setItem(MODE_KEY, mode)
    localStorage.setItem(COLLAPSED_KEY, '0')
  }

  function toggleSidebarCollapsed() {
    sidebarCollapsed.value = !sidebarCollapsed.value
    localStorage.setItem(COLLAPSED_KEY, sidebarCollapsed.value ? '1' : '0')
  }

  return {
    sidebarOpen,
    sidebarMode,
    sidebarCollapsed,
    openSidebar,
    closeSidebar,
    showSidebar,
    toggleSidebarCollapsed,
  }
})
