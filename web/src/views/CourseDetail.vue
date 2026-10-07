<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import {
  Download, ArrowLeft, CircleCheck, History, LoaderCircle, Pencil, Save, Send, X,
  Bold, Italic, Underline, Clock,
} from 'lucide-vue-next'
import { appendCourseQuiz, getCourseDetail, quizToQuestions, quizToReview, regenerateCourseContent, updateCourse, reportCourseProgress } from '../api/course'
import { useToastStore } from '../stores/toast'
import type { CourseDetailData } from '../api/course'
import { updateNoteContent } from '../api/note'
import { renderNoteHtml } from '../utils/markdown'
import DOMPurify from 'dompurify'
import MdSourceEditor from '../components/notes/MdSourceEditor.vue'
import ChatPanel from '../components/chat/ChatPanel.vue'
import ModelPicker from '../components/chat/ModelPicker.vue'
import { useAgentStore } from '../stores/agent'

/**
 * 网课详情（PRD §4.1 + §3.2 时间戳同步观看）：
 * 左列标题操作 + 视频 + 学习笔记（用户随想，工具栏含时间戳插入）；
 * 右列 AI 笔记 / 转写对照 / AI 问答（与 /chat 共享会话，回答时间戳可跳视频）。
 */
const route = useRoute()
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

// ---- 课后习题（B26 习题产物化）----
const toast = useToastStore()
const quizGenerating = ref(false)
const expandedAnswers = reactive(new Set<number>())
const showAllAnswers = ref(false)
const savedToQuestions = reactive(new Set<number>())
const savedToReview = reactive(new Set<number>())

const quizQuestions = computed(() => data.value?.quizQuestions ?? [])

function toggleAnswer(id: number) {
  if (expandedAnswers.has(id)) {
    expandedAnswers.delete(id)
  } else {
    expandedAnswers.add(id)
  }
}

function toggleAllAnswers() {
  showAllAnswers.value = !showAllAnswers.value
}

/** 追加出题：异步后轮询（与重新生成同模式） */
async function appendQuiz(count = 5) {
  if (quizGenerating.value) {
    return
  }
  quizGenerating.value = true
  try {
    await appendCourseQuiz(courseId, count)
    toast.push('已开始追加出题，约 1 分钟')
    const before = quizQuestions.value.length
    for (let i = 0; i < 12; i++) {
      await new Promise((r) => setTimeout(r, 10000))
      await reload()
      if (quizQuestions.value.length > before || i === 11) {
        break
      }
    }
    if (quizQuestions.value.length > before) {
      toast.push(`已新增 ${quizQuestions.value.length - before} 道课后习题`)
    } else {
      toast.push('追加出题超时，可稍后手动刷新', 'error')
    }
  } catch (e) {
    toast.push(e instanceof Error ? e.message : '追加出题失败', 'error')
  } finally {
    quizGenerating.value = false
  }
}

async function saveToQuestions(id: number) {
  try {
    await quizToQuestions(id)
    savedToQuestions.add(id)
    toast.push('已加入题目管理')
  } catch (e) {
    toast.push(e instanceof Error ? e.message : '加入失败', 'error')
  }
}

