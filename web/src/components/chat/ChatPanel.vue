<script setup lang="ts">
import { nextTick, reactive, ref, watch } from 'vue'
import {
  AlertCircle, Check, Copy, Film, LoaderCircle, MonitorPlay, RefreshCw, RotateCcw, Share2, Sparkles,
  ThumbsDown, ThumbsUp, Volume2,
} from 'lucide-vue-next'
import { useAgentStore } from '../../stores/agent'
import { renderIntentChips, renderMarkdown } from '../../utils/markdown'
import { retryCourse } from '../../api/course'
import IntentCard from './IntentCard.vue'
import { Button } from '@/components/ui/button'
import { Avatar, AvatarFallback } from '@/components/ui/avatar'
import { ScrollArea } from '@/components/ui/scroll-area'

/**
 * 会话消息流面板（/chat 与网课详情「AI 问答」共用）：
 * 渲染 agent store 的消息（流式 / 转写进度 / 折叠 / 动作条）并自动滚底。
 * 输入框由宿主页面自行实现；chipTimestamps 开启时把回答里的 [mm:ss] 渲染成可点击胶囊（emit chip 事件）。
 */
const props = defineProps<{
  /** 空会话时的标题文案 */
  emptyTitle?: string
  /** 是否把 [mm:ss] 渲染成可点击时间戳胶囊 */
  chipTimestamps?: boolean
}>()

const emit = defineEmits<{ chip: [ts: string]; intent: [text: string] }>()

const agent = useAgentStore()
const scrollBox = ref<HTMLElement | null>(null)
const copiedIndex = ref<number | null>(null)
const expandedTranscripts = reactive(new Set<number>())
/** 重试提交中的课程 ID（防重复点击；成功后卡片状态由 COURSE 事件切回 processing） */
const retryingCourseId = ref<number | null>(null)

/** failed 课程任务卡片：重新提交处理（与视频管理页同一条重试链路） */
async function retryFailedCourse(courseId: number) {
  if (retryingCourseId.value !== null) {
    return
  }
  retryingCourseId.value = courseId
  try {
    await retryCourse(courseId)
  } catch {
    // 失败提示由全局请求层/轮询状态体现，这里保持卡片 failed 态可再次重试
  } finally {
    retryingCourseId.value = null
  }
}

watch(
  () => agent.messages.map((m) => m.content).join('|'),
  () => {
    nextTick(() => {
      // 消息流已换 ScrollArea：滚动发生在视口元素上
      const viewport = scrollBox.value?.querySelector<HTMLElement>('[data-slot="scroll-area-viewport"]')
      viewport?.scrollTo({ top: viewport.scrollHeight })
    })
  }
)

async function copyMessage(index: number, content: string) {
  try {
    await navigator.clipboard.writeText(content)
    copiedIndex.value = index
    setTimeout(() => {
      if (copiedIndex.value === index) {
        copiedIndex.value = null
      }
    }, 1500)
  } catch {
    // 剪贴板不可用（非安全上下文等）时静默忽略
  }
}

function formatDuration(sec?: number): string {
  if (!sec && sec !== 0) {
    return ''
  }
  const m = Math.floor(sec / 60)
  const s = Math.floor(sec % 60)
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
}

/** 转写占位气泡的进度文案（B11：分片进度 → 正在转写第 x / y 段） */
function transcriptProgressText(i: number): string {
  const msg = agent.messages[i]
  const t = msg.transcribe
  if (!t) {
    return ''
  }
  if (t.total > 0 && t.done > 0 && t.status === 'processing') {
    return t.done >= t.total ? '正在合并转写文本…' : `正在转写第 ${t.done + 1} / ${t.total} 段…`
  }
  return '正在提取音频…'
}

function toggleTranscript(i: number) {
  const msg = agent.messages[i]
  if (msg.id === undefined) {
    return
  }
  if (expandedTranscripts.has(msg.id)) {
    expandedTranscripts.delete(msg.id)
  } else {
    expandedTranscripts.add(msg.id)
  }
}

/** 转写折叠预览：截取前 600 字符（Markdown 语境下按行截断更整齐） */
function transcriptPreview(content: string): string {
  const limit = 600
  if (content.length <= limit) {
    return content
  }
  const head = content.slice(0, limit)
  return head.slice(0, head.lastIndexOf('\n') > 0 ? head.lastIndexOf('\n') : limit)
}

/** 课程流水线阶段 → 进度文案（事件 text 优先，历史 payload 只有 stage 时按阶段映射） */
function courseStageText(task: { stage: string; text?: string }): string {
  if (task.text) {
    return task.text
  }
  const labels: Record<string, string> = {
    PENDING: '排队等待处理…',
    UPLOADING: '正在上传视频到云存储…',
    EXTRACTING: '正在提取音频与关键帧…',
    TRANSCRIBING: '正在转写语音…',
    ANALYZING: '正在识别画面关键帧…',
    UNDERSTANDING: '正在进行内容理解…',
    REVIEWING: '正在多角色评审…',
    NOTE_GENERATING: '正在生成 AI 笔记…',
    QUIZ_GENERATING: '正在生成课后习题…',
  }
  return labels[task.stage] ?? '正在处理…'
}

