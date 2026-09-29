<script setup lang="ts">
import { computed, nextTick, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  Bold, Italic, Underline, Paintbrush, Highlighter, Eraser, Save, X,
  NotebookPen, MonitorPlay, Camera, Plus, Download, PencilLine, Sparkles,
  ArrowLeft, FolderPlus, FolderInput, FolderTree,
} from 'lucide-vue-next'
import TreeNode from '../components/notes/TreeNode.vue'
import type { TreeNodeData } from '../types/notes'

/**
 * 笔记整理（PRD §3.4 OneNote 式页面管理）：
 * 分层树形结构（分组自定义、最多 5 层，笔记为叶子）；知识联系跳转网课 / 题目 / 笔记；
 * 富文本在线编辑。TODO(切片三/四)：数据接入后端 note 接口，当前为假数据审阅版。
 */
interface KnowledgeLink {
  type: 'course' | 'question' | 'note'
  label: string
  target: string
  ts?: string
}
interface NoteItem {
  text: string
  ts?: string
}
interface NotePage {
  id: number
  title: string
  source: 'AI 生成' | '手动创建'
  updatedAt: string
  sections: { heading: string; items: NoteItem[] }[]
  links: KnowledgeLink[]
}

// ---- 笔记内容（叶子） ----
const notes = ref<NotePage[]>([
  {
    id: 1, title: '罗尔定理', source: 'AI 生成', updatedAt: '2026-09-26 21:02',
    sections: [
      { heading: '定理内容', items: [
        { text: '条件：f(x) 在 [a,b] 上连续，在 (a,b) 内可导，且 f(a)=f(b)。', ts: '08:24' },
        { text: '结论：存在 ξ∈(a,b)，使得 f\'(ξ)=0。' },
      ] },
      { heading: '几何意义', items: [{ text: '两端等高的连续光滑曲线上，至少存在一点切线水平。', ts: '11:02' }] },
      { heading: '使用注意', items: [{ text: '三个条件缺一不可，解题前必须逐条验证，尤其是端点函数值相等。', ts: '38:16' }] },
    ],
    links: [
      { type: 'course', label: '网课：第 12 讲 微分中值定理', target: '/courses/1', ts: '08:24' },
      { type: 'question', label: '题目：验证 f(x)=x²-x 在 [0,1] 上满足罗尔定理', target: '/questions' },
      { type: 'note', label: '笔记：拉格朗日中值定理', target: '2' },
    ],
  },
  {
    id: 2, title: '拉格朗日中值定理', source: 'AI 生成', updatedAt: '2026-09-26 21:15',
    sections: [
      { heading: '定理内容', items: [{ text: '去掉罗尔定理 f(a)=f(b) 的限制：f(b)-f(a)=f\'(ξ)(b-a)，ξ∈(a,b)。', ts: '21:40' }] },
      { heading: '与罗尔定理的关系', items: [{ text: '构造辅助函数 F(x)=f(x)-f(a)-(f(b)-f(a))/(b-a)·(x-a)，对 F 用罗尔定理即得。' }] },
    ],
    links: [
      { type: 'note', label: '笔记：罗尔定理', target: '1' },
      { type: 'course', label: '网课：第 12 讲 微分中值定理', target: '/courses/1', ts: '21:40' },
    ],
  },
  {
    id: 3, title: '布尔逻辑与逻辑门', source: 'AI 生成', updatedAt: '2026-09-27 22:40',
    sections: [
      { heading: '三种基本门电路', items: [
        { text: 'AND（与）：全部输入为 1 才输出 1。' },
        { text: 'OR（或）：任一输入为 1 即输出 1。' },
        { text: 'NOT（非）：输入取反。', ts: '04:18' },
      ] },
      { heading: '为什么计算机用二进制', items: [{ text: '二进制只需要区分高低电平，抗干扰能力强，物理实现最可靠。', ts: '07:30' }] },
    ],
    links: [{ type: 'course', label: '网课：第 3 讲 布尔逻辑与逻辑门', target: '/courses/3', ts: '04:18' }],
  },
  {
    id: 4, title: '矩阵的秩与线性方程组', source: 'AI 生成', updatedAt: '2026-09-28 10:20',
    sections: [
      { heading: '秩的定义', items: [{ text: '矩阵中非零子式的最高阶数称为矩阵的秩，记作 r(A)。' }] },
      { heading: '线性方程组解的判定', items: [{ text: 'r(A)=r(A|b) 且 r=n 时有唯一解；r<n 时有无穷多解；r(A)≠r(A|b) 时无解。' }] },
    ],
    links: [],
  },
])

