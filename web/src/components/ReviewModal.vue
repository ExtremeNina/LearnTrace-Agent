<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Check, GraduationCap, List, LoaderCircle, X } from 'lucide-vue-next'
import * as reviewApi from '../api/review'
import type { ReviewCardInfo, ReviewStatsInfo } from '../types/api'
import type { ReviewGrade } from '../api/review'
import { renderMarkdown } from '../utils/markdown'
import { useReviewModalStore } from '../stores/reviewModal'

/**
 * 今日复习弹窗（复习由独立 /review 页改为全局弹窗，挂 MainLayout）：
 * 统计首屏 → 刷卡会话（翻面 → 三档评分，键盘空格/1/2/3）→ 完成态；附「查看队列」视图（全量列表 + 移除）。
 * 调度由后端 ReviewScheduler（SM-2 简化版）负责，弹窗只负责呈现与评分。
 */
const modal = useReviewModalStore()
const router = useRouter()

const phase = ref<'stats' | 'session' | 'done' | 'queue'>('stats')
const loading = ref(false)
const error = ref('')
const stats = ref<ReviewStatsInfo | null>(null)
/** 刷卡会话队列（仅到期卡片） */
const queue = ref<ReviewCardInfo[]>([])
/** 队列视图：今日队列全量列表 */
const allCards = ref<ReviewCardInfo[]>([])
const allCardsLoading = ref(false)
const removingId = ref<number | null>(null)
const index = ref(0)
const flipped = ref(false)
const submitting = ref(false)

const current = computed(() => queue.value[index.value] ?? null)

function typeLabel(card: ReviewCardInfo | null): string {
  switch (card?.cardType) {
    case 'note':
      return '笔记'
    case 'similar':
      return 'AI 相似题'
    default:
      return '拍照题目'
  }
}

// 弹窗打开即加载统计；关闭时重置会话态（下次打开重新开始）
watch(
  () => modal.isOpen,
  (open) => {
    if (open) {
      load()
    } else {
      phase.value = 'stats'
      queue.value = []
      index.value = 0
      flipped.value = false
      error.value = ''
    }
  },
)

