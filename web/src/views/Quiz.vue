<script setup lang="ts">
import { computed, onUnmounted, reactive, ref } from 'vue'
import { ListChecks, LoaderCircle, ArrowLeft, ArrowRight, Send, RotateCcw, Plus } from 'lucide-vue-next'
import { pickQuiz } from '../api/quiz'
import { addReviewCard, addReviewCardsBatch } from '../api/review'
import type { ReviewCardType } from '../api/review'
import type { QuizPickInfo, ReviewBatchAddResult } from '../types/api'
import { renderMarkdown } from '../utils/markdown'
import { SUBJECTS } from '../constants/subjects'

/**
 * 练习 / 测验模式（路线图 P0-3）：配置组卷 → 一屏一题作答（正向计时）→ 结果回顾与错题入队。
 * 组卷为后端无状态随机抽题（拍照题目 + AI 相似题），作答只在前端本地，不落库；
 * 结果页勾选做错的题，批量加入复习队列沉淀为复习卡。
 */
const phase = ref<'config' | 'session' | 'result'>('config')
const loading = ref(false)
const error = ref('')

const form = reactive({
  count: 10 as 5 | 10,
  subject: '',
  period: 'all' as '7d' | '30d' | 'all',
  withPhoto: true,
  withSimilar: true,
})

const items = ref<QuizPickInfo[]>([])
const answers = ref<string[]>([])
const index = ref(0)
const startedAt = ref(0)
const elapsedSec = ref(0)
let timer: number | null = null

const selected = ref<boolean[]>([])
const addedKeys = ref<Set<string>>(new Set())
const addingKeys = ref<Set<string>>(new Set())
const batchAdding = ref(false)
const batchResult = ref<ReviewBatchAddResult | null>(null)
const batchError = ref('')

const current = computed(() => items.value[index.value] ?? null)
const answeredCount = computed(() => {
  let count = 0
  for (const a of answers.value) {
    if (a.trim()) {
      count++
    }
  }
  return count
})
const selectedCount = computed(() => selected.value.filter(Boolean).length)
const sourceLabel = (item: QuizPickInfo) => (item.cardType === 'similar' ? 'AI 相似题' : '拍照题目')

function itemKey(item: QuizPickInfo) {
  return `${item.cardType}:${item.refId}`
}

function formatElapsed(sec: number): string {
  const m = Math.floor(sec / 60)
  const s = sec % 60
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
}

