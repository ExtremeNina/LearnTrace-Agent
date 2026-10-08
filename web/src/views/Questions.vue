<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { BookmarkPlus, Camera, Check, ImageOff, Pencil, Search, Sparkles, Trash2, X } from 'lucide-vue-next'
import * as questionApi from '../api/question'
import * as reviewApi from '../api/review'
import { useToastStore } from '../stores/toast'
import type { QuestionItemInfo } from '../types/api'
import { SUBJECTS } from '../constants/subjects'
import { renderMarkdown } from '../utils/markdown'

/**
 * 题目管理（PRD §3.3 + §8）：拍照题目与 AI 生成的相似题合并列表（相似题标注「AI 生成」）、
 * 搜索框 + 学科/日期筛选同行、类方形圆角卡片网格、批量管理模式（勾选删除）、
 * 详情、编辑与删除；相似题生成统一走 AI 对话（B26 反馈精简）。结构对齐视频管理页
 */
const PAGE_SIZE = 10

const toast = useToastStore()

const records = ref<QuestionItemInfo[]>([])
const loading = ref(false)
const error = ref('')
const page = ref(1)
const pages = ref(1)
const total = ref(0)
/** 按题干关键词搜索（服务端模糊匹配） */
const keyword = ref('')
/** 按日期筛选（yyyy-MM-dd），空为全部 */
const dateFilter = ref('')
/** 按学科筛选，空为全部 */
const subjectFilter = ref('')
/** 当前查看 / 编辑的记录 */
const active = ref<QuestionItemInfo | null>(null)
const editMode = ref(false)

// ---- 批量管理（勾选删除；key = source-id，两类来源删除接口不同） ----
const selectMode = ref(false)
const selectedKeys = ref<string[]>([])
const batchDeleting = ref(false)
const editForm = reactive({
  questionText: '',
  userAnswer: '',
  correctAnswer: '',
  analysis: '',
  userNote: '',
  subject: '',
})

const route = useRoute()

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

