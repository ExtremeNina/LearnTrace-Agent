<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import type { CourseQuizQuestionInfo } from '../../api/course'
import { appendCourseQuiz, quizToQuestions, quizToReview } from '../../api/course'
import { useToastStore } from '../../stores/toast'
import { formatTs, parseTs } from '../../utils/format'

const props = defineProps<{
  courseId: number
  questions: CourseQuizQuestionInfo[]
  /** 课程 error_msg（追加出题失败时后端写入的真实原因） */
  errorMsg?: string | null
  reload: () => Promise<void>
}>()

const emit = defineEmits<{ seek: [sec: number] }>()

const toast = useToastStore()

const quizQuestions = computed(() => props.questions)

interface ParsedQuizQuestion {
  stem: string
  options: string[]
  subQuestions: string[]
}

/**
 * 题面结构化解析（启发式）：把「题干 A. xx B. xx C. xx D. xx」拆为选项行、「（1）…（2）…」拆为小问行；
 * 找不到 A-D 连续序列或（N）小问时回退原样整段渲染（stem 即全文）。题干中的代码/括号说明不误切
 */
function parseQuestionText(text: string): ParsedQuizQuestion {
  const t = text.trim()
  // 选项序列：空格/行首 + 字母 A-D + [.、．:：]，须构成 A,B,C,… 连续序列（≥2 个）才认定
  const markerRegex = /(?:^|[\s　])([A-D])[.、．:：]\s*/g
  const marks: { letter: string; start: number; end: number }[] = []
  let m: RegExpExecArray | null
  while ((m = markerRegex.exec(t)) !== null) {
    marks.push({ letter: m[1], start: m.index, end: m.index + m[0].length })
  }
  let optionsStart = -1
  for (let i = 0; i < marks.length; i++) {
    if (marks[i].letter !== 'A') {
      continue
    }
    let code = 66
    let j = i + 1
    while (j < marks.length && marks[j].letter.charCodeAt(0) === code) {
      code++
      j++
    }
    if (j - i >= 2) {
      optionsStart = i
      break
    }
  }
  if (optionsStart >= 0) {
    const stem = t.slice(0, marks[optionsStart].start).trim()
    const options: string[] = []
    for (let i = optionsStart; i < marks.length; i++) {
      const end = i + 1 < marks.length ? marks[i + 1].start : t.length
      options.push(t.slice(marks[i].start, end).trim())
    }
    return { stem, options, subQuestions: [] }
  }
  // 小问拆分：（1）（2）… 或 (1)(2)…，≥2 个才认定；排除代码内括号（括号内非纯数字不命中）
  const subRegex = /(?:^|[\s　])[(（](\d{1,2})[)）]/g
  const subs: number[] = []
  let s: RegExpExecArray | null
  while ((s = subRegex.exec(t)) !== null) {
    subs.push(s.index)
  }
  if (subs.length >= 2) {
    const stem = t.slice(0, subs[0]).trim()
    const subQuestions: string[] = []
    for (let i = 0; i < subs.length; i++) {
      const end = i + 1 < subs.length ? subs[i + 1] : t.length
      subQuestions.push(t.slice(subs[i], end).trim())
    }
    return { stem, options: [], subQuestions }
  }
  return { stem: t, options: [], subQuestions: [] }
}

const parsedQuizQuestions = computed(() => quizQuestions.value.map((q) => parseQuestionText(q.questionText)))

const quizGenerating = ref(false)
const expandedAnswers = reactive(new Set<number>())
const showAllAnswers = ref(false)
const savedToQuestions = reactive(new Set<number>())
const savedToReview = reactive(new Set<number>())

function toggleAnswer(id: number) {
  if (expandedAnswers.has(id)) {
    expandedAnswers.delete(id)
  } else {
    expandedAnswers.add(id)
  }
}

function toggleAllAnswers() {
  showAllAnswers.value = !showAllAnswers.value
}

/** 追加出题：异步后轮询（与重新生成同模式） */
async function appendQuiz(count = 5) {
  if (quizGenerating.value) {
    return
  }
  quizGenerating.value = true
  try {
    await appendCourseQuiz(props.courseId, count)
    toast.push('已开始追加出题，约 1 分钟')
    const before = quizQuestions.value.length
    for (let i = 0; i < 12; i++) {
      await new Promise((r) => setTimeout(r, 10000))
      await props.reload()
      if (quizQuestions.value.length > before || i === 11) {
        break
      }
    }
    if (quizQuestions.value.length > before) {
      toast.push(`已新增 ${quizQuestions.value.length - before} 道课后习题`)
    } else {
      // 后端把真实失败原因写进了课程 error_msg（如模型服务余额不足），优先展示
      const err = props.errorMsg ?? ''
      toast.push(err && err.includes('余额不足') ? err : '追加出题超时，可稍后手动刷新', 'error')
    }
  } catch (e) {
    toast.push(e instanceof Error ? e.message : '追加出题失败', 'error')
  } finally {
    quizGenerating.value = false
  }
}

