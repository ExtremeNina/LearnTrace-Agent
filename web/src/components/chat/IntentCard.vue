<script setup lang="ts">
import { computed, reactive } from 'vue'
import { ListChecks, Send } from 'lucide-vue-next'

/**
 * 意图确认大卡片（B27，plan 模式风格）：意图 Agent 的结构化提问——
 * 每个问题一张卡（选项块点选高亮），全部选完后出现「确认开始」按钮，
 * 确认时把选择拼接为「标题：选项；…」文本交回父级发送（意图 Agent 解析消费）
 */
export interface IntentCardData {
  message: string
  questions: { title: string; options: string[] }[]
}

const props = defineProps<{ card: IntentCardData }>()

const emit = defineEmits<{ confirm: [text: string] }>()

const selections = reactive<Record<string, string>>({})

const allAnswered = computed(
  () => props.card.questions.length > 0 && props.card.questions.every((q) => selections[q.title])
)

function pick(title: string, option: string) {
  selections[title] = option
}

function confirm() {
  if (!allAnswered.value) {
    return
  }
  const text = props.card.questions
    .map((q) => `${q.title}：${selections[q.title]}`)
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
      <p class="flex items-center gap-1.5 text-[13px] font-medium text-gray-800">
        <ListChecks :size="14" class="text-blue-500" />
        {{ q.title }}
      </p>
      <div class="mt-2 flex flex-wrap gap-2">
        <button
          v-for="opt in q.options"
          :key="opt"
          type="button"
          class="rounded-xl border px-3 py-1.5 text-[13px] transition-all"
          :class="selections[q.title] === opt
            ? 'border-primary bg-primary-soft font-medium text-primary ring-1 ring-primary/30'
            : 'border-gray-200 text-gray-600 hover:border-blue-300 hover:bg-blue-50'"
          @click="pick(q.title, opt)"
        >
          {{ opt }}
        </button>
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
      {{ allAnswered ? '确认开始' : '选完上面的选项后确认' }}
    </button>
  </div>
</template>
