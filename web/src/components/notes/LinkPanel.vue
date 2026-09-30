<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  Camera, Check, MonitorPlay, NotebookPen, Pencil, Search, SquarePen, Trash2, X,
} from 'lucide-vue-next'
import {
  addNoteLink, getNoteTree, removeNoteLink, updateNoteLinkRemark,
} from '../../api/note'
import type { NoteLinkInfo, NoteTreeNodeInfo } from '../../api/note'
import type { CourseInfo } from '../../api/course'
import { listCourses } from '../../api/course'
import { listQuestions } from '../../api/question'
import type { QuestionRecordInfo } from '../../types/api'

/**
 * 知识联系侧栏（PRD §3.4）：
 * 展示本笔记的知识联系；聚合搜索网课 / 题目 / 笔记就地挂链，
 * 支持可选「关联说明」——标注目标与知识点相关的地方，添加后可补改可删除。
 * 搜索在前端聚合（数据源：课程列表 / 题目前 100 条 / 笔记树叶子），个人学习数据量下够用。
 */
const props = defineProps<{
  noteId: number
  links: NoteLinkInfo[]
}>()

const emit = defineEmits<{
  (e: 'changed'): void
  (e: 'jump', link: NoteLinkInfo): void
}>()

const TYPE_LABEL: Record<string, string> = { course: '网课', question: '题目', note: '笔记' }

function typeIcon(type: string) {
  return type === 'course' ? MonitorPlay : type === 'question' ? Camera : NotebookPen
}

function formatTs(sec: number): string {
  const m = Math.floor(sec / 60)
  const s = sec % 60
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
}

