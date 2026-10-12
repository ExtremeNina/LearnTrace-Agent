<script setup lang="ts">
import { computed, reactive } from 'vue'
import { ChevronUp, ListChecks, Pencil, Send } from 'lucide-vue-next'

/**
 * 意图确认大卡片（B27 立项 / 2026-10-11 固定题改造）：意图 Agent 的结构化提问——
 * 每个问题一张卡（选项块点选高亮 + 下方自定义文本框），每题选项、文本二选一或并存，
 * 全部作答后「确认开始」可点，确认时拼接为「标题：选项（补充：文本）；…」交回父级发送（意图 Agent 解析消费）。
 * 已作答的题目自动收起为一行摘要（2026-10-12），点击可再展开修改，避免多题占满一屏
 */
export interface IntentCardData {
  message: string
  questions: { title: string; options: string[] }[]
}

const props = defineProps<{ card: IntentCardData }>()

const emit = defineEmits<{ confirm: [text: string] }>()

const selections = reactive<Record<string, string>>({})
const customs = reactive<Record<string, string>>({})
/** 手动展开（编辑中）的题目：覆盖自动收起 */
const editing = reactive(new Set<string>())

/** 单题已作答：选中选项或填写自定义文本任一 */
function answered(title: string): boolean {
  return Boolean(selections[title]) || Boolean(customs[title]?.trim())
}

/** 摘要行答案文案：选项优先，其次自定义文本 */
function answerText(title: string): string {
  return selections[title] || customs[title]?.trim() || ''
}

/** 已作答且不在编辑中 → 收起为摘要行 */
function isCollapsed(title: string): boolean {
  return answered(title) && !editing.has(title)
}

function toggleEdit(title: string) {
  if (editing.has(title)) {
    editing.delete(title)
  } else {
    editing.add(title)
  }
}

const allAnswered = computed(
  () => props.card.questions.length > 0 && props.card.questions.every((q) => answered(q.title))
)

function pick(title: string, option: string) {
  selections[title] = selections[title] === option ? '' : option
}

function confirm() {
  if (!allAnswered.value) {
    return
  }
  const text = props.card.questions
    .map((q) => {
      const extra = customs[q.title]?.trim()
      const choice = selections[q.title]
      if (choice && extra) {
        return `${q.title}：${choice}（补充：${extra}）`
      }
      return `${q.title}：${choice || extra}`
    })
    .join('；')
  emit('confirm', text)
}
</script>

<template>
  <div class="mt-2 rounded-2xl border border-blue-100 bg-blue-50/40 p-3.5">
    <div
      v-for="q in card.questions"
      :key="q.title"
      class="mb-2.5 rounded-xl border border-blue-100 bg-white p-3 last:mb-0"
    >
      <!-- 已作答且未在编辑：收起为一行摘要（点击展开修改） -->
      <button
        v-if="isCollapsed(q.title)"
        type="button"
        class="flex w-full items-center gap-1.5 text-left"
        :title="'点击展开修改'"
        @click="toggleEdit(q.title)"
      >
        <ListChecks :size="14" class="shrink-0 text-blue-500" />
        <span class="shrink-0 text-[13px] font-medium text-gray-800">{{ q.title }}</span>
        <span class="ml-auto truncate rounded-md bg-blue-50 px-2 py-0.5 text-[13px] text-primary">{{ answerText(q.title) }}</span>
        <Pencil :size="12" class="shrink-0 text-gray-400" />
      </button>
      <template v-else>
        <p class="flex items-center gap-1.5 text-[13px] font-medium text-gray-800">
          <ListChecks :size="14" class="text-blue-500" />
          {{ q.title }}
          <!-- 已作答（编辑中）：手动收起 -->
          <button
            v-if="answered(q.title)"
            type="button"
            class="ml-auto flex items-center gap-0.5 text-[12px] text-gray-400 transition-colors hover:text-primary"
            @click="toggleEdit(q.title)"
          >
            <ChevronUp :size="13" />
            收起
          </button>
        </p>
        <div class="mt-2 flex flex-col gap-2">
          <button
            v-for="opt in q.options"
            :key="opt"
            type="button"
            class="w-full overflow-x-auto whitespace-nowrap rounded-xl border px-3 py-1.5 text-left text-[13px] transition-all"
            :class="selections[q.title] === opt
              ? 'border-primary bg-primary-soft font-medium text-primary ring-1 ring-primary/30'
              : 'border-gray-200 text-gray-600 hover:border-blue-300 hover:bg-blue-50'"
            @click="pick(q.title, opt)"
          >
            {{ opt }}
          </button>
          <input
            v-model="customs[q.title]"
            type="text"
            class="w-full rounded-xl border border-gray-200 bg-gray-50 px-3 py-1.5 text-[13px] text-gray-700 outline-none transition-colors placeholder:text-gray-400 focus:border-blue-300 focus:bg-white"
            placeholder="或输入自定义回答…"
            @click.stop
          />
        </div>
      </template>
    </div>
    <button
      type="button"
      class="flex w-full items-center justify-center gap-1.5 rounded-xl bg-gradient-to-r from-blue-500 to-blue-600 px-3 py-2 text-[13px] font-medium text-white transition-opacity"
      :class="allAnswered ? 'hover:opacity-90' : 'cursor-not-allowed opacity-40'"
      :disabled="!allAnswered"
      @click="confirm"
    >
      <Send :size="13" />
      {{ allAnswered ? '确认开始' : '每题选择选项或输入自定义回答后确认' }}
    </button>
  </div>
</template>
