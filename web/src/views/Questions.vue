<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { BookmarkPlus, Camera, Check, ImageOff, Pencil, Sparkles, Trash2, X } from 'lucide-vue-next'
import * as questionApi from '../api/question'
import * as conversationApi from '../api/conversation'
import * as reviewApi from '../api/review'
import { useAgentStore } from '../stores/agent'
import { useToastStore } from '../stores/toast'
import type { QuestionItemInfo } from '../types/api'
import { SUBJECTS } from '../constants/subjects'
import { renderMarkdown } from '../utils/markdown'

/**
 * 题目记录（PRD §3.3 + §8）：拍照题目与 AI 生成的相似题合并列表（相似题标注「AI 生成」）、
 * 按日期与学科筛选、详情、编辑与删除、一键生成相似题
 */
const PAGE_SIZE = 10

const agentStore = useAgentStore()
const toast = useToastStore()
const router = useRouter()

const records = ref<QuestionItemInfo[]>([])
const loading = ref(false)
const error = ref('')
const page = ref(1)
const pages = ref(1)
const total = ref(0)
/** 按日期筛选（yyyy-MM-dd），空为全部 */
const dateFilter = ref('')
/** 按学科筛选，空为全部 */
const subjectFilter = ref('')
/** 当前查看 / 编辑的记录 */
const active = ref<QuestionItemInfo | null>(null)
const editMode = ref(false)
const editForm = reactive({
  questionText: '',
  userAnswer: '',
  correctAnswer: '',
  analysis: '',
  userNote: '',
  subject: '',
})

const route = useRoute()

/** 生成相似弹窗（PRD §8：在当前会话继续 / 创建新会话） */
const similarOpen = ref(false)
const generating = ref(false)
/** 复习队列状态（详情打开时查询，null = 查询中） */
const inReview = ref<boolean | null>(null)

onMounted(async () => {
  await load()
  // 支持从笔记知识联系跳转：/questions?open=3 → 自动弹出该题详情（知识联系只指向拍照题目）
  const openId = Number(route.query.open)
  if (openId) {
    const target = records.value.find((r) => r.id === openId && r.source === 'photo')
    if (target) {
      openDetail(target)
    }
  }
})

function openDetail(record: QuestionItemInfo) {
  active.value = record
  editMode.value = false
  // 查询复习队列状态（photo / similar 两类来源都支持加入复习）
  inReview.value = null
  reviewApi
    .getReviewStatus(record.source === 'similar_ai' ? 'similar' : 'question', record.id)
    .then((v) => (inReview.value = v))
    .catch(() => (inReview.value = null))
}

/** 加入今日复习队列（加入即可在复习页刷到） */
async function addToReview() {
  const q = active.value
  if (!q || inReview.value) {
    return
  }
  try {
    await reviewApi.addReviewCard(q.source === 'similar_ai' ? 'similar' : 'question', q.id)
    inReview.value = true
    toast.push('已加入今日复习')
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加入复习失败'
  }
}

/**
 * 发起相似题生成（PRD §8）：把原题作为上下文写入跨页种子消息，
 * 跳转 Agent 页自动发出；保存时模型凭「来源题目ID」回填 sourceQuestionId
 */
