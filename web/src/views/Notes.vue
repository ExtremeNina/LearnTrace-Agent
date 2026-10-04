<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import DOMPurify from 'dompurify'
import {
  Bold, Italic, Underline, Paintbrush, Highlighter, Eraser, Save, X,
  NotebookPen, Download, PencilLine, GraduationCap, Check,
  ArrowLeft, FolderPlus, FolderInput, FolderTree,
} from 'lucide-vue-next'
import TreeNode from '../components/notes/TreeNode.vue'
import MdSourceEditor from '../components/notes/MdSourceEditor.vue'
import LinkPanel from '../components/notes/LinkPanel.vue'
import { getReviewStatus, addReviewCard } from '../api/review'
import { useToastStore } from '../stores/toast'
import { useUiStore } from '../stores/ui'
import type { TreeNodeData } from '../types/notes'
import {
  createGroup, createNote, deleteNote, getNoteDetail, getNoteTree,
  moveNote, renameNote, updateNoteContent,
} from '../api/note'
import type { NoteDetailInfo, NoteTreeNodeInfo } from '../api/note'
import { renderMarkdown } from '../utils/markdown'

// KeepAlive 按名字缓存：切到其他页面再回来时保留上次打开的笔记与树状态
defineOptions({ name: 'NotesView' })

/**
 * 笔记整理（PRD §3.4 OneNote 式页面管理）：
 * 分层树（分组自定义、最多 5 层）+ 知识联系（网课 / 题目 / 笔记）+ 富文本编辑。
 * 数据来自后端 /notes 接口。
 */
const MAX_LEVELS = 5

// ---- 三栏宽度拖拽（桌面端）：分层树 / 编辑区 / 知识联系 ----
const ui = useUiStore()
/** 编辑区保底宽度：任一侧栏加宽时为中间编辑区保留的最小空间（两侧栏各自的边界由 ui store 夹紧） */
const EDITOR_MIN_WIDTH = 360
const pageRef = ref<HTMLDivElement | null>(null)
let stopActiveResize: (() => void) | null = null

function startResize(e: MouseEvent, side: 'tree' | 'links') {
  e.preventDefault()
  const startX = e.clientX
  const startWidth = side === 'tree' ? ui.notesTreeWidth : ui.notesLinksWidth
  const onMove = (ev: MouseEvent) => {
    // 左栏向右拖变宽，右栏向左拖变宽
    const delta = side === 'tree' ? ev.clientX - startX : startX - ev.clientX
    let next = startWidth + delta
    const page = pageRef.value
    if (page) {
      // 本栏加宽不得把编辑区挤到保底宽度以下（另一栏宽度在本次拖拽中不变）
      const other = side === 'tree' ? ui.notesLinksWidth : ui.notesTreeWidth
      next = Math.min(next, page.clientWidth - other - EDITOR_MIN_WIDTH)
    }
    if (side === 'tree') {
      ui.setNotesTreeWidth(next)
    } else {
      ui.setNotesLinksWidth(next)
    }
  }
  const onUp = () => {
    window.removeEventListener('mousemove', onMove)
    window.removeEventListener('mouseup', onUp)
    document.body.style.cursor = ''
    document.body.style.userSelect = ''
    stopActiveResize = null
  }
  stopActiveResize = onUp
  document.body.style.cursor = 'col-resize'
  document.body.style.userSelect = 'none'
  window.addEventListener('mousemove', onMove)
  window.addEventListener('mouseup', onUp)
}

// ---- 分层树（id: g{数字}=分组 / n{数字}=笔记） ----
const tree = ref<TreeNodeData[]>([])
const expanded = reactive(new Set<string>())
const targetGroupId = ref<string | null>(null)
const creatingType = ref<'group' | 'note' | null>(null)
const newName = ref('')
const renamingId = ref<string | null>(null)
const renameValue = ref('')
let treeLoadedOnce = false

function toTreeData(nodes: NoteTreeNodeInfo[]): TreeNodeData[] {
  return nodes.map((n) => ({
    id: (n.type === 'group' ? 'g' : 'n') + n.id,
    name: n.title,
    type: n.type,
    noteId: n.type === 'note' ? n.id : undefined,
    children: n.children ? toTreeData(n.children) : n.type === 'group' ? [] : undefined,
  }))
}

