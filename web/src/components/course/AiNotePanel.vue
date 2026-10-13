<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref, watch } from 'vue'
import { Check, ChevronDown, History, Send, X } from 'lucide-vue-next'
import type { CourseDetailData } from '../../api/course'
import { updateNoteContent } from '../../api/note'
import { renderNoteHtml } from '../../utils/markdown'
import { parseTs } from '../../utils/format'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/ui/collapsible'
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from '@/components/ui/dropdown-menu'
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs'
import MdSourceEditor from '../notes/MdSourceEditor.vue'
import ChatPanel from '../chat/ChatPanel.vue'
import ModelPicker from '../chat/ModelPicker.vue'
import TranscriptPanel from './TranscriptPanel.vue'
import QuizPanel from './QuizPanel.vue'
import { useAgentStore } from '../../stores/agent'

/**
 * 右列 AI 模块面板（课程详情）：tab 栏（Tabs）+ AI 笔记 / 课后习题 / 转写对照 / AI 问答四个内容区
 * + AI 笔记分页底栏（Pagination 语义，章节直达用 DropdownMenu）+ 历史对话弹窗（Dialog）。
 * 根元素为 Card——与首页"继续学习"卡片同一视觉语言（描边 + 贴地阴影）。
 * 所有视频跳转通过 emit('seek') 上抛，编辑态由父层 v-model:editing 托管（头部「编辑」按钮触发）。
 */
const props = defineProps<{
  data: CourseDetailData
  courseId: number
  duration: number
  getCurrentSec: () => number
  /** AI 笔记编辑态（父层头部「编辑」按钮 v-model 托管） */
  editing: boolean
  /** 刷新课程数据（追加出题轮询用，数据所有权在父层） */
  reload: () => Promise<void>
}>()

const emit = defineEmits<{ seek: [sec: number]; 'update:editing': [v: boolean] }>()

const agent = useAgentStore()

const tabs = [
  { key: 'note', label: 'AI 笔记' },
  { key: 'quiz', label: '课后习题' },
  { key: 'transcript', label: '转写对照' },
  { key: 'ask', label: 'AI 问答' },
] as const

const activeTab = ref<string>('note')

watch(activeTab, () => {
  pageNavOpen.value = false
})

// ---- AI 笔记分页：按顶级标题（## 段）切页（生成侧按章节分页产出，展示侧同构切分），每页独立走渲染管线 ----
const notePages = computed(() => {
  const note = props.data.note
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
  if (activeTab.value !== 'note' || props.editing || notePages.value.length <= 1) return
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
  () => props.data.note?.content,
  () => {
    notePageIndex.value = 0
    pageNavOpen.value = false
  }
)

/** 页码跳转下拉：底栏页码可点，向上展开「页码 + 章节名」列表直达任意页 */
const pageNavOpen = ref(false)

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
    emit('seek', parseTs(chip.getAttribute('data-ts') || ''))
  }
}

window.addEventListener('keydown', onNoteKeydown)
onUnmounted(() => {
  window.removeEventListener('keydown', onNoteKeydown)
})

// ---- 在线编辑 AI 笔记：Markdown 源码编辑，保存回笔记正文接口（入口为父层头部「编辑」按钮） ----
const noteDraft = ref('')
const noteSaving = ref(false)
const noteError = ref('')

function startNoteEdit() {
  if (!props.data.note) {
    return
  }
  noteDraft.value = props.data.note.content
  noteError.value = ''
  noteSaving.value = false
  emit('update:editing', true)
}

async function saveNoteEdit() {
  if (!props.data.note) {
    return
  }
  noteSaving.value = true
  noteError.value = ''
  try {
    await updateNoteContent(props.data.note.id, noteDraft.value)
    props.data.note.content = noteDraft.value
    emit('update:editing', false)
  } catch (e) {
    noteError.value = e instanceof Error ? e.message : '保存失败，请稍后重试'
  } finally {
    noteSaving.value = false
  }
}

defineExpose({ startNoteEdit })

// ---- AI 问答（与 /chat 共享会话与流式状态；随消息透传当前播放位置） ----
const askDraft = ref('')