// ---- 分层树（最多 5 层） ----
const MAX_LEVELS = 5

const tree = ref<TreeNodeData[]>([
  {
    id: 'g1', name: '数学', type: 'group', children: [
      {
        id: 'g1-1', name: '大一上', type: 'group', children: [
          {
            id: 'g1-1-1', name: '高等数学', type: 'group', children: [
              { id: 'note-1', name: '罗尔定理', type: 'note', noteId: 1 },
              { id: 'note-2', name: '拉格朗日中值定理', type: 'note', noteId: 2 },
            ],
          },
          { id: 'g1-1-2', name: '线性代数', type: 'group', children: [
            { id: 'note-4', name: '矩阵的秩与线性方程组', type: 'note', noteId: 4 },
          ] },
        ],
      },
      { id: 'g1-2', name: '大一下', type: 'group', children: [] },
    ],
  },
  {
    id: 'g2', name: '计算机科学', type: 'group', children: [
      { id: 'note-3', name: '布尔逻辑与逻辑门', type: 'note', noteId: 3 },
    ],
  },
])

const expanded = reactive(new Set<string>(['g1', 'g1-1', 'g1-1-1']))
const targetGroupId = ref<string | null>(null)
const creatingType = ref<'group' | 'note' | null>(null)
const newName = ref('')
const renamingId = ref<string | null>(null)
const renameValue = ref('')

let groupSeq = 100

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