async function loadTree() {
  tree.value = toTreeData(await getNoteTree())
  if (!treeLoadedOnce) {
    treeLoadedOnce = true
    const walk = (nodes: TreeNodeData[]) => {
      for (const n of nodes) {
        if (n.type === 'group') {
          expanded.add(n.id)
          walk(n.children ?? [])
        }
      }
    }
    walk(tree.value)
  }
}

function findNode(nodes: TreeNodeData[], id: string): TreeNodeData | null {
  for (const n of nodes) {
    if (n.id === id) {
      return n
    }
    if (n.children) {
      const found = findNode(n.children, id)
      if (found) {
        return found
      }
    }
  }
  return null
}

function nodeDepth(nodes: TreeNodeData[], id: string, level: number): number {
  for (const n of nodes) {
    if (n.id === id) {
      return level
    }
    if (n.children) {
      const d = nodeDepth(n.children, id, level + 1)
      if (d > 0) {
        return d
      }
    }
  }
  return -1
}

function depthOf(id: string): number {
  return nodeDepth(tree.value, id, 1)
}

function toggleExpand(id: string) {
  if (expanded.has(id)) {
    expanded.delete(id)
  } else {
    expanded.add(id)
  }
}

function selectGroup(id: string | null) {
  // 点击已选中的分组 = 取消新建位置选择
  if (id !== null && targetGroupId.value === id) {
    targetGroupId.value = null
    return
  }
  targetGroupId.value = id
}

/** 新建位置显示文本：分组名或根目录 */
const targetGroupLabel = computed(() => {
  if (!targetGroupId.value) {
    return '根目录'
  }
  return findNode(tree.value, targetGroupId.value)?.name ?? '根目录'
})

function numericGroupId(): number | null {
  if (!targetGroupId.value) {
    return null
  }
  const n = findNode(tree.value, targetGroupId.value)
  if (!n || n.type !== 'group') {
    return null
  }
  return Number(n.id.slice(1))
}

function onCreate(type: 'group' | 'note', groupId: string) {
  targetGroupId.value = groupId
  startCreate(type)
}

function startCreate(type: 'group' | 'note') {
  // 层级限制只针对分组（笔记是叶子，挂在第 5 层分组内合法）
  if (type === 'group' && targetGroupId.value) {
    const parentNumeric = numericGroupId()
    if (parentNumeric !== null) {
      const parentDepth = depthOf(targetGroupId.value!)
      if (parentDepth + 1 > MAX_LEVELS) {
        alert('最多支持 ' + MAX_LEVELS + ' 层，无法在更深层继续创建')
        return
      }
    }
  }
  creatingType.value = type
  newName.value = ''
  if (targetGroupId.value) {
    expanded.add(targetGroupId.value)
  }
  nextTick(() => {
    document.getElementById('new-name-input')?.focus()
  })
}

async function confirmCreate() {
  const name = newName.value.trim()
  if (!name || !creatingType.value) {
    creatingType.value = null
    return
  }
  const parentNumeric = numericGroupId()
  if (creatingType.value === 'group') {
    await createGroup(parentNumeric, name)
  } else {
    const noteId = await createNote(parentNumeric, name)
    await openNote(noteId)
  }
  await loadTree()
  creatingType.value = null
  newName.value = ''
}

function startRename(id: string) {
  const node = findNode(tree.value, id)
  if (node) {
    renamingId.value = id
    renameValue.value = node.name
  }
}

async function confirmRename() {
  if (renamingId.value) {
    const node = findNode(tree.value, renamingId.value)
    const name = renameValue.value.trim()
    if (node && name) {
      const numericId = Number(node.id.slice(1))
      await renameNote(numericId, name)
      await loadTree()
      if (selectedDetail.value && selectedDetail.value.id === numericId) {
        selectedDetail.value.title = name
      }
    }
  }
  renamingId.value = null
}

/** 统计子树内的分组数与笔记数（删除确认提示用） */
function countSubtree(node: TreeNodeData): { groups: number; notes: number } {
  let groups = 0
  let notes = 0
  for (const child of node.children ?? []) {
    if (child.type === 'group') {
      groups += 1
      const sub = countSubtree(child)
      groups += sub.groups
      notes += sub.notes
    } else {
      notes += 1
    }
  }
  return { groups, notes }
}