async function saveToQuestions(id: number) {
  try {
    await quizToQuestions(id)
    savedToQuestions.add(id)
    toast.push('已加入题目管理')
  } catch (e) {
    toast.push(e instanceof Error ? e.message : '加入失败', 'error')
  }
}

async function saveToReview(id: number) {
  try {
    await quizToReview(id)
    savedToReview.add(id)
    toast.push('已加入复习计划（含题目管理）')
  } catch (e) {
    toast.push(e instanceof Error ? e.message : '加入失败', 'error')
  }
}

/** 时间戳胶囊点击（容器事件委托）→ 上抛 seek */
function onQuizClick(e: MouseEvent) {
  const chip = (e.target as HTMLElement).closest('[data-ts]')
  if (chip) {
    emit('seek', parseTs(chip.getAttribute('data-ts') || ''))
  }
}
</script>

<template>
  <div class="flex min-h-0 flex-1 flex-col" @click="onQuizClick">
    <div class="flex shrink-0 items-center justify-between border-b border-line px-3 py-2">
      <p class="text-[13px] text-ink-2">
        共 {{ quizQuestions.length }} 题<span v-if="quizQuestions.length"> · 时间戳可点击跳转</span>
      </p>
      <div class="flex items-center gap-2">
        <button
          v-if="quizQuestions.length"
          class="text-[12px] text-ink-2 transition-colors hover:text-primary"
          @click="toggleAllAnswers"
        >
          {{ showAllAnswers ? '隐藏全部答案' : '显示全部答案' }}
        </button>
        <button
          class="rounded-full bg-primary px-3 py-1 text-[12px] text-white transition-opacity hover:opacity-90 disabled:opacity-50"
          :disabled="quizGenerating"
          @click="appendQuiz(5)"
        >
          {{ quizGenerating ? '出题中…' : quizQuestions.length ? '再出几道' : '生成课后习题' }}
        </button>
      </div>
    </div>
    <div class="min-h-0 flex-1 overflow-y-auto p-3">
      <div
        v-if="quizQuestions.length === 0"
        class="flex h-40 flex-col items-center justify-center gap-2 text-[13px] text-ink-2"
      >
        <p>{{ quizGenerating ? '出题 Agent 正在出题，判题 Agent 把关中…' : '这门课还没有课后习题' }}</p>
        <p v-if="!quizGenerating" class="text-[12px] text-ink-2/70">点击右上角「生成课后习题」，题目数量由 AI 按时长与知识点规划</p>
      </div>
      <div class="flex flex-col gap-2.5">
        <div
          v-for="(q, qi) in quizQuestions"
          :key="q.id"
          class="rounded-xl bg-panel px-3 py-2.5"
        >
          <p class="text-[14px] font-medium leading-6 text-ink">
            {{ qi + 1 }}. {{ parsedQuizQuestions[qi]?.stem ?? q.questionText }}
          </p>
          <div
            v-if="parsedQuizQuestions[qi]?.options.length"
            class="mt-1.5 flex flex-col gap-1 pl-4"
          >
            <p v-for="opt in parsedQuizQuestions[qi]!.options" :key="opt" class="text-[13.5px] leading-6 text-ink">
              {{ opt }}
            </p>
          </div>
          <div
            v-else-if="parsedQuizQuestions[qi]?.subQuestions.length"
            class="mt-1.5 flex flex-col gap-1 pl-4"
          >
            <p v-for="sub in parsedQuizQuestions[qi]!.subQuestions" :key="sub" class="text-[13.5px] leading-6 text-ink">
              {{ sub }}
            </p>
          </div>
          <div
            v-if="showAllAnswers || expandedAnswers.has(q.id)"
            class="mt-2 rounded-lg bg-surface px-3 py-2 text-[13px] leading-6"
          >
            <p class="font-medium text-ink">答案</p>
            <p class="mt-0.5 text-ink">{{ q.answer }}</p>
            <template v-if="q.analysis">
              <p class="mt-1.5 font-medium text-ink">解析</p>
              <p class="mt-0.5 text-ink-2">{{ q.analysis }}</p>
            </template>
          </div>
          <div class="mt-2 flex items-center gap-3 text-[12px]">
            <button
              class="text-primary hover:underline"
              @click="toggleAnswer(q.id)"
            >
              {{ showAllAnswers || expandedAnswers.has(q.id) ? '收起答案' : '查看答案' }}
            </button>
            <button
              class="text-ink-2 transition-colors hover:text-primary disabled:opacity-50"
              :disabled="savedToQuestions.has(q.id)"
              @click="saveToQuestions(q.id)"
            >
              {{ savedToQuestions.has(q.id) ? '已入题目管理' : '加入题目管理' }}
            </button>
            <button
              class="text-ink-2 transition-colors hover:text-primary disabled:opacity-50"
              :disabled="savedToReview.has(q.id)"
              @click="saveToReview(q.id)"
            >
              {{ savedToReview.has(q.id) ? '已入复习计划' : '加入复习计划' }}
            </button>
            <button
              v-if="q.sourceSec != null"
              class="ts-chip ml-auto"
              :data-ts="formatTs(q.sourceSec)"
            >{{ formatTs(q.sourceSec) }}</button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
