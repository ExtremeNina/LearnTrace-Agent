<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  AlertTriangle, BookOpen, ChevronRight, CircleCheck, Clock, FileText, GraduationCap,
  Image as ImageIcon, ListChecks, MessageSquareText, MonitorPlay, NotebookPen, Play, Plus,
  Presentation, Send, Sparkles, Target, Video,
} from 'lucide-vue-next'
import { getHomeOverview, heartbeatStudyTime } from '../api/home'
import type { HomeOverview } from '../api/home'
import { uploadCourse } from '../api/course'
import { useAgentStore } from '../stores/agent'
import { useToastStore } from '../stores/toast'
import { renderMarkdown } from '../utils/markdown'

/**
 * 首页学习仪表盘（B25，视觉按设计稿实现——靛蓝色系 / 渐变按钮 / 卡片白底圆角；深色主题适配后置）：
 * 问候 banner + 继续学习（本课重点）/ 今日复习 / 最近学习 / 上传资料（左主列）
 * + AI 助手（与 /chat 共享会话）/ 学习数据 / 坚持学习（右辅列）。推荐学习不做（拍板）。
 */
defineOptions({ name: 'HomeView' })

const router = useRouter()
const toast = useToastStore()
const overview = ref<HomeOverview | null>(null)
const loading = ref(true)
const error = ref('')

