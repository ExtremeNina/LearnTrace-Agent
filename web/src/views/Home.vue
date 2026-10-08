<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  ChevronRight, Clock, Film, GraduationCap, History,
  LoaderCircle, MonitorPlay, Play, Plus,
  Send, Sparkles, Target, X,
} from 'lucide-vue-next'
import { getHomeOverview, heartbeatStudyTime } from '../api/home'
import type { HomeOverview } from '../api/home'
import ModelPicker from '../components/chat/ModelPicker.vue'
import { useAgentStore } from '../stores/agent'
import { useReviewModalStore } from '../stores/reviewModal'
import { renderIntentChips, renderMarkdown } from '../utils/markdown'
import bannerWaterUrl from '../assets/banner-water.webp'

/**
 * 首页学习仪表盘（B25，视觉按设计稿实现——靛蓝色系 / 渐变按钮 / 卡片白底圆角；深色主题适配后置）：
 * 问候 banner + 继续学习（本课重点）/ 今日复习 / 最近学习（左主列）
 * + AI 助手（与 /chat 共享会话，高度与左列最近学习下边界对齐，输入框加号整合上传资料）。
 * 学习数据 / 坚持学习 / 独立上传资料卡已按用户反馈移除。推荐学习不做（拍板）。
 */
defineOptions({ name: 'HomeView' })

const router = useRouter()
const overview = ref<HomeOverview | null>(null)
const loading = ref(true)
const error = ref('')
const reviewModal = useReviewModalStore()

// 复习弹窗关闭后刷新首页统计（待复习数 / 今日队列 chips 同步）
watch(
  () => reviewModal.isOpen,
  (open, old) => {
    if (!open && old) {
      getHomeOverview()
        .then((v) => (overview.value = v))
        .catch(() => undefined)
    }
  },
)

onMounted(async () => {
  // 先确保 agentSocket 已连接（/chat 页挂载时才连，首页直连否则流式消息堵在 outbox）
  agent.ensureSocketConnected()
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
  // 每日首次打开：AI 对话模块自动加载今日简报（B26 反馈：简报移入对话）
  loadDailyBriefing()
  // 学习时长心跳：每 60 秒上报一次在站时长（仅页面可见时）
  heartbeatTimer = setInterval(() => flushStudyTime(60), 60_000)
})

// ---- 今日简报（对话气泡形态；成功后缓存，刷新注入，开新对话才消失） ----

function loadDailyBriefing() {
  // 当日简报已生成过 → 直接注入缓存气泡（生成失败无缓存时会走到下方重新生成，天然实现次日/下次进入重试）
  if (agent.injectCachedBriefing()) {
    return
  }
  agent.showBriefingPlaceholder()
  agent
    .loadBriefing()
    .catch(() => {
      // 简报生成失败静默：移除占位气泡，不干扰对话（下次进入重试）
      agent.removeBriefingPlaceholder()
    })
}

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

// ---- 右栏 AI 助手：与 /chat 共享同一会话（agent store + agentSocket，流式同步） ----
const agent = useAgentStore()
const draft = ref('')
const assistantBox = ref<HTMLDivElement | null>(null)
const uploadInputRef = ref<HTMLInputElement | null>(null)
// 历史对话弹窗（B26 反馈：对话模块直接可见的历史入口）
const showHistory = ref(false)

async function openHistory() {
  showHistory.value = true
  await agent.loadConversations()
}

async function pickConversation(id: number) {
  showHistory.value = false
  await agent.openConversation(id)
}

// ---- 上传资料（整合进 AI 助手输入框加号，统一走对话分流：
// ≤30min 默认语音转写 / >30min 或「做成课程」走课程流水线，进度回流对话） ----
function openUpload() {
  if (agent.uploading) {
    return
  }
  uploadInputRef.value?.click()
}

function onUploadFile(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (file) {
    agent.uploadPendingVideo(file)
  }
}

