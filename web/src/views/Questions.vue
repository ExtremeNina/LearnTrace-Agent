<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Camera, ImageOff, Pencil, Trash2, X } from 'lucide-vue-next'
import * as questionApi from '../api/question'
import type { QuestionRecordInfo } from '../types/api'
import { renderMarkdown } from '../utils/markdown'

/**
 * 拍照记录（PRD §3.3）：题目分页列表（按日期筛选）、详情、编辑与删除
 */
const PAGE_SIZE = 10

const records = ref<QuestionRecordInfo[]>([])
const loading = ref(false)
const error = ref('')
const page = ref(1)
const pages = ref(1)
const total = ref(0)
/** 按日期筛选（yyyy-MM-dd），空为全部 */
const dateFilter = ref('')
/** 当前查看 / 编辑的记录 */
const active = ref<QuestionRecordInfo | null>(null)
const editMode = ref(false)
const editForm = reactive({
  questionText: '',
  userAnswer: '',
  correctAnswer: '',
  userNote: '',
})

onMounted(() => load())

async function load() {
  loading.value = true
  error.value = ''
  try {
    const result = await questionApi.listQuestions({
      date: dateFilter.value || undefined,
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

function onDateChange() {
  page.value = 1
  load()
}

function clearDate() {
  dateFilter.value = ''
  onDateChange()
}

function goPage(target: number) {
  if (target < 1 || target > pages.value || target === page.value) {
    return
  }
  page.value = target
  load()
}

function openDetail(record: QuestionRecordInfo) {
  active.value = record
  editMode.value = false
}

function startEdit() {
  if (!active.value) {
    return
  }
  editForm.questionText = active.value.questionText ?? ''
  editForm.userAnswer = active.value.userAnswer ?? ''
  editForm.correctAnswer = active.value.correctAnswer ?? ''
  editForm.userNote = active.value.userNote ?? ''
  editMode.value = true
}

async function saveEdit() {
  if (!active.value) {
    return
  }
  try {
    const updated = await questionApi.updateQuestion(active.value.id, { ...editForm })
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
    await questionApi.deleteQuestion(active.value.id)
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
      <div class="flex items-center justify-between">
        <h1 class="flex items-center gap-2 text-[18px] font-semibold">
          <Camera :size="20" class="text-ink-2" />
          拍照记录
        </h1>
        <!-- 按日期筛选 -->
        <div class="flex items-center gap-2 text-[13px]">
          <input
            v-model="dateFilter"
            type="date"
            class="rounded-lg border border-line px-2.5 py-1.5 outline-none focus:border-primary"
            @change="onDateChange"
          />
          <button
            v-if="dateFilter"
            class="rounded-lg px-2 py-1.5 text-ink-2 hover:bg-line/60 hover:text-ink"
            @click="clearDate"
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
        <p class="text-[14px] text-ink-2">{{ dateFilter ? '该日期下没有保存过题目' : '还没有保存过题目' }}</p>
        <p class="mt-1 text-[12px] text-ink-2">在对话里拍照发一道题，解答后回复「保存」即可收进这里</p>
      </div>

      <!-- 列表 -->
      <div v-else class="mt-6 flex flex-col gap-3">
        <button
          v-for="r in records"
          :key="r.id"
          class="flex items-start gap-3 rounded-2xl border border-line bg-white p-4 text-left transition-colors hover:border-ink-2/40"
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
      <div class="max-h-[85vh] w-full max-w-2xl overflow-y-auto rounded-3xl border border-line bg-white p-6 shadow-xl">
        <!-- 查看态 -->
        <template v-if="!editMode">
          <div class="flex items-start justify-between">
            <h2 class="text-[16px] font-semibold">题目详情</h2>
            <div class="flex items-center gap-1">
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
          <p class="mt-1 text-[12px] text-ink-2">{{ formatTime(active.createdAt) }}</p>

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
            <h3 class="mt-5 text-[13px] font-semibold text-ink-2">错因分析</h3>
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
              <span class="mb-1 block text-[12px] text-ink-2">我的作答</span>
              <textarea
                v-model="editForm.userAnswer"
                rows="3"
                class="w-full resize-y rounded-xl border border-line px-3 py-2 text-[14px] outline-none focus:border-primary"
              ></textarea>
            </label>
            <label class="block">
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