async function generateSimilar(mode: 'current' | 'new') {
  const q = active.value
  if (!q || generating.value) {
    return
  }
  generating.value = true
  error.value = ''
  try {
    const parts = [
      '请基于下面这道题出一道同型的相似题：保持同一考点与难度，先给我题目让我作答，先不要公布解答。',
      '',
      '【原题】',
      q.questionText ?? '（无题目文本）',
    ]
    if (q.correctAnswer) {
      parts.push('', '【原题解答】', q.correctAnswer)
    }
    parts.push('', `（来源题目ID：${q.id}；用户确认保存相似题时请把它作为 sourceQuestionId 传入）`)

    let conversationId: number | null = null
    if (mode === 'current') {
      // 最近活跃的会话；一个都没有时先创建
      const list = await conversationApi.listConversations()
      conversationId = list.length > 0 ? list[0].id : (await conversationApi.createConversation()).id
    }
    agentStore.setSeed({ conversationId, content: parts.join('\n') })
    similarOpen.value = false
    active.value = null
    router.push({ name: 'agent' })
  } catch (e) {
    error.value = e instanceof Error ? e.message : '发起失败，请稍后重试'
  } finally {
    generating.value = false
  }
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const result = await questionApi.listQuestions({
      date: dateFilter.value || undefined,
      subject: subjectFilter.value || undefined,
      page: page.value,
      size: PAGE_SIZE,
    })
    records.value = result.list
    total.value = result.total
    pages.value = result.pages
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

function onFilterChange() {
  page.value = 1
  load()
}

function clearFilters() {
  dateFilter.value = ''
  subjectFilter.value = ''
  onFilterChange()
}

function goPage(target: number) {
  if (target < 1 || target > pages.value || target === page.value) {
    return
  }
  page.value = target
  load()
}

function startEdit() {
  if (!active.value) {
    return
  }
  editForm.questionText = active.value.questionText ?? ''
  editForm.userAnswer = active.value.userAnswer ?? ''
  editForm.correctAnswer = active.value.correctAnswer ?? ''
  editForm.analysis = active.value.analysis ?? ''
  editForm.userNote = active.value.userNote ?? ''
  editForm.subject = active.value.subject ?? ''
  editMode.value = true
}

async function saveEdit() {
  if (!active.value) {
    return
  }
  try {
    const updated = await questionApi.updateQuestion(active.value.id, active.value.source, { ...editForm })
    active.value = updated
    editMode.value = false
    load()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '保存失败，请稍后重试'
  }
}

async function removeActive() {
  if (!active.value || !window.confirm('确定删除这道题目吗？删除后不可恢复。')) {
    return
  }
  try {
    await questionApi.deleteQuestion(active.value.id, active.value.source)
    const wasLastOnPage = records.value.length === 1 && page.value > 1
    active.value = null
    if (wasLastOnPage) {
      page.value -= 1
    }
    load()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '删除失败，请稍后重试'
  }
}

/** 去掉 Markdown / 公式符号后截取摘要 */
function excerpt(text: string | null): string {
  if (!text) {
    return '（无题目文本）'
  }
  const plain = text.replace(/[#*`$\\]/g, ' ').replace(/\s+/g, ' ').trim()
  return plain.length > 60 ? plain.slice(0, 60) + '…' : plain
}

function formatTime(iso: string): string {
  return iso ? iso.replace('T', ' ').slice(0, 16) : ''
}

const pageLabel = computed(() => `第 ${page.value} / ${pages.value} 页 · 共 ${total.value} 题`)
</script>

<template>
  <div class="h-full overflow-y-auto">
    <div class="mx-auto max-w-3xl px-4 py-8">
      <div class="flex flex-wrap items-center justify-between gap-3">
        <h1 class="flex items-center gap-2 text-[18px] font-semibold">
          <Camera :size="20" class="text-ink-2" />
          拍照记录
        </h1>
        <!-- 按日期与学科筛选 -->
        <div class="flex items-center gap-2 text-[13px]">
          <select
            v-model="subjectFilter"
            class="rounded-lg border border-line bg-surface px-2.5 py-1.5 outline-none focus:border-primary"
            @change="onFilterChange"
          >
            <option value="">全部学科</option>
            <option v-for="s in SUBJECTS" :key="s" :value="s">{{ s }}</option>
          </select>
          <input
            v-model="dateFilter"
            type="date"
            class="rounded-lg border border-line px-2.5 py-1.5 outline-none focus:border-primary"
            @change="onFilterChange"
          />
          <button
            v-if="dateFilter || subjectFilter"
            class="rounded-lg px-2 py-1.5 text-ink-2 hover:bg-line/60 hover:text-ink"
            @click="clearFilters"
          >
            清除
          </button>
        </div>
      </div>

      <!-- 加载 / 出错 -->
      <p v-if="loading" class="mt-8 text-[14px] text-ink-2">加载中…</p>
      <p v-else-if="error" class="mt-8 text-[14px] text-red-600">{{ error }}</p>

      <!-- 空状态 -->
      <div v-else-if="records.length === 0" class="mt-8 rounded-xl border border-dashed border-line py-16 text-center">
        <p class="text-[14px] text-ink-2">{{ dateFilter || subjectFilter ? '该筛选条件下没有保存过题目' : '还没有保存过题目' }}</p>
        <p class="mt-1 text-[12px] text-ink-2">在对话里拍照发一道题，解答后回复「保存」即可收进这里</p>
      </div>

      <!-- 列表 -->
      <div v-else class="mt-6 flex flex-col gap-3">
        <button
          v-for="r in records"
          :key="r.id"
          class="flex items-start gap-3 rounded-2xl border border-line bg-surface p-4 text-left transition-colors hover:border-ink-2/40"
          @click="openDetail(r)"
        >
          <img
            v-if="r.imageOssKey"
            :src="r.imageOssKey"
            alt="题目图"
            class="h-16 w-16 shrink-0 rounded-xl border border-line object-cover"
          />
          <div v-else class="flex h-16 w-16 shrink-0 items-center justify-center rounded-xl border border-line text-ink-2">
            <ImageOff :size="18" />
          </div>
          <div class="min-w-0 flex-1">
            <p class="truncate text-[14px] text-ink">{{ excerpt(r.questionText) }}</p>
            <p class="mt-1.5 text-[12px] text-ink-2">{{ formatTime(r.createdAt) }}</p>
          </div>
          <span
            v-if="r.source === 'similar_ai'"
            class="flex shrink-0 items-center gap-1 self-start rounded-md bg-violet-50 px-2 py-0.5 text-[11px] text-violet-600"
          >
            <Sparkles :size="11" />
            AI 生成
          </span>
          <span
            v-if="r.subject"
            class="shrink-0 self-start rounded-md bg-primary-soft px-2 py-0.5 text-[11px] text-primary"
          >
            {{ r.subject }}
          </span>
        </button>
      </div>

      <!-- 分页 -->
      <div v-if="!loading && !error && total > 0" class="mt-6 flex items-center justify-between text-[13px] text-ink-2">
        <span>{{ pageLabel }}</span>
        <div class="flex items-center gap-2">
          <button
            class="rounded-lg border border-line px-3 py-1.5 hover:bg-line/60 disabled:opacity-40"
            :disabled="page <= 1"
            @click="goPage(page - 1)"
          >
            上一页
          </button>
          <button
            class="rounded-lg border border-line px-3 py-1.5 hover:bg-line/60 disabled:opacity-40"
            :disabled="page >= pages"
            @click="goPage(page + 1)"
          >
            下一页
          </button>
        </div>
      </div>
    </div>

    <!-- 详情 / 编辑弹窗 -->
    <div
      v-if="active"
      class="fixed inset-0 z-50 flex items-center justify-center bg-ink/25 px-4 backdrop-blur-sm"
      @click.self="active = null; editMode = false"
    >
      <div class="max-h-[85vh] w-full max-w-2xl overflow-y-auto rounded-3xl border border-line bg-surface p-6 shadow-xl">
        <!-- 查看态 -->
        <template v-if="!editMode">
          <div class="flex items-start justify-between">
            <h2 class="text-[16px] font-semibold">题目详情</h2>
            <div class="flex items-center gap-1">
              <button
                class="flex items-center gap-1 rounded-lg px-2.5 py-1.5 text-[13px]"
                :class="inReview ? 'text-green-600' : 'text-ink-2 hover:bg-line/60 hover:text-ink'"
                :disabled="inReview !== false"
                :title="inReview ? '已在复习队列' : '加入今日复习队列'"
                @click="addToReview"
              >
                <Check v-if="inReview" :size="14" />
                <BookmarkPlus v-else :size="14" />
                {{ inReview ? '已加入复习' : '加入复习' }}
              </button>
              <button
                v-if="active.source === 'photo'"
                class="flex items-center gap-1 rounded-lg px-2.5 py-1.5 text-[13px] text-primary hover:bg-primary-soft"
                title="基于这道题生成相似题"
                @click="similarOpen = true"
              >
                <Sparkles :size="14" />
                生成相似题
              </button>
              <button
                class="flex h-8 w-8 items-center justify-center rounded-lg text-ink-2 hover:bg-line/60 hover:text-ink"
                title="编辑"
                @click="startEdit"
              >
                <Pencil :size="15" />
              </button>
              <button
                class="flex h-8 w-8 items-center justify-center rounded-lg text-ink-2 hover:bg-red-50 hover:text-red-600"
                title="删除"
                @click="removeActive"
              >
                <Trash2 :size="15" />
              </button>
              <button
                class="flex h-8 w-8 items-center justify-center rounded-lg text-ink-2 hover:bg-line/60 hover:text-ink"
                title="关闭"
                @click="active = null"
              >
                <X :size="16" />
              </button>
            </div>
          </div>
          <p class="mt-1 text-[12px] text-ink-2">
            {{ formatTime(active.createdAt) }}
            <span
              v-if="active.source === 'similar_ai'"
              class="ml-2 inline-flex items-center gap-1 rounded-md bg-violet-50 px-1.5 py-0.5 text-violet-600"
            >
              <Sparkles :size="11" />
              AI 生成
            </span>
            <span v-if="active.subject" class="ml-2 rounded-md bg-primary-soft px-1.5 py-0.5 text-primary">{{ active.subject }}</span>
          </p>

          <img
            v-if="active.imageOssKey"
            :src="active.imageOssKey"
            alt="题目图"
            class="mt-4 max-h-64 rounded-xl border border-line"
          />

          <h3 class="mt-5 text-[13px] font-semibold text-ink-2">题目</h3>
          <div class="markdown-body mt-1 text-[14px]" v-html="renderMarkdown(active.questionText || '（无题目文本）')"></div>

          <template v-if="active.correctAnswer">
            <h3 class="mt-5 text-[13px] font-semibold text-ink-2">解答</h3>
            <div class="markdown-body mt-1 text-[14px]" v-html="renderMarkdown(active.correctAnswer)"></div>
          </template>

          <template v-if="active.userAnswer">
            <h3 class="mt-5 text-[13px] font-semibold text-ink-2">我的作答</h3>
            <div class="markdown-body mt-1 text-[14px]" v-html="renderMarkdown(active.userAnswer)"></div>
          </template>

          <template v-if="active.analysis">
            <h3 class="mt-5 text-[13px] font-semibold text-ink-2">{{ active.source === 'similar_ai' ? '解析' : '错因分析' }}</h3>
            <div class="markdown-body mt-1 text-[14px]" v-html="renderMarkdown(active.analysis)"></div>
          </template>

          <template v-if="active.userNote">
            <h3 class="mt-5 text-[13px] font-semibold text-ink-2">笔记</h3>
            <div class="markdown-body mt-1 text-[14px]" v-html="renderMarkdown(active.userNote)"></div>
          </template>
        </template>

        <!-- 编辑态 -->
        <template v-else>
          <div class="flex items-start justify-between">
            <h2 class="text-[16px] font-semibold">编辑题目</h2>
            <button
              class="flex h-8 w-8 items-center justify-center rounded-lg text-ink-2 hover:bg-line/60 hover:text-ink"
              title="关闭"
              @click="editMode = false"
            >
              <X :size="16" />
            </button>
          </div>

          <div class="mt-4 flex flex-col gap-4">
            <label class="block">
              <span class="mb-1 block text-[12px] text-ink-2">学科</span>
              <select
                v-model="editForm.subject"
                class="w-full rounded-xl border border-line bg-surface px-3 py-2 text-[14px] outline-none focus:border-primary"
              >
                <option value="">未分类</option>
                <option v-for="s in SUBJECTS" :key="s" :value="s">{{ s }}</option>
              </select>
            </label>
            <label class="block">
              <span class="mb-1 block text-[12px] text-ink-2">题目</span>
              <textarea
                v-model="editForm.questionText"
                rows="4"
                class="w-full resize-y rounded-xl border border-line px-3 py-2 text-[14px] outline-none focus:border-primary"
              ></textarea>
            </label>
            <label class="block">
              <span class="mb-1 block text-[12px] text-ink-2">解答</span>
              <textarea
                v-model="editForm.correctAnswer"
                rows="6"
                class="w-full resize-y rounded-xl border border-line px-3 py-2 text-[14px] outline-none focus:border-primary"
              ></textarea>
            </label>
            <label class="block">
              <span class="mb-1 block text-[12px] text-ink-2">{{ active.source === 'similar_ai' ? '解析' : '错因分析' }}</span>
              <textarea
                v-model="editForm.analysis"
                rows="4"
                class="w-full resize-y rounded-xl border border-line px-3 py-2 text-[14px] outline-none focus:border-primary"
              ></textarea>
            </label>
            <label v-if="active.source !== 'similar_ai'" class="block">
              <span class="mb-1 block text-[12px] text-ink-2">我的作答</span>
              <textarea
                v-model="editForm.userAnswer"
                rows="3"
                class="w-full resize-y rounded-xl border border-line px-3 py-2 text-[14px] outline-none focus:border-primary"
              ></textarea>
            </label>
            <label v-if="active.source !== 'similar_ai'" class="block">
              <span class="mb-1 block text-[12px] text-ink-2">笔记</span>
              <textarea
                v-model="editForm.userNote"
                rows="3"
                class="w-full resize-y rounded-xl border border-line px-3 py-2 text-[14px] outline-none focus:border-primary"
              ></textarea>
            </label>
          </div>

          <div class="mt-5 flex justify-end gap-2">
            <button
              class="rounded-xl border border-line px-4 py-2 text-[14px] text-ink hover:bg-line/60"
              @click="editMode = false"
            >
              取消
            </button>
            <button
              class="rounded-xl bg-primary px-4 py-2 text-[14px] text-white hover:opacity-90"
              @click="saveEdit"
            >
              保存修改
            </button>
          </div>
        </template>
      </div>
    </div>
    <!-- 生成相似题：选择会话（PRD §8） -->
    <div
      v-if="similarOpen && active"
      class="fixed inset-0 z-[60] flex items-center justify-center bg-ink/25 px-4 backdrop-blur-sm"
      @click.self="similarOpen = false"
    >
      <div class="w-full max-w-sm rounded-3xl border border-line bg-surface p-6 shadow-xl">
        <div class="flex items-start justify-between">
          <h3 class="flex items-center gap-1.5 text-[15px] font-semibold">
            <Sparkles :size="15" class="text-primary" />
            生成相似题
          </h3>
          <button
            class="flex h-8 w-8 items-center justify-center rounded-lg text-ink-2 hover:bg-line/60 hover:text-ink"
            title="关闭"
            @click="similarOpen = false"
          >
            <X :size="16" />
          </button>
        </div>
        <p class="mt-2 text-[13px] leading-6 text-ink-2">
          Agent 会以「{{ excerpt(active.questionText) }}」为上下文出同型的相似题，确认满意后再保存。
        </p>
        <div class="mt-4 flex flex-col gap-2">
          <button
            class="rounded-xl border border-line px-4 py-2.5 text-[14px] text-ink hover:border-primary hover:bg-primary-soft/50 disabled:opacity-40"
            :disabled="generating"
            @click="generateSimilar('current')"
          >
            在当前会话继续
          </button>
          <button
            class="rounded-xl bg-primary px-4 py-2.5 text-[14px] text-white hover:opacity-90 disabled:opacity-40"
            :disabled="generating"
            @click="generateSimilar('new')"
          >
            创建新会话
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
