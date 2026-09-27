<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { Camera, ImageOff, X } from 'lucide-vue-next'
import * as questionApi from '../api/question'
import type { QuestionRecordInfo } from '../types/api'
import { renderMarkdown } from '../utils/markdown'

/**
 * 拍照记录（PRD §3.3）：已保存题目的列表与详情查看
 */
const records = ref<QuestionRecordInfo[]>([])
const loading = ref(false)
const error = ref('')
/** 当前查看详情的记录 */
const active = ref<QuestionRecordInfo | null>(null)

onMounted(load)

async function load() {
  loading.value = true
  error.value = ''
  try {
    records.value = await questionApi.listQuestions()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败，请稍后重试'
  } finally {
    loading.value = false
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
</script>

<template>
  <div class="h-full overflow-y-auto">
    <div class="mx-auto max-w-3xl px-4 py-8">
      <h1 class="flex items-center gap-2 text-[18px] font-semibold">
        <Camera :size="20" class="text-ink-2" />
        拍照记录
      </h1>

      <!-- 加载 / 出错 -->
      <p v-if="loading" class="mt-8 text-[14px] text-ink-2">加载中…</p>
      <p v-else-if="error" class="mt-8 text-[14px] text-red-600">{{ error }}</p>

      <!-- 空状态 -->
      <div v-else-if="records.length === 0" class="mt-8 rounded-xl border border-dashed border-line py-16 text-center">
        <p class="text-[14px] text-ink-2">还没有保存过题目</p>
        <p class="mt-1 text-[12px] text-ink-2">在对话里拍照发一道题，解答后回复「保存」即可收进这里</p>
      </div>

      <!-- 列表 -->
      <div v-else class="mt-6 flex flex-col gap-3">
        <button
          v-for="r in records"
          :key="r.id"
          class="flex items-start gap-3 rounded-2xl border border-line bg-white p-4 text-left transition-colors hover:border-ink-2/40"
          @click="active = r"
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
    </div>

    <!-- 详情弹窗 -->
    <div
      v-if="active"
      class="fixed inset-0 z-50 flex items-center justify-center bg-ink/25 px-4 backdrop-blur-sm"
      @click.self="active = null"
    >
      <div class="max-h-[85vh] w-full max-w-2xl overflow-y-auto rounded-3xl border border-line bg-white p-6 shadow-xl">
        <div class="flex items-start justify-between">
          <h2 class="text-[16px] font-semibold">题目详情</h2>
          <button
            class="flex h-7 w-7 items-center justify-center rounded-full text-ink-2 hover:bg-line/60 hover:text-ink"
            title="关闭"
            @click="active = null"
          >
            <X :size="16" />
          </button>
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
      </div>
    </div>
  </div>
</template>
