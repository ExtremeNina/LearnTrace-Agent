import { defineStore } from 'pinia'
import { ref } from 'vue'

/**
 * 布局 UI 状态：移动端抽屉开关、桌面端侧栏模式（对话 / 学习资产 / 任务）与收缩、侧栏宽度（可拖拽）
 */
export type SidebarMode = 'chat' | 'assets' | 'tasks'

const MODE_KEY = 'xj_sidebar_mode'
const COLLAPSED_KEY = 'xj_sidebar_collapsed'
const WIDTH_KEY = 'xj_sidebar_width'

/** 侧栏宽度边界（拖拽调宽的下限与上限） */
export const SIDEBAR_MIN_WIDTH = 200
export const SIDEBAR_MAX_WIDTH = 480
export const SIDEBAR_DEFAULT_WIDTH = 240

export const useUiStore = defineStore('ui', () => {
  const sidebarOpen = ref(false)

  /** 侧栏当前模式：chat = 新对话与会话历史 / assets = 学习资产 / tasks = 今日任务 */
  const sidebarMode = ref<SidebarMode>((localStorage.getItem(MODE_KEY) as SidebarMode) || 'chat')

  /** 桌面端侧栏是否收缩（移动端抽屉不受影响） */
  const sidebarCollapsed = ref(localStorage.getItem(COLLAPSED_KEY) === '1')

  /** 侧栏宽度（px，拖拽可调，持久化） */
  const sidebarWidth = ref(Number(localStorage.getItem(WIDTH_KEY)) || SIDEBAR_DEFAULT_WIDTH)

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

  function setSidebarWidth(width: number) {
    sidebarWidth.value = Math.min(SIDEBAR_MAX_WIDTH, Math.max(SIDEBAR_MIN_WIDTH, width))
    localStorage.setItem(WIDTH_KEY, String(sidebarWidth.value))
  }

  return {
    sidebarOpen,
    sidebarMode,
    sidebarCollapsed,
    sidebarWidth,
    openSidebar,
    closeSidebar,
    showSidebar,
    toggleSidebarCollapsed,
    setSidebarWidth,
  }
})
