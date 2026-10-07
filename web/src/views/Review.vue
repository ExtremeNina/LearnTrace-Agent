<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Check, GraduationCap, LoaderCircle, RefreshCw, Sun } from 'lucide-vue-next'
import * as reviewApi from '../api/review'
import { getTodayBriefing, refreshBriefing } from '../api/briefing'
import type { BriefingInfo, ReviewCardInfo, ReviewStatsInfo } from '../types/api'
import type { ReviewGrade } from '../api/review'
import { renderMarkdown } from '../utils/markdown'

/**
 * 今日待复习（路线图 P0-1）：统计首屏（含每日简报）→ 卡片流（翻面 → 三档评分）→ 完成态。
 * 调度由后端 ReviewScheduler（SM-2 简化版）负责，页面只负责呈现与评分。
 */
const router = useRouter()

const phase = ref<'stats' | 'session' | 'done'>('stats')
const loading = ref(false)
const error = ref('')
const stats = ref<ReviewStatsInfo | null>(null)
const queue = ref<ReviewCardInfo[]>([])
const index = ref(0)
const flipped = ref(false)
const submitting = ref(false)

// 每日简报（惰性生成：首次打开触发 LLM，可能有数秒等待）
const briefing = ref<BriefingInfo | null>(null)
const briefingLoading = ref(false)
const briefingError = ref('')
const briefingRefreshing = ref(false)

const current = computed(() => queue.value[index.value] ?? null)
const typeLabel = computed(() => {
  switch (current.value?.cardType) {
    case 'note':
      return '笔记'
    case 'similar':
      return 'AI 相似题'
    default:
      return '拍照题目'
  }
})

onMounted(load)

