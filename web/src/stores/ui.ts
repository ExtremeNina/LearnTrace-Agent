import { defineStore } from 'pinia'
import { ref } from 'vue'

/**
 * 布局 UI 状态（B25 导航重构后）：移动端抽屉开关 + 笔记页栏宽（可拖拽持久化）。
 * 旧的三模式侧栏（对话 / 学习台）状态随导航重构移除——会话栏下沉至 /chat 页内。
 */

const NOTES_TREE_WIDTH_KEY = 'xj_notes_tree_width'
const NOTES_LINKS_WIDTH_KEY = 'xj_notes_links_width'

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
  /** 移动端 SideNav 抽屉开关 */
  const sidebarOpen = ref(false)

  function openSidebar() {
    sidebarOpen.value = true
  }

  function closeSidebar() {
    sidebarOpen.value = false
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
    openSidebar,
    closeSidebar,
    notesTreeWidth,
    notesLinksWidth,
    setNotesTreeWidth,
    setNotesLinksWidth,
  }
})