function sendDraft() {
  const text = draft.value.trim()
  if ((text === '' && !agent.pendingVideo) || agent.streaming || agent.uploading) {
    return
  }
  const content = text || '请转写这个视频'
  draft.value = ''
  agent.send(content)
}

/** 意图确认选项卡片点击（事件委托）：选项文本直接作为用户消息发送 */
function onAssistantClick(e: MouseEvent) {
  const chip = (e.target as HTMLElement).closest('[data-chip]') as HTMLElement | null
  if (chip?.dataset.chip && !agent.streaming && !agent.uploading) {
    agent.send(chip.dataset.chip)
  }
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
        <div class="grid grid-cols-1 gap-5 lg:grid-cols-[minmax(0,1fr)_400px]">
            <!-- 问候 banner：水中照片背景 + 浅蓝渐变遮罩（保证文字可读）+ 手写标语（右上） -->
            <div class="relative min-w-0 overflow-hidden rounded-lg border border-blue-100 px-6 py-8 md:px-9 md:py-10 lg:col-start-1 lg:row-start-1">
              <img :src="bannerWaterUrl" class="absolute inset-0 h-full w-full object-cover" alt="" />
              <div class="absolute inset-0 bg-gradient-to-r from-blue-100/90 via-blue-50/75 to-blue-100/30" />
              <p class="pointer-events-none absolute right-8 top-5 select-none text-[14px] font-medium italic text-blue-500/90 md:right-10 md:top-6" style="transform: rotate(-4deg)">
                学习，让你遇见更大的自己
              </p>
              <h1 class="relative mt-2 text-[24px] font-bold tracking-tight text-gray-900 md:text-[28px]">
                {{ greeting }}，{{ overview.nickname || '同学' }} 👋
              </h1>
              <p class="relative mt-1.5 text-[13px] text-gray-700/90">今天也要加油学习呀！你的知识正在一点点积累～</p>
            </div>

            <!-- 继续学习 -->
            <section v-if="continueCourse" class="min-w-0 rounded-lg border border-gray-100 bg-white p-6 shadow-sm lg:col-start-1 lg:row-start-2">
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
              <div class="mt-5 flex flex-col gap-4 lg:flex-row">
                <!-- 左：软底面板——封面 + 文字列（进度条 / 时间）+ 右下角继续按钮 -->
                <div class="flex min-w-0 flex-1 items-stretch gap-4 rounded-lg border border-transparent bg-gray-50/80 p-4 transition-all hover:border-blue-200 hover:bg-white hover:shadow-sm">
                  <div class="w-36 shrink-0 self-stretch overflow-hidden rounded-lg bg-gradient-to-br from-blue-500 to-blue-600">
                    <img
                      v-if="coverUrl(continueCourse.id)"
                      :src="coverUrl(continueCourse.id)!"
                      class="h-full w-full object-cover"
                      alt=""
                    />
                    <span v-else class="flex h-full w-full items-center justify-center text-[24px] font-bold text-white">
                      {{ (continueCourse.title || '课').slice(0, 1) }}
                    </span>
                  </div>
                  <div class="flex min-w-0 flex-1 flex-col justify-center py-0.5">
                    <p class="truncate text-[16px] font-semibold text-gray-900">{{ continueCourse.title }}</p>
                    <p class="mt-1 text-[12px] text-gray-400">
                      {{ continueCourse.subject || '网课' }} · 课程进度 {{ continueCourse.progressPct != null ? continueCourse.progressPct + '%' : '—' }}
                    </p>
                    <div class="mt-3 h-1.5 w-full overflow-hidden rounded-full bg-gray-200/70">
                      <div
                        class="h-full rounded-full bg-gradient-to-r from-blue-400 to-blue-600"
                        :style="{ width: (continueCourse.progressPct ?? 0) + '%' }"
                      />
                    </div>
                    <div class="mt-4 flex items-end justify-between">
                      <p class="text-[12px] tabular-nums text-gray-500">
                        {{ formatTs(continueCourse.lastPositionSec) }} / {{ formatDuration(continueCourse.duration) }}
                      </p>
                      <button
                        class="flex shrink-0 items-center gap-1.5 rounded-full bg-gradient-to-r from-blue-500 to-blue-600 px-4 py-2 text-[13px] font-medium text-white transition-opacity hover:opacity-90"
                        @click="router.push(`/courses/${continueCourse.id}?t=${formatTs(continueCourse.lastPositionSec)}`)"
                      >
                        <Play :size="14" />
                        继续学习
                      </button>
                    </div>
                  </div>
                </div>
                <!-- 右：本课重点（最新 AI 笔记知识点 / LLM 从转写提炼） -->
                <div v-if="keyPoints.length > 0" class="rounded-lg border border-transparent bg-blue-50/70 p-4 transition-all hover:border-blue-200 hover:shadow-sm lg:w-60 lg:shrink-0">
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
            <section class="min-w-0 rounded-lg border border-gray-100 bg-white p-6 shadow-sm lg:col-start-1 lg:row-start-3">
              <div class="flex items-center justify-between">
                <h2 class="flex items-center gap-2 text-[15px] font-bold text-gray-900">
                  <GraduationCap :size="17" class="text-blue-500" />
                  今日复习
                </h2>
                <button class="flex items-center text-[12px] text-gray-400 transition-colors hover:text-blue-500" @click="reviewModal.open()">
                  查看全部
                  <ChevronRight :size="13" />
                </button>
              </div>
              <div
                v-if="dueCount > 0"
                class="mt-4 flex flex-col gap-3 rounded-lg border border-transparent bg-blue-50/70 px-4 py-5 transition-all hover:border-blue-200 hover:bg-white hover:shadow-sm sm:flex-row sm:items-center"
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
                  @click="reviewModal.open()"
                >
                  开始复习
                  <ChevronRight :size="14" />
                </button>
              </div>
              <div v-else class="mt-4 rounded-lg border border-dashed border-blue-100 px-4 py-6 text-center text-[13px] text-gray-400">
                今日复习已清空 ✅ 到题目 / 笔记详情页「加入复习」，卡片会按记忆曲线出现在这里
              </div>
            </section>

            <!-- 最近学习 -->
            <section class="min-w-0 rounded-lg border border-gray-100 bg-white p-6 shadow-sm lg:col-start-1 lg:row-start-4">
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
                  class="rounded-lg border border-gray-100 bg-white p-4 transition-all hover:border-blue-200 hover:shadow-sm"
                >
                  <div class="h-28 w-full overflow-hidden rounded-lg bg-gradient-to-br from-blue-400 to-blue-600">
                    <img
                      v-if="coverUrl(course.id)"
                      :src="coverUrl(course.id)!"
                      class="h-full w-full object-cover"
                      alt=""
                    />
                    <span v-else class="flex h-full w-full items-center justify-center text-[20px] font-bold text-white">
                      {{ (course.title || '课').slice(0, 1) }}
                    </span>
                  </div>
                  <p class="mt-3 truncate text-[14px] font-semibold text-gray-900">{{ course.title }}</p>
                  <p class="mt-1.5 text-[11px] text-gray-400">上次学到: {{ formatTs(course.lastPositionSec) }}</p>
                  <div class="mt-2 flex items-center gap-2">
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
              <div v-else class="mt-4 rounded-lg border border-dashed border-blue-100 px-4 py-6 text-center text-[13px] text-gray-400">
                暂无学习记录，上传第一门网课吧
              </div>
            </section>

            <!-- AI 助手：与 /chat 共享会话。wrapper 占右列 grid 位（高度完全由左列四行决定），
                 面板 lg 下绝对定位填满 wrapper——不参与行高计算，对话再长也不撑高左列三卡片；
                 消息区 min-h-0 flex-1 内部滚动（对话内滚动条），下边界精确对齐最近学习卡 -->
            <div class="min-h-44 lg:col-start-2 lg:row-start-1 lg:row-span-4 lg:relative">
              <section class="flex flex-col overflow-hidden rounded-lg border border-gray-100 bg-white p-6 shadow-sm lg:absolute lg:inset-0">
              <div class="flex items-center justify-between">
                <h2 class="flex items-center gap-2 text-[15px] font-bold text-gray-900">
                  <Sparkles :size="17" class="text-blue-500" />
                  AI 助手
                </h2>
                <div class="flex items-center gap-2">
                  <button
                    class="flex h-7 w-7 items-center justify-center rounded-full border border-blue-200 text-blue-500 transition-colors hover:bg-blue-50"
                    title="查看历史对话"
                    @click="openHistory"
                  >
                    <History :size="14" />
                  </button>
                  <button
                    class="rounded-full border border-blue-200 px-3 py-1 text-[12px] text-blue-500 transition-colors hover:bg-blue-50"
                    title="开新会话（原会话保留在 /chat 历史）"
                    @click="agent.startNew()"
                  >
                    新对话
                  </button>
                </div>
              </div>
              <div
                ref="assistantBox"
                class="mt-3 flex min-h-0 flex-1 flex-col gap-2.5 overflow-y-auto pr-3"
                @click="onAssistantClick"
              >
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
                  <div v-if="msg.role === 'user'" class="ml-auto w-fit max-w-[85%] rounded-lg bg-blue-500 px-3 py-2 text-[13px] text-white">
                    <!-- B11 视频消息标识（与 ChatPanel 一致） -->
                    <div v-if="msg.video" class="mb-1.5 flex items-center gap-1.5 rounded-md bg-white/15 px-2 py-1 text-[12px]">
                      <Film :size="13" />
                      视频<span v-if="msg.video.durationSec" class="text-white/80">（{{ formatDuration(msg.video.durationSec) }}）</span>
                    </div>
                    <span class="whitespace-pre-wrap">{{ msg.content }}</span>
                  </div>
                  <div
                    v-else-if="msg.streaming && !msg.content && !msg.transcribe && !msg.courseTask"
                    class="mr-4 text-[12px] text-blue-400"
                  >
                    正在思考<span class="animate-pulse">…</span>
                  </div>
                  <!-- B11 后台任务进度：转写分片 / 课程流水线阶段 -->
                  <div
                    v-else-if="msg.transcribe?.status === 'processing' || msg.courseTask?.status === 'processing'"
                    class="mr-4 flex items-center gap-2 py-1 text-[12px] text-blue-400"
                  >
                    <LoaderCircle :size="13" class="animate-spin" />
                    {{ msg.transcribe
                      ? (msg.transcribe.total > 0 && msg.transcribe.done > 0 ? `正在转写第 ${msg.transcribe.done + 1} / ${msg.transcribe.total} 段…` : '正在提取音频…')
                      : (msg.courseTask?.text || '课程处理中…') }}
                  </div>
                  <!-- B11 课程任务完成：附跳转链接 -->
                  <template v-else-if="msg.courseTask?.status === 'done'">
                    <div class="mr-4 text-[13px] leading-6 text-gray-700">{{ msg.courseTask.text }}</div>
                    <RouterLink
                      v-if="msg.courseTask.link"
                      :to="msg.courseTask.link"
                      class="mr-4 mt-1.5 inline-flex items-center gap-1.5 rounded-full bg-blue-50 px-3 py-1.5 text-[12px] text-blue-600 transition-colors hover:bg-blue-100"
                    >
                      <MonitorPlay :size="13" />
                      查看课程与 AI 笔记
                    </RouterLink>
                  </template>
                  <div
                    v-else
                    class="assistant-md mr-4 text-[13px] leading-6 text-gray-700"
                    v-html="renderIntentChips(renderMarkdown(msg.content))"
                  ></div>
                </template>
              </div>
              <!-- 模型切换器（与对话页一致，点击切换 / 管理模型） -->
              <div class="mt-3 flex items-center">
                <ModelPicker tone="blue" />
              </div>
              <div class="relative mt-2">
                <div class="flex items-center rounded-full border border-blue-200 bg-white pl-2 pr-1 transition-colors focus-within:border-blue-400">
                  <!-- 加号：整合上传资料（统一走对话分流：≤30min 默认转写 / >30min 或「做成课程」走课程流水线） -->
                  <button
                    class="flex h-8 w-8 shrink-0 items-center justify-center rounded-full text-ink-2 transition-colors hover:bg-blue-50 hover:text-blue-500"
                    :class="agent.uploading ? 'animate-pulse text-blue-400' : ''"
                    :title="agent.uploading ? '正在上传…' : '上传资料（≤30 分钟默认仅转写语音；>30 分钟自动按网课处理）'"
                    :disabled="agent.uploading"
                    @click="openUpload"
                  >
                    <Plus :size="17" />
                  </button>
                  <input
                    v-model="draft"
                    class="min-w-0 flex-1 bg-transparent py-2.5 pr-2 text-[13px] text-ink outline-none"
                    placeholder="有问题尽管问我…"
                    @keydown.enter="sendDraft"
                  />
                  <button
                    class="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-gradient-to-r from-blue-500 to-blue-600 text-white transition-opacity hover:opacity-90 disabled:opacity-40"
                    :disabled="agent.streaming || !draft.trim()"
                    title="发送"
                    @click="sendDraft"
                  >
                    <Send :size="14" />
                  </button>
                </div>
              </div>
              <p class="mt-2 text-[11px] text-gray-500">
                {{ agent.uploading ? '视频上传中（大视频需耐心等待）…' : agent.pendingVideo ? '视频已就绪，发送后按意图转写或建课' : agent.streaming ? '正在回答…' : '基于你的学习数据，提供更精准的回答' }}
              </p>
              <input ref="uploadInputRef" type="file" accept="video/mp4,video/x-matroska,video/quicktime,video/webm,.mp4,.mkv,.mov,.webm" class="hidden" @change="onUploadFile" />

              <!-- 历史对话弹窗 -->
              <div
                v-if="showHistory"
                class="fixed inset-0 z-50 flex items-center justify-center bg-ink/25 px-4 backdrop-blur-sm"
                @click.self="showHistory = false"
              >
                <div class="max-h-[70vh] w-full max-w-md overflow-y-auto rounded-3xl border border-line bg-surface p-5 shadow-xl">
                  <div class="flex items-center justify-between">
                    <h3 class="text-[15px] font-semibold text-ink">历史对话</h3>
                    <button
                      class="flex h-8 w-8 items-center justify-center rounded-lg text-ink-2 hover:bg-line/60 hover:text-ink"
                      title="关闭"
                      @click="showHistory = false"
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
                      @click="pickConversation(c.id)"
                    >
                      <span class="truncate">{{ c.title }}</span>
                      <span class="shrink-0 text-[11px] text-ink-2">{{ relativeTime(c.updatedAt ?? c.createdAt) }}</span>
                    </button>
                  </div>
                </div>
              </div>
            </section>
            </div>
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
.assistant-md :deep(.intent-chip) {
  display: inline-flex;
  align-items: center;
  margin: 0.25rem 0.5rem 0.25rem 0;
  padding: 0.3rem 0.9rem;
  border: 1px solid #bfdbfe;
  border-radius: 9999px;
  background: #eff6ff;
  color: #2563eb;
  font-size: 13px;
  line-height: 1.4;
  cursor: pointer;
  transition: background 0.15s ease, border-color 0.15s ease;
}
.assistant-md :deep(.intent-chip:hover) {
  background: #dbeafe;
  border-color: #60a5fa;
}
</style>