function removeNode(nodes: TreeNodeData[], id: string): boolean {
  const idx = nodes.findIndex((n) => n.id === id)
  if (idx >= 0) {
    nodes.splice(idx, 1)
    return true
  }
  for (const n of nodes) {
    if (n.children && removeNode(n.children, id)) {
      return true
    }
  }
  return false
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

function onCreate(type: 'group' | 'note', groupId: string) {
  targetGroupId.value = groupId
  startCreate(type)
}

function startCreate(type: 'group' | 'note') {
  // 层级校验：目标分组之下的层级不能超过 5 层
  if (targetGroupId.value) {
    const parentDepth = depthOf(targetGroupId.value)
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

function confirmCreate() {
  const name = newName.value.trim()
  if (!name || !creatingType.value) {
    creatingType.value = null
    return
  }
  const parent = targetGroupId.value ? findNode(tree.value, targetGroupId.value) : null
  const siblings = parent ? parent.children! : tree.value

  if (creatingType.value === 'group') {
    siblings.push({ id: 'g' + ++groupSeq, name, type: 'group', children: [] })
    if (parent) {
      expanded.add(parent.id)
    }
  } else {
    const note: NotePage = {
      id: Math.max(...notes.value.map((n) => n.id)) + 1,
      title: name,
      source: '手动创建',
      updatedAt: new Date().toISOString().slice(0, 16).replace('T', ' '),
      sections: [{ heading: '要点', items: [{ text: '' }] }],
      links: [],
    }
    notes.value.push(note)
    siblings.push({ id: 'note-' + note.id, name, type: 'note', noteId: note.id })
    if (parent) {
      expanded.add(parent.id)
    }
    selectedId.value = note.id
    startEdit()
  }
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

function confirmRename() {
  if (renamingId.value) {
    const node = findNode(tree.value, renamingId.value)
    const name = renameValue.value.trim()
    if (node && name) {
      node.name = name
      if (node.type === 'note' && node.noteId) {
        const page = notes.value.find((n) => n.id === node.noteId)
        if (page) {
          page.title = name
        }
      }
    }
  }
  renamingId.value = null
}

function deleteGroup(id: string) {
  const node = findNode(tree.value, id)
  if (node && node.children && node.children.length > 0) {
    alert('分组不为空，请先清空其中的内容')
    return
  }
  removeNode(tree.value, id)
  if (targetGroupId.value === id) {
    targetGroupId.value = null
  }
}

/** 移动笔记到目标分组（层级校验） */
function moveNote(noteId: number, destGroupId: string) {
  const leafId = 'note-' + noteId
  const dest = destGroupId === 'root' ? null : findNode(tree.value, destGroupId)
  if (dest && dest.type !== 'group') {
    return
  }
  if (dest && depthOf(dest.id) + 1 > MAX_LEVELS) {
    alert('最多支持 ' + MAX_LEVELS + ' 层，无法移动到该分组')
    return
  }
  const page = notes.value.find((n) => n.id === noteId)
  removeNode(tree.value, leafId)
  const leaf: TreeNodeData = { id: leafId, name: page!.title, type: 'note', noteId }
  if (dest) {
    dest.children!.push(leaf)
    expanded.add(dest.id)
  } else {
    tree.value.push(leaf)
  }
}

/** 笔记当前所在分组路径 */
function noteLocation(noteId: number): string {
  let location = '根目录'
  const walk = (nodes: TreeNodeData[], trail: string[]): boolean => {
    for (const n of nodes) {
      trail.push(n.name)
      if (n.type === 'note' && n.noteId === noteId) {
        location = trail.join(' / ')
        return true
      }
      if (n.children && walk(n.children, trail)) {
        return true
      }
      trail.pop()
    }
    return false
  }
  walk(tree.value, [])
  return location
}

/** 分组完整路径 */
function groupPathOf(id: string): string {
  const path: string[] = []
  const walk = (nodes: TreeNodeData[], trail: string[]): boolean => {
    for (const n of nodes) {
      trail.push(n.name)
      if (n.id === id) {
        path.push(...trail)
        return true
      }
      if (n.children && walk(n.children, trail)) {
        return true
      }
      trail.pop()
    }
    return false
  }
  walk(tree.value, [])
  return path.join(' / ')
}

const targetGroupPath = computed(() => targetGroupId.value ? groupPathOf(targetGroupId.value) : '根目录')

/** 分组下拉选项（含路径与深度） */
const groupOptions = computed(() => {
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

// ---- 选中与详情 ----
const selectedId = ref<number | null>(null)
const activeTab = ref<'note' | 'links'>('note')
const router = useRouter()
const editedHtmlByNote = reactive(new Map<number, string>())

const selectedNote = computed(() => notes.value.find((n) => n.id === selectedId.value) ?? null)

function openNote(id: number) {
  selectedId.value = id
  activeTab.value = 'note'
}

function openLink(link: KnowledgeLink) {
  if (link.type === 'note') {
    const id = Number(link.target)
    if (notes.value.some((n) => n.id === id)) {
      selectedId.value = id
      activeTab.value = 'note'
    }
    return
  }
  const target = link.ts ? `${link.target}?t=${link.ts}` : link.target
  router.push(target)
}

// ---- 富文本编辑 ----
const editing = ref(false)
const editedHtml = ref('')
const editorRef = ref<HTMLDivElement | null>(null)

function escapeHtml(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

function noteToHtml(n: NotePage): string {
  const parts: string[] = [`<p><strong>${escapeHtml(n.title)}</strong></p>`]
  for (const section of n.sections) {
    parts.push(`<p><strong>${escapeHtml(section.heading)}</strong></p>`)
    for (const item of section.items) {
      const chip = item.ts
        ? `<span class="ts-chip" data-ts="${item.ts}" contenteditable="false">${item.ts}</span>`
        : ''
      parts.push(`<p>${escapeHtml(item.text)} ${chip}</p>`)
    }
  }
  return parts.join('')
}

function startEdit() {
  if (!selectedNote.value) {
    return
  }
  editedHtml.value = editedHtmlByNote.get(selectedNote.value.id) || noteToHtml(selectedNote.value)
  editing.value = true
  nextTick(() => {
    if (editorRef.value) {
      editorRef.value.innerHTML = editedHtml.value
      editorRef.value.focus()
    }
  })
}

function saveEdit() {
  if (editorRef.value && selectedNote.value) {
    editedHtmlByNote.set(selectedNote.value.id, editorRef.value.innerHTML)
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

function onNoteClick(e: MouseEvent) {
  const chip = (e.target as HTMLElement).closest('[data-ts]')
  if (chip) {
    window.location.href = `/courses/1?t=${chip.getAttribute('data-ts')}`
  }
}
</script>

<template>
  <div class="flex h-full overflow-hidden">
    <!-- 左：分层树 -->
    

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
          <span class="truncate">所在位置：{{ noteLocation(selectedNote.id) }}</span>
          <select
            class="ml-auto shrink-0 rounded-lg border border-line bg-white px-2 py-1 text-[12px] text-ink outline-none"
            @change="moveNote(selectedNote.id, ($event.target as HTMLSelectElement).value)"
          >
            <option value="" disabled selected>移动到分组…</option>
            <option value="root">（根目录）</option>
            <option v-for="g in groupOptions" :key="g.id" :value="g.id">
              {{ '　'.repeat(g.depth - 1) + g.label }}
            </option>
          </select>
        </div>

        <!-- 标签 -->
        <div class="flex shrink-0 gap-1 border-b border-line bg-panel px-4 pt-2 pb-2">
          <button
            class="rounded-lg px-4 py-1.5 text-[14px]"
            :class="activeTab === 'note' ? 'bg-white font-medium text-ink shadow-sm' : 'text-ink-2 hover:text-ink'"
            @click="activeTab = 'note'"
          >
            笔记内容
          </button>
          <button
            class="rounded-lg px-4 py-1.5 text-[14px]"
            :class="activeTab === 'links' ? 'bg-white font-medium text-ink shadow-sm' : 'text-ink-2 hover:text-ink'"
            @click="activeTab = 'links'"
          >
            知识联系
          </button>
        </div>

        <div class="min-h-0 flex-1 overflow-y-auto">
          <!-- 笔记内容 -->
          <template v-if="activeTab === 'note'">
            <div v-if="editing" class="ml-6 max-w-3xl px-4 pt-4">
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
              <div v-if="editedHtmlByNote.get(selectedNote.id)" class="note-view text-[14px] leading-7 text-ink" v-html="editedHtmlByNote.get(selectedNote.id)" />
              <div v-else class="flex flex-col gap-5 rounded-2xl border border-line bg-white p-5">
                <div v-for="section in selectedNote.sections" :key="section.heading">
                  <p class="text-[15px] font-semibold text-ink">{{ section.heading }}</p>
                  <ul class="mt-2 flex flex-col gap-2">
                    <li v-for="(item, i) in section.items" :key="i" class="text-[14px] leading-7 text-ink">
                      {{ item.text }}
                      <button
                        v-if="item.ts"
                        class="ml-1 inline-flex items-center rounded-md bg-primary-soft px-1.5 py-0.5 align-middle text-[12px] text-primary hover:underline"
                        @click.stop="router.push(`/courses/1?t=${item.ts}`)"
                      >
                        {{ item.ts }}
                      </button>
                    </li>
                  </ul>
                </div>
              </div>
              <p v-if="editedHtmlByNote.get(selectedNote.id)" class="mt-2 text-[12px] text-ink-2">
                点击文中的时间戳可跳转网课对应位置核对。
              </p>
            </div>

            <!-- 知识联系 -->
            <div class="ml-6 max-w-3xl px-4 pb-6">
              <div class="rounded-2xl border border-line bg-panel px-4 py-3">
                <p class="text-[13px] font-semibold text-ink">知识联系</p>
                <div class="mt-2 flex flex-wrap gap-2">
                  <button
                    v-for="link in selectedNote.links"
                    :key="link.label"
                    class="flex items-center gap-1.5 rounded-full border border-line bg-white px-3 py-1.5 text-[13px] text-ink hover:border-primary hover:text-primary"
                    @click="openLink(link)"
                  >
                    <MonitorPlay v-if="link.type === 'course'" :size="14" class="text-ink-2" />
                    <Camera v-else-if="link.type === 'question'" :size="14" class="text-ink-2" />
                    <NotebookPen v-else :size="14" class="text-ink-2" />
                    {{ link.label }}
                    <span v-if="link.ts" class="text-[11px] text-ink-2">{{ link.ts }}</span>
                  </button>
                  <span v-if="selectedNote.links.length === 0" class="text-[12px] text-ink-2">暂无知识联系</span>
                </div>
              </div>
            </div>
          </template>

          <!-- 知识联系（独立标签页） -->
          <div v-if="activeTab === 'links'" class="ml-6 max-w-3xl px-4 py-5">
            <div class="flex flex-col gap-2 rounded-2xl border border-line bg-white p-5">
              <button
                v-for="link in selectedNote.links"
                :key="link.label"
                class="flex items-center gap-3 rounded-xl px-3 py-2.5 text-left text-[14px] text-ink hover:bg-panel"
                @click="openLink(link)"
              >
                <MonitorPlay v-if="link.type === 'course'" :size="16" class="text-ink-2" />
                <Camera v-else-if="link.type === 'question'" :size="16" class="text-ink-2" />
                <NotebookPen v-else :size="16" class="text-ink-2" />
                <span class="flex-1 truncate">{{ link.label }}</span>
                <span v-if="link.ts" class="text-[12px] text-primary">{{ link.ts }}</span>
              </button>
              <p v-if="selectedNote.links.length === 0" class="text-[13px] text-ink-2">暂无知识联系，可在编辑中添加关联的网课 / 题目 / 笔记</p>
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

    <!-- 右：笔记分层树 -->
    <aside class="w-72 shrink-0 flex-col border-l border-line bg-white md:flex" :class="selectedId === null ? 'flex' : 'hidden'">
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
        新建位置：{{ targetGroupPath }}
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