/** 收集子树内全部笔记 id（判断当前打开笔记是否会被连带删除） */
function collectNoteIds(node: TreeNodeData): number[] {
  const ids: number[] = []
  const walk = (n: TreeNodeData) => {
    if (n.type === 'note' && n.noteId !== undefined) {
      ids.push(n.noteId)
    }
    for (const c of n.children ?? []) {
      walk(c)
    }
  }
  walk(node)
  return ids
}

async function deleteGroup(id: string) {
  const node = findNode(tree.value, id)
  if (!node) {
    return
  }
  const sub = countSubtree(node)
  const scope = sub.groups + sub.notes > 0
    ? `其下 ${sub.groups} 个子分组和 ${sub.notes} 篇笔记将一并删除`
    : '这是一个空分组'
  if (!window.confirm(`确定删除分组「${node.name}」吗？\n\n${scope}，这些笔记的知识联系也会被清除，删除后不可恢复。`)) {
    return
  }
  const affectedNoteIds = collectNoteIds(node)
  await deleteNote(Number(id.slice(1)))
  await loadTree()
  // 新建位置若在被删子树内则复位
  if (targetGroupId.value && !findNode(tree.value, targetGroupId.value)) {
    targetGroupId.value = null
  }
  // 当前打开的笔记若被连带删除则关闭详情
  if (selectedId.value !== null && affectedNoteIds.includes(selectedId.value)) {
    selectedId.value = null
    selectedDetail.value = null
  }
}

async function deleteNoteLeaf(noteId: number) {
  if (!window.confirm('确定删除这篇笔记吗？它的知识联系也会被清除，删除后不可恢复。')) {
    return
  }
  await deleteNote(noteId)
  if (selectedId.value === noteId) {
    selectedId.value = null
    selectedDetail.value = null
  }
  await loadTree()
}

// ---- 详情 ----
const selectedId = ref<number | null>(null)
const selectedDetail = ref<NoteDetailInfo | null>(null)
const router = useRouter()
const toast = useToastStore()

const selectedNote = computed(() => selectedDetail.value)

/** 复习队列状态（打开笔记时查询，null = 查询中） */
const noteInReview = ref<boolean | null>(null)

async function openNote(id: number) {
  // 切换笔记时退出编辑态，避免编辑状态与未保存草稿被带进另一篇笔记
  editing.value = false
  selectedDetail.value = await getNoteDetail(id)
  selectedId.value = id
  noteInReview.value = null
  getReviewStatus('note', id)
    .then((v) => (noteInReview.value = v))
    .catch(() => (noteInReview.value = null))
  // 手动笔记且尚无内容（新建 / 未书写过）：自动进入编辑态，免去先找编辑按钮
  if (isManualEmpty(selectedDetail.value)) {
    startEdit()
  }
}

/** 加入今日复习队列（复习页的抽卡来源之一） */
async function addNoteToReview() {
  if (selectedId.value === null || noteInReview.value) {
    return
  }
  try {
    await addReviewCard('note', selectedId.value)
    noteInReview.value = true
    toast.push('已加入今日复习')
  } catch (e) {
    toast.push(e instanceof Error ? e.message : '加入复习失败', 'error')
  }
}

/** 手动笔记内容是否为空（剥掉标签与空白后无有效文字） */
function isManualEmpty(d: NoteDetailInfo): boolean {
  if (d.source !== '手动创建') {
    return false
  }
  return d.content.replace(/<[^>]*>/g, '').replace(/&nbsp;/g, ' ').trim() === ''
}

/** 知识联系面板增删改后刷新详情（保留树不动） */
async function reloadDetail() {
  if (selectedId.value == null) {
    return
  }
  selectedDetail.value = await getNoteDetail(selectedId.value)
}

function openLink(link: { linkType: string; targetId: number; tsSec?: number | null }) {
  if (link.linkType === 'note') {
    openNote(link.targetId)
    return
  }
  if (link.linkType === 'course') {
    router.push(link.tsSec != null ? `/courses/${link.targetId}?t=${formatTs(link.tsSec)}` : `/courses/${link.targetId}`)
    return
  }
  // 题目：跳拍照记录列表并自动弹出该题详情
  if (link.linkType === 'question') {
    router.push(`/questions?open=${link.targetId}`)
  }
}

