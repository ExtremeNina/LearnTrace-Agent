<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  Download, ArrowLeft, CircleCheck, LoaderCircle, Pencil, RefreshCw,
} from 'lucide-vue-next'
import { getCourseDetail, renderCourseNote, reportCourseProgress } from '../api/course'
import { useToastStore } from '../stores/toast'
import type { CourseDetailData } from '../api/course'
import { renderMarkdown } from '../utils/markdown'
import { buildNoteMarkdown, buildQuizMarkdown, downloadMarkdown, exportNoteDocx, exportQuizDocx, printContent, sanitizeFileName } from '../utils/export'
import { parseTs } from '../utils/format'
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuLabel, DropdownMenuTrigger } from '@/components/ui/dropdown-menu'
import { Skeleton } from '@/components/ui/skeleton'
import AiNotePanel from '../components/course/AiNotePanel.vue'
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
  try {
    await item.run()
    toast.push('导出成功')
  } catch (e) {
    toast.push(e instanceof Error ? e.message : '导出失败，请重试')
  } finally {
    exporting.value = false
  }
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

onUnmounted(() => {
  window.removeEventListener('pagehide', reportProgressNow)
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

function formatSize(bytes: number | null | undefined): string {
  if (!bytes) {
    return '--'
  }
  return Math.round(bytes / 1024 / 1024) + 'MB'
}

// AI 笔记分页 / 知识点折叠 / 编辑与问答状态已抽至 components/course/AiNotePanel.vue
const noteEditing = ref(false)
const aiPanel = ref<InstanceType<typeof AiNotePanel> | null>(null)

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
      <div v-if="loading" class="grid grid-cols-1 gap-5 lg:grid-cols-[3fr_2fr]">
        <div class="flex min-w-0 flex-col gap-5">
          <Skeleton class="h-9 w-2/3 rounded-xl" />
          <Skeleton class="aspect-video w-full rounded-2xl" />
          <Skeleton class="min-h-[180px] flex-1 rounded-xl" />
        </div>
        <Skeleton class="min-h-0 flex-1 rounded-xl" />
      </div>
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
              @click="aiPanel?.startNoteEdit()"
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
            <DropdownMenu>
              <DropdownMenuTrigger as-child>
                <button
                  class="flex items-center gap-1.5 rounded-xl border border-line px-3 py-1.5 text-[13px] text-ink hover:bg-panel disabled:cursor-not-allowed disabled:opacity-50"
                  :disabled="exporting"
                  title="导出 AI 笔记与课后习题"
                >
                  <Download :size="15" />
                  {{ exporting ? '导出中…' : '导出' }}
                </button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end" class="w-60">
                <template v-for="group in exportMenuGroups" :key="group.title">
                  <DropdownMenuLabel class="px-3 pb-1 pt-2 text-[11px] font-medium text-ink-2">{{ group.title }}</DropdownMenuLabel>
                  <DropdownMenuItem
                    v-for="item in group.items"
                    :key="item.key"
                    :disabled="item.disabled"
                    :title="item.disabled ? '对应内容尚未生成' : undefined"
                    @select="runExportItem(item)"
                  >
                    {{ item.label }}
                  </DropdownMenuItem>
                </template>
              </DropdownMenuContent>
            </DropdownMenu>
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

          <!-- 右：AI 笔记 / 课后习题 / 转写对照 / AI 问答：面板见 components/course/AiNotePanel.vue -->
          <AiNotePanel
            ref="aiPanel"
            :data="data"
            :course-id="courseId"
            :duration="duration"
            :get-current-sec="() => videoRef?.currentTime ?? 0"
            :reload="reload"
            v-model:editing="noteEditing"
            @seek="seekTo"
          />
        </div>
      </template>
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
