<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  Download, ArrowLeft, CircleCheck, History, LoaderCircle, Pencil, Save, Send, X,
  ChevronDown, Check, RefreshCw,
} from 'lucide-vue-next'
import { getCourseDetail, renderCourseNote, reportCourseProgress } from '../api/course'
import { useToastStore } from '../stores/toast'
import type { CourseDetailData } from '../api/course'
import { updateNoteContent } from '../api/note'
import { renderMarkdown, renderNoteHtml } from '../utils/markdown'
import { buildNoteMarkdown, buildQuizMarkdown, downloadMarkdown, exportNoteDocx, exportQuizDocx, printContent, sanitizeFileName } from '../utils/export'
import { parseTs } from '../utils/format'
import MdSourceEditor from '../components/notes/MdSourceEditor.vue'
import ChatPanel from '../components/chat/ChatPanel.vue'
import ModelPicker from '../components/chat/ModelPicker.vue'
import TranscriptPanel from '../components/course/TranscriptPanel.vue'
import QuizPanel from '../components/course/QuizPanel.vue'
import StudyNoteEditor from '../components/course/StudyNoteEditor.vue'
import { useAgentStore } from '../stores/agent'

/**
 * 网课详情（PRD §4.1 + §3.2 时间戳同步观看）：
 * 左列标题操作 + 视频 + 学习笔记（用户随想，工具栏含时间戳插入）；
 * 右列 AI 笔记 / 转写对照 / AI 问答（与 /chat 共享会话，回答时间戳可跳视频）。
 */
const route = useRoute()
const router = useRouter()
const courseId = Number(route.params.id)
const agent = useAgentStore()

const data = ref<CourseDetailData | null>(null)
const loading = ref(true)
const error = ref('')
const activeTab = ref<'note' | 'transcript' | 'ask' | 'quiz'>('note')
const videoRef = ref<HTMLVideoElement | null>(null)
/** 视频元数据时长（老数据 duration 字段可能为空，用播放器时长兜底） */
const videoDuration = ref(0)

/** 播放进度上报（B25 首页「继续学习 / 最近学习」供数）：15 秒节流，暂停 / 离开页面补报 */
const PROGRESS_REPORT_INTERVAL_MS = 15_000
let lastProgressAt = 0

function reportProgressNow() {
  const video = videoRef.value
  if (!video || !data.value || video.currentTime <= 0) {
    return
  }
  reportCourseProgress(courseId, Math.floor(video.currentTime)).catch(() => {
    // 上报失败静默：下个节流窗口自然重试，不打断观看
  })
}

function onVideoTimeUpdate() {
  const now = Date.now()
  if (now - lastProgressAt >= PROGRESS_REPORT_INTERVAL_MS) {
    lastProgressAt = now
    reportProgressNow()
  }
}

/** 拉取详情（重生成后轮询复用） */
async function reload() {
  data.value = await getCourseDetail(courseId)
}

// ---- 课后习题：面板已抽至 components/course/QuizPanel.vue（父层保留数据与导出依赖）----
const toast = useToastStore()

const quizQuestions = computed(() => data.value?.quizQuestions ?? [])

// 课后习题题面解析 / 展开收起 / 追加出题 / 存题存复习已随 QuizPanel 迁移

// ---- 导出（AI 笔记 / 课后习题：Markdown 与 Word；打印 / 存为 PDF 走浏览器兜底）----
const showExportMenu = ref(false)
const exporting = ref(false)

interface ExportMenuItem {
  key: string
  label: string
  disabled: boolean
  run: () => Promise<void> | void
}