// ---- 富文本编辑 ----
const editing = ref(false)
const editorRef = ref<HTMLDivElement | null>(null)
/** AI 笔记的 Markdown 源码草稿（AI 笔记一律存 Markdown，富文本 HTML 会破坏详情页渲染与时间戳胶囊） */
const mdDraft = ref('')

const isAiNote = computed(() => selectedDetail.value?.source === 'AI 生成')

/** 编辑器预览里点时间戳胶囊 → 跳转关联网课对应位置 */
function onEditorChip(ts: string) {
  if (selectedDetail.value?.courseId) {
    router.push(`/courses/${selectedDetail.value.courseId}?t=${ts}`)
  }
}

/** 手动笔记正文是否为 Markdown 形态（无 HTML 标签且含 md 结构标记）——历史数据兼容渲染 */
function looksLikeMarkdown(s: string): boolean {
  if (/<[a-z][^>]*>/i.test(s)) {
    return false
  }
  return /(^|\n)\s*#{1,6}\s/.test(s) || /\n\s*[-*]\s/.test(s) || /(^|\n)\s*\d+\.\s/.test(s)
}

function startEdit() {
  if (!selectedDetail.value) {
    return
  }
  if (selectedDetail.value.source === 'AI 生成') {
    // AI 笔记：编辑 Markdown 源码（独立编辑器）
    mdDraft.value = selectedDetail.value.content
    editing.value = true
    return
  }
  // 手动笔记：原地编辑——阅读与编辑是同一个 DOM，点击后仅变为可编辑，排版不变
  editing.value = true
  nextTick(() => {
    const el = editorRef.value
    if (!el) {
      return
    }
    el.focus()
    try {
      const range = (document as unknown as { caretRangeFromPoint?: (x: number, y: number) => Range | null }).caretRangeFromPoint?.(lastClickX, lastClickY)
      if (range && el.contains(range.startContainer)) {
        const sel = window.getSelection()
        sel?.removeAllRanges()
        sel?.addRange(range)
      }
    } catch {
      // 光标落点失败则停在起点
    }
  })
}

async function saveEdit() {
  if (!selectedDetail.value) {
    editing.value = false
    return
  }
  try {
    if (selectedDetail.value.source === 'AI 生成') {
      await updateNoteContent(selectedDetail.value.id, mdDraft.value)
      selectedDetail.value.content = mdDraft.value
    } else if (editorRef.value) {
      const html = editorRef.value.innerHTML
      await updateNoteContent(selectedDetail.value.id, html)
      selectedDetail.value.content = html
    }
    editing.value = false
  } catch (e) {
    // 保存失败留在编辑态，内容不丢
    window.alert(e instanceof Error ? e.message : '保存失败，请稍后重试')
  }
}

function cancelEdit() {
  if (!isAiNote.value && selectedDetail.value) {
    // 原地编辑放弃：DOM 恢复为未修改的渲染结果
    const html = noteHtml.value
    editing.value = false
    nextTick(() => {
      if (editorRef.value) {
        editorRef.value.innerHTML = html
      }
    })
    return
  }
  editing.value = false
}

function exec(command: string, value?: string) {
  document.execCommand('styleWithCSS', false, 'true')
  document.execCommand(command, false, value)
  editorRef.value?.focus()
}

function onFontSizeChange(e: Event) {
  exec('fontSize', (e.target as HTMLSelectElement).value)
}

function onColorChange(e: Event) {
  exec('foreColor', (e.target as HTMLInputElement).value)
}

function onHighlightChange(e: Event) {
  exec('hiliteColor', (e.target as HTMLInputElement).value)
}

// ---- 时间戳渲染与跳转 ----
function formatTs(sec: number): string {
  const h = Math.floor(sec / 3600)
  const m = Math.floor((sec % 3600) / 60)
  const s = sec % 60
  const mm = String(m).padStart(2, '0')
  const ss = String(s).padStart(2, '0')
  return h > 0 ? `${h}:${mm}:${ss}` : `${mm}:${ss}`
}

function renderWithChips(d: NoteDetailInfo): string {
  // AI 笔记恒为 Markdown；手动笔记兼容历史 Markdown 形态内容（无 HTML 标签且含 md 结构标记）
  const base = d.source === 'AI 生成' || looksLikeMarkdown(d.content)
    ? renderMarkdown(d.content)
    : DOMPurify.sanitize(d.content)
  const courseSuffix = d.courseTitle ? `（${d.courseTitle}）` : ''
  return base.replace(/\[(\d{1,2}:[0-5]\d(?::\d{2})?)\]/g,
    `<span class="ts-chip" data-ts="$1">$1${courseSuffix}</span>`)
}