/** 去掉 Markdown / 公式符号后截取摘要（题目搜索结果用） */
function excerpt(text: string | null): string {
  if (!text) {
    return '（无题目文本）'
  }
  const plain = text.replace(/[#*`$\\]/g, ' ').replace(/\s+/g, ' ').trim()
  return plain.length > 40 ? plain.slice(0, 40) + '…' : plain
}

// ---- 列表：删除 / 编辑说明 ----
async function onRemove(link: NoteLinkInfo) {
  if (!window.confirm(`确定删除与「${link.title}」的知识联系吗？`)) {
    return
  }
  await removeNoteLink(props.noteId, link.id)
  emit('changed')
}

const editingId = ref<number | null>(null)
const editDraft = ref('')

function startEditRemark(link: NoteLinkInfo) {
  editingId.value = link.id
  editDraft.value = link.remark ?? ''
}

async function saveEditRemark() {
  if (editingId.value == null) {
    return
  }
  await updateNoteLinkRemark(props.noteId, editingId.value, editDraft.value)
  editingId.value = null
  emit('changed')
}

// ---- 聚合搜索（数据源挂载时加载一次） ----
const keyword = ref('')
const courses = ref<CourseInfo[]>([])
const questions = ref<QuestionRecordInfo[]>([])
const noteOptions = ref<{ id: number; title: string }[]>([])

onMounted(async () => {
  try {
    courses.value = await listCourses()
  } catch {
    courses.value = []
  }
  try {
    questions.value = (await listQuestions({ page: 1, size: 100 })).list
  } catch {
    questions.value = []
  }
  try {
    const leaves: { id: number; title: string }[] = []
    const walk = (nodes: NoteTreeNodeInfo[]) => {
      for (const n of nodes) {
        if (n.type === 'note' && n.id !== props.noteId) {
          leaves.push({ id: n.id, title: n.title })
        }
        walk(n.children ?? [])
      }
    }
    walk(await getNoteTree())
    noteOptions.value = leaves
  } catch {
    noteOptions.value = []
  }
})

const kw = computed(() => keyword.value.trim())
const courseResults = computed(() =>
  kw.value ? courses.value.filter((c) => c.title.includes(kw.value)).slice(0, 5) : []
)
const questionResults = computed(() =>
  kw.value ? questions.value.filter((q) => (q.questionText ?? '').includes(kw.value)).slice(0, 5) : []
)
const noteResults = computed(() =>
  kw.value ? noteOptions.value.filter((n) => n.title.includes(kw.value)).slice(0, 5) : []
)
const hasResults = computed(
  () => courseResults.value.length + questionResults.value.length + noteResults.value.length > 0
)

// ---- 选中目标 → 补充说明 / 时间戳 → 挂链 ----
const selected = ref<{ linkType: 'course' | 'question' | 'note'; targetId: number; title: string } | null>(null)
const remarkDraft = ref('')
const tsDraft = ref('')
const adding = ref(false)
const addError = ref('')

function pick(linkType: 'course' | 'question' | 'note', targetId: number, title: string) {
  selected.value = { linkType, targetId, title }
  remarkDraft.value = ''
  tsDraft.value = ''
  addError.value = ''
}

async function confirmAdd() {
  if (!selected.value) {
    return
  }
  let tsSec: number | null = null
  if (selected.value.linkType === 'course' && tsDraft.value.trim()) {
    const m = tsDraft.value.trim().match(/^(\d{1,2}):([0-5]\d)$/)
    if (!m) {
      addError.value = '时间戳格式应为 分:秒，如 02:27'
      return
    }
    tsSec = Number(m[1]) * 60 + Number(m[2])
  }
  adding.value = true
  addError.value = ''
  try {
    await addNoteLink(props.noteId, selected.value.linkType, selected.value.targetId, tsSec, remarkDraft.value)
    selected.value = null
    keyword.value = ''
    emit('changed')
  } catch (e) {
    addError.value = e instanceof Error ? e.message : '添加失败，请稍后重试'
  } finally {
    adding.value = false
  }
}
</script>

<template>
  <div>
    <!-- 联系列表 -->
    <div class="flex flex-col gap-2">
      <div v-for="link in links" :key="link.id" class="rounded-xl border border-line bg-white px-3 py-2.5">
        <div class="flex items-start gap-2">
          <component :is="typeIcon(link.linkType)" :size="15" class="mt-1 shrink-0 text-ink-2" />
          <button class="min-w-0 flex-1 text-left" :title="TYPE_LABEL[link.linkType]" @click="emit('jump', link)">
            <p class="truncate text-[13px] text-ink hover:text-primary">{{ link.title }}</p>
            <p v-if="link.remark" class="mt-0.5 text-[12px] leading-5 text-ink-2">{{ link.remark }}</p>
          </button>
          <span v-if="link.tsSec != null" class="shrink-0 pt-0.5 text-[12px] text-primary">{{ formatTs(link.tsSec) }}</span>
          <button
            class="flex h-6 w-6 shrink-0 items-center justify-center rounded-md text-ink-2 hover:bg-line/60 hover:text-ink"
            title="编辑说明"
            @click="startEditRemark(link)"
          >
            <Pencil :size="13" />
          </button>
          <button
            class="flex h-6 w-6 shrink-0 items-center justify-center rounded-md text-ink-2 hover:bg-red-50 hover:text-red-600"
            title="删除"
            @click="onRemove(link)"
          >
            <Trash2 :size="13" />
          </button>
        </div>
        <!-- 编辑说明 -->
        <div v-if="editingId === link.id" class="mt-2 flex items-center gap-1.5">
          <input
            v-model="editDraft"
            type="text"
            placeholder="这条联系相关的点…"
            class="min-w-0 flex-1 rounded-lg border border-line px-2 py-1.5 text-[12px] outline-none focus:border-primary"
            @keyup.enter="saveEditRemark"
          />
          <button class="flex h-7 w-7 items-center justify-center rounded-lg bg-primary text-white hover:opacity-90" title="保存说明" @click="saveEditRemark">
            <Check :size="14" />
          </button>
          <button class="flex h-7 w-7 items-center justify-center rounded-lg border border-line text-ink-2 hover:bg-line/60" title="取消" @click="editingId = null">
            <X :size="14" />
          </button>
        </div>
      </div>
      <p v-if="links.length === 0" class="text-[12px] leading-5 text-ink-2">
        还没有知识联系。在下方搜索网课 / 题目 / 笔记，把这个知识点关联起来。
      </p>
    </div>

    <!-- 添加 -->
    <div class="mt-4 border-t border-line pt-3">
      <p class="flex items-center gap-1 text-[12px] font-semibold text-ink-2">
        <SquarePen :size="13" />
        添加关联
      </p>
      <div class="relative mt-2">
        <Search :size="14" class="pointer-events-none absolute left-2.5 top-1/2 -translate-y-1/2 text-ink-2" />
        <input
          v-model="keyword"
          type="text"
          placeholder="搜索网课 / 题目 / 笔记…"
          class="w-full rounded-xl border border-line bg-white py-2 pl-8 pr-3 text-[13px] text-ink outline-none focus:border-primary"
        />
      </div>

      <!-- 选中目标，补充说明后挂链 -->
      <div v-if="selected" class="mt-2 rounded-xl border border-primary bg-primary-soft/40 p-2.5">
        <div class="flex items-center gap-2">
          <span class="shrink-0 rounded border border-line bg-white px-1.5 py-0.5 text-[11px] text-ink-2">{{ TYPE_LABEL[selected.linkType] }}</span>
          <span class="min-w-0 flex-1 truncate text-[13px] text-ink" :title="selected.title">{{ selected.title }}</span>
          <button class="flex h-6 w-6 shrink-0 items-center justify-center rounded-md text-ink-2 hover:bg-white" title="取消" @click="selected = null">
            <X :size="14" />
          </button>
        </div>
        <input
          v-model="remarkDraft"
          type="text"
          placeholder="关联说明（可选）：这里和知识点的关系…"
          class="mt-2 w-full rounded-lg border border-line bg-white px-2 py-1.5 text-[12px] outline-none focus:border-primary"
        />
        <input
          v-if="selected.linkType === 'course'"
          v-model="tsDraft"
          type="text"
          placeholder="跳转时间戳（可选），如 02:27"
          class="mt-1.5 w-full rounded-lg border border-line bg-white px-2 py-1.5 text-[12px] outline-none focus:border-primary"
        />
        <p v-if="addError" class="mt-1 text-[12px] text-red-600">{{ addError }}</p>
        <button
          class="mt-2 w-full rounded-lg bg-primary py-1.5 text-[13px] text-white hover:opacity-90 disabled:opacity-50"
          :disabled="adding"
          @click="confirmAdd"
        >
          {{ adding ? '添加中…' : '挂上这条联系' }}
        </button>
      </div>

      <!-- 搜索结果分组 -->
      <div v-else-if="kw" class="mt-2 flex flex-col gap-1">
        <p v-if="!hasResults" class="px-1 py-2 text-[12px] text-ink-2">没有匹配的网课 / 题目 / 笔记</p>
        <template v-if="courseResults.length">
          <p class="px-1 pt-1 text-[11px] text-ink-2">网课</p>
          <button
            v-for="c in courseResults"
            :key="'c' + c.id"
            class="flex items-center gap-2 rounded-lg px-1 py-1.5 text-left hover:bg-panel"
            @click="pick('course', c.id, c.title)"
          >
            <MonitorPlay :size="14" class="shrink-0 text-ink-2" />
            <span class="min-w-0 flex-1 truncate text-[13px] text-ink">{{ c.title }}</span>
          </button>
        </template>
        <template v-if="questionResults.length">
          <p class="px-1 pt-1 text-[11px] text-ink-2">题目</p>
          <button
            v-for="q in questionResults"
            :key="'q' + q.id"
            class="flex items-center gap-2 rounded-lg px-1 py-1.5 text-left hover:bg-panel"
            @click="pick('question', q.id, excerpt(q.questionText))"
          >
            <Camera :size="14" class="shrink-0 text-ink-2" />
            <span class="min-w-0 flex-1 truncate text-[13px] text-ink">{{ excerpt(q.questionText) }}</span>
          </button>
        </template>
        <template v-if="noteResults.length">
          <p class="px-1 pt-1 text-[11px] text-ink-2">笔记</p>
          <button
            v-for="n in noteResults"
            :key="'n' + n.id"
            class="flex items-center gap-2 rounded-lg px-1 py-1.5 text-left hover:bg-panel"
            @click="pick('note', n.id, n.title)"
          >
            <NotebookPen :size="14" class="shrink-0 text-ink-2" />
            <span class="min-w-0 flex-1 truncate text-[13px] text-ink">{{ n.title }}</span>
          </button>
        </template>
      </div>
    </div>
  </div>
</template>