function sendAsk() {
  const text = askDraft.value.trim()
  if (!text || agent.streaming) {
    return
  }
  askDraft.value = ''
  const currentSec = props.data.course.videoOssKey ? Math.floor(props.getCurrentSec()) : 0
  agent.send(text, currentSec > 0 ? currentSec : undefined)
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
</script>

<template>
  <Card class="flex min-h-0 flex-1 flex-col gap-0 rounded-xl p-0">
    <Tabs v-model="activeTab" class="flex min-h-0 flex-1 flex-col gap-0">
      <TabsList class="h-auto w-full shrink-0 gap-1 rounded-none border-b border-line bg-transparent p-2">
        <TabsTrigger
          v-for="tab in tabs"
          :key="tab.key"
          :value="tab.key"
          class="h-auto flex-1 rounded-lg border-0 py-1.5 text-[14px] font-normal text-ink-2 hover:text-ink data-[state=active]:bg-surface data-[state=active]:font-medium data-[state=active]:text-ink data-[state=active]:shadow-sm"
        >
          {{ tab.label }}
        </TabsTrigger>
      </TabsList>

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
        <ChatPanel chip-timestamps empty-title="学习中有疑问？直接问我" @chip="(ts) => emit('seek', parseTs(ts))" />
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

      <!-- 课后习题：面板见 components/course/QuizPanel.vue -->
      <QuizPanel
        v-else-if="activeTab === 'quiz'"
        :course-id="courseId"
        :questions="data.quizQuestions ?? []"
        :error-msg="data.course.errorMsg"
        :reload="reload"
        @seek="(sec) => emit('seek', sec)"
      />

      <div v-else ref="noteScrollRef" class="min-h-0 flex-1 overflow-y-auto p-3">
        <!-- AI 笔记 -->
        <template v-if="activeTab === 'note'">
          <!-- 知识点折叠面板：默认收起，展开后内容过多时内部滚动，避免挤压正文（章节导航由时间线表格与底栏页码承担） -->
          <Collapsible v-if="data.knowledgePoints?.length" v-model:open="knowledgeExpanded" class="mb-3 rounded-xl bg-panel px-3 py-2.5">
            <CollapsibleTrigger class="flex w-full items-center justify-between text-left">
              <span class="text-[12px] font-semibold text-ink">
                重点知识点
                <Badge variant="secondary" class="mx-1 font-normal">{{ data.knowledgePoints.length }}</Badge>
                个
              </span>
              <ChevronDown
                :size="14"
                class="shrink-0 text-ink-2 transition-transform"
                :class="knowledgeExpanded ? '' : '-rotate-90'"
              />
            </CollapsibleTrigger>
            <CollapsibleContent class="mt-2 flex max-h-48 flex-wrap gap-1.5 overflow-y-auto">
              <button
                v-for="p in data.knowledgePoints"
                :key="p.id"
                class="rounded-full bg-surface px-2.5 py-1 text-[11px] text-ink-2 transition-colors hover:text-primary"
                :title="p.detail || ''"
                @click="p.timeSec != null && emit('seek', p.timeSec)"
              >
                {{ p.name }}<span v-if="p.important === 1" class="text-amber-500"> · 重点</span><span v-if="p.errorProne === 1" class="text-red-400"> · 易错</span>
              </button>
            </CollapsibleContent>
          </Collapsible>
          <div v-if="editing" class="px-1 pb-3">
            <p v-if="noteError" class="mb-2 text-[12px] text-red-600">{{ noteError }}</p>
            <MdSourceEditor v-model="noteDraft" height-class="h-[420px]" @chip="(ts) => emit('seek', parseTs(ts))">
              <template #actions>
                <button
                  class="flex items-center gap-1 rounded-lg bg-ink px-3 py-1.5 text-[13px] text-white hover:opacity-80 disabled:opacity-50"
                  :disabled="noteSaving"
                  @click="saveNoteEdit"
                >
                  <Send :size="14" />
                  保存
                </button>
                <button
                  class="flex items-center gap-1 rounded-lg border border-line px-3 py-1.5 text-[13px] text-ink-2 hover:bg-line/40"
                  @click="emit('update:editing', false)"
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
          @seek="(sec) => emit('seek', sec)"
        />
      </div>
    </Tabs>

    <!-- 分页：结构性底栏（固定在面板底部、不参与滚动），仅 AI 笔记阅读态且多页时显示；页码可点开下拉直达任意页 -->
    <div
      v-if="activeTab === 'note' && !editing && notePages.length > 1"
      class="flex shrink-0 items-center justify-between gap-3 border-t border-line bg-panel px-3 py-2 text-[13px] text-ink-2"
    >
      <DropdownMenu v-model:open="pageNavOpen">
        <DropdownMenuTrigger as-child>
          <button
            type="button"
            class="flex max-w-full items-center gap-1 rounded-lg px-2 py-1 transition-colors hover:bg-line/60"
            title="点击展开页面列表，选择章节直达"
          >
            <span class="truncate">第 {{ notePageIndex + 1 }} / {{ notePages.length }} 页 · {{ notePages[notePageIndex].label }}</span>
            <ChevronDown :size="13" class="shrink-0 transition-transform" :class="pageNavOpen ? '' : '-rotate-90'" />
          </button>
        </DropdownMenuTrigger>
        <DropdownMenuContent side="top" align="start" class="max-h-64 w-72 overflow-y-auto">
          <DropdownMenuItem
            v-for="(page, i) in notePages"
            :key="i"
            class="gap-2"
            :class="i === notePageIndex ? 'bg-panel font-medium text-primary' : ''"
            :title="page.label"
            @select="jumpToNotePage(i)"
          >
            <span class="w-5 shrink-0 text-right text-[11px] text-ink-2">{{ i + 1 }}</span>
            <span class="truncate">{{ page.label }}</span>
            <Check v-if="i === notePageIndex" :size="13" class="ml-auto shrink-0 text-primary" />
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
      <div class="flex shrink-0 items-center gap-2">
        <Button
          variant="outline"
          size="sm"
          class="h-auto rounded-lg border-line bg-surface px-3 py-1.5 text-[12px]"
          :disabled="notePageIndex <= 0"
          @click="goNotePage(notePageIndex - 1)"
        >
          上一页
        </Button>
        <Button
          variant="outline"
          size="sm"
          class="h-auto rounded-lg border-line bg-surface px-3 py-1.5 text-[12px]"
          :disabled="notePageIndex >= notePages.length - 1"
          @click="goNotePage(notePageIndex + 1)"
        >
          下一页
        </Button>
      </div>
    </div>

    <!-- AI 问答历史对话弹窗（课程页默认新会话，可切换历史会话） -->
    <Dialog v-model:open="showAskHistory">
      <DialogContent class="max-w-md">
        <DialogHeader>
          <DialogTitle class="text-[15px] font-semibold">历史对话</DialogTitle>
        </DialogHeader>
        <p v-if="agent.conversations.length === 0" class="py-6 text-center text-[13px] text-ink-2">
          暂无会话，发一条消息开始吧
        </p>
        <div class="mt-3 flex max-h-[55vh] flex-col gap-1 overflow-y-auto">
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
      </DialogContent>
    </Dialog>
  </Card>
</template>