async function load() {
  loading.value = true
  error.value = ''
  try {
    stats.value = await reviewApi.getReviewStats()
    phase.value = 'stats'
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败，请稍后重试'
  } finally {
    loading.value = false
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

async function openQueue() {
  allCardsLoading.value = true
  phase.value = 'queue'
  try {
    allCards.value = await reviewApi.getTodayQueue()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '队列加载失败'
  } finally {
    allCardsLoading.value = false
  }
}

async function removeFromQueue(card: ReviewCardInfo) {
  if (removingId.value !== null) {
    return
  }
  removingId.value = card.id
  try {
    await reviewApi.removeReviewCard(card.id)
    allCards.value = allCards.value.filter((c) => c.id !== card.id)
    stats.value = await reviewApi.getReviewStats()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '移除失败，请稍后重试'
  } finally {
    removingId.value = null
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
  if (!modal.isOpen || phase.value !== 'session') {
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

window.addEventListener('keydown', onKeydown)
onUnmounted(() => window.removeEventListener('keydown', onKeydown))
</script>

<template>
  <Teleport to="body">
    <Transition name="review-modal">
      <div
        v-if="modal.isOpen"
        class="fixed inset-0 z-[70] flex items-center justify-center bg-black/40 backdrop-blur-sm p-4"
        @click.self="modal.close()"
      >
        <div class="flex max-h-[85vh] w-full max-w-xl flex-col overflow-hidden rounded-3xl border border-line bg-white shadow-xl">
          <!-- 头部 -->
          <div class="flex shrink-0 items-center justify-between border-b border-line px-5 py-3.5">
            <h2 class="flex items-center gap-2 text-[15px] font-semibold text-ink">
              <GraduationCap :size="17" class="text-primary" />
              今日复习
            </h2>
            <button
              class="flex h-8 w-8 items-center justify-center rounded-full text-ink-2 transition-colors hover:bg-panel hover:text-ink"
              title="关闭"
              @click="modal.close()"
            >
              <X :size="17" />
            </button>
          </div>

          <!-- 内容区 -->
          <div class="min-h-0 flex-1 overflow-y-auto px-5 py-4">
            <p v-if="loading" class="flex items-center gap-2 py-8 text-[14px] text-ink-2">
              <LoaderCircle :size="15" class="animate-spin" />
              加载中…
            </p>
            <p v-else-if="error" class="py-4 text-[13px] text-red-600">{{ error }}</p>

            <!-- 统计首屏 -->
            <template v-else-if="phase === 'stats' && stats">
              <div class="grid grid-cols-3 gap-3">
                <div class="rounded-2xl border border-line bg-surface p-4 text-center">
                  <p class="text-[24px] font-semibold text-primary">{{ stats.dueCount }}</p>
                  <p class="mt-1 text-[12px] text-ink-2">待复习</p>
                </div>
                <div class="rounded-2xl border border-line bg-surface p-4 text-center">
                  <p class="text-[24px] font-semibold text-ink">{{ stats.reviewedToday }}</p>
                  <p class="mt-1 text-[12px] text-ink-2">今日已复习</p>
                </div>
                <div class="rounded-2xl border border-line bg-surface p-4 text-center">
                  <p class="text-[24px] font-semibold text-ink">{{ stats.total }}</p>
                  <p class="mt-1 text-[12px] text-ink-2">队列总数</p>
                </div>
              </div>
              <button
                class="mt-5 w-full rounded-2xl bg-primary py-3 text-[15px] text-white transition-opacity hover:opacity-90 disabled:opacity-40"
                :disabled="stats.dueCount === 0"
                @click="startSession"
              >
                {{ stats.dueCount === 0 ? '今天没有到期的卡片' : `开始复习（${stats.dueCount} 张）` }}
              </button>
              <button
                class="mt-3 flex w-full items-center justify-center gap-1.5 rounded-2xl border border-line py-2.5 text-[14px] text-ink transition-colors hover:bg-panel"
                @click="openQueue"
              >
                <List :size="15" class="text-ink-2" />
                查看复习队列
              </button>
              <p class="mt-3 text-center text-[12px] text-ink-2">
                在题目 / 笔记 / 课后习题页点「加入复习」，卡片会按记忆曲线出现在这里
              </p>
            </template>

            <!-- 队列视图 -->
            <template v-else-if="phase === 'queue'">
              <p v-if="allCardsLoading" class="flex items-center gap-2 py-8 text-[14px] text-ink-2">
                <LoaderCircle :size="15" class="animate-spin" />
                加载队列…
              </p>
              <p v-else-if="allCards.length === 0" class="py-10 text-center text-[13px] text-ink-2">
                队列为空——到题目 / 笔记 / 课后习题页点「加入复习」
              </p>
              <ul v-else class="flex flex-col gap-2">
                <li
                  v-for="card in allCards"
                  :key="card.id"
                  class="group flex items-start gap-3 rounded-2xl border border-line bg-surface px-4 py-3"
                >
                  <div class="min-w-0 flex-1">
                    <div class="flex items-center gap-2">
                      <span class="rounded-full bg-panel px-2 py-0.5 text-[11px] text-ink-2">{{ typeLabel(card) }}</span>
                      <span class="text-[11px] text-ink-2">到期 {{ card.dueAt }}</span>
                    </div>
                    <p class="mt-1.5 line-clamp-2 text-[13px] leading-6 text-ink">
                      {{ card.frontText || '（无内容）' }}
                    </p>
                  </div>
                  <button
                    class="shrink-0 rounded-lg px-2 py-1 text-[12px] text-ink-2 transition-colors hover:bg-red-50 hover:text-red-600 disabled:opacity-40"
                    :disabled="removingId === card.id"
                    title="从队列移除"
                    @click="removeFromQueue(card)"
                  >
                    {{ removingId === card.id ? '移除中…' : '移除' }}
                  </button>
                </li>
              </ul>
              <button
                class="mt-4 w-full rounded-2xl border border-line py-2.5 text-[14px] text-ink transition-colors hover:bg-panel"
                @click="phase = 'stats'"
              >
                返回统计
              </button>
            </template>

            <!-- 刷卡态 -->
            <template v-else-if="phase === 'session' && current">
              <div class="flex items-center justify-between text-[12px] text-ink-2">
                <span>{{ index + 1 }} / {{ queue.length }}</span>
                <span class="flex items-center gap-1.5">
                  <GraduationCap :size="13" />
                  {{ typeLabel(current) }}
                </span>
                <button class="rounded-lg px-2 py-1 hover:bg-line/60 hover:text-ink" @click="load">结束</button>
              </div>
              <div class="mt-2 h-1.5 overflow-hidden rounded-full bg-line">
                <div class="h-full rounded-full bg-primary transition-all" :style="{ width: `${(index / queue.length) * 100}%` }"></div>
              </div>

              <div class="mt-4 rounded-3xl border border-line bg-surface p-5">
                <img
                  v-if="current.imageUrl"
                  :src="current.imageUrl"
                  alt="题目图"
                  class="mb-4 max-h-44 rounded-xl border border-line"
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
                  class="mt-5 w-full rounded-2xl bg-primary py-3 text-[15px] text-white transition-opacity hover:opacity-90"
                  @click="flip"
                >
                  显示答案（空格）
                </button>
              </div>

              <div v-if="flipped" class="mt-4 grid grid-cols-3 gap-3">
                <button
                  class="rounded-2xl border border-red-200 bg-red-50 py-3 text-[14px] text-red-600 transition-opacity hover:opacity-90 disabled:opacity-40"
                  :disabled="submitting"
                  @click="grade(0)"
                >
                  生疏（1）
                </button>
                <button
                  class="rounded-2xl border border-amber-200 bg-amber-50 py-3 text-[14px] text-amber-600 transition-opacity hover:opacity-90 disabled:opacity-40"
                  :disabled="submitting"
                  @click="grade(1)"
                >
                  模糊（2）
                </button>
                <button
                  class="rounded-2xl border border-green-200 bg-green-50 py-3 text-[14px] text-green-600 transition-opacity hover:opacity-90 disabled:opacity-40"
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
              <div class="flex flex-col items-center gap-4 py-10 text-center">
                <span class="flex h-14 w-14 items-center justify-center rounded-full bg-green-50 text-green-600">
                  <Check :size="26" />
                </span>
                <h3 class="text-[17px] font-semibold text-ink">今日复习完成</h3>
                <p class="text-[13px] text-ink-2">
                  已刷 {{ queue.length }} 张
                  <template v-if="stats"> · 队列里还有 {{ stats.total - stats.reviewedToday }} 张等待下次到期</template>
                </p>
                <div class="mt-1 flex gap-2">
                  <button class="rounded-xl border border-line px-4 py-2 text-[14px] text-ink hover:bg-panel" @click="load">
                    返回统计
                  </button>
                  <button
                    class="rounded-xl border border-line px-4 py-2 text-[14px] text-ink hover:bg-panel"
                    @click="router.push('/questions')"
                  >
                    去题目记录加新卡
                  </button>
                </div>
              </div>
            </template>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.review-modal-enter-active,
.review-modal-leave-active {
  transition: opacity 0.18s ease;
}
.review-modal-enter-active > div,
.review-modal-leave-active > div {
  transition: transform 0.18s ease;
}
.review-modal-enter-from,
.review-modal-leave-to {
  opacity: 0;
}
.review-modal-enter-from > div,
.review-modal-leave-to > div {
  transform: scale(0.96);
}
</style>