async function load() {
  loading.value = true
  error.value = ''
  try {
    const result = await questionApi.listQuestions({
      date: dateFilter.value || undefined,
      subject: subjectFilter.value || undefined,
      keyword: keyword.value.trim() || undefined,
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
  keyword.value = ''
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

// ---- 批量管理 ----

function recKey(r: QuestionItemInfo): string {
  return `${r.source}-${r.id}`
}

function toggleSelect(r: QuestionItemInfo) {
  const key = recKey(r)
  const idx = selectedKeys.value.indexOf(key)
  if (idx >= 0) {
    selectedKeys.value.splice(idx, 1)
  } else {
    selectedKeys.value.push(key)
  }
}

function toggleSelectMode() {
  selectMode.value = !selectMode.value
  selectedKeys.value = []
}

const allSelected = computed(() => records.value.length > 0 && records.value.every((r) => selectedKeys.value.includes(recKey(r))))

function toggleSelectAll() {
  if (allSelected.value) {
    selectedKeys.value = []
  } else {
    selectedKeys.value = records.value.map(recKey)
  }
}

/** 批量删除：循环单删接口逐个容错（单条失败不中断），结果汇总提示 */
async function batchDelete() {
  const n = selectedKeys.value.length
  if (n === 0 || batchDeleting.value) {
    return
  }
  if (!window.confirm(`确定删除选中的 ${n} 道题目吗？删除后不可恢复。`)) {
    return
  }
  batchDeleting.value = true
  let ok = 0
  let failed = 0
  for (const key of [...selectedKeys.value]) {
    const [source, id] = [key.slice(0, key.indexOf('-')), Number(key.slice(key.indexOf('-') + 1))] as const
    try {
      await questionApi.deleteQuestion(id, source === 'similar_ai' ? 'similar_ai' : 'photo')
      ok++
    } catch {
      failed++
    }
  }
  batchDeleting.value = false
  selectedKeys.value = []
  toast.push(failed === 0 ? `已删除 ${ok} 道题目` : `已删除 ${ok} 道，${failed} 道删除失败`)
  if (failed > 0) {
    await load()
    return
  }
  if (records.value.length === 0 && page.value > 1) {
    page.value -= 1
  }
  await load()
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
    <div class="max-w-5xl px-6 py-8">
      <!-- 标题行：标题 + 统计 + 批量管理按钮紧跟其右 -->
      <div class="flex items-center gap-4">
        <h1 class="flex items-center gap-2 text-[18px] font-semibold text-ink">
          <Camera :size="20" class="text-ink-2" />
          题目管理
          <span class="text-[13px] font-normal text-ink-2">共 {{ total }} 题</span>
        </h1>
        <button
          class="flex items-center gap-1.5 rounded-xl border px-3.5 py-2 text-[14px] transition-colors"
          :class="selectMode ? 'border-primary bg-primary-soft text-primary' : 'border-line text-ink hover:bg-panel'"
          @click="toggleSelectMode"
        >
          <Check v-if="selectMode" :size="16" />
          <Trash2 v-else :size="16" />
          {{ selectMode ? '退出批量管理' : '批量管理' }}
        </button>
      </div>

      <!-- 过滤行：搜索框 + 学科 / 日期筛选同一行 -->
      <div class="mt-5 flex flex-wrap items-center gap-3">
        <div class="relative">
          <Search :size="15" class="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-ink-2" />
          <input
            v-model="keyword"
            type="text"
            placeholder="按题干关键词搜索…"
            class="w-64 rounded-xl border border-line bg-surface py-2 pl-9 pr-3 text-[13px] text-ink outline-none focus:border-primary"
            @keydown.enter="onFilterChange"
          />
        </div>
        <select
          v-model="subjectFilter"
          class="rounded-xl border border-line bg-surface px-2.5 py-2 text-[13px] outline-none focus:border-primary"
          @change="onFilterChange"
        >
          <option value="">全部学科</option>
          <option v-for="s in SUBJECTS" :key="s" :value="s">{{ s }}</option>
        </select>
        <input
          v-model="dateFilter"
          type="date"
          class="rounded-xl border border-line px-2.5 py-1.5 text-[13px] outline-none focus:border-primary"
          @change="onFilterChange"
        />
        <button
          v-if="keyword || dateFilter || subjectFilter"
          class="rounded-xl px-2 py-2 text-[13px] text-ink-2 hover:bg-line/60 hover:text-ink"
          @click="clearFilters"
        >
          清除
        </button>
        <button
          v-if="keyword !== ''"
          class="ml-auto rounded-xl bg-primary px-3.5 py-2 text-[13px] text-white hover:opacity-90"
          @click="onFilterChange"
        >
          搜索
        </button>
      </div>

      <!-- 加载 / 出错 -->
      <p v-if="loading" class="mt-8 text-[14px] text-ink-2">加载中…</p>
      <p v-else-if="error" class="mt-8 text-[14px] text-red-600">{{ error }}</p>

      <!-- 空状态 -->
      <div v-else-if="records.length === 0" class="mt-10 rounded-2xl border border-dashed border-line py-16 text-center">
        <p class="text-[14px] text-ink-2">{{ keyword || dateFilter || subjectFilter ? '没有符合条件的题目' : '还没有保存过题目' }}</p>
        <p class="mt-1 text-[12px] text-ink-2">在对话里拍照发一道题，解答后回复「保存」即可收进这里</p>
      </div>

      <!-- 卡片网格：auto-fill 自适应列数每行放满才换行；批量模式下点击即勾选 -->
      <div v-else class="mt-6 grid grid-cols-[repeat(auto-fill,minmax(280px,1fr))] gap-3">
        <div
          v-for="r in records"
          :key="recKey(r)"
          class="relative overflow-hidden rounded-2xl border bg-surface p-4 transition-shadow"
          :class="[
            selectMode && selectedKeys.includes(recKey(r)) ? 'border-primary ring-2 ring-primary/30' : 'border-line',
            selectMode ? 'cursor-pointer' : 'cursor-pointer hover:shadow-md',
          ]"
          @click="selectMode ? toggleSelect(r) : openDetail(r)"
        >
          <!-- 批量选择勾选框 -->
          <span
            v-if="selectMode"
            class="absolute right-2.5 top-2.5 z-10 flex h-6 w-6 items-center justify-center rounded-md border-2 bg-surface/90"
            :class="selectedKeys.includes(recKey(r)) ? 'border-primary bg-primary text-white' : 'border-line text-transparent'"
          >
            <Check :size="14" />
          </span>

          <div class="flex items-start gap-3">
            <img
              v-if="r.imageOssKey"
              :src="r.imageOssKey"
              alt="题目图"
              class="h-16 w-16 shrink-0 rounded-xl border border-line object-cover"
            />
            <div v-else class="flex h-16 w-16 shrink-0 items-center justify-center rounded-xl border border-line text-ink-2">
              <ImageOff :size="18" />
            </div>
            <div class="min-w-0 flex-1 pr-6">
              <p class="line-clamp-2 text-[13px] leading-relaxed text-ink">{{ excerpt(r.questionText) }}</p>
            </div>
          </div>
          <div class="mt-3 flex flex-wrap items-center gap-1.5">
            <span
              v-if="r.source === 'similar_ai'"
              class="flex items-center gap-1 rounded-md bg-violet-50 px-2 py-0.5 text-[11px] text-violet-600"
            >
              <Sparkles :size="11" />
              AI 生成
            </span>
            <span v-if="r.subject" class="rounded-md bg-primary-soft px-2 py-0.5 text-[11px] text-primary">
              {{ r.subject }}
            </span>
            <span class="ml-auto text-[11px] text-ink-2">{{ formatTime(r.createdAt) }}</span>
          </div>
        </div>
      </div>

      <!-- 分页：靠左，上下页按钮紧跟页数右侧 -->
      <div v-if="!loading && !error && total > 0" class="mt-6 flex items-center gap-4 text-[13px] text-ink-2">
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

    <!-- 批量管理操作栏（底部浮动，对齐视频管理页） -->
    <div
      v-if="selectMode"
      class="fixed bottom-6 left-1/2 z-30 flex -translate-x-1/2 items-center gap-3 rounded-2xl border border-line bg-surface px-4 py-2.5 shadow-lg"
    >
      <button
        class="rounded-lg px-2.5 py-1.5 text-[13px] text-ink-2 hover:bg-line/60 hover:text-ink"
        @click="toggleSelectAll"
      >
        {{ allSelected ? '取消全选' : '全选本页' }}
      </button>
      <span class="text-[13px] text-ink-2">已选 {{ selectedKeys.length }} 题</span>
      <button
        class="flex items-center gap-1.5 rounded-xl bg-red-500 px-3.5 py-2 text-[13px] text-white hover:opacity-90 disabled:opacity-40"
        :disabled="selectedKeys.length === 0 || batchDeleting"
        @click="batchDelete"
      >
        <Trash2 :size="14" />
        {{ batchDeleting ? '删除中…' : `删除所选（${selectedKeys.length}）` }}
      </button>
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
  </div>
</template>
