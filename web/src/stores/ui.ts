import { defineStore } from 'pinia'
import { ref } from 'vue'

/**
 * 布局 UI 状态：移动端抽屉开关、桌面端侧栏模式（对话 / 学习资产 / 任务）与收缩、侧栏宽度（可拖拽）
 */
export type SidebarMode = 'chat' | 'assets' | 'tasks'

const MODE_KEY = 'xj_sidebar_mode'
const COLLAPSED_KEY = 'xj_sidebar_collapsed'
const WIDTH_KEY = 'xj_sidebar_width'
const NOTES_TREE_WIDTH_KEY = 'xj_notes_tree_width'
const NOTES_LINKS_WIDTH_KEY = 'xj_notes_links_width'

/** 侧栏宽度边界（拖拽调宽的下限与上限） */
export const SIDEBAR_MIN_WIDTH = 200
export const SIDEBAR_MAX_WIDTH = 480
export const SIDEBAR_DEFAULT_WIDTH = 240

/** 笔记页三栏宽度边界（拖拽调宽的下限与上限；默认值 = 原 w-72 / w-80） */
export const NOTES_TREE_MIN_WIDTH = 200
export const NOTES_TREE_MAX_WIDTH = 400
export const NOTES_TREE_DEFAULT_WIDTH = 288
export const NOTES_LINKS_MIN_WIDTH = 240
export const NOTES_LINKS_MAX_WIDTH = 480
export const NOTES_LINKS_DEFAULT_WIDTH = 320

/** 从 localStorage 读栏宽并夹紧到边界（无记录或非法时用默认值） */
function persistedWidth(key: string, fallback: number, min: number, max: number): number {
  const saved = Number(localStorage.getItem(key))
  if (!Number.isFinite(saved) || saved <= 0) {
    return fallback
  }
  return Math.min(max, Math.max(min, saved))
}

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

  /** 笔记页栏宽（px，拖拽可调，持久化）：分层树 / 知识联系；编辑区占剩余空间 */
  const notesTreeWidth = ref(persistedWidth(NOTES_TREE_WIDTH_KEY, NOTES_TREE_DEFAULT_WIDTH, NOTES_TREE_MIN_WIDTH, NOTES_TREE_MAX_WIDTH))
  const notesLinksWidth = ref(persistedWidth(NOTES_LINKS_WIDTH_KEY, NOTES_LINKS_DEFAULT_WIDTH, NOTES_LINKS_MIN_WIDTH, NOTES_LINKS_MAX_WIDTH))

  function setNotesTreeWidth(width: number) {
    notesTreeWidth.value = Math.min(NOTES_TREE_MAX_WIDTH, Math.max(NOTES_TREE_MIN_WIDTH, width))
    localStorage.setItem(NOTES_TREE_WIDTH_KEY, String(notesTreeWidth.value))
  }

  function setNotesLinksWidth(width: number) {
    notesLinksWidth.value = Math.min(NOTES_LINKS_MAX_WIDTH, Math.max(NOTES_LINKS_MIN_WIDTH, width))
    localStorage.setItem(NOTES_LINKS_WIDTH_KEY, String(notesLinksWidth.value))
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
    notesTreeWidth,
    notesLinksWidth,
    setNotesTreeWidth,
    setNotesLinksWidth,
  }
})