async function saveToReview(id: number) {
  try {
    await quizToReview(id)
    savedToReview.add(id)
    toast.push('已加入复习计划（含题目管理）')
  } catch (e) {
    toast.push(e instanceof Error ? e.message : '加入失败', 'error')
  }
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

// ---- 内容重生成（B26 阶段 2）：触发后轮询刷新章节与知识点 ----
const regenerating = ref(false)

async function onRegenerate() {
  if (regenerating.value) {
    return
  }
  regenerating.value = true
  try {
    await regenerateCourseContent(courseId)
    // 轮询 3 次（内容理解 + 笔记渲染需数分钟），期间页面可正常使用
    for (let i = 0; i < 3; i++) {
      setTimeout(() => { reload().catch(() => undefined) }, 15000 * (i + 1))
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
    // loading 骨架切换后 DOM 需一次异步渲染，video 元素此刻才挂载（B26 反馈：继续学习进度失效根因）
    await nextTick()
    // 支持从笔记页 / 首页「继续学习」带进度跳转进入：/courses/1?t=08:24 → 加载后自动 seek 续播
    const t = route.query.t
    if (t) {
      const sec = parseTs(String(t))
      if (Number.isFinite(sec) && sec >= 0) {
        const seek = () => seekTo(sec)
        if (videoRef.value && videoRef.value.readyState >= 1) {
          seek()
        } else {
          videoRef.value?.addEventListener('loadedmetadata', seek, { once: true })
        }
      }
    }
    videoRef.value?.addEventListener('loadedmetadata', () => {
      videoDuration.value = Math.floor(videoRef.value?.duration ?? 0)
    })
    // 学习笔记初始内容在 DOM 挂载后灌入（不绑定响应式，避免保存后光标重置）
    nextTick(() => initStudyBox())
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
})

// 离开页面（含浏览器关闭 / 刷新）补报一次进度，最长丢失不超过一个节流窗口
window.addEventListener('pagehide', reportProgressNow)

onUnmounted(() => {
  window.removeEventListener('pagehide', reportProgressNow)
  reportProgressNow()
})

function parseTs(ts: string): number {
  const parts = ts.split(':').map(Number)
  return parts.length === 3
    ? parts[0] * 3600 + parts[1] * 60 + parts[2]
    : parts[0] * 60 + parts[1]
}

function seekTo(sec: number) {
  const video = videoRef.value
  if (video) {
    video.currentTime = sec
    // 自动播放可能被浏览器策略拦截（如直接带进度进入页面）：静默降级，进度定位不受影响
    video.play().catch(() => undefined)
  }
}

/** 编辑器预览里点时间戳胶囊 → 跳转视频对应位置 */
function onEditorChip(ts: string) {
  seekTo(parseTs(ts))
}

function formatTs(sec: number): string {
  const h = Math.floor(sec / 3600)
  const m = Math.floor((sec % 3600) / 60)
  const s = sec % 60
  const mm = String(m).padStart(2, '0')
  const ss = String(s).padStart(2, '0')
  return h > 0 ? `${h}:${mm}:${ss}` : `${mm}:${ss}`
}

function formatSize(bytes: number | null | undefined): string {
  if (!bytes) {
    return '--'
  }
  return Math.round(bytes / 1024 / 1024) + 'MB'
}

/** 笔记渲染：与笔记整理页同一管线（归一化 → Markdown → [mm:ss] 时间戳胶囊） */
const noteHtml = computed(() => {
  const note = data.value?.note
  return note ? renderNoteHtml(note.content) : ''
})

function onNoteClick(e: MouseEvent) {
  const chip = (e.target as HTMLElement).closest('[data-ts]')
  if (chip) {
    seekTo(parseTs(chip.getAttribute('data-ts') || ''))
  }
}

function onTabChange(tab: 'note' | 'transcript' | 'ask' | 'quiz') {
  activeTab.value = tab
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

// ---- 学习笔记（视频下方随想区，富文本 + 时间戳插入） ----
const studyRef = ref<HTMLDivElement | null>(null)
const studyDirty = ref(false)
let studySavedHtml = ''

function initStudyBox() {
  if (!studyRef.value) {
    return
  }
  studySavedHtml = DOMPurify.sanitize(data.value?.course.studyNote ?? '')
  studyRef.value.innerHTML = studySavedHtml
  studyDirty.value = false
}

function onStudyInput() {
  if (studyRef.value) {
    studyDirty.value = studyRef.value.innerHTML !== studySavedHtml
  }
}

async function saveStudyNote() {
  if (!studyRef.value || !studyDirty.value) {
    return
  }
  const html = studyRef.value.innerHTML
  try {
    const updated = await updateCourse(courseId, { studyNote: html })
    if (data.value) {
      data.value.course.studyNote = html
    }
    studySavedHtml = updated.studyNote ?? html
    studyDirty.value = false
  } catch (e) {
    window.alert(e instanceof Error ? e.message : '保存失败，请稍后重试')
  }
}

function execStudy(command: string) {
  document.execCommand('styleWithCSS', false, 'true')
  document.execCommand(command, false)
  studyRef.value?.focus()
}

/** 在光标处插入当前播放进度的时间戳胶囊（保存后可点击跳回该片段） */
function insertStudyTs() {
  const video = videoRef.value
  const box = studyRef.value
  if (!video || !box) {
    return
  }
  const ts = formatTs(video.currentTime)
  box.focus()
  document.execCommand('insertHTML', false, `<span class="ts-chip" data-ts="${ts}">${ts}</span>&nbsp;`)
  onStudyInput()
}

/** 学习笔记内点击时间戳胶囊 → 跳回视频对应位置 */
function onStudyClick(e: MouseEvent) {
  const chip = (e.target as HTMLElement).closest('[data-ts]')
  if (chip) {
    seekTo(parseTs(chip.getAttribute('data-ts') || ''))
  }
}

// ---- 转写对照：段落与关键帧整合 ----
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

/** 转写段落 + 落在该时间段内的关键帧（未落段的帧挂到最后一段） */
const segmentsWithFrames = computed(() => {
  const frames = [...(data.value?.frames ?? [])].sort((a, b) => a.timeSec - b.timeSec)
  const used = new Set<number>()
  const rows = (data.value?.transcript ?? []).map((seg) => {
    const segFrames = frames.filter((f) => f.timeSec >= seg.startSec && f.timeSec < seg.endSec && !used.has(f.id))
    segFrames.forEach((f) => used.add(f.id))
    return { seg, frames: segFrames }
  })
  const rest = frames.filter((f) => !used.has(f.id))
  if (rest.length && rows.length) {
    rows[rows.length - 1].frames.push(...rest)
  }
  return rows
})

/** 转写修正元数据解析（B26 阶段 1 修正管线） */
function correctionOf(meta: string | null | undefined): { status?: string; suggestion?: string; original?: string } | null {
  if (!meta) {
    return null
  }
  try {
    return JSON.parse(meta)
  } catch {
    return null
  }
}

/** SUGGESTED（未确认建议）计数：转写对照页顶部的批量确认入口 */
const suggestedCount = computed(
  () => (data.value?.transcript ?? []).filter((s) => correctionOf(s.correctionMeta)?.status === 'SUGGESTED').length
)

/** 时间轴刻度：转写段起点 + 关键帧位置 */
const timelineTicks = computed(() => {
  const total = duration.value
  if (!total) {
    return []
  }
  const ticks: { t: number; kind: 'transcript' | 'frame'; pct: number }[] = []
  for (const seg of data.value?.transcript ?? []) {
    if (seg.startSec <= total) {
      ticks.push({ t: seg.startSec, kind: 'transcript', pct: (seg.startSec / total) * 100 })
    }
  }
  for (const f of data.value?.frames ?? []) {
    if (f.timeSec <= total) {
      ticks.push({ t: f.timeSec, kind: 'frame', pct: (f.timeSec / total) * 100 })
    }
  }
  return ticks
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
            <button class="flex items-center gap-1.5 rounded-xl border border-line px-3 py-1.5 text-[13px] text-ink hover:bg-panel">
              <Download :size="15" />
              导出
            </button>
          </div>
          </div>

          <video
            v-if="data.course.videoOssKey"
            ref="videoRef"
            class="mt-5 aspect-video w-full shrink-0 rounded-2xl border border-line bg-black"
            controls
            preload="metadata"
            :src="data.course.videoOssKey"
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

            <!-- 学习笔记（用户随想） -->
            <div class="mt-4 flex min-h-[180px] flex-1 flex-col rounded-xl bg-surface shadow-card">
              <div class="flex flex-wrap items-center gap-2 border-b border-line px-3 py-2">
                <p class="text-[13px] font-semibold text-ink">学习笔记</p>
                <div class="ml-auto flex items-center gap-1">
                  <button class="flex h-7 w-7 items-center justify-center rounded-lg text-ink hover:bg-line/60" title="加粗" @mousedown.prevent @click="execStudy('bold')">
                    <Bold :size="14" />
                  </button>
                  <button class="flex h-7 w-7 items-center justify-center rounded-lg text-ink hover:bg-line/60" title="斜体" @mousedown.prevent @click="execStudy('italic')">
                    <Italic :size="14" />
                  </button>
                  <button class="flex h-7 w-7 items-center justify-center rounded-lg text-ink hover:bg-line/60" title="下划线" @mousedown.prevent @click="execStudy('underline')">
                    <Underline :size="14" />
                  </button>
                  <button
                    class="flex h-7 items-center gap-1 rounded-lg px-2 text-[12px] text-ink hover:bg-line/60"
                    title="插入当前播放进度的时间戳"
                    :disabled="!data.course.videoOssKey"
                    @mousedown.prevent
                    @click="insertStudyTs"
                  >
                    <Clock :size="14" />
                    时间戳
                  </button>
                  <button
                    class="ml-1 flex items-center gap-1 rounded-lg px-2.5 py-1.5 text-[12px]"
                    :class="studyDirty ? 'bg-primary text-white hover:opacity-90' : 'border border-line text-ink-2 opacity-50 cursor-not-allowed'"
                    :disabled="!studyDirty"
                    @click="saveStudyNote"
                  >
                    <Save :size="13" />
                    保存
                  </button>
                </div>
              </div>
              <div
                ref="studyRef"
                class="study-view min-h-0 flex-1 overflow-y-auto px-4 py-3 text-[14px] leading-7 text-ink outline-none"
                contenteditable="true"
                @input="onStudyInput"
                @click="onStudyClick"
              ></div>
              <p class="border-t border-line px-4 py-1.5 text-[11px] text-ink-2">
                记录观看感想；点「时间戳」插入当前进度，保存后点击胶囊可跳回该片段。
              </p>
            </div>
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

            <!-- 课后习题（B26 习题产物化）：出题 Agent + 判题 Agent 产物 -->
            <div v-else-if="activeTab === 'quiz'" class="flex min-h-0 flex-1 flex-col" @click="onNoteClick">
              <div class="flex shrink-0 items-center justify-between border-b border-line px-3 py-2">
                <p class="text-[13px] text-ink-2">
                  共 {{ quizQuestions.length }} 题<span v-if="quizQuestions.length"> · 时间戳可点击跳转</span>
                </p>
                <div class="flex items-center gap-2">
                  <button
                    v-if="quizQuestions.length"
                    class="text-[12px] text-ink-2 transition-colors hover:text-primary"
                    @click="toggleAllAnswers"
                  >
                    {{ showAllAnswers ? '隐藏全部答案' : '显示全部答案' }}
                  </button>
                  <button
                    class="rounded-full bg-primary px-3 py-1 text-[12px] text-white transition-opacity hover:opacity-90 disabled:opacity-50"
                    :disabled="quizGenerating"
                    @click="appendQuiz(5)"
                  >
                    {{ quizGenerating ? '出题中…' : quizQuestions.length ? '再出几道' : '生成课后习题' }}
                  </button>
                </div>
              </div>
              <div class="min-h-0 flex-1 overflow-y-auto p-3">
                <div
                  v-if="quizQuestions.length === 0"
                  class="flex h-40 flex-col items-center justify-center gap-2 text-[13px] text-ink-2"
                >
                  <p>{{ quizGenerating ? '出题 Agent 正在出题，判题 Agent 把关中…' : '这门课还没有课后习题' }}</p>
                  <p v-if="!quizGenerating" class="text-[12px] text-ink-2/70">点击右上角「生成课后习题」，题目数量由 AI 按时长与知识点规划</p>
                </div>
                <div class="flex flex-col gap-2.5">
                  <div
                    v-for="(q, qi) in quizQuestions"
                    :key="q.id"
                    class="rounded-xl bg-panel px-3 py-2.5"
                  >
                    <p class="text-[14px] font-medium leading-6 text-ink">
                      {{ qi + 1 }}. {{ q.questionText }}
                      <button
                        v-if="q.sourceSec != null"
                        class="ts-chip ml-1"
                        :data-ts="formatTs(q.sourceSec)"
                      >{{ formatTs(q.sourceSec) }}</button>
                    </p>
                    <div
                      v-if="showAllAnswers || expandedAnswers.has(q.id)"
                      class="mt-2 rounded-lg bg-surface px-3 py-2 text-[13px] leading-6"
                    >
                      <p class="font-medium text-ink">答案</p>
                      <p class="mt-0.5 text-ink">{{ q.answer }}</p>
                      <template v-if="q.analysis">
                        <p class="mt-1.5 font-medium text-ink">解析</p>
                        <p class="mt-0.5 text-ink-2">{{ q.analysis }}</p>
                      </template>
                    </div>
                    <div class="mt-2 flex items-center gap-3 text-[12px]">
                      <button
                        class="text-primary hover:underline"
                        @click="toggleAnswer(q.id)"
                      >
                        {{ showAllAnswers || expandedAnswers.has(q.id) ? '收起答案' : '查看答案' }}
                      </button>
                      <button
                        class="text-ink-2 transition-colors hover:text-primary disabled:opacity-50"
                        :disabled="savedToQuestions.has(q.id)"
                        @click="saveToQuestions(q.id)"
                      >
                        {{ savedToQuestions.has(q.id) ? '已入题目管理' : '加入题目管理' }}
                      </button>
                      <button
                        class="text-ink-2 transition-colors hover:text-primary disabled:opacity-50"
                        :disabled="savedToReview.has(q.id)"
                        @click="saveToReview(q.id)"
                      >
                        {{ savedToReview.has(q.id) ? '已入复习计划' : '加入复习计划' }}
                      </button>
                    </div>
                  </div>
                </div>
              </div>
            </div>

            <div v-else class="min-h-0 flex-1 overflow-y-auto p-3">
              <!-- AI 笔记 -->
              <template v-if="activeTab === 'note'">
                <!-- B26 阶段 2：内容结构（章节导航 + 知识点） -->
                <div v-if="data.sections?.length" class="mb-3 rounded-xl bg-panel px-3 py-2.5">
                  <div class="flex items-center justify-between">
                    <p class="text-[12px] font-semibold text-ink">内容结构</p>
                    <button
                      class="text-[11px] text-ink-2 transition-colors hover:text-primary disabled:opacity-50"
                      :disabled="regenerating"
                      @click="onRegenerate"
                    >
                      {{ regenerating ? '重新生成中…' : '重新生成' }}
                    </button>
                  </div>
                  <button
                    v-for="s in data.sections"
                    :key="s.id"
                    class="mt-1.5 flex w-full items-center gap-2 text-left text-[12px] text-ink-2 transition-colors hover:text-primary"
                    :title="s.summary || ''"
                    @click="seekTo(s.startSec)"
                  >
                    <span class="shrink-0 font-medium text-primary">{{ formatTs(s.startSec) }}</span>
                    <span class="truncate">{{ s.title }}</span>
                  </button>
                </div>
                <div v-if="data.knowledgePoints?.length" class="mb-3 flex flex-wrap gap-1.5">
                  <button
                    v-for="p in data.knowledgePoints"
                    :key="p.id"
                    class="rounded-full bg-panel px-2.5 py-1 text-[11px] text-ink-2 transition-colors hover:text-primary"
                    :title="p.detail || ''"
                    @click="p.timeSec != null && seekTo(p.timeSec)"
                  >
                    {{ p.name }}<span v-if="p.important === 1" class="text-amber-500"> · 重点</span><span v-if="p.errorProne === 1" class="text-red-400"> · 易错</span>
                  </button>
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

              <!-- 转写对照（整合关键帧） -->
              <template v-else>
                <div v-if="timelineTicks.length" class="mb-3 px-1 pt-1">
                  <div class="relative h-2 rounded-full bg-line/70">
                    <button
                      v-for="(tick, i) in timelineTicks"
                      :key="i"
                      class="absolute top-1/2 h-3 w-[3px] -translate-y-1/2 rounded-full"
                      :class="tick.kind === 'frame' ? 'bg-primary' : 'bg-ink-2/60'"
                      :style="{ left: tick.pct + '%' }"
                      :title="formatTs(tick.t) + (tick.kind === 'frame' ? ' · 关键帧' : ' · 转写段')"
                      @click="seekTo(tick.t)"
                    ></button>
                  </div>
                  <div class="mt-1 flex justify-between text-[11px] text-ink-2">
                    <span>00:00</span>
                    <span>{{ formatTs(duration) }}</span>
                  </div>
                  <div class="mt-1 flex gap-3 text-[11px] text-ink-2">
                    <span class="flex items-center gap-1"><span class="h-1.5 w-1.5 rounded-full bg-ink-2/60"></span>转写段落</span>
                    <span class="flex items-center gap-1"><span class="h-1.5 w-1.5 rounded-full bg-primary"></span>关键帧</span>
                    <span class="ml-auto">点击刻度跳转</span>
                  </div>
                </div>

                <div class="flex flex-col gap-7">
                  <!-- B26 阶段 1：未确认转写修正批量入口 -->
                  <div v-if="suggestedCount > 0" class="flex items-center gap-2 rounded-xl bg-amber-50 px-3 py-2 text-[12px] text-amber-600">
                    ⚠️ 有 {{ suggestedCount }} 处疑似转写错误（下方已标注建议写法），确认无误可忽略
                  </div>
                  <div v-for="row in segmentsWithFrames" :key="row.seg.id">
                    <div class="flex gap-4">
                      <button class="w-12 shrink-0 pt-0.5 text-left text-[12px] font-medium text-primary hover:underline" @click="seekTo(row.seg.startSec)">
                        {{ formatTs(row.seg.startSec) }}
                      </button>
                      <p class="min-w-0 flex-1 text-[13px] leading-6.5 text-ink">
                        <template v-if="row.seg.textCorrected">
                          {{ row.seg.textCorrected }}
                          <span
                            class="cursor-help text-[11px] text-ink-2 line-through decoration-ink-2/40"
                            :title="'已自动修正（原文：' + (correctionOf(row.seg.correctionMeta)?.original || row.seg.text) + '）'"
                          >{{ row.seg.text }}</span>
                        </template>
                        <template v-else>{{ row.seg.text }}</template>
                        <!-- SUGGESTED：未确认建议，内联标注 -->
                        <span
                          v-if="correctionOf(row.seg.correctionMeta)?.status === 'SUGGESTED'"
                          class="ml-1 cursor-help border-b border-dashed border-amber-500 text-amber-600"
                          :title="'疑似转写错误，建议写作：' + (correctionOf(row.seg.correctionMeta)?.suggestion || '')"
                        >{{ row.seg.text }}</span>
                      </p>
                    </div>
                    <div v-if="row.frames.length" class="mt-2 flex gap-2 overflow-x-auto pb-1">
                      <button
                        v-for="f in row.frames"
                        :key="f.id"
                        class="relative shrink-0"
                        :title="f.ocrText || '关键帧 ' + formatTs(f.timeSec)"
                        @click="seekTo(f.timeSec)"
                      >
                        <img :src="f.ossKey" :alt="'第 ' + f.timeSec + 's 画面'" class="h-14 w-24 rounded-lg border border-line object-cover" />
                        <span class="absolute bottom-0.5 right-0.5 rounded bg-black/60 px-1 text-[10px] text-white">{{ formatTs(f.timeSec) }}</span>
                      </button>
                    </div>
                  </div>
                  <p v-if="!(data.transcript.length || data.frames.length)" class="rounded-xl border border-dashed border-line py-8 text-center text-[13px] text-ink-2">
                    未获得转写与关键帧数据
                  </p>
                </div>
              </template>
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
.study-view :deep(.ts-chip) {
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
.study-view :deep(.ts-chip:hover) {
  text-decoration: underline;
}
.study-view:empty::before {
  content: '记录这一节课的感想与疑问…';
  color: #9ca3af;
}

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