/** Markdown 渲染；chipTimestamps 时把 [mm:ss] 包成可点击胶囊（课程问答跳视频用）；[chip:xxx] 渲染为意图选项卡 */
function renderContent(content: string): string {
  const html = renderMarkdown(content)
  let out = renderIntentChips(html)
  if (props.chipTimestamps) {
    out = out.replace(
      /\[(\d{1,2}:[0-5]\d(?::\d{2})?)\]/g,
      '<span class="ts-chip" data-ts="$1">$1</span>'
    )
  }
  return out
}

function onPanelClick(e: MouseEvent) {
  const chip = e.target as HTMLElement
  const intentChip = chip.closest('[data-chip]') as HTMLElement | null
  if (intentChip?.dataset.chip) {
    emit('intent', intentChip.dataset.chip)
    return
  }
  const target = chip.closest('[data-ts]') as HTMLElement | null
  if (target?.dataset.ts) {
    emit('chip', target.dataset.ts)
  }
}
</script>

<template>
  <div ref="scrollBox" class="min-h-0 flex-1" @click="onPanelClick">
    <div v-if="agent.messages.length === 0" class="flex h-full flex-col items-center justify-center gap-5 px-4">
      <svg width="52" height="52" viewBox="0 0 48 48" fill="none" class="text-ink-2/60">
        <path
          d="M24 6c-5 0-9 3.4-10 8-4.2.8-7.5 4.4-7.5 9 0 5 4 9 9 9h17c5 0 9-4 9-9 0-4.6-3.3-8.2-7.5-9C33 9.4 29 6 24 6Z"
          stroke="currentColor"
          stroke-width="2.4"
          stroke-linejoin="round"
        />
        <path d="M19 27l5-5 5 5M24 22v10" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" />
      </svg>
      <h1 class="text-center text-[24px] font-medium tracking-tight text-ink md:text-[26px]">
        {{ emptyTitle || '你好，今天想学点什么？' }}
      </h1>
    </div>

    <ScrollArea v-else class="h-full">
      <div class="mx-auto flex max-w-3xl flex-col gap-5 px-4 py-6">
      <div v-for="(msg, i) in agent.messages" :key="i" class="flex gap-2" :class="msg.role === 'user' ? 'justify-end' : 'justify-start'">
        <!-- AI 标识小图标：仅助手消息显示 -->
        <Avatar
          v-if="msg.role === 'assistant'"
          class="mt-1 size-7 shrink-0"
          title="学迹 AI"
        >
          <AvatarFallback class="bg-primary-soft text-primary">
            <Sparkles :size="14" />
          </AvatarFallback>
        </Avatar>
        <div
          class="max-w-[85%] rounded-2xl px-4 py-2.5 text-[15px] leading-7"
          :class="msg.role === 'user'
            ? 'whitespace-pre-wrap rounded-br-md bg-primary-soft text-ink'
            : 'text-ink'"
        >
          <img
            v-if="msg.imageUrl"
            :src="msg.imageUrl"
            alt="附图"
            class="mb-2 max-h-48 rounded-xl border border-line"
          />
          <!-- B11 视频消息：用户侧显示视频标识 -->
          <div
            v-if="msg.video"
            class="mb-2 flex items-center gap-2 rounded-xl border border-line bg-surface px-3 py-2 text-[13px] text-ink"
          >
            <Film :size="15" class="text-primary" />
            视频<span v-if="msg.video.durationSec" class="text-ink-2">（{{ formatDuration(msg.video.durationSec) }}）</span>
          </div>
          <!-- 助手消息：Markdown + 公式渲染；用户消息：纯文本 -->
          <template v-if="msg.role === 'assistant'">
            <!-- B11 转写占位：进度 -->
            <div v-if="msg.transcribe?.status === 'processing'" class="flex items-center gap-2 py-1 text-[14px] text-ink-2">
              <LoaderCircle :size="15" class="animate-spin text-primary" />
              {{ transcriptProgressText(i) }}
            </div>
            <!-- B11 转写完成：默认折叠，可展开全文 -->
            <template v-else-if="msg.transcribe?.status === 'done'">
              <div class="markdown-body" v-html="renderContent(expandedTranscripts.has(msg.id ?? -1) ? msg.content : transcriptPreview(msg.content) + '\n\n…')"></div>
              <Button
                variant="ghost"
                size="sm"
                class="mt-2 h-7 px-1 text-[13px] text-primary hover:text-primary hover:underline"
                @click="toggleTranscript(i)"
              >
                {{ expandedTranscripts.has(msg.id ?? -1) ? '收起全文' : '查看全文' }}
              </Button>
            </template>
            <!-- B11 分流：课程流水线进度 / 完成链接 -->
            <div v-else-if="msg.courseTask?.status === 'processing'" class="flex items-center gap-2 py-1 text-[14px] text-ink-2">
              <LoaderCircle :size="15" class="animate-spin text-primary" />
              {{ courseStageText(msg.courseTask) }}
            </div>
            <template v-else-if="msg.courseTask?.status === 'done'">
              <div class="text-[14px] leading-6 text-ink">{{ msg.courseTask.text }}</div>
              <Button
                v-if="msg.courseTask.link"
                as-child
                variant="secondary"
                class="mt-2 w-fit rounded-full px-3.5 text-[13px] text-primary hover:text-primary"
              >
                <RouterLink :to="msg.courseTask.link">
                  <MonitorPlay :size="14" />
                  查看课程与 AI 笔记
                </RouterLink>
              </Button>
            </template>
            <!-- 失败卡片：错误文案 + 重试（与视频管理页共用 retry 接口；重试成功后 COURSE 事件切回 processing） -->
            <template v-else-if="msg.courseTask?.status === 'failed'">
              <div class="flex items-start gap-1.5 text-[14px] leading-6 text-red-500">
                <AlertCircle :size="15" class="mt-0.5 shrink-0" />
                <span>{{ msg.courseTask.text || msg.content }}</span>
              </div>
              <Button
                v-if="msg.courseTask.courseId"
                variant="outline"
                size="sm"
                class="mt-2 w-fit rounded-full px-3.5 text-[13px]"
                :disabled="retryingCourseId !== null"
                @click="retryFailedCourse(msg.courseTask.courseId)"
              >
                <RotateCcw :size="14" />
                {{ retryingCourseId === msg.courseTask.courseId ? '已重新提交处理…' : '重试' }}
              </Button>
            </template>
            <!-- B27 意图确认大卡片：结构化提问 + 选项组 + 确认按钮（点击回发拼接文本）；confirmed 时折叠为单行 -->
            <template v-else-if="msg.intentCard">
              <div class="break-words text-[14px] leading-6 text-ink">{{ msg.intentCard.message }}</div>
              <IntentCard :card="msg.intentCard" :confirmed="msg.intentCard.confirmed" @confirm="emit('intent', $event)" />
            </template>
            <div v-else class="markdown-body break-words" v-html="renderContent(msg.content)"></div>

            <!-- 动作条：复制可用，其余为占位 -->
            <div v-if="!msg.streaming" class="mt-2.5 flex items-center gap-0.5 text-ink-2">
              <Button
                variant="ghost"
                size="icon"
                class="size-7 rounded-md text-ink-2 hover:text-ink"
                :title="copiedIndex === i ? '已复制' : '复制'"
                @click="copyMessage(i, msg.content)"
              >
                <Check v-if="copiedIndex === i" :size="15" class="text-primary" />
                <Copy v-else :size="15" />
              </Button>
              <Button variant="ghost" size="icon" class="size-7 rounded-md text-ink-2 hover:text-ink" title="重新生成（开发中）">
                <RefreshCw :size="15" />
              </Button>
              <Button variant="ghost" size="icon" class="size-7 rounded-md text-ink-2 hover:text-ink" title="有帮助（开发中）">
                <ThumbsUp :size="15" />
              </Button>
              <Button variant="ghost" size="icon" class="size-7 rounded-md text-ink-2 hover:text-ink" title="没帮助（开发中）">
                <ThumbsDown :size="15" />
              </Button>
              <Button variant="ghost" size="icon" class="size-7 rounded-md text-ink-2 hover:text-ink" title="朗读（开发中）">
                <Volume2 :size="15" />
              </Button>
              <Button variant="ghost" size="icon" class="size-7 rounded-md text-ink-2 hover:text-ink" title="分享（开发中）">
                <Share2 :size="15" />
              </Button>
            </div>

            <!-- 解答类回答：保存引导 -->
            <p
              v-if="msg.fromQuestion && !msg.streaming"
              class="mt-2.5 rounded-xl bg-panel px-3 py-2 text-[13px] leading-5 text-ink-2"
            >
              这道题如果值得整理，回复「保存」，我会把它收进题目管理，方便考前集中复习。
            </p>
            </template>
            <template v-else>{{ msg.content }}</template>
            <span v-if="msg.streaming" class="animate-pulse text-primary">▍</span>
          </div>
        </div>
      </div>
    </ScrollArea>
    </div>
</template>

<style scoped>
.ts-chip {
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

/* 意图确认选项卡片（视频分流）：点击即作为用户消息发送 */
.intent-chip {
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

.intent-chip:hover {
  background: #dbeafe;
  border-color: #60a5fa;
}
</style>