onMounted(async () => {
  try {
    overview.value = await getHomeOverview()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
  // 助手面板续接上次会话（与 /chat 同一份状态）
  agent.loadConversations()
  if (agent.messages.length === 0) {
    agent.restoreLastConversation()
  }
  // 学习时长心跳：每 60 秒上报一次在站时长（仅页面可见时）
  heartbeatTimer = setInterval(() => flushStudyTime(60), 60_000)
})

// ---- 学习时长心跳（今日学习时长供数） ----
let heartbeatTimer: ReturnType<typeof setInterval> | null = null

function flushStudyTime(seconds: number) {
  if (document.visibilityState !== 'visible') {
    return
  }
  heartbeatStudyTime(seconds).catch(() => {
    // 心跳失败静默，下一轮继续
  })
}

onUnmounted(() => {
  if (heartbeatTimer) {
    clearInterval(heartbeatTimer)
  }
  flushStudyTime(60)
})

const greeting = computed(() => {
  const h = new Date().getHours()
  if (h < 12) return '早上好'
  if (h < 18) return '下午好'
  return '晚上好'
})

const continueCourse = computed(() => overview.value?.continueCourse ?? null)
const keyPoints = computed(() => overview.value?.keyPoints ?? [])
const dueCount = computed(() => overview.value?.stats.dueCount ?? 0)
/** 复习时长建议：每张卡约 3 分钟，最少 5 分钟 */
const suggestMinutes = computed(() => Math.max(5, dueCount.value * 3))

function formatTs(sec?: number | null): string {
  if (sec == null) return '0:00'
  const h = Math.floor(sec / 3600)
  const m = Math.floor((sec % 3600) / 60)
  const s = sec % 60
  const mm = String(m).padStart(2, '0')
  const ss = String(s).padStart(2, '0')
  return h > 0 ? `${h}:${mm}:${ss}` : `${mm}:${ss}`
}

function formatDuration(sec?: number | null): string {
  if (sec == null || sec <= 0) return '—'
  return formatTs(sec)
}

/** 相对时间：N 分钟 / 小时 / 天前（简单规则，不引库） */
function relativeTime(iso?: string | null): string {
  if (!iso) return ''
  const t = new Date(iso.replace(' ', 'T')).getTime()
  if (Number.isNaN(t)) return ''
  const diffMin = Math.floor((Date.now() - t) / 60000)
  if (diffMin < 1) return '刚刚'
  if (diffMin < 60) return `${diffMin} 分钟前`
  const diffHour = Math.floor(diffMin / 60)
  if (diffHour < 24) return `${diffHour} 小时前`
  return `${Math.floor(diffHour / 24)} 天前`
}

function truncate(text: string, max = 14): string {
  return text.length > max ? text.slice(0, max) + '…' : text
}

/** 课程封面：第一帧抽帧图（overview.coverUrls），无则回退首字块 */
function coverUrl(id: number): string | null {
  return overview.value?.coverUrls?.[String(id)] ?? null
}

// ---- 上传资料（视频直传网课流水线；图片引导去对话拍照解题；PDF / PPT 随 B19 后置） ----
const uploadInputRef = ref<HTMLInputElement | null>(null)
const uploadingCourse = ref(false)

function openUpload() {
  uploadInputRef.value?.click()
}

function onUploadFile(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (file) {
    submitUpload(file)
  }
}

function onDropFile(e: DragEvent) {
  const file = e.dataTransfer?.files?.[0]
  if (file) {
    submitUpload(file)
  }
}

async function submitUpload(file: File) {
  const isVideo = file.type.startsWith('video/') || /\.(mp4|mkv|mov|avi|webm|m4v)$/i.test(file.name)
  if (!isVideo) {
    if (file.type.startsWith('image/')) {
      toast.push('图片解题请在对话中发送图片，已为你打开对话')
      router.push('/chat')
    } else {
      toast.push('PDF / PPT 摄取即将上线（B19），当前支持视频与图片')
    }
    return
  }
  if (uploadingCourse.value) {
    return
  }
  uploadingCourse.value = true
  try {
    await uploadCourse(file, file.name)
    toast.push('视频已提交转写处理，可在「课程」查看进度')
    overview.value = await getHomeOverview()
  } catch (e) {
    toast.push(e instanceof Error ? e.message : '上传失败', 'error')
  } finally {
    uploadingCourse.value = false
  }
}

// ---- 右栏 AI 助手：与 /chat 共享同一会话（agent store + agentSocket，流式同步） ----
const agent = useAgentStore()
const draft = ref('')
const assistantBox = ref<HTMLDivElement | null>(null)
const ASSISTANT_CHIPS = [
  { icon: MessageSquareText, text: '解析这段内容' },
  { icon: NotebookPen, text: '生成本章笔记' },
  { icon: ListChecks, text: '出 5 道相关习题' },
  { icon: Sparkles, text: '总结知识点' },
]

function sendDraft() {
  const text = draft.value.trim()
  if (!text || agent.streaming) {
    return
  }
  draft.value = ''
  agent.send(text)
}

// 新消息或流式输出推进时滚到底部
watch(
  () => [agent.messages.length, agent.streaming],
  () => {
    nextTick(() => {
      const box = assistantBox.value
      if (box) {
        box.scrollTop = box.scrollHeight
      }
    })
  }
)
</script>

<template>
  <div class="h-full overflow-y-auto bg-gray-50">
    <div class="px-4 py-5 md:px-6">
      <!-- 加载 / 错误 -->
      <div v-if="loading" class="flex h-64 items-center justify-center text-[13px] text-ink-2">
        加载中…
      </div>
      <div v-else-if="error" class="flex h-64 items-center justify-center text-[13px] text-ink-2">
        {{ error }}
      </div>

      <template v-else-if="overview">
        <div class="grid grid-cols-1 items-start gap-5 lg:grid-cols-[minmax(0,1fr)_340px]">
          <!-- 左主列：banner + 学习卡片（banner 与右栏 AI 助手同排） -->
          <div class="flex min-w-0 flex-col gap-5">
            <!-- 问候 banner：蓝调渐变 + 手写标语（右上）+ 远山装饰 -->
            <div class="relative overflow-hidden rounded-2xl border border-blue-100 bg-gradient-to-r from-blue-200 via-blue-100/70 to-white px-6 py-8 md:px-9 md:py-10">
              <svg class="pointer-events-none absolute bottom-0 right-0 h-20 w-96 text-blue-200/80" viewBox="0 0 384 80" fill="none" preserveAspectRatio="none">
                <path d="M0 80 L70 26 L140 80 Z" fill="currentColor" opacity="0.45" />
                <path d="M110 80 L192 10 L274 80 Z" fill="currentColor" opacity="0.65" />
                <path d="M250 80 L318 32 L386 80 Z" fill="currentColor" opacity="0.4" />
              </svg>
              <p class="pointer-events-none absolute right-8 top-5 select-none text-[14px] font-medium italic text-blue-400/90 md:right-10 md:top-6" style="transform: rotate(-4deg)">
                学习，让你遇见更大的自己
              </p>
              <h1 class="relative mt-2 text-[24px] font-bold tracking-tight text-gray-900 md:text-[28px]">
                {{ greeting }}，{{ overview.nickname || '同学' }} 👋
              </h1>
              <p class="relative mt-1.5 text-[13px] text-gray-500">今天也要加油学习呀！你的知识正在一点点积累～</p>
            </div>

            <!-- 继续学习 -->
            <section v-if="continueCourse" class="rounded-2xl border border-gray-100 bg-white p-6 shadow-sm">
              <div class="flex items-center justify-between">
                <h2 class="flex items-center gap-2 text-[15px] font-bold text-gray-900">
                  <MonitorPlay :size="17" class="text-blue-500" />
                  继续学习
                </h2>
                <RouterLink to="/courses" class="flex items-center text-[12px] text-gray-400 transition-colors hover:text-blue-500">
                  查看全部
                  <ChevronRight :size="13" />
                </RouterLink>
              </div>
              <div class="mt-5 flex flex-col gap-5 lg:flex-row">
                <div class="flex min-w-0 flex-1 items-center gap-4">
                  <div class="h-16 w-24 shrink-0 overflow-hidden rounded-xl bg-gradient-to-br from-blue-500 to-blue-600">
                    <img
                      v-if="coverUrl(continueCourse.id)"
                      :src="coverUrl(continueCourse.id)!"
                      class="h-full w-full object-cover"
                      alt=""
                    />
                    <span v-else class="flex h-full w-full items-center justify-center text-[20px] font-bold text-white">
                      {{ (continueCourse.title || '课').slice(0, 1) }}
                    </span>
                  </div>
                  <div class="min-w-0 flex-1">
                    <p class="truncate text-[16px] font-semibold text-gray-900">{{ continueCourse.title }}</p>
                    <p class="mt-0.5 text-[12px] text-gray-400">
                      网课 · 课程进度 {{ continueCourse.progressPct != null ? continueCourse.progressPct + '%' : '—' }}
                    </p>
                    <p class="mt-1 text-[12px] tabular-nums text-gray-500">
                      {{ formatTs(continueCourse.lastPositionSec) }} / {{ formatDuration(continueCourse.duration) }}
                    </p>
                    <button
                      class="mt-2.5 flex items-center gap-1.5 rounded-full bg-gradient-to-r from-blue-500 to-blue-600 px-4 py-2 text-[13px] font-medium text-white transition-opacity hover:opacity-90"
                      @click="router.push(`/courses/${continueCourse.id}`)"
                    >
                      <Play :size="14" />
                      继续学习
                    </button>
                  </div>
                </div>
                <!-- 本课重点：来自该课最新 AI 笔记的知识点小节 -->
                <div v-if="keyPoints.length > 0" class="rounded-2xl bg-blue-50/70 p-4 lg:w-64 lg:shrink-0">
                  <p class="text-[13px] font-semibold text-gray-900">本课重点</p>
                  <ul class="mt-2 flex flex-col gap-1.5">
                    <li
                      v-for="point in keyPoints"
                      :key="point"
                      class="flex items-start gap-1.5 text-[12px] leading-5 text-gray-600"
                    >
                      <span class="mt-1.5 h-1 w-1 shrink-0 rounded-full bg-blue-400" />
                      <span class="min-w-0">{{ point }}</span>
                    </li>
                  </ul>
                </div>
              </div>
            </section>

            <!-- 今日复习 -->
            <section class="rounded-2xl border border-gray-100 bg-white p-6 shadow-sm">
              <div class="flex items-center justify-between">
                <h2 class="flex items-center gap-2 text-[15px] font-bold text-gray-900">
                  <GraduationCap :size="17" class="text-blue-500" />
                  今日复习
                </h2>
                <RouterLink to="/review" class="flex items-center text-[12px] text-gray-400 transition-colors hover:text-blue-500">
                  查看全部
                  <ChevronRight :size="13" />
                </RouterLink>
              </div>
              <div
                v-if="dueCount > 0"
                class="mt-4 flex flex-col gap-3 rounded-2xl bg-blue-50/70 px-4 py-5 sm:flex-row sm:items-center"
              >
                <div class="flex min-w-0 flex-1 items-start gap-3">
                  <div class="flex h-11 w-11 shrink-0 items-center justify-center rounded-full bg-white text-blue-500 shadow-sm">
                    <Target :size="20" />
                  </div>
                  <div class="min-w-0">
                    <p class="text-[14px] font-medium text-gray-900">
                      发现 <span class="text-blue-500">{{ dueCount }}</span> 个薄弱知识点
                    </p>
                    <p class="mt-0.5 text-[12px] text-gray-500">建议花 {{ suggestMinutes }} 分钟进行复习</p>
                    <div v-if="overview.todayQueue.length > 0" class="mt-2 flex flex-wrap gap-1.5">
                      <span
                        v-for="card in overview.todayQueue"
                        :key="card.frontText"
                        class="rounded-full bg-white px-2.5 py-0.5 text-[11px] text-gray-500"
                      >
                        {{ truncate(card.frontText, 10) }}
                      </span>
                    </div>
                  </div>
                </div>
                <button
                  class="flex shrink-0 items-center gap-1.5 self-start rounded-full bg-gradient-to-r from-blue-500 to-blue-600 px-5 py-2.5 text-[13px] font-medium text-white transition-opacity hover:opacity-90 sm:self-center"
                  @click="router.push('/review')"
                >
                  开始复习
                  <ChevronRight :size="14" />
                </button>
              </div>
              <div v-else class="mt-4 rounded-2xl border border-dashed border-blue-100 px-4 py-6 text-center text-[13px] text-gray-400">
                今日复习已清空 ✅ 到题目 / 笔记详情页「加入复习」，卡片会按记忆曲线出现在这里
              </div>
            </section>

            <!-- 最近学习 -->
            <section class="rounded-2xl border border-gray-100 bg-white p-6 shadow-sm">
              <div class="flex items-center justify-between">
                <h2 class="flex items-center gap-2 text-[15px] font-bold text-gray-900">
                  <Clock :size="17" class="text-blue-500" />
                  最近学习
                </h2>
                <RouterLink to="/courses" class="flex items-center text-[12px] text-gray-400 transition-colors hover:text-blue-500">
                  查看全部
                  <ChevronRight :size="13" />
                </RouterLink>
              </div>
              <div v-if="overview.recentCourses.length > 0" class="mt-4 grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-4">
                <RouterLink
                  v-for="course in overview.recentCourses"
                  :key="course.id"
                  :to="`/courses/${course.id}`"
                  class="rounded-2xl border border-gray-100 p-5 transition-all hover:border-blue-200 hover:shadow-sm"
                >
                  <div class="flex items-center gap-2.5">
                    <div class="h-10 w-10 shrink-0 overflow-hidden rounded-lg bg-gradient-to-br from-blue-400 to-blue-600">
                      <img
                        v-if="coverUrl(course.id)"
                        :src="coverUrl(course.id)!"
                        class="h-full w-full object-cover"
                        alt=""
                      />
                      <span v-else class="flex h-full w-full items-center justify-center text-[13px] font-bold text-white">
                        {{ (course.title || '课').slice(0, 1) }}
                      </span>
                    </div>
                    <p class="min-w-0 flex-1 truncate text-[13px] font-medium text-gray-900">{{ course.title }}</p>
                  </div>
                  <p class="mt-3 text-[11px] text-gray-400">上次学到 {{ formatTs(course.lastPositionSec) }}</p>
                  <div class="mt-2.5 flex items-center gap-2">
                    <div class="h-1.5 flex-1 overflow-hidden rounded-full bg-gray-100">
                      <div
                        class="h-full rounded-full bg-gradient-to-r from-blue-400 to-blue-600"
                        :style="{ width: (course.progressPct ?? 0) + '%' }"
                      />
                    </div>
                    <span class="text-[11px] tabular-nums text-gray-400">{{ course.progressPct != null ? course.progressPct + '%' : '—' }}</span>
                  </div>
                  <p class="mt-2.5 text-[11px] text-gray-400/80">{{ relativeTime(course.lastStudiedAt || course.updatedAt) }}</p>
                </RouterLink>
              </div>
              <div v-else class="mt-4 rounded-2xl border border-dashed border-blue-100 px-4 py-6 text-center text-[13px] text-gray-400">
                暂无学习记录，上传第一门网课吧
              </div>
            </section>

            <!-- 上传资料：视频直传流水线；图片去对话拍照解题；PDF / PPT 随 B19 -->
            <section
              class="flex cursor-pointer flex-col items-center gap-3 rounded-2xl border-2 border-dashed border-blue-200 bg-white px-4 py-6 transition-colors hover:bg-blue-50/40 sm:flex-row sm:px-8"
              :class="uploadingCourse ? 'pointer-events-none opacity-60' : ''"
              @click="openUpload"
              @dragover.prevent
              @drop.prevent="onDropFile"
            >
              <div class="flex h-10 w-10 items-center justify-center rounded-full bg-gradient-to-r from-blue-500 to-blue-600 text-white">
                <Plus :size="18" />
              </div>
              <div class="min-w-0 flex-1 text-center sm:text-left">
                <p class="text-[14px] font-semibold text-gray-900">
                  {{ uploadingCourse ? '正在上传…' : '上传资料' }}
                </p>
                <p class="mt-0.5 text-[12px] text-gray-400">支持视频（点击或拖拽，自动转写）、图片等多种格式</p>
              </div>
              <div class="flex items-center gap-2.5">
                <span class="flex h-9 w-9 items-center justify-center rounded-xl bg-blue-50 text-blue-500" title="视频：点击或拖拽上传" @click.stop="openUpload">
                  <Video :size="17" />
                </span>
                <span class="flex h-9 w-9 items-center justify-center rounded-xl bg-red-50 text-red-400" title="PDF 摄取即将上线" @click.stop="toast.push('PDF 摄取即将上线（B19）')">
                  <FileText :size="17" />
                </span>
                <span class="flex h-9 w-9 items-center justify-center rounded-xl bg-orange-50 text-orange-400" title="PPT 摄取即将上线" @click.stop="toast.push('PPT 摄取即将上线（B19）')">
                  <Presentation :size="17" />
                </span>
                <span class="flex h-9 w-9 items-center justify-center rounded-xl bg-emerald-50 text-emerald-500" title="图片解题：在对话中发送" @click.stop="router.push('/chat'); toast.push('在对话中发送图片即可拍照解题')">
                  <ImageIcon :size="17" />
                </span>
              </div>
              <input ref="uploadInputRef" type="file" accept="video/mp4,video/x-matroska,video/quicktime,video/webm,.mp4,.mkv,.mov,.webm" class="hidden" @change="onUploadFile" />
            </section>
          </div>

          <!-- 右辅列 -->
          <aside class="flex flex-col gap-5">
            <!-- AI 助手：与 /chat 共享会话 -->
            <section class="rounded-2xl border border-gray-100 bg-white p-6 shadow-sm">
              <div class="flex items-center justify-between">
                <h2 class="flex items-center gap-2 text-[15px] font-bold text-gray-900">
                  <Sparkles :size="17" class="text-blue-500" />
                  AI 助手
                </h2>
                <button
                  class="rounded-full border border-blue-200 px-3 py-1 text-[12px] text-blue-500 transition-colors hover:bg-blue-50"
                  title="开新会话（原会话保留在 /chat 历史）"
                  @click="agent.startNew()"
                >
                  新对话
                </button>
              </div>
              <div ref="assistantBox" class="mt-3 flex max-h-[420px] min-h-44 flex-col gap-2.5 overflow-y-auto">
                <div v-if="agent.messages.length === 0" class="text-[12px] leading-5 text-gray-500">
                  <p class="text-[13px] font-semibold text-gray-900">你好！我是你的学习助手</p>
                  <p class="mt-1.5">我可以帮你：</p>
                  <ul class="mt-1 list-disc pl-4">
                    <li>解析课程内容</li>
                    <li>回答学习问题</li>
                    <li>生成笔记、练习题</li>
                    <li>整理知识点和错题</li>
                  </ul>
                </div>
                <template v-for="(msg, i) in agent.messages" :key="i">
                  <div v-if="msg.role === 'user'" class="ml-8 whitespace-pre-wrap rounded-2xl bg-blue-500 px-3 py-2 text-[13px] text-white">
                    {{ msg.content }}
                  </div>
                  <div
                    v-else-if="msg.streaming && !msg.content"
                    class="mr-4 rounded-2xl bg-blue-50/60 px-3 py-2.5 text-[12px] text-blue-400"
                  >
                    正在思考<span class="animate-pulse">…</span>
                  </div>
                  <div
                    v-else
                    class="assistant-md mr-4 rounded-2xl bg-blue-50/60 px-3 py-2 text-[13px] leading-6 text-gray-700"
                    v-html="renderMarkdown(msg.content)"
                  ></div>
                </template>
              </div>
              <div class="mt-3 grid grid-cols-2 gap-2">
                <button
                  v-for="chip in ASSISTANT_CHIPS"
                  :key="chip.text"
                  class="flex items-center gap-1.5 rounded-xl bg-blue-50/70 px-2.5 py-1.5 text-[11px] text-blue-600 transition-colors hover:bg-blue-100"
                  @click="draft = chip.text"
                >
                  <component :is="chip.icon" :size="13" />
                  {{ chip.text }}
                </button>
              </div>
              <div class="relative mt-3">
                <input
                  v-model="draft"
                  class="w-full rounded-full border border-blue-200 bg-white py-2.5 pl-4 pr-12 text-[13px] text-ink outline-none focus:border-blue-400"
                  placeholder="有问题尽管问我…"
                  @keydown.enter="sendDraft"
                />
                <button
                  class="absolute right-1 top-1/2 flex h-8 w-8 -translate-y-1/2 items-center justify-center rounded-full bg-gradient-to-r from-blue-500 to-blue-600 text-white transition-opacity hover:opacity-90 disabled:opacity-40"
                  :disabled="agent.streaming || !draft.trim()"
                  title="发送"
                  @click="sendDraft"
                >
                  <Send :size="14" />
                </button>
              </div>
              <p class="mt-2 text-[11px] text-gray-300">
                {{ agent.streaming ? '正在回答…' : '基于你的学习数据，提供更精准的回答' }}
              </p>
            </section>

            <!-- 学习数据 -->
            <section class="rounded-2xl border border-gray-100 bg-white p-6 shadow-sm">
              <div class="flex items-center justify-between">
                <h2 class="flex items-center gap-2 text-[15px] font-bold text-gray-900">
                  <BookOpen :size="17" class="text-blue-500" />
                  学习数据
                </h2>
                <RouterLink to="/review" class="flex items-center text-[12px] text-gray-400 transition-colors hover:text-blue-500">
                  查看详情
                  <ChevronRight :size="13" />
                </RouterLink>
              </div>
              <div class="mt-4 grid grid-cols-2 gap-3">
                <div class="rounded-2xl bg-gray-50 p-3.5">
                  <div class="flex h-8 w-8 items-center justify-center rounded-full bg-blue-50 text-blue-500">
                    <Clock :size="15" />
                  </div>
                  <p class="mt-2 text-[20px] font-bold tabular-nums text-gray-900">{{ overview.stats.todayStudyMinutes }}</p>
                  <p class="mt-0.5 text-[11px] text-gray-400">今日学习时长（分钟）</p>
                </div>
                <div class="rounded-2xl bg-gray-50 p-3.5">
                  <div class="flex h-8 w-8 items-center justify-center rounded-full bg-emerald-50 text-emerald-500">
                    <MonitorPlay :size="15" />
                  </div>
                  <p class="mt-2 text-[20px] font-bold tabular-nums text-gray-900">{{ overview.stats.coursesTotal }}</p>
                  <p class="mt-0.5 text-[11px] text-gray-400">学习课程数</p>
                </div>
                <div class="rounded-2xl bg-gray-50 p-3.5">
                  <div class="flex h-8 w-8 items-center justify-center rounded-full bg-amber-50 text-amber-500">
                    <CircleCheck :size="15" />
                  </div>
                  <p class="mt-2 text-[20px] font-bold tabular-nums text-gray-900">{{ overview.stats.reviewedToday }}</p>
                  <p class="mt-0.5 text-[11px] text-gray-400">今日已复习</p>
                </div>
                <div class="rounded-2xl bg-gray-50 p-3.5">
                  <div class="flex h-8 w-8 items-center justify-center rounded-full bg-red-50 text-red-400">
                    <AlertTriangle :size="15" />
                  </div>
                  <p class="mt-2 text-[20px] font-bold tabular-nums text-gray-900">{{ overview.stats.dueCount }}</p>
                  <p class="mt-0.5 text-[11px] text-gray-400">待复习卡片</p>
                </div>
              </div>
            </section>

            <!-- 坚持学习 -->
            <section class="relative overflow-hidden rounded-2xl border border-blue-100 bg-gradient-to-br from-blue-100 to-blue-50 p-5">
              <p class="text-[15px] font-bold text-gray-900">坚持学习</p>
              <p class="mt-1 max-w-[60%] text-[12px] leading-5 text-gray-500">会让你看到不一样的风景</p>
              <span class="pointer-events-none absolute -bottom-3 right-3 select-none text-[56px] leading-none">🌱</span>
            </section>
          </aside>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.assistant-md :deep(p) {
  margin-bottom: 0.4rem;
}
.assistant-md :deep(p:last-child) {
  margin-bottom: 0;
}
.assistant-md :deep(ul),
.assistant-md :deep(ol) {
  padding-left: 1.1rem;
}
</style>