async function startQuiz() {
  if (!form.withPhoto && !form.withSimilar) {
    error.value = '至少选择一种题目来源'
    return
  }
  loading.value = true
  error.value = ''
  try {
    const sources: ReviewCardType[] = []
    if (form.withPhoto) {
      sources.push('question')
    }
    if (form.withSimilar) {
      sources.push('similar')
    }
    const picked = await pickQuiz({
      count: form.count,
      subject: form.subject,
      period: form.period,
      sources,
    })
    if (!picked.length) {
      error.value = '题库中没有符合条件的题目，先去积累一些题目吧'
      return
    }
    items.value = picked
    answers.value = picked.map(() => '')
    selected.value = picked.map(() => false)
    addedKeys.value = new Set()
    batchResult.value = null
    batchError.value = ''
    index.value = 0
    startedAt.value = Date.now()
    elapsedSec.value = 0
    timer = window.setInterval(() => {
      elapsedSec.value = Math.floor((Date.now() - startedAt.value) / 1000)
    }, 1000)
    phase.value = 'session'
  } catch (e) {
    error.value = e instanceof Error ? e.message : '抽题失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

function prevQuestion() {
  if (index.value > 0) {
    index.value--
  }
}

async function nextOrSubmit() {
  if (index.value < items.value.length - 1) {
    index.value++
    return
  }
  const unanswered = items.value.length - answeredCount.value
  if (unanswered > 0 && !window.confirm(`还有 ${unanswered} 题未作答，确认交卷？`)) {
    return
  }
  finishQuiz()
}

function finishQuiz() {
  if (timer !== null) {
    window.clearInterval(timer)
    timer = null
  }
  phase.value = 'result'
}

function backToConfig() {
  phase.value = 'config'
}

/** 单题加入复习队列 */
async function addItem(item: QuizPickInfo) {
  const key = itemKey(item)
  addingKeys.value.add(key)
  try {
    await addReviewCard(item.cardType, item.refId)
    addedKeys.value.add(key)
    const i = items.value.indexOf(item)
    if (i >= 0) {
      selected.value[i] = false
    }
  } catch (e) {
    const msg = e instanceof Error ? e.message : '加入失败'
    window.alert(msg.includes('已在复习队列') ? '该题已在复习队列中' : msg)
  } finally {
    addingKeys.value.delete(key)
  }
}

/** 勾选的题批量加入复习队列 */
async function addSelected() {
  if (!selectedCount.value) {
    return
  }
  batchAdding.value = true
  batchError.value = ''
  batchResult.value = null
  try {
    const list: { cardType: ReviewCardType; refId: number }[] = []
    for (let i = 0; i < items.value.length; i++) {
      if (selected.value[i]) {
        const item = items.value[i]
        list.push({ cardType: item.cardType, refId: item.refId })
      }
    }
    const result = await addReviewCardsBatch(list)
    batchResult.value = result
    for (let i = 0; i < items.value.length; i++) {
      if (selected.value[i]) {
        addedKeys.value.add(itemKey(items.value[i]))
        selected.value[i] = false
      }
    }
  } catch (e) {
    batchError.value = e instanceof Error ? e.message : '批量加入失败，请稍后重试'
  } finally {
    batchAdding.value = false
  }
}

onUnmounted(() => {
  if (timer !== null) {
    window.clearInterval(timer)
    timer = null
  }
})
</script>

<template>
  <div class="h-full overflow-y-auto">
    <div class="mx-auto max-w-2xl px-4 py-8">
      <h1 class="flex items-center gap-2 text-[18px] font-semibold text-ink">
        <ListChecks :size="20" class="text-primary" />
        练习测验
      </h1>

      <p v-if="loading" class="mt-6 flex items-center gap-2 text-[14px] text-ink-2">
        <LoaderCircle :size="15" class="animate-spin" />
        正在组卷…
      </p>
      <p v-else-if="error && phase === 'config'" class="mt-6 text-[14px] text-red-600">{{ error }}</p>

      <!-- 阶段一：配置组卷 -->
      <template v-else-if="phase === 'config'">
        <div class="mt-5 rounded-2xl border border-line bg-surface p-5">
          <div>
            <p class="text-[13px] font-semibold text-ink">题量</p>
            <div class="mt-2 flex gap-2">
              <button
                v-for="n in [5, 10] as const"
                :key="n"
                class="rounded-xl px-4 py-1.5 text-[13px] transition-colors"
                :class="form.count === n ? 'bg-primary-soft font-medium text-primary' : 'border border-line text-ink-2 hover:bg-panel'"
                @click="form.count = n"
              >
                {{ n }} 题
              </button>
            </div>
          </div>

          <div class="mt-5">
            <p class="text-[13px] font-semibold text-ink">范围</p>
            <div class="mt-2 flex flex-wrap gap-2">
              <select
                v-model="form.subject"
                class="rounded-xl border border-line bg-surface px-3 py-1.5 text-[13px] text-ink outline-none focus:border-primary"
              >
                <option value="">全部学科</option>
                <option v-for="s in SUBJECTS" :key="s" :value="s">{{ s }}</option>
              </select>
              <div class="flex gap-2">
                <button
                  v-for="p in [
                    { key: '7d', label: '最近 7 天' },
                    { key: '30d', label: '最近 30 天' },
                    { key: 'all', label: '全部时间' },
                  ]"
                  :key="p.key"
                  class="rounded-xl px-3 py-1.5 text-[13px] transition-colors"
                  :class="form.period === p.key ? 'bg-primary-soft font-medium text-primary' : 'border border-line text-ink-2 hover:bg-panel'"
                  @click="form.period = p.key as '7d' | '30d' | 'all'"
                >
                  {{ p.label }}
                </button>
              </div>
            </div>
          </div>

          <div class="mt-5">
            <p class="text-[13px] font-semibold text-ink">来源</p>
            <div class="mt-2 flex gap-4">
              <label class="flex cursor-pointer items-center gap-1.5 text-[13px] text-ink">
                <input v-model="form.withPhoto" type="checkbox" class="accent-primary" />
                拍照题目
              </label>
              <label class="flex cursor-pointer items-center gap-1.5 text-[13px] text-ink">
                <input v-model="form.withSimilar" type="checkbox" class="accent-primary" />
                AI 相似题
              </label>
            </div>
          </div>
        </div>

        <button
          class="mt-6 w-full rounded-2xl bg-primary py-3 text-[15px] text-white hover:opacity-90 disabled:opacity-40"
          :disabled="loading"
          @click="startQuiz"
        >
          开始练习
        </button>
        <p class="mt-3 text-center text-[12px] text-ink-2">
          先做后看解析；交卷后勾选做错的题，一键加入复习队列
        </p>
      </template>

      <!-- 阶段二：一屏一题作答 -->
      <template v-else-if="phase === 'session' && current">
        <div class="flex items-center justify-between text-[12px] text-ink-2">
          <span>第 {{ index + 1 }} / {{ items.length }} 题 · {{ sourceLabel(current) }}</span>
          <span>用时 {{ formatElapsed(elapsedSec) }}</span>
          <button class="rounded-lg px-2 py-1 hover:bg-line/60 hover:text-ink" @click="finishQuiz">交卷</button>
        </div>
        <div class="mt-2 h-1.5 overflow-hidden rounded-full bg-line">
          <div class="h-full rounded-full bg-primary transition-all" :style="{ width: `${((index + 1) / items.length) * 100}%` }"></div>
        </div>

        <div class="mt-5 rounded-2xl border border-line bg-surface p-6">
          <img
            v-if="current.imageUrl"
            :src="current.imageUrl"
            alt="题目图"
            class="mb-4 max-h-48 rounded-xl border border-line"
          />
          <div class="markdown-body text-[15px] leading-7 text-ink" v-html="renderMarkdown(current.questionText || '（无题目文本）')"></div>

          <div class="mt-5 border-t border-line pt-4">
            <p class="text-[13px] font-semibold text-ink-2">你的作答（选填，交卷后与参考答案对照）</p>
            <textarea
              v-model="answers[index]"
              rows="4"
              placeholder="写下你的思路或解答…"
              class="mt-2 w-full resize-y rounded-xl border border-line bg-surface px-3 py-2 text-[14px] leading-6 text-ink outline-none focus:border-primary"
            ></textarea>
          </div>
        </div>

        <div class="mt-4 flex items-center gap-3">
          <button
            class="flex items-center gap-1 rounded-xl border border-line px-4 py-2.5 text-[14px] text-ink hover:bg-panel disabled:cursor-not-allowed disabled:opacity-40"
            :disabled="index === 0"
            @click="prevQuestion"
          >
            <ArrowLeft :size="15" />
            上一题
          </button>
          <button
            class="ml-auto flex items-center gap-1 rounded-xl bg-primary px-5 py-2.5 text-[14px] text-white hover:opacity-90"
            @click="nextOrSubmit"
          >
            <template v-if="index < items.length - 1">
              下一题
              <ArrowRight :size="15" />
            </template>
            <template v-else>
              <Send :size="15" />
              交卷
            </template>
          </button>
        </div>
      </template>

      <!-- 阶段三：结果回顾与错题入队 -->
      <template v-else-if="phase === 'result'">
        <div class="rounded-2xl border border-line bg-surface p-5 text-center">
          <p class="text-[26px] font-semibold text-primary">{{ answeredCount }} / {{ items.length }}</p>
          <p class="mt-1 text-[12px] text-ink-2">已作答 · 用时 {{ formatElapsed(elapsedSec) }}</p>
        </div>

        <div class="mt-4 flex items-center justify-between">
          <p class="text-[13px] text-ink-2">勾选做错的题，加入复习队列</p>
          <button
            class="flex items-center gap-1 rounded-xl bg-primary px-3.5 py-2 text-[13px] text-white hover:opacity-90 disabled:opacity-40"
            :disabled="batchAdding || selectedCount === 0"
            @click="addSelected"
          >
            <Plus :size="14" />
            加入复习（{{ selectedCount }}）
          </button>
        </div>
        <p v-if="batchResult" class="mt-2 text-[12px] text-green-600">
          已加入 {{ batchResult.addedCount }} 题<span v-if="batchResult.skippedCount">，{{ batchResult.skippedCount }} 题已在队列被跳过</span>
        </p>
        <p v-else-if="batchError" class="mt-2 text-[12px] text-red-600">{{ batchError }}</p>

        <div class="mt-3 flex flex-col gap-4">
          <div v-for="(item, i) in items" :key="itemKey(item)" class="rounded-2xl border border-line bg-surface p-5">
            <div class="flex items-center gap-2 text-[12px] text-ink-2">
              <span class="font-medium text-ink">第 {{ i + 1 }} 题</span>
              <span>{{ sourceLabel(item) }}</span>
              <span v-if="item.subject" class="rounded-md bg-primary-soft px-1.5 py-0.5 text-primary">{{ item.subject }}</span>
              <button
                class="ml-auto flex items-center gap-1 rounded-lg px-2 py-1 hover:bg-line/60 disabled:opacity-40"
                :disabled="addedKeys.has(itemKey(item)) || addingKeys.has(itemKey(item))"
                @click="addItem(item)"
              >
                <LoaderCircle v-if="addingKeys.has(itemKey(item))" :size="13" class="animate-spin" />
                {{ addedKeys.has(itemKey(item)) ? '已加入' : '加入复习' }}
              </button>
              <label class="ml-1 flex cursor-pointer items-center gap-1">
                <input v-model="selected[i]" type="checkbox" class="accent-primary" :disabled="addedKeys.has(itemKey(item))" />
                勾选
              </label>
            </div>

            <div class="markdown-body mt-3 text-[14px] leading-7 text-ink" v-html="renderMarkdown(item.questionText || '（无题目文本）')"></div>
            <img v-if="item.imageUrl" :src="item.imageUrl" alt="题目图" class="mt-2 max-h-40 rounded-xl border border-line" />

            <div v-if="answers[i]?.trim()" class="mt-3 rounded-xl bg-panel px-3 py-2">
              <p class="text-[12px] font-semibold text-ink-2">你的作答</p>
              <p class="mt-1 whitespace-pre-wrap text-[13px] leading-6 text-ink">{{ answers[i] }}</p>
            </div>
            <div class="mt-3">
              <p class="text-[12px] font-semibold text-ink-2">参考答案</p>
              <div class="markdown-body mt-1 text-[13px] leading-6 text-ink" v-html="renderMarkdown(item.correctAnswer || '（无参考答案）')"></div>
            </div>
            <div v-if="item.analysis" class="mt-3">
              <p class="text-[12px] font-semibold text-ink-2">解析 / 错因</p>
              <div class="markdown-body mt-1 text-[13px] leading-6 text-ink" v-html="renderMarkdown(item.analysis)"></div>
            </div>
          </div>
        </div>

        <button
          class="mt-6 flex w-full items-center justify-center gap-1.5 rounded-2xl border border-line py-3 text-[14px] text-ink hover:bg-panel"
          @click="backToConfig"
        >
          <RotateCcw :size="15" />
          再练一组
        </button>
      </template>
    </div>
  </div>
</template>
