<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import DOMPurify from 'dompurify'
import {
  Bold, Italic, Underline, Paintbrush, Highlighter, Eraser, Save, X,
  NotebookPen, MonitorPlay, Camera, Plus, Download, PencilLine, Sparkles,
  ArrowLeft, FolderPlus, FolderInput, FolderTree, ListPlus,
} from 'lucide-vue-next'
import TreeNode from '../components/notes/TreeNode.vue'
import MdSourceEditor from '../components/notes/MdSourceEditor.vue'
import type { TreeNodeData } from '../types/notes'
import {
  addNoteLink, createGroup, createNote, deleteNote, getNoteDetail, getNoteTree,
  moveNote, removeNoteLink, renameNote, updateNoteContent,
} from '../api/note'
import type { NoteDetailInfo, NoteTreeNodeInfo } from '../api/note'
import { renderMarkdown } from '../utils/markdown'
import { listCourses } from '../api/course'
import type { CourseInfo } from '../api/course'

/**
 * 笔记整理（PRD §3.4 OneNote 式页面管理）：
 * 分层树（分组自定义、最多 5 层）+ 知识联系（网课 / 题目 / 笔记）+ 富文本编辑。
 * 数据来自后端 /notes 接口。
 */
const MAX_LEVELS = 5

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
  targetGroupId.value = id
}

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
  const parentNumeric = numericGroupId()
  if (parentNumeric !== null) {
    const parentDepth = depthOf(targetGroupId.value!)
    if (parentDepth + 1 > MAX_LEVELS) {
      alert('最多支持 ' + MAX_LEVELS + ' 层，无法在更深层继续创建')
      return
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

async function deleteGroup(id: string) {
  const node = findNode(tree.value, id)
  if (node && node.children && node.children.length > 0) {
    alert('分组不为空，请先清空其中的内容')
    return
  }
  await deleteNote(Number(id.slice(1)))
  if (targetGroupId.value === id) {
    targetGroupId.value = null
  }
  await loadTree()
}

// ---- 详情 ----
const selectedId = ref<number | null>(null)
const selectedDetail = ref<NoteDetailInfo | null>(null)
const activeTab = ref<'note' | 'links'>('note')
const router = useRouter()
const editedHtmlByNote = reactive(new Map<number, string>())

const selectedNote = computed(() => selectedDetail.value)

async function openNote(id: number) {
  selectedDetail.value = await getNoteDetail(id)
  selectedId.value = id
  activeTab.value = 'note'
}

function openLink(link: { linkType: string; targetId: number; tsSec?: number | null }) {
  if (link.linkType === 'note') {
    openNote(link.targetId)
    return
  }
  if (link.linkType === 'course') {
    router.push(link.tsSec != null ? `/courses/${link.targetId}?t=${formatTs(link.tsSec)}` : `/courses/${link.targetId}`)
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

function escapeHtml(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

function detailToHtml(d: NoteDetailInfo): string {
  const parts: string[] = [`<p><strong>${escapeHtml(d.title)}</strong></p>`]
  parts.push(renderMarkdown(d.content))
  return parts.join('')
}

function startEdit() {
  if (!selectedDetail.value) {
    return
  }
  // AI 笔记：直接编辑 Markdown 源码；手动笔记：先切编辑态让富文本编辑器挂载，再在 nextTick 中灌入内容
  if (selectedDetail.value.source === 'AI 生成') {
    mdDraft.value = selectedDetail.value.content
    editing.value = true
    return
  }
  editing.value = true
  nextTick(() => {
    if (editorRef.value) {
      editorRef.value.innerHTML = editedHtmlByNote.get(selectedDetail.value!.id) || detailToHtml(selectedDetail.value!)
      editorRef.value.focus()
    }
  })
}

async function saveEdit() {
  if (!selectedDetail.value) {
    editing.value = false
    return
  }
  if (selectedDetail.value.source === 'AI 生成') {
    await updateNoteContent(selectedDetail.value.id, mdDraft.value)
    selectedDetail.value.content = mdDraft.value
  } else if (editorRef.value) {
    const html = editorRef.value.innerHTML
    await updateNoteContent(selectedDetail.value.id, html)
    editedHtmlByNote.set(selectedDetail.value.id, html)
    selectedDetail.value.content = html
  }
  editing.value = false
}

function cancelEdit() {
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
function parseTs(ts: string): number {
  const parts = ts.split(':').map(Number)
  return parts.length === 3
    ? parts[0] * 3600 + parts[1] * 60 + parts[2]
    : parts[0] * 60 + parts[1]
}

function formatTs(sec: number): string {
  const h = Math.floor(sec / 3600)
  const m = Math.floor((sec % 3600) / 60)
  const s = sec % 60
  const mm = String(m).padStart(2, '0')
  const ss = String(s).padStart(2, '0')
  return h > 0 ? `${h}:${mm}:${ss}` : `${mm}:${ss}`
}

function renderWithChips(d: NoteDetailInfo): string {
  const base = d.source === 'AI 生成' ? renderMarkdown(d.content) : DOMPurify.sanitize(d.content)
  const courseSuffix = d.courseTitle ? `（${d.courseTitle}）` : ''
  return base.replace(/\[(\d{1,2}:[0-5]\d(?::\d{2})?)\]/g,
    `<span class="ts-chip" data-ts="$1">$1${courseSuffix}</span>`)
}

const noteHtml = computed(() => {
  const d = selectedDetail.value
  if (!d) {
    return ''
  }
  const cached = editedHtmlByNote.get(d.id)
  return renderWithChips({ ...d, content: cached ?? d.content })
})

function onNoteClick(e: MouseEvent) {
  const chip = (e.target as HTMLElement).closest('[data-ts]')
  if (chip && selectedDetail.value?.courseId) {
    router.push(`/courses/${selectedDetail.value.courseId}?t=${chip.getAttribute('data-ts')}`)
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

function onTabChange(tab: 'note' | 'links') {
  activeTab.value = tab
}

// ---- 知识联系：添加 / 删除 ----
const showLinkForm = ref(false)
const linkType = ref<'course' | 'question' | 'note'>('course')
const linkTargetId = ref<number | null>(null)
const linkTs = ref('')
const linkError = ref('')
const courseOptions = ref<CourseInfo[]>([])
const noteOptions = computed(() => {
  const options: { id: number; title: string }[] = []
  const walk = (nodes: TreeNodeData[]) => {
    for (const n of nodes) {
      if (n.type === 'note' && n.noteId !== undefined && n.noteId !== selectedDetail.value?.id) {
        options.push({ id: n.noteId, title: n.name })
      }
      walk(n.children ?? [])
    }
  }
  walk(tree.value)
  return options
})

async function openLinkForm() {
  linkType.value = 'course'
  linkTargetId.value = null
  linkTs.value = ''
  linkError.value = ''
  showLinkForm.value = true
  if (courseOptions.value.length === 0) {
    try {
      courseOptions.value = await listCourses()
    } catch {
      courseOptions.value = []
    }
  }
}

async function submitLink() {
  if (!selectedDetail.value) {
    return
  }
  if (linkTargetId.value === null) {
    linkError.value = '请选择或填写关联目标'
    return
  }
  let tsSec: number | null = null
  if (linkTs.value.trim()) {
    if (!/^\d{1,2}:[0-5]\d(:\d{2})?$/.test(linkTs.value.trim())) {
      linkError.value = '时间戳格式应为 mm:ss'
      return
    }
    tsSec = parseTs(linkTs.value.trim())
  }
  try {
    await addNoteLink(selectedDetail.value.id, linkType.value, linkTargetId.value, tsSec)
    showLinkForm.value = false
    selectedDetail.value = await getNoteDetail(selectedDetail.value.id)
  } catch (e) {
    linkError.value = e instanceof Error ? e.message : '添加失败'
  }
}

async function removeLink(linkId: number) {
  if (!selectedDetail.value) {
    return
  }
  await removeNoteLink(selectedDetail.value.id, linkId)
  selectedDetail.value = await getNoteDetail(selectedDetail.value.id)
}

onMounted(async () => {
  await loadTree()
})

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
  <div class="flex h-full overflow-hidden">
    <!-- 左：分层树 -->
    <aside
      class="w-72 shrink-0 flex-col border-l border-line bg-white md:flex"
      :class="selectedId === null ? 'flex' : 'hidden'"
    >
      <div class="flex items-center justify-between px-4 pt-5 pb-2">
        <p class="flex items-center gap-1.5 text-[14px] font-semibold text-ink">
          <FolderTree :size="16" class="text-ink-2" />
          笔记分层
        </p>
        <div class="flex gap-1">
          <button class="flex h-7 w-7 items-center justify-center rounded-lg text-ink hover:bg-line/60" title="在当前分组下新建分组" @click="startCreate('group')">
            <FolderPlus :size="16" />
          </button>
          <button class="flex h-7 w-7 items-center justify-center rounded-lg text-ink hover:bg-line/60" title="在当前分组下新建笔记" @click="startCreate('note')">
            <Plus :size="16" />
          </button>
        </div>
      </div>
      <p class="px-4 pb-2 text-[11px] text-ink-2">
        新建位置：{{ targetGroupId ? numericGroupId() ?? '' : '根目录' }}（最多 5 层）
      </p>

      <!-- 内联新建输入 -->
      <div v-if="creatingType" class="mx-3 mb-2 flex items-center gap-1.5 rounded-xl border border-primary bg-white px-2.5 py-1.5">
        <NotebookPen :size="14" class="text-ink-2" />
        <input
          id="new-name-input"
          v-model="newName"
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
        />
      </div>
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
            <button class="flex items-center gap-1.5 rounded-xl border border-line px-3 py-1.5 text-[13px] text-ink hover:bg-panel">
              <Sparkles :size="15" />
              AI 润色
            </button>
            <button
              class="flex items-center gap-1.5 rounded-xl border px-3 py-1.5 text-[13px] hover:bg-panel"
              :class="editing ? 'border-primary text-primary' : 'border-line text-ink'"
              @click="editing ? cancelEdit() : startEdit()"
            >
              <PencilLine :size="15" />
              {{ editing ? '取消' : '编辑' }}
            </button>
            <button class="hidden items-center gap-1.5 rounded-xl border border-line px-3 py-1.5 text-[13px] text-ink hover:bg-panel sm:flex">
              <Download :size="15" />
              导出
            </button>
          </div>
        </div>

        <!-- 移动到分组 -->
        <div class="flex shrink-0 items-center gap-2 border-b border-line px-4 py-2 text-[12px] text-ink-2">
          <FolderInput :size="14" />
          <select
            class="ml-auto shrink-0 rounded-lg border border-line bg-white px-2 py-1 text-[12px] text-ink outline-none"
            @change="onMoveChange"
          >
            <option value="" disabled selected>移动到分组…</option>
            <option value="0">（根目录）</option>
            <option v-for="g in groupPathOptions" :key="g.id" :value="g.id">
              {{ '　'.repeat(g.depth - 1) + g.label }}
            </option>
          </select>
        </div>

        <!-- 标签 -->
        <div class="flex shrink-0 gap-1 border-b border-line bg-panel px-4 pt-2 pb-2">
          <button
            class="rounded-lg px-4 py-1.5 text-[14px]"
            :class="activeTab === 'note' ? 'bg-white font-medium text-ink shadow-sm' : 'text-ink-2 hover:text-ink'"
            @click="onTabChange('note')"
          >
            笔记内容
          </button>
          <button
            class="rounded-lg px-4 py-1.5 text-[14px]"
            :class="activeTab === 'links' ? 'bg-white font-medium text-ink shadow-sm' : 'text-ink-2 hover:text-ink'"
            @click="onTabChange('links')"
          >
            知识联系
          </button>
        </div>

        <div class="min-h-0 flex-1 overflow-y-auto">
          <!-- 笔记内容 -->
          <template v-if="activeTab === 'note'">
            <!-- AI 笔记：Markdown 源码编辑（存 Markdown，保证详情页渲染与时间戳胶囊一致） -->
            <div v-if="editing && isAiNote" class="ml-6 max-w-3xl px-4 pt-4">
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

            <div v-else-if="editing" class="ml-6 max-w-3xl px-4 pt-4">
              <div class="mb-3 flex flex-wrap items-center gap-1.5 rounded-xl border border-line bg-panel px-2.5 py-2">
                <button class="flex h-8 w-8 items-center justify-center rounded-lg text-ink hover:bg-line/60" title="加粗" @mousedown.prevent @click="exec('bold')">
                  <Bold :size="15" />
                </button>
                <button class="flex h-8 w-8 items-center justify-center rounded-lg text-ink hover:bg-line/60" title="斜体" @mousedown.prevent @click="exec('italic')">
                  <Italic :size="15" />
                </button>
                <button class="flex h-8 w-8 items-center justify-center rounded-lg text-ink hover:bg-line/60" title="下划线" @mousedown.prevent @click="exec('underline')">
                  <Underline :size="15" />
                </button>
                <select class="h-8 rounded-lg border border-line bg-white px-1.5 text-[13px] text-ink outline-none" title="字号" @change="onFontSizeChange">
                  <option value="2">字号 小</option>
                  <option value="3" selected>字号 标准</option>
                  <option value="5">字号 大</option>
                  <option value="7">字号 特大</option>
                </select>
                <label class="flex h-8 w-8 cursor-pointer items-center justify-center rounded-lg text-ink hover:bg-line/60" title="字体颜色">
                  <Paintbrush :size="15" />
                  <input type="color" class="sr-only" value="#0d0d0d" @input="onColorChange" />
                </label>
                <label class="flex h-8 w-8 cursor-pointer items-center justify-center rounded-lg text-ink hover:bg-line/60" title="背景高亮">
                  <Highlighter :size="15" />
                  <input type="color" class="sr-only" value="#fff3c4" @input="onHighlightChange" />
                </label>
                <button class="flex h-8 w-8 items-center justify-center rounded-lg text-ink hover:bg-line/60" title="清除格式" @mousedown.prevent @click="exec('removeFormat')">
                  <Eraser :size="15" />
                </button>
                <span class="mx-1 h-5 w-px bg-line" />
                <button class="flex items-center gap-1 rounded-lg bg-ink px-3 py-1.5 text-[13px] text-white hover:opacity-80" @click="saveEdit">
                  <Save :size="14" />
                  保存
                </button>
                <button class="flex items-center gap-1 rounded-lg border border-line px-3 py-1.5 text-[13px] text-ink-2 hover:bg-line/40" @click="cancelEdit">
                  <X :size="14" />
                  放弃
                </button>
              </div>
              <div
                ref="editorRef"
                class="note-view min-h-[400px] whitespace-pre-wrap rounded-2xl border border-primary bg-white p-5 text-[14px] leading-7 text-ink outline-none"
                contenteditable="true"
                @click="onNoteClick"
              />
            </div>

            <div v-else class="ml-6 max-w-3xl px-4 py-5" @click="onNoteClick">
              <div class="note-view text-[14px] leading-7 text-ink" v-html="noteHtml" />
              <p v-if="selectedNote.courseId" class="mt-2 text-[12px] text-ink-2">
                点击文中的时间戳可跳转网课对应位置核对。
              </p>
            </div>

            <!-- 知识联系 -->
            <div class="ml-6 max-w-3xl px-4 pb-6">
              <div class="rounded-2xl border border-line bg-panel px-4 py-3">
                <p class="text-[13px] font-semibold text-ink">知识联系</p>
                <div class="mt-2 flex flex-wrap gap-2">
                  <span
                    v-for="link in selectedNote.links"
                    :key="link.id"
                    class="flex cursor-pointer items-center gap-1.5 rounded-full border border-line bg-white px-3 py-1.5 text-[13px] text-ink hover:border-primary hover:text-primary"
                    @click="openLink(link)"
                  >
                    <MonitorPlay v-if="link.linkType === 'course'" :size="14" class="text-ink-2" />
                    <Camera v-else-if="link.linkType === 'question'" :size="14" class="text-ink-2" />
                    <NotebookPen v-else :size="14" class="text-ink-2" />
                    {{ link.title }}
                    <span v-if="link.tsSec != null" class="text-[11px] text-ink-2">{{ formatTs(link.tsSec) }}</span>
                    <button class="text-ink-2 hover:text-red-500" title="删除知识联系" @click="removeLink(link.id)">
                      <X :size="12" />
                    </button>
                  </span>
                  <button
                    class="flex items-center gap-1 rounded-full border border-dashed border-line px-3 py-1.5 text-[13px] text-ink-2 hover:border-primary hover:text-primary"
                    @click="openLinkForm"
                  >
                    <ListPlus :size="14" />
                    添加知识联系
                  </button>
                </div>
              </div>
            </div>
          </template>

          <!-- 知识联系（独立标签页） -->
          <div v-if="activeTab === 'links'" class="ml-6 max-w-3xl px-4 py-5">
            <div class="flex flex-col gap-2 rounded-2xl border border-line bg-white p-5">
              <div v-for="link in selectedNote.links" :key="link.id" class="flex cursor-pointer items-center gap-3 rounded-xl px-3 py-2.5 text-left text-[14px] text-ink hover:bg-panel" @click="openLink(link)">
                <MonitorPlay v-if="link.linkType === 'course'" :size="16" class="text-ink-2" />
                <Camera v-else-if="link.linkType === 'question'" :size="16" class="text-ink-2" />
                <NotebookPen v-else :size="16" class="text-ink-2" />
                <span class="flex-1 truncate">{{ link.title }}</span>
                <span v-if="link.tsSec != null" class="text-[12px] text-primary">{{ formatTs(link.tsSec) }}</span>
                <button class="text-ink-2 hover:text-red-500" title="删除" @click="removeLink(link.id)">
                  <X :size="14" />
                </button>
              </div>
              <p v-if="selectedNote.links.length === 0" class="text-[13px] text-ink-2">暂无知识联系，点击下方按钮添加关联的网课 / 题目 / 笔记</p>
              <button
                class="flex items-center justify-center gap-1 rounded-xl border border-dashed border-line py-2 text-[13px] text-ink-2 hover:border-primary hover:text-primary"
                @click="openLinkForm"
              >
                <ListPlus :size="15" />
                添加知识联系
              </button>
            </div>

            <!-- 添加表单 -->
            <div v-if="showLinkForm" class="mt-3 rounded-2xl border border-primary bg-white p-4">
              <div class="flex flex-col gap-3">
                <div>
                  <label class="mb-1 block text-[12px] text-ink-2">类型</label>
                  <select v-model="linkType" class="w-full rounded-lg border border-line bg-white px-2 py-1.5 text-[13px] text-ink outline-none">
                    <option value="course">网课</option>
                    <option value="question">题目</option>
                    <option value="note">笔记</option>
                  </select>
                </div>
                <div v-if="linkType === 'course'">
                  <label class="mb-1 block text-[12px] text-ink-2">选择网课</label>
                  <select v-model.number="linkTargetId" class="w-full rounded-lg border border-line bg-white px-2 py-1.5 text-[13px] text-ink outline-none">
                    <option :value="null" disabled>选择网课</option>
                    <option v-for="c in courseOptions" :key="c.id" :value="c.id">{{ c.title }}</option>
                  </select>
                </div>
                <div v-else-if="linkType === 'note'">
                  <label class="mb-1 block text-[12px] text-ink-2">选择笔记</label>
                  <select v-model.number="linkTargetId" class="w-full rounded-lg border border-line bg-white px-2 py-1.5 text-[13px] text-ink outline-none">
                    <option :value="null" disabled>选择笔记</option>
                    <option v-for="n in noteOptions" :key="n.id" :value="n.id">{{ n.title }}</option>
                  </select>
                </div>
                <div v-else>
                  <label class="mb-1 block text-[12px] text-ink-2">题目 ID</label>
                  <input v-model.number="linkTargetId" type="number" class="w-full rounded-lg border border-line px-2 py-1.5 text-[13px] outline-none focus:border-primary" placeholder="输入拍照记录中的题目 ID" />
                </div>
                <div v-if="linkType === 'course'">
                  <label class="mb-1 block text-[12px] text-ink-2">跳转时间戳（可选，如 04:18）</label>
                  <input v-model="linkTs" type="text" class="w-full rounded-lg border border-line px-2 py-1.5 text-[13px] outline-none focus:border-primary" placeholder="mm:ss" />
                </div>
                <p v-if="linkError" class="text-[12px] text-red-600">{{ linkError }}</p>
                <button class="rounded-xl bg-primary py-2 text-[13px] text-white hover:opacity-90" @click="submitLink">添加</button>
              </div>
            </div>
          </div>
        </div>
      </template>

      <!-- 未选择笔记 -->
      <div v-else class="flex h-full flex-col items-center justify-center gap-3">
        <NotebookPen :size="40" class="text-ink-2/50" />
        <p class="text-[15px] text-ink-2">从左侧选择一个笔记页，或新建分组与笔记</p>
      </div>
    </div>
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
</style>