const noteHtml = computed(() => {
  const d = selectedDetail.value
  return d ? renderWithChips(d) : ''
})

/** 手动笔记阅读态是否无内容（显示占位提示） */
const readEmpty = computed(() => !!selectedDetail.value && isManualEmpty(selectedDetail.value))

/** 单击进入原地编辑时的落点坐标（用于把光标放到点击处） */
let lastClickX = 0
let lastClickY = 0

/**
 * 笔记内容区点击语义（OneNote 式）：
 * - 单击正文：手动笔记进入编辑态（编辑中则为正常光标操作）
 * - 单击时间戳胶囊：跳转关联网课（编辑中仅移动光标）
 * - 单击内容区空白：编辑中 = 放弃修改退出编辑态；非编辑态不响应（不冒蓝框）
 */
function onContentAreaClick(e: MouseEvent) {
  const target = e.target as HTMLElement
  const chip = target.closest('[data-ts]')
  if (chip) {
    if (!editing.value && selectedDetail.value?.courseId) {
      router.push(`/courses/${selectedDetail.value.courseId}?t=${chip.getAttribute('data-ts')}`)
    }
    return
  }
  if (target.closest('.note-view')) {
    if (!editing.value && selectedDetail.value && selectedDetail.value.source !== 'AI 生成') {
      lastClickX = e.clientX
      lastClickY = e.clientY
      startEdit()
    }
    return
  }
  if (editing.value && !isAiNote.value) {
    cancelEdit()
  }
}

function onMoveChange(e: Event) {
  const v = (e.target as HTMLSelectElement).value
  if (!selectedDetail.value) {
    return
  }
  const pid = v === '0' ? null : Number(v.slice(1))
  moveNote(selectedDetail.value.id, pid).then(loadTree)
}

// ---- 知识联系 ----

onMounted(async () => {
  document.addEventListener('mousedown', onDocMouseDownWhileInlineInput)
  await loadTree()
})

onUnmounted(() => {
  document.removeEventListener('mousedown', onDocMouseDownWhileInlineInput)
  if (stopActiveResize) {
    stopActiveResize()
  }
})

/**
 * 重命名 / 新建输入打开时，点击输入框以外的空白区域即保存；
 * 输入为空时视为取消，修复输入状态无法退出的 bug
 */
function onDocMouseDownWhileInlineInput(e: MouseEvent) {
  const target = e.target as HTMLElement
  if (target.closest('[data-inline-input]')) {
    return
  }
  if (renamingId.value !== null) {
    confirmRename()
    return
  }
  if (creatingType.value !== null) {
    confirmCreate()
  }
}

// ---- 移动到分组下拉选项 ----
const groupPathOptions = computed(() => {
  const options: { id: string; label: string; depth: number }[] = []
  const walk = (nodes: TreeNodeData[], depth: number, trail: string[]) => {
    for (const n of nodes) {
      if (n.type === 'group') {
        const trailNext = [...trail, n.name]
        options.push({ id: n.id, label: trailNext.join(' / '), depth })
        if (n.children && depth < MAX_LEVELS) {
          walk(n.children, depth + 1, trailNext)
        }
      }
    }
  }
  walk(tree.value, 1, [])
  return options
})
</script>