async function load() {
  loading.value = true
  error.value = ''
  try {
    stats.value = await reviewApi.getReviewStats()
    phase.value = 'stats'
    // 简报异步加载（惰性生成可能耗时数秒，不阻塞统计首屏）
    loadBriefing()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

async function loadBriefing() {
  briefingLoading.value = true
  briefingError.value = ''
  try {
    briefing.value = await getTodayBriefing()
  } catch (e) {
    briefingError.value = e instanceof Error ? e.message : '简报生成失败，可点击刷新重试'
  } finally {
    briefingLoading.value = false
  }
}

async function refreshBriefingCard() {
  briefingRefreshing.value = true
  briefingError.value = ''
  try {
    briefing.value = await refreshBriefing()
  } catch (e) {
    briefingError.value = e instanceof Error ? e.message : '刷新失败，请稍后重试'
  } finally {
    briefingRefreshing.value = false
  }
}

async function startSession() {
  loading.value = true
  error.value = ''
  try {
    queue.value = await reviewApi.getTodayQueue()
    index.value = 0
    flipped.value = false
    phase.value = queue.value.length > 0 ? 'session' : 'stats'
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

function flip() {
  if (!flipped.value) {
    flipped.value = true
  }
}

async function grade(g: ReviewGrade) {
  const card = current.value
  if (!card || !flipped.value || submitting.value) {
    return
  }
  submitting.value = true
  try {
    await reviewApi.submitReview(card.id, g)
    if (index.value >= queue.value.length - 1) {
      stats.value = await reviewApi.getReviewStats()
      phase.value = 'done'
    } else {
      index.value += 1
      flipped.value = false
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : '评分失败，请稍后重试'
  } finally {
    submitting.value = false
  }
}

function onKeydown(e: KeyboardEvent) {
  if (phase.value !== 'session') {
    return
  }
  if (e.code === 'Space') {
    e.preventDefault()
    flip()
    return
  }
  if (flipped.value && (e.key === '1' || e.key === '2' || e.key === '3')) {
    grade((Number(e.key) - 1) as ReviewGrade)
  }
}

onMounted(() => window.addEventListener('keydown', onKeydown))
onUnmounted(() => window.removeEventListener('keydown', onKeydown))
</script>

<template>
  <div class="h-full overflow-y-auto">
    <div class="mx-auto max-w-2xl px-4 py-8">
      <!-- 加载 / 出错 -->
      <p v-if="loading" class="flex items-center gap-2 text-[14px] text-ink-2">
        <LoaderCircle :size="15" class="animate-spin" />
        加载中…
      </p>
      <p v-else-if="error" class="text-[14px] text-red-600">{{ error }}</p>

      <!-- 统计首屏 -->
      <template v-else-if="phase === 'stats' && stats">
        <h1 class="flex items-center gap-2 text-[18px] font-semibold text-ink">
          <GraduationCap :size="20" class="text-primary" />
          今日待复习
        </h1>

        <!-- 每日简报 -->
        <div class="mt-5 rounded-2xl border border-line bg-surface p-5">
          <div class="flex items-center justify-between">
            <h2 class="flex items-center gap-2 text-[14px] font-semibold text-ink">
              <Sun :size="15" class="text-primary" />
              今日简报
              <span v-if="briefing?.briefDate" class="text-[12px] font-normal text-ink-2">{{ briefing.briefDate }}</span>
            </h2>
            <button
              class="flex items-center gap-1 rounded-lg px-2 py-1 text-[12px] text-ink-2 hover:bg-line/60 hover:text-ink"
              :disabled="briefingRefreshing"
              @click="refreshBriefingCard"
            >
              <RefreshCw :size="12" :class="briefingRefreshing ? 'animate-spin' : ''" />
              重新生成
            </button>
          </div>
          <div v-if="briefingLoading" class="mt-3 flex items-center gap-2 text-[13px] text-ink-2">
            <LoaderCircle :size="14" class="animate-spin" />
            正在为你生成今日简报…
          </div>
          <p v-else-if="briefingError" class="mt-3 text-[12px] text-red-600">{{ briefingError }}</p>
          <div
            v-else-if="briefing?.content"
            class="markdown-body mt-2 text-[13px] leading-6 text-ink"
            v-html="renderMarkdown(briefing.content)"
          ></div>
        </div>

        <div class="mt-4 grid grid-cols-3 gap-4">
          <div class="rounded-2xl border border-line bg-surface p-5 text-center">
            <p class="text-[26px] font-semibold text-primary">{{ stats.dueCount }}</p>
            <p class="mt-1 text-[12px] text-ink-2">待复习</p>
          </div>
          <div class="rounded-2xl border border-line bg-surface p-5 text-center">
            <p class="text-[26px] font-semibold text-ink">{{ stats.reviewedToday }}</p>
            <p class="mt-1 text-[12px] text-ink-2">今日已复习</p>
          </div>
          <div class="rounded-2xl border border-line bg-surface p-5 text-center">
            <p class="text-[26px] font-semibold text-ink">{{ stats.total }}</p>
            <p class="mt-1 text-[12px] text-ink-2">队列总数</p>
          </div>
        </div>
        <button
          class="mt-6 w-full rounded-2xl bg-primary py-3 text-[15px] text-white hover:opacity-90 disabled:opacity-40"
          :disabled="stats.dueCount === 0"
          @click="startSession"
        >
          {{ stats.dueCount === 0 ? '今天没有到期的卡片' : `开始复习（${stats.dueCount} 张）` }}
        </button>
        <p class="mt-3 text-center text-[12px] text-ink-2">
          在题目 / 笔记 / 课后习题页点「加入复习」，卡片会按记忆曲线出现在这里
        </p>
      </template>

      <!-- 刷卡态 -->
      <template v-else-if="phase === 'session' && current">
        <div class="flex items-center justify-between text-[12px] text-ink-2">
          <span>{{ index + 1 }} / {{ queue.length }}</span>
          <span class="flex items-center gap-1.5">
            <GraduationCap :size="13" />
            {{ typeLabel }}
          </span>
          <button class="rounded-lg px-2 py-1 hover:bg-line/60 hover:text-ink" @click="load">结束</button>
        </div>
        <div class="mt-2 h-1.5 overflow-hidden rounded-full bg-line">
          <div class="h-full rounded-full bg-primary transition-all" :style="{ width: `${(index / queue.length) * 100}%` }"></div>
        </div>

        <!-- 卡片 -->
        <div class="mt-5 rounded-3xl border border-line bg-surface p-6 shadow-sm">
          <img
            v-if="current.imageUrl"
            :src="current.imageUrl"
            alt="题目图"
            class="mb-4 max-h-48 rounded-xl border border-line"
          />
          <div class="markdown-body text-[15px] leading-7 text-ink" v-html="renderMarkdown(current.frontText || '（无内容）')"></div>

          <template v-if="flipped">
            <div class="my-4 h-px bg-line"></div>
            <h3 class="text-[13px] font-semibold text-ink-2">
              {{ current.cardType === 'note' ? '笔记内容' : '解答' }}
            </h3>
            <div class="markdown-body mt-1 text-[14px] leading-7 text-ink" v-html="renderMarkdown(current.backText || '（无解答内容）')"></div>
            <template v-if="current.analysis">
              <h3 class="mt-4 text-[13px] font-semibold text-ink-2">错因 / 解析</h3>
              <div class="markdown-body mt-1 text-[14px] leading-7 text-ink" v-html="renderMarkdown(current.analysis)"></div>
            </template>
          </template>

          <button
            v-if="!flipped"
            class="mt-6 w-full rounded-2xl bg-primary py-3 text-[15px] text-white hover:opacity-90"
            @click="flip"
          >
            显示答案（空格）
          </button>
        </div>

        <!-- 三档评分 -->
        <div v-if="flipped" class="mt-4 grid grid-cols-3 gap-3">
          <button
            class="rounded-2xl border border-red-200 bg-red-50 py-3 text-[14px] text-red-600 hover:opacity-90 disabled:opacity-40 dark:border-red-900/50 dark:bg-red-950/40 dark:text-red-400"
            :disabled="submitting"
            @click="grade(0)"
          >
            生疏（1）
          </button>
          <button
            class="rounded-2xl border border-amber-200 bg-amber-50 py-3 text-[14px] text-amber-600 hover:opacity-90 disabled:opacity-40 dark:border-amber-900/50 dark:bg-amber-950/40 dark:text-amber-400"
            :disabled="submitting"
            @click="grade(1)"
          >
            模糊（2）
          </button>
          <button
            class="rounded-2xl border border-green-200 bg-green-50 py-3 text-[14px] text-green-600 hover:opacity-90 disabled:opacity-40 dark:border-green-900/50 dark:bg-green-950/40 dark:text-green-400"
            :disabled="submitting"
            @click="grade(2)"
          >
            熟练（3）
          </button>
        </div>
        <p v-if="flipped" class="mt-2 text-center text-[12px] text-ink-2">
          生疏 → 明天再来；模糊 → 稍微拉远；熟练 → 按记忆曲线拉远
        </p>
      </template>

      <!-- 完成态 -->
      <template v-else-if="phase === 'done'">
        <div class="mt-16 flex flex-col items-center gap-4 text-center">
          <span class="flex h-14 w-14 items-center justify-center rounded-full bg-green-50 text-green-600 dark:bg-green-950/40">
            <Check :size="26" />
          </span>
          <h2 class="text-[18px] font-semibold text-ink">今日复习完成</h2>
          <p class="text-[13px] text-ink-2">
            已刷 {{ queue.length }} 张
            <template v-if="stats"> · 队列里还有 {{ stats.total - stats.reviewedToday }} 张等待下次到期</template>
          </p>
          <div class="mt-2 flex gap-2">
            <button class="rounded-xl border border-line px-4 py-2 text-[14px] text-ink hover:bg-panel" @click="load">
              返回统计
            </button>
            <button
              class="flex items-center gap-1.5 rounded-xl border border-line px-4 py-2 text-[14px] text-ink hover:bg-panel"
              @click="router.push('/questions')"
            >
              去题目记录加新卡
            </button>
          </div>
        </div>
      </template>
    </div>
  </div>
</template>