const exportMenuGroups = computed<{ title: string; items: ExportMenuItem[] }[]>(() => {
  const detail = data.value
  const hasNote = !!detail?.note
  const hasQuiz = quizQuestions.value.length > 0
  const title = detail?.course.title ?? ''
  return [
    {
      title: 'AI 笔记',
      items: [
        {
          key: 'note-md',
          label: 'Markdown（.md）',
          disabled: !hasNote,
          run: () => downloadMarkdown(buildNoteMarkdown(detail!), `${sanitizeFileName(title)}-AI笔记.md`),
        },
        {
          key: 'note-docx',
          label: 'Word（.docx）',
          disabled: !hasNote,
          run: () => exportNoteDocx(detail!),
        },
      ],
    },
    {
      title: '课后习题',
      items: [
        {
          key: 'quiz-md-answers',
          label: 'Markdown · 含答案解析',
          disabled: !hasQuiz,
          run: () => downloadMarkdown(buildQuizMarkdown(detail!, true), `${sanitizeFileName(title)}-课后习题(含答案).md`),
        },
        {
          key: 'quiz-md-plain',
          label: 'Markdown · 仅题面',
          disabled: !hasQuiz,
          run: () => downloadMarkdown(buildQuizMarkdown(detail!, false), `${sanitizeFileName(title)}-课后习题(仅题面).md`),
        },
        {
          key: 'quiz-docx-answers',
          label: 'Word · 含答案解析',
          disabled: !hasQuiz,
          run: () => exportQuizDocx(detail!, true),
        },
        {
          key: 'quiz-docx-plain',
          label: 'Word · 仅题面',
          disabled: !hasQuiz,
          run: () => exportQuizDocx(detail!, false),
        },
      ],
    },
    {
      title: '其他',
      items: [
        {
          key: 'print',
          label: '打印 / 存为 PDF（AI 笔记）',
          disabled: !hasNote,
          run: () => printContent(`${title}-AI笔记`, renderMarkdown(buildNoteMarkdown(detail!))),
        },
      ],
    },
  ]
})

async function runExportItem(item: ExportMenuItem) {
  if (item.disabled || exporting.value || !data.value) {
    return
  }
  exporting.value = true
  showExportMenu.value = false
  try {
    await item.run()
    toast.push('导出成功')
  } catch (e) {
    toast.push(e instanceof Error ? e.message : '导出失败，请重试')
  } finally {
    exporting.value = false
  }
}

function closeExportMenu() {
  showExportMenu.value = false
}

// ---- AI 问答：历史对话弹窗（课程页默认新会话，可切换历史会话继续聊） ----
const showAskHistory = ref(false)

async function openAskHistory() {
  showAskHistory.value = true
  await agent.loadConversations()
}

async function pickAskConversation(id: number) {
  showAskHistory.value = false
  await agent.openConversation(id)
}

// ---- 笔记重渲染（2026-10-11：只重跑渲染与质检，理解结构 / 出题不动；风格与画像随课程生效） ----
const regenerating = ref(false)

async function onRegenerate() {
  if (regenerating.value) {
    return
  }
  regenerating.value = true
  try {
    await renderCourseNote(courseId)
    // 轮询 3 次（渲染含质检重试需 1~2 分钟），期间页面可正常使用；末次检查失败原因（如余额不足）
    for (let i = 0; i < 3; i++) {
      setTimeout(() => {
        reload().then(() => {
          const err = data.value?.course.errorMsg
          if (i === 2 && err && err.includes('余额不足')) {
            toast.push(err, 'error')
          }
        }).catch(() => undefined)
      }, 15000 * (i + 1))
    }
  } finally {
    regenerating.value = false
  }
}