<template>
  <div ref="pageRef" class="flex h-full overflow-hidden">
    <!-- 左：分层树 -->
    <aside
      class="relative w-72 shrink-0 flex-col border-l border-line bg-surface md:flex md:w-[var(--notes-tree-w)]"
      :class="selectedId === null ? 'flex' : 'hidden'"
      :style="{ '--notes-tree-w': ui.notesTreeWidth + 'px' }"
    >
      <div class="flex items-center justify-between px-4 pt-5 pb-2">
        <p class="flex items-center gap-1.5 text-[14px] font-semibold text-ink">
          <FolderTree :size="16" class="text-ink-2" />
          笔记分层
        </p>
        <div class="flex gap-1">
          <button class="flex h-7 w-7 items-center justify-center rounded-lg text-ink hover:bg-line/60" title="新建分组" @click="startCreate('group')">
            <FolderPlus :size="16" />
          </button>
        </div>
      </div>
      <p class="px-4 pb-2 text-[11px] text-ink-2">
        新建位置：{{ targetGroupLabel }}（最多 5 层）
      </p>

      <!-- 内联新建输入 -->
      <div v-if="creatingType" class="mx-3 mb-2 flex items-center gap-1.5 rounded-xl border border-primary bg-surface px-2.5 py-1.5">
        <NotebookPen :size="14" class="text-ink-2" />
        <input
          id="new-name-input"
          v-model="newName"
          data-inline-input="1"
          class="min-w-0 flex-1 text-[13px] outline-none"
          :placeholder="creatingType === 'group' ? '分组名称' : '笔记标题'"
          @keydown.enter="confirmCreate"
          @keydown.esc="creatingType = null"
        />
        <button class="text-ink-2 hover:text-ink" @click="confirmCreate"><Save :size="14" /></button>
        <button class="text-ink-2 hover:text-ink" @click="creatingType = null"><X :size="14" /></button>
      </div>

      <!-- 分层树 -->
      <div class="flex-1 overflow-y-auto px-3 pb-3 text-[14px]">
        <TreeNode
          v-for="node in tree"
          :key="node.id"
          :node="node"
          :depth="1"
          :expanded="expanded"
          :selected-note-id="selectedId"
          :renaming-id="renamingId"
          :rename-value="renameValue"
          :target-group-id="targetGroupId"
          :max-levels="MAX_LEVELS"
          @toggle="toggleExpand"
          @open="openNote"
          @select-group="selectGroup"
          @create="onCreate"
          @start-rename="startRename"
          @confirm-rename="confirmRename"
          @rename-input="renameValue = $event"
          @delete-group="deleteGroup"
          @delete-note="deleteNoteLeaf"
        />
      </div>

      <!-- 分隔竖线 + 拖拽调宽手柄（右缘，200~400 持久化） -->
      <div
        class="absolute inset-y-0 right-0 z-10 hidden w-[3px] cursor-col-resize bg-line transition-colors hover:bg-primary md:block"
        title="拖拽调整宽度"
        @mousedown="startResize($event, 'tree')"
      ></div>
    </aside>

    <!-- 右：笔记详情 -->
    <div class="flex min-w-0 flex-1 flex-col" :class="selectedId === null ? 'hidden md:flex' : 'flex'">
      <template v-if="selectedNote">
        <div class="flex items-center gap-3 border-b border-line px-4 py-3">
          <button
            class="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg text-ink-2 hover:bg-line/60 md:hidden"
            @click="selectedId = null"
          >
            <ArrowLeft :size="18" />
          </button>
          <div class="min-w-0">
            <h1 class="truncate text-[18px] font-semibold">{{ selectedNote.title }}</h1>
            <p class="text-[12px] text-ink-2">{{ selectedNote.source }} · 更新于 {{ selectedNote.updatedAt }}</p>
          </div>
          <div class="ml-auto flex shrink-0 items-center gap-2">
            <!-- 加入复习队列 -->
            <button
              class="flex items-center gap-1.5 rounded-xl border px-3 py-1.5 text-[13px]"
              :class="noteInReview ? 'border-green-300 text-green-600' : 'border-line text-ink hover:bg-panel'"
              :disabled="noteInReview !== false"
              :title="noteInReview ? '已在复习队列' : '加入今日复习队列'"
              @click="addNoteToReview"
            >
              <Check v-if="noteInReview" :size="15" />
              <GraduationCap v-else :size="15" />
              {{ noteInReview ? '已加入复习' : '加入复习' }}
            </button>
            <!-- AI 笔记：编辑按钮（Markdown 源码修订） -->
            <button
              v-if="isAiNote"
              class="flex items-center gap-1.5 rounded-xl border px-3 py-1.5 text-[13px] hover:bg-panel"
              :class="editing ? 'border-primary text-primary' : 'border-line text-ink'"
              @click="editing ? cancelEdit() : startEdit()"
            >
              <PencilLine :size="15" />
              {{ editing ? '取消' : '编辑' }}
            </button>
            <!-- 手动笔记：单击正文即进入编辑，头部保存按钮（未进入编辑时置灰） -->
            <button
              v-else
              class="flex items-center gap-1.5 rounded-xl border px-3 py-1.5 text-[13px]"
              :class="editing ? 'border-primary bg-primary text-white hover:opacity-90' : 'border-line text-ink-2 opacity-50 cursor-not-allowed'"
              :disabled="!editing"
              title="单击正文内容即可编辑，改完点这里保存"
              @click="saveEdit"
            >
              <Save :size="15" />
              保存
            </button>
            <button
              v-if="!isAiNote && editing"
              class="flex items-center gap-1.5 rounded-xl border border-line px-3 py-1.5 text-[13px] text-ink hover:bg-line/60"
              @click="cancelEdit"
            >
              放弃
            </button>
            <button class="hidden items-center gap-1.5 rounded-xl border border-line px-3 py-1.5 text-[13px] text-ink hover:bg-panel sm:flex">
              <Download :size="15" />
              导出
            </button>
          </div>
        </div>

        <!-- 移动到分组（编辑手动笔记时，格式工具栏固定在本行） -->
        <div class="flex shrink-0 items-center gap-2 border-b border-line px-4 py-2 text-[12px] text-ink-2">
          <FolderInput :size="14" />
          <div v-if="editing && !isAiNote" class="ml-auto flex items-center gap-1">
            <button class="flex h-7 w-7 items-center justify-center rounded-lg text-ink hover:bg-line/60" title="加粗" @mousedown.prevent @click="exec('bold')">
              <Bold :size="14" />
            </button>
            <button class="flex h-7 w-7 items-center justify-center rounded-lg text-ink hover:bg-line/60" title="斜体" @mousedown.prevent @click="exec('italic')">
              <Italic :size="14" />
            </button>
            <button class="flex h-7 w-7 items-center justify-center rounded-lg text-ink hover:bg-line/60" title="下划线" @mousedown.prevent @click="exec('underline')">
              <Underline :size="14" />
            </button>
            <select class="h-7 rounded-lg border border-line bg-surface px-1 text-[12px] text-ink outline-none" title="字号" @change="onFontSizeChange">
              <option value="2">小</option>
              <option value="3" selected>标准</option>
              <option value="5">大</option>
              <option value="7">特大</option>
            </select>
            <label class="flex h-7 w-7 cursor-pointer items-center justify-center rounded-lg text-ink hover:bg-line/60" title="字体颜色">
              <Paintbrush :size="14" />
              <input type="color" class="sr-only" value="#0d0d0d" @input="onColorChange" />
            </label>
            <label class="flex h-7 w-7 cursor-pointer items-center justify-center rounded-lg text-ink hover:bg-line/60" title="背景高亮">
              <Highlighter :size="14" />
              <input type="color" class="sr-only" value="#fff3c4" @input="onHighlightChange" />
            </label>
            <button class="flex h-7 w-7 items-center justify-center rounded-lg text-ink hover:bg-line/60" title="清除格式" @mousedown.prevent @click="exec('removeFormat')">
              <Eraser :size="14" />
            </button>
          </div>
          <select
            :class="editing && !isAiNote ? 'shrink-0' : 'ml-auto shrink-0'"
            class="rounded-lg border border-line bg-surface px-2 py-1 text-[12px] text-ink outline-none"
            @change="onMoveChange"
          >
            <option value="" disabled selected>移动到分组…</option>
            <option value="0">（根目录）</option>
            <option v-for="g in groupPathOptions" :key="g.id" :value="g.id">
              {{ '　'.repeat(g.depth - 1) + g.label }}
            </option>
          </select>
        </div>

        <div class="min-h-0 flex-1 overflow-y-auto" @click="onContentAreaClick">
          <!-- 笔记内容（mr-6：与右侧知识联系边栏保持间距，窄窗口下不贴边） -->
          <div v-if="editing && isAiNote" class="ml-6 mr-6 max-w-3xl px-4 pt-4">
              <MdSourceEditor v-model="mdDraft" @chip="onEditorChip">
                <template #actions>
                  <button class="flex items-center gap-1 rounded-lg bg-ink px-3 py-1.5 text-[13px] text-white hover:opacity-80" @click="saveEdit">
                    <Save :size="14" />
                    保存
                  </button>
                  <button class="flex items-center gap-1 rounded-lg border border-line px-3 py-1.5 text-[13px] text-ink-2 hover:bg-line/40" @click="cancelEdit">
                    <X :size="14" />
                    放弃
                  </button>
                </template>
              </MdSourceEditor>
            </div>

            <!-- 手动笔记：原地编辑——阅读与编辑是同一个 DOM，点击后仅变为可编辑；内边距两态一致，排版不变 -->
            <div v-else class="ml-6 mr-6 max-w-3xl py-2">
              <p
                v-if="readEmpty && !editing"
                class="cursor-pointer px-5 py-4 text-[14px] text-ink-2"
                @click="startEdit"
              >这篇笔记还没有内容，单击此处即可开始书写。</p>
              <div
                v-else
                ref="editorRef"
                class="note-view px-5 py-4 text-[14px] leading-7 text-ink outline-none focus:outline-none focus-visible:outline-none"
                :class="editing ? 'rounded-xl bg-surface shadow-[0_0_0_1.5px_rgba(59,130,246,0.45)]' : ''"
                :contenteditable="editing"
                v-html="noteHtml"
              ></div>
              <p v-if="selectedNote.courseId && !editing" class="mt-2 px-5 text-[12px] text-ink-2">
                点击文中的时间戳可跳转网课对应位置核对；单击正文任意位置可直接编辑。
              </p>
            </div>

            <!-- 知识联系（移动端折叠区；桌面端在右侧栏） -->
            <div v-if="!editing" class="ml-6 max-w-3xl px-4 pb-6 lg:hidden">
              <details class="rounded-2xl border border-line bg-surface px-4 py-3">
                <summary class="cursor-pointer text-[13px] font-semibold text-ink">
                  知识联系（{{ selectedNote.links.length }}）
                </summary>
                <div class="mt-3">
                  <LinkPanel :note-id="selectedNote.id" :links="selectedNote.links" @changed="reloadDetail" @jump="openLink" />
                </div>
              </details>
            </div>
        </div>
      </template>

      <!-- 未选择笔记 -->
      <div v-else class="flex h-full flex-col items-center justify-center gap-3">
        <NotebookPen :size="40" class="text-ink-2/50" />
        <p class="text-[15px] text-ink-2">从左侧选择一个笔记页，或新建分组与笔记</p>
      </div>
    </div>

    <!-- 右：知识联系侧栏（桌面端常驻第三列，移动端在正文下方折叠区；滚动收进内层，拖拽手柄不随内容滚动） -->
    <aside
      class="relative hidden w-80 shrink-0 flex-col border-l border-line lg:flex lg:w-[var(--notes-links-w)]"
      :style="{ '--notes-links-w': ui.notesLinksWidth + 'px' }"
    >
      <div class="min-h-0 flex-1 overflow-y-auto px-4 py-4">
        <LinkPanel
          v-if="selectedDetail"
          :note-id="selectedDetail.id"
          :links="selectedDetail.links"
          @changed="reloadDetail"
          @jump="openLink"
        />
        <p v-else class="text-[12px] leading-5 text-ink-2">
          选择一篇笔记后，在这里管理它的知识联系：关联讲到的网课片段、做过的题目、相关笔记，并可附一句关联说明。
        </p>
      </div>
      <!-- 分隔竖线 + 拖拽调宽手柄（左缘，240~480 持久化） -->
      <div
        class="absolute inset-y-0 left-0 z-10 hidden w-[3px] cursor-col-resize bg-line transition-colors hover:bg-primary lg:block"
        title="拖拽调整宽度"
        @mousedown="startResize($event, 'links')"
      ></div>
    </aside>
  </div>
</template>

<style scoped>
.note-view :deep(.ts-chip) {
  display: inline-flex;
  align-items: center;
  margin-left: 0.25rem;
  padding: 0.05rem 0.4rem;
  border-radius: 0.375rem;
  background: #eff6ff;
  color: #2563eb;
  font-size: 12px;
  cursor: pointer;
  vertical-align: middle;
}
.note-view :deep(p) {
  margin-bottom: 0.75rem;
}
.note-view :deep(table) {
  width: 100%;
  border-collapse: collapse;
  margin: 0.75rem 0;
  font-size: 13px;
}
.note-view :deep(th),
.note-view :deep(td) {
  border: 1px solid #e5e7eb;
  padding: 0.4rem 0.75rem;
  text-align: left;
  vertical-align: top;
}
.note-view :deep(th) {
  background: #f9fafb;
  font-weight: 600;
}
</style>
