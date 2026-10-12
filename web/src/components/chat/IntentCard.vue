<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ChevronDown, ListChecks, Send } from 'lucide-vue-next'

/**
 * 意图确认大卡片（B27 立项 / 2026-10-11 固定题改造）：意图 Agent 的结构化提问——
 * 每个问题一张卡（选项块点选高亮 + 下方自定义文本框），每题选项、文本二选一或并存，
 * 全部作答后「确认开始」可点，确认时拼接为「标题：选项（补充：文本）；…」交回父级发送（意图 Agent 解析消费）。
 * 整卡折叠（2026-10-12，用户口径）：确认回答后卡片收起为一行头部 + 箭头（同「已思考 ∨」样式），
 * 点击箭头展开、再点折叠；confirmed 由宿主传入（历史加载按后续用户回答启发式判定）
 */
export interface IntentCardData {
  message: string
  questions: { title: string; options: string[] }[]
}

const props = defineProps<{ card: IntentCardData; confirmed?: boolean }>()

const emit = defineEmits<{ confirm: [text: string] }>()

const selections = reactive<Record<string, string>>({})
const customs = reactive<Record<string, string>>({})
/** 用户手动切换的折叠态（null = 跟随 confirmed 自动） */
const userCollapsed = ref<boolean | null>(null)

const collapsed = computed(() => userCollapsed.value !== null ? userCollapsed.value : Boolean(props.confirmed))

function toggleCollapsed() {
  userCollapsed.value = !collapsed.value
}

/** 折叠态头部文案：已确认显示完成态，未确认（临时折叠）截取提问文本 */
const headerText = computed(() => {
  if (props.confirmed) {
    return '意图确认 · 已完成'
  }
  const text = props.card.message ?? ''
  return text.length > 24 ? text.slice(0, 24) + '…' : text || '意图确认'
})

/** 单题已作答：选中选项或填写自定义文本任一 */
function answered(title: string): boolean {
  return Boolean(selections[title]) || Boolean(customs[title]?.trim())
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
  userCollapsed.value = true
  emit('confirm', text)
}
</script>

<template>
  <div class="mt-2 rounded-2xl border border-blue-100 bg-blue-50/40 p-3.5">
    <!-- 折叠头部：点击箭头/整行切换展开折叠 -->
    <button
      type="button"
      class="flex w-full items-center gap-1.5 text-left"
      @click="toggleCollapsed"
    >
      <ListChecks :size="14" class="shrink-0 text-blue-500" />
      <span class="truncate text-[13px] font-medium text-gray-800">{{ headerText }}</span>
      <ChevronDown
        :size="14"
        class="ml-auto shrink-0 text-gray-400 transition-transform"
        :class="collapsed ? '-rotate-90' : ''"
      />
    </button>
    <div v-show="!collapsed" class="mt-2.5">
      <div
        v-for="q in card.questions"
        :key="q.title"
        class="mb-2.5 rounded-xl border border-blue-100 bg-white p-3 last:mb-0"
      >
        <p class="flex items-center gap-1.5 text-[13px] font-medium text-gray-800">
          <ListChecks :size="14" class="text-blue-500" />
          {{ q.title }}
        </p>
        <div class="mt-2 flex flex-col gap-2">
          <button
            v-for="opt in q.options"
            :key="opt"
            type="button"
            class="w-full rounded-xl border px-3 py-1.5 text-left text-[13px] transition-all"
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
  </div>
</template>