onMounted(async () => {
  // AI 问答 tab 与 /chat 共用 agentSocket，进页面先建连
  agent.ensureSocketConnected()
  // 课程页 AI 问答独立会话语义（B26 反馈）：进入默认新会话，不跟随首页的会话恢复；
  // 需要继续旧对话时用「历史对话」弹窗切换
  agent.startNew()
  try {
    await reload()
    // 带 ?t= 进入时的初始 seek 由 video 的 @loadedmetadata 模板绑定触发（挂载即生效，不依赖挂载时序）；
    // 元数据已就绪（缓存命中，事件可能已错过）时在此兜底立即 seek
    if (videoRef.value && videoRef.value.readyState >= 1) {
      applyInitialSeek()
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
})

// 离开页面（含浏览器关闭 / 刷新）补报一次进度，最长丢失不超过一个节流窗口
window.addEventListener('pagehide', reportProgressNow)
// 导出菜单点击外部关闭
document.addEventListener('click', closeExportMenu)
// 页码跳转下拉点击外部关闭
document.addEventListener('click', closePageNav)
// AI 笔记键盘翻页
window.addEventListener('keydown', onNoteKeydown)

onUnmounted(() => {
  window.removeEventListener('pagehide', reportProgressNow)
  document.removeEventListener('click', closeExportMenu)
  document.removeEventListener('click', closePageNav)
  window.removeEventListener('keydown', onNoteKeydown)
  reportProgressNow()
})

function seekTo(sec: number) {
  const video = videoRef.value
  if (video) {
    video.currentTime = sec
    // 自动播放可能被浏览器策略拦截（如直接带进度进入页面）：静默降级，进度定位不受影响
    video.play().catch(() => undefined)
  }
}

/** 带 ?t= 进入时的初始 seek 只执行一次（继续学习 / 笔记时间戳跳转） */
const initialSeek = { done: false }

/** video 元数据加载完成（模板 @loadedmetadata 绑定）：记录时长 + 执行初始 seek */
function onVideoLoadedMetadata() {
  videoDuration.value = Math.floor(videoRef.value?.duration ?? 0)
  applyInitialSeek()
}

/** 执行初始 seek：?t=（首页继续学习 / 笔记时间戳）优先，无参数时回退上次播放进度（与首页共享同一份服务端进度）；
 *  成功后清掉 URL 参数（防止页面内手动拖动后刷新又被拽回旧进度） */
function applyInitialSeek() {
  if (initialSeek.done) {
    return
  }
  const t = route.query.t
  initialSeek.done = true
  let sec = t ? parseTs(String(t)) : NaN
  if (!Number.isFinite(sec) || sec < 0) {
    sec = data.value?.course.lastPositionSec ?? 0
  }
  if (Number.isFinite(sec) && sec > 0) {
    seekTo(sec)
    if (t) {
      router.replace({ query: {} }).catch(() => undefined)
    }
  }
}

/** 编辑器预览里点时间戳胶囊 → 跳转视频对应位置 */
function onEditorChip(ts: string) {
  seekTo(parseTs(ts))
}

function formatSize(bytes: number | null | undefined): string {
  if (!bytes) {
    return '--'
  }
  return Math.round(bytes / 1024 / 1024) + 'MB'
}

/** 笔记分页：按顶级标题（## 段）切页（生成侧按章节分页产出，展示侧同构切分），每页独立走渲染管线 */
const notePages = computed(() => {
  const note = data.value?.note
  if (!note?.content) {
    return [] as { label: string; markdown: string }[]
  }
  const lines = note.content.split('\n')
  const pages: { label: string; markdown: string }[] = []
  let current: string[] = []
  for (const line of lines) {
    if (/^## /.test(line) && current.some((l) => l.trim())) {
      pages.push({ label: current[0].replace(/^#\s*/, '').trim(), markdown: current.join('\n') })
      current = []
    }
    current.push(line)
  }
  if (current.some((l) => l.trim())) {
    pages.push({ label: current[0].replace(/^#\s*/, '').trim(), markdown: current.join('\n') })
  }
  // 开篇短页合并：课程概览、章节时间线内容少且同属课程导读，并入首页减少翻页次数
  const merged: { label: string; markdown: string }[] = []
  for (const page of pages) {
    const isPreface = /课程概览|章节时间线/.test(page.label)
    if (isPreface && merged.length > 0) {
      merged[merged.length - 1].markdown += '\n\n' + page.markdown
    } else {
      merged.push({ ...page })
    }
  }
  return merged.map((page) => ({
    // 页标题行尾的时间戳（方案 B：章节标题自带 [mm:ss]）不进页码标签
    label: page.label.replace(/^##\s*/, '').replace(/\s*\[\d{1,3}:[0-5]\d]\s*$/, '').trim() || '正文',
    markdown: page.markdown,
  }))
})

const notePageIndex = ref(0)

/** 知识点折叠面板展开态（默认收起，避免标签过多挤压正文） */
const knowledgeExpanded = ref(false)
/** 笔记滚动容器：切页后滚回顶部 */
const noteScrollRef = ref<HTMLElement | null>(null)

/** 切页后滚回顶部：切页属定位跳转，非连续阅读 */
function goNotePage(idx: number) {
  notePageIndex.value = idx
  nextTick(() => {
    noteScrollRef.value?.scrollTo({ top: 0 })
  })
}

/** 键盘 ←/→ 翻页（AI 笔记阅读态，常驻底栏配套）：输入控件聚焦时让路，编辑态不响应 */
function onNoteKeydown(e: KeyboardEvent) {
  if (activeTab.value !== 'note' || noteEditing.value || notePages.value.length <= 1) return
  if (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight') return
  if (e.altKey || e.ctrlKey || e.metaKey || e.shiftKey) return
  const target = e.target as HTMLElement | null
  if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable)) return
  if (e.key === 'ArrowLeft' && notePageIndex.value > 0) {
    e.preventDefault()
    goNotePage(notePageIndex.value - 1)
  } else if (e.key === 'ArrowRight' && notePageIndex.value < notePages.value.length - 1) {
    e.preventDefault()
    goNotePage(notePageIndex.value + 1)
  }
}

watch(
  () => data.value?.note?.content,
  () => {
    notePageIndex.value = 0
    pageNavOpen.value = false
  }
)

/** 页码跳转下拉：底栏页码可点，向上展开「页码 + 章节名」列表直达任意页（导出菜单同款外部点击关闭） */
const pageNavOpen = ref(false)

function closePageNav() {
  pageNavOpen.value = false
}

function jumpToNotePage(idx: number) {
  goNotePage(idx)
  pageNavOpen.value = false
}

/** 当前页渲染：与笔记整理页同一管线（归一化 → Markdown → [mm:ss] 时间戳胶囊） */
const noteHtml = computed(() => {
  const page = notePages.value[notePageIndex.value]
  return page ? renderNoteHtml(page.markdown) : ''
})

function onNoteClick(e: MouseEvent) {
  const chip = (e.target as HTMLElement).closest('[data-ts]')
  if (chip) {
    seekTo(parseTs(chip.getAttribute('data-ts') || ''))
  }
}

function onTabChange(tab: 'note' | 'transcript' | 'ask' | 'quiz') {
  activeTab.value = tab
  pageNavOpen.value = false
}

// ---- AI 问答（与 /chat 共享会话与流式状态；随消息透传当前播放位置） ----
const askDraft = ref('')

function sendAsk() {
  const text = askDraft.value.trim()
  if (!text || agent.streaming) {
    return
  }
  askDraft.value = ''
  const currentSec = data.value?.course.videoOssKey ? Math.floor(videoRef.value?.currentTime ?? 0) : 0
  agent.send(text, currentSec > 0 ? currentSec : undefined)
}

// 在线编辑 AI 笔记：Markdown 源码编辑，保存回笔记正文接口。入口为右上角「编辑」按钮。
// AI 笔记必须存 Markdown（详情页靠 renderMarkdown 渲染并转换 [mm:ss] 时间戳胶囊），不能存富文本 HTML
const noteEditing = ref(false)
const noteDraft = ref('')
const noteSaving = ref(false)
const noteError = ref('')

function startNoteEdit() {
  if (!data.value?.note) {
    return
  }
  noteDraft.value = data.value.note.content
  noteError.value = ''
  noteSaving.value = false
  noteEditing.value = true
}

async function saveNoteEdit() {
  if (!data.value?.note) {
    return
  }
  noteSaving.value = true
  noteError.value = ''
  try {
    await updateNoteContent(data.value.note.id, noteDraft.value)
    data.value.note.content = noteDraft.value
    noteEditing.value = false
  } catch (e) {
    noteError.value = e instanceof Error ? e.message : '保存失败，请稍后重试'
  } finally {
    noteSaving.value = false
  }
}

// ---- 视频总时长（学习笔记/转写对照面板已抽至 components/course/） ----
const duration = computed(() => {
  if (data.value?.course.duration) {
    return data.value.course.duration
  }
  if (videoDuration.value) {
    return videoDuration.value
  }
  const maxSeg = Math.max(0, ...(data.value?.transcript ?? []).map((s) => s.endSec))
  const maxFrame = Math.max(0, ...(data.value?.frames ?? []).map((f) => f.timeSec))
  return Math.max(maxSeg, maxFrame)
})
</script>

<template>
  <div class="h-full overflow-y-auto lg:overflow-hidden">
    <div class="flex min-h-full flex-col px-4 py-6 lg:h-full">
      <!-- 加载 / 错误 -->
      <div v-if="loading" class="flex h-64 items-center justify-center text-[14px] text-ink-2">加载中…</div>
      <div v-else-if="error" class="flex h-64 items-center justify-center text-[14px] text-red-500">{{ error }}</div>

      <template v-else-if="data">
        <div class="grid min-h-0 flex-1 grid-cols-1 gap-5 lg:grid-cols-[3fr_2fr]">
          <!-- 左：标题操作 + 视频 + 学习笔记 -->
          <div class="flex min-h-0 flex-col">
            <!-- 返回 + 标题 + 操作（标题行，与右侧 AI 模块上边界对齐） -->
            <div class="flex shrink-0 items-center gap-3">
          <RouterLink
            to="/courses"
            class="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg text-ink-2 hover:bg-line/60"
          >
            <ArrowLeft :size="18" />
          </RouterLink>
          <div class="min-w-0">
            <h1 class="truncate text-[18px] font-semibold">{{ data.course.title }}</h1>
            <p class="mt-0.5 flex items-center gap-2 text-[12px] text-ink-2">
              <span
                v-if="data.course.subject"
                class="rounded-md bg-primary-soft px-1.5 py-0.5 text-primary"
              >
                {{ data.course.subject }}
              </span>
              <span>{{ data.course.createdAt }} · {{ formatSize(data.course.videoSize) }}</span>
              <span v-if="data.course.expectations" class="text-primary">已注入你的特别要求</span>
            </p>
          </div>
          <div class="ml-auto flex shrink-0 items-center gap-2">
            <button
              class="flex items-center gap-1.5 rounded-xl border border-line px-3 py-1.5 text-[13px] text-ink hover:bg-panel disabled:cursor-not-allowed disabled:opacity-50"
              :disabled="noteEditing || !data.note"
              :title="data.note ? '编辑 AI 笔记内容' : 'AI 笔记生成后可编辑'"
              @click="startNoteEdit"
            >
              <Pencil :size="15" />
              编辑
            </button>
            <button class="flex items-center gap-1.5 rounded-xl border border-line px-3 py-1.5 text-[13px] text-ink hover:bg-panel">
              <CircleCheck :size="15" />
              AI 润色
            </button>
            <button
              class="flex items-center gap-1.5 rounded-xl border border-line px-3 py-1.5 text-[13px] text-ink hover:bg-panel disabled:cursor-not-allowed disabled:opacity-50"
              :disabled="regenerating || !data.note"
              :title="data.note ? '只重新渲染 AI 笔记（理解结构与出题不动）' : 'AI 笔记生成后可重新生成'"
              @click="onRegenerate"
            >
              <RefreshCw :size="15" :class="regenerating ? 'animate-spin' : ''" />
              {{ regenerating ? '生成中…' : '重新生成' }}
            </button>
            <div class="relative">
              <button
                class="flex items-center gap-1.5 rounded-xl border border-line px-3 py-1.5 text-[13px] text-ink hover:bg-panel disabled:cursor-not-allowed disabled:opacity-50"
                :disabled="exporting"
                title="导出 AI 笔记与课后习题"
                @click.stop="showExportMenu = !showExportMenu"
              >
                <Download :size="15" />
                {{ exporting ? '导出中…' : '导出' }}
              </button>
              <div
                v-if="showExportMenu"
                class="absolute right-0 z-30 mt-1.5 w-60 rounded-xl border border-line bg-surface py-1.5 shadow-lg"
                @click.stop
              >
                <div v-for="group in exportMenuGroups" :key="group.title">
                  <p class="px-3.5 pb-1 pt-2 text-[11px] font-medium text-ink-2">{{ group.title }}</p>
                  <button
                    v-for="item in group.items"
                    :key="item.key"
                    class="block w-full px-3.5 py-1.5 text-left text-[13px] text-ink transition-colors hover:bg-panel disabled:cursor-not-allowed disabled:opacity-40"
                    :disabled="item.disabled"
                    :title="item.disabled ? '对应内容尚未生成' : undefined"
                    @click="runExportItem(item)"
                  >
                    {{ item.label }}
                  </button>
                </div>
              </div>
            </div>
          </div>
          </div>

          <video
            v-if="data.course.videoOssKey"
            ref="videoRef"
            class="mt-5 aspect-video w-full shrink-0 rounded-2xl border border-line bg-black"
            controls
            preload="metadata"
            :src="data.course.videoOssKey"
            @loadedmetadata="onVideoLoadedMetadata"
            @timeupdate="onVideoTimeUpdate"
            @pause="reportProgressNow"
          />
          <div v-else class="mt-5 flex aspect-video w-full items-center justify-center rounded-2xl border border-line bg-panel text-[14px] text-ink-2">
            视频处理中，稍后可在线观看
          </div>

            <div class="mt-3 flex shrink-0 items-center gap-1.5 rounded-xl border border-line bg-primary-soft/60 px-3 py-2 text-[12px] text-ink-2">
              <template v-if="data.course.status === 'SUCCESS'">
                <CircleCheck :size="14" class="shrink-0 text-green-600" />
                AI 笔记由转写与画面识别生成，点击时间戳可跳回原片段核对。
              </template>
              <template v-else>
                <LoaderCircle :size="14" class="shrink-0 animate-spin text-amber-600" />
                网课正在流水线处理中，完成后可在线观看并生成 AI 笔记。
              </template>
            </div>

            <!-- 学习笔记（用户随想）：组件见 components/course/StudyNoteEditor.vue -->
            <StudyNoteEditor
              :course-id="courseId"
              :initial-content="data.course.studyNote ?? ''"
              :can-insert-ts="!!data.course.videoOssKey"
              :get-current-sec="() => videoRef?.currentTime ?? 0"
              @seek="seekTo"
              @saved="(html) => { if (data) data.course.studyNote = html }"
            />
          </div>

          <!-- 右：AI 笔记 / 转写对照 / AI 问答 -->
          <div class="flex min-h-0 flex-col rounded-xl bg-surface shadow-card">
            <div class="flex shrink-0 items-center gap-1 border-b border-line p-2">
              <button
                v-for="tab in [
                  { key: 'note', label: 'AI 笔记' },
                  { key: 'quiz', label: '课后习题' },
                  { key: 'transcript', label: '转写对照' },
                  { key: 'ask', label: 'AI 问答' },
                ]"
                :key="tab.key"
                class="flex-1 rounded-lg py-1.5 text-[14px] transition-colors"
                :class="activeTab === tab.key ? 'bg-surface font-medium text-ink shadow-sm' : 'text-ink-2 hover:text-ink'"
                @click="onTabChange(tab.key as 'note' | 'transcript' | 'ask')"
              >
                {{ tab.label }}
              </button>
            </div>

            <!-- AI 问答：独立会话语义（默认新会话，可切历史）；回答时间戳可点击跳视频 -->
            <div v-if="activeTab === 'ask'" class="flex min-h-0 flex-1 flex-col">
              <div class="flex shrink-0 items-center justify-between border-b border-line px-3 py-1.5">
                <p class="text-[12px] text-ink-2">当前为新会话 · 时间戳可点击跳转</p>
                <div class="flex items-center gap-2">
                  <button
                    class="flex items-center gap-1 rounded-full border border-line px-2.5 py-1 text-[12px] text-ink-2 transition-colors hover:border-ink-2/50 hover:text-ink"
                    title="查看历史对话"
                    @click="openAskHistory"
                  >
                    <History :size="13" />
                    历史对话
                  </button>
                  <ModelPicker tone="ink" direction="down" />
                </div>
              </div>
              <ChatPanel chip-timestamps empty-title="学习中有疑问？直接问我" @chip="(ts) => seekTo(parseTs(ts))" />
              <div class="shrink-0 border-t border-line p-3">
                <div class="relative">
                  <input
                    v-model="askDraft"
                    class="w-full rounded-full border border-line bg-white py-2 pl-4 pr-11 text-[13px] text-ink outline-none focus:border-primary"
                    placeholder="问点什么…（AI 知道这门课的重点与转写）"
                    @keydown.enter="sendAsk"
                  />
                  <button
                    class="absolute right-1 top-1/2 flex h-7 w-7 -translate-y-1/2 items-center justify-center rounded-full bg-primary text-white transition-opacity hover:opacity-90 disabled:opacity-40"
                    :disabled="agent.streaming || !askDraft.trim()"
                    title="发送"
                    @click="sendAsk"
                  >
                    <Send :size="13" />
                  </button>
                </div>
                <p class="mt-1.5 text-[11px] text-ink-2">回答中的 [mm:ss] 时间戳可点击跳转视频对应位置</p>
              </div>
            </div>

            <!-- 课后习题（B26 习题产物化）：面板见 components/course/QuizPanel.vue -->
            <QuizPanel
              v-else-if="activeTab === 'quiz'"
              :course-id="courseId"
              :questions="quizQuestions"
              :error-msg="data.course.errorMsg"
              :reload="reload"
              @seek="seekTo"
            />

            <div v-else ref="noteScrollRef" class="min-h-0 flex-1 overflow-y-auto p-3">
              <!-- AI 笔记 -->
              <template v-if="activeTab === 'note'">
                <!-- 知识点折叠面板：默认收起，展开后内容过多时内部滚动，避免挤压正文（章节导航由时间线表格与底栏页码承担） -->
                <div v-if="data.knowledgePoints?.length" class="mb-3 rounded-xl bg-panel px-3 py-2.5">
                  <button
                    type="button"
                    class="flex w-full items-center justify-between text-left"
                    @click="knowledgeExpanded = !knowledgeExpanded"
                  >
                    <span class="text-[12px] font-semibold text-ink">重点知识点（{{ data.knowledgePoints.length }} 个）</span>
                    <ChevronDown
                      :size="14"
                      class="shrink-0 text-ink-2 transition-transform"
                      :class="knowledgeExpanded ? '' : '-rotate-90'"
                    />
                  </button>
                  <div v-show="knowledgeExpanded" class="mt-2 flex max-h-48 flex-wrap gap-1.5 overflow-y-auto">
                    <button
                      v-for="p in data.knowledgePoints"
                      :key="p.id"
                      class="rounded-full bg-surface px-2.5 py-1 text-[11px] text-ink-2 transition-colors hover:text-primary"
                      :title="p.detail || ''"
                      @click="p.timeSec != null && seekTo(p.timeSec)"
                    >
                      {{ p.name }}<span v-if="p.important === 1" class="text-amber-500"> · 重点</span><span v-if="p.errorProne === 1" class="text-red-400"> · 易错</span>
                    </button>
                  </div>
                </div>
                <div v-if="noteEditing" class="px-1 pb-3">
                  <p v-if="noteError" class="mb-2 text-[12px] text-red-600">{{ noteError }}</p>
                  <MdSourceEditor v-model="noteDraft" height-class="h-[420px]" @chip="onEditorChip">
                    <template #actions>
                      <button
                        class="flex items-center gap-1 rounded-lg bg-ink px-3 py-1.5 text-[13px] text-white hover:opacity-80 disabled:opacity-50"
                        :disabled="noteSaving"
                        @click="saveNoteEdit"
                      >
                        <Save :size="14" />
                        保存
                      </button>
                      <button
                        class="flex items-center gap-1 rounded-lg border border-line px-3 py-1.5 text-[13px] text-ink-2 hover:bg-line/40"
                        @click="noteEditing = false"
                      >
                        <X :size="14" />
                        取消
                      </button>
                    </template>
                  </MdSourceEditor>
                </div>
                <template v-else>
                  <div class="note-view text-[14px] leading-7 text-ink" v-html="noteHtml" @click="onNoteClick"></div>
                  <p v-if="!data.note" class="rounded-xl border border-dashed border-line py-8 text-center text-[13px] text-ink-2">
                    AI 笔记生成中，完成后展示在这里
                  </p>
                  <p class="mt-2 text-[11px] text-ink-2">AI 笔记由转写与画面识别生成，点击时间戳可跳回原片段核对。</p>
                </template>
              </template>

              <!-- 转写对照（整合关键帧）：面板见 components/course/TranscriptPanel.vue -->
              <TranscriptPanel
                v-else
                :transcript="data.transcript"
                :frames="data.frames"
                :duration="duration"
                @seek="seekTo"
              />
            </div>

            <!-- 分页：结构性底栏（tab 栏同款，固定在面板底部、不参与滚动），仅 AI 笔记阅读态且多页时显示；页码可点开下拉直达任意页 -->
            <div
              v-if="activeTab === 'note' && !noteEditing && notePages.length > 1"
              class="flex shrink-0 items-center justify-between gap-3 border-t border-line bg-panel px-3 py-2 text-[13px] text-ink-2"
            >
              <div class="relative min-w-0">
                <button
                  type="button"
                  class="flex max-w-full items-center gap-1 rounded-lg px-2 py-1 transition-colors hover:bg-line/60"
                  title="点击展开页面列表，选择章节直达"
                  @click.stop="pageNavOpen = !pageNavOpen"
                >
                  <span class="truncate">第 {{ notePageIndex + 1 }} / {{ notePages.length }} 页 · {{ notePages[notePageIndex].label }}</span>
                  <ChevronDown :size="13" class="shrink-0 transition-transform" :class="pageNavOpen ? '' : '-rotate-90'" />
                </button>
                <div
                  v-if="pageNavOpen"
                  class="absolute bottom-full left-0 z-30 mb-2 max-h-64 w-72 overflow-y-auto rounded-xl border border-line bg-surface py-1.5 shadow-lg"
                  @click.stop
                >
                  <button
                    v-for="(page, i) in notePages"
                    :key="i"
                    class="flex w-full items-center gap-2 px-3 py-1.5 text-left text-[13px] transition-colors hover:bg-panel"
                    :class="i === notePageIndex ? 'bg-panel font-medium text-primary' : 'text-ink'"
                    :title="page.label"
                    @click="jumpToNotePage(i)"
                  >
                    <span class="w-5 shrink-0 text-right text-[11px] text-ink-2">{{ i + 1 }}</span>
                    <span class="truncate">{{ page.label }}</span>
                    <Check v-if="i === notePageIndex" :size="13" class="ml-auto shrink-0 text-primary" />
                  </button>
                </div>
              </div>
              <div class="flex shrink-0 items-center gap-2">
                <button
                  type="button"
                  class="rounded-lg border border-line bg-surface px-3 py-1.5 text-[12px] transition-colors hover:bg-line/60 disabled:opacity-40"
                  :disabled="notePageIndex <= 0"
                  @click="goNotePage(notePageIndex - 1)"
                >
                  上一页
                </button>
                <button
                  type="button"
                  class="rounded-lg border border-line bg-surface px-3 py-1.5 text-[12px] transition-colors hover:bg-line/60 disabled:opacity-40"
                  :disabled="notePageIndex >= notePages.length - 1"
                  @click="goNotePage(notePageIndex + 1)"
                >
                  下一页
                </button>
              </div>
            </div>
          </div>
        </div>
      </template>

      <!-- AI 问答历史对话弹窗（课程页默认新会话，可切换历史会话） -->
      <div
        v-if="showAskHistory"
        class="fixed inset-0 z-50 flex items-center justify-center bg-ink/25 px-4 backdrop-blur-sm"
        @click.self="showAskHistory = false"
      >
        <div class="max-h-[70vh] w-full max-w-md overflow-y-auto rounded-3xl border border-line bg-surface p-5 shadow-xl">
          <div class="flex items-center justify-between">
            <h3 class="text-[15px] font-semibold text-ink">历史对话</h3>
            <button
              class="flex h-8 w-8 items-center justify-center rounded-lg text-ink-2 hover:bg-line/60 hover:text-ink"
              title="关闭"
              @click="showAskHistory = false"
            >
              <X :size="15" />
            </button>
          </div>
          <p v-if="agent.conversations.length === 0" class="py-6 text-center text-[13px] text-ink-2">
            暂无会话，发一条消息开始吧
          </p>
          <div class="mt-3 flex flex-col gap-1">
            <button
              v-for="c in agent.conversations"
              :key="c.id"
              class="flex w-full items-center justify-between gap-3 rounded-xl px-3.5 py-2.5 text-left text-[14px] text-ink transition-colors hover:bg-panel"
              :class="agent.activeId === c.id ? 'bg-line/60' : ''"
              @click="pickAskConversation(c.id)"
            >
              <span class="truncate">{{ c.title }}</span>
              <span class="shrink-0 text-[11px] text-ink-2">{{ c.updatedAt?.slice(5, 16) ?? '' }}</span>
            </button>
          </div>
        </div>
      </div>

    </div>
  </div>
</template>

<style scoped>
.note-view :deep(h1),
.note-view :deep(h2),
.note-view :deep(h3) {
  font-weight: 600;
  font-size: 15px;
  margin: 1.25rem 0 0.5rem;
}
.note-view :deep(h1) {
  font-size: 17px;
}
.note-view :deep(h1:first-child),
.note-view :deep(h2:first-child),
.note-view :deep(h3:first-child) {
  margin-top: 0;
}
.note-view :deep(ul) {
  list-style: disc;
  padding-left: 1.4rem;
  margin: 0.25rem 0 0.75rem;
}
.note-view :deep(ol) {
  list-style: decimal;
  padding-left: 1.4rem;
  margin: 0.25rem 0 0.75rem;
}
.note-view :deep(li) {
  margin: 0.25rem 0;
}
.note-view :deep(hr) {
  margin: 1rem 0;
  border-color: #e5e7eb;
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
.note-view :deep(.ts-chip:hover) {
  text-decoration: underline;
}
</style>
