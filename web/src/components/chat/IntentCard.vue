<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ChevronsUpDown, ListChecks, Send } from 'lucide-vue-next'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/ui/collapsible'
import { Input } from '@/components/ui/input'
import { Toggle } from '@/components/ui/toggle'

/**
 * 意图确认大卡片（B27 立项 / 2026-10-11 固定题改造）：意图 Agent 的结构化提问——
 * 每题一组（选项点选高亮 + 自定义文本框，二选一或并存），全部作答后「确认开始」可点，
 * 确认时拼接为「标题：选项（补充：文本）；…」交回父级发送（意图 Agent 解析消费）。
 * 整卡折叠：Collapsible 承载（官方交互模式——整行 Trigger + 双 chevron + 高度动画）；
 * 已确认态展开显示只读答案摘要（不再渲染禁用表单）；confirmed 由宿主传入（历史加载按后续用户回答启发式判定）
 */
export interface IntentCardData {
  message: string
  questions: { title: string; options: string[] }[]
  /** 已确认态回显：标题 → 用户回答主体值（含（补充：x）时拆入自定义输入框） */
  answers?: Record<string, string>
}

const props = defineProps<{ card: IntentCardData; confirmed?: boolean }>()

const emit = defineEmits<{ confirm: [text: string] }>()

const selections = reactive<Record<string, string>>({})
const customs = reactive<Record<string, string>>({})
/** 用户手动切换的折叠态（null = 跟随 confirmed 自动） */
const userCollapsed = ref<boolean | null>(null)
/** 锁定：已确认（历史回显或实时确认后）选项不可再改 */
const confirmedLocal = ref(false)
const locked = computed(() => Boolean(props.confirmed) || confirmedLocal.value)

/** Collapsible 开合状态（open = 未折叠）：写入端映射回 userCollapsed */
const open = computed({
  get: () => !(userCollapsed.value !== null ? userCollapsed.value : Boolean(props.confirmed)),
  set: (v: boolean) => {
    userCollapsed.value = !v
  },
})

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

/** 单题答案文本（确认摘要回显用）：选项 + 补充说明 */
function answerOf(title: string): string {
  const extra = customs[title]?.trim()
  const choice = selections[title]
  if (choice && extra) {
    return `${choice}（补充：${extra}）`
  }
  return choice || extra || ''
}

function pick(title: string, option: string) {
  if (locked.value) {
    return
  }
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
  confirmedLocal.value = true
  emit('confirm', text)
}

// 已确认回显：从确认回答解析的 answers 回填——选项命中的高亮，其余（含补充文本）入自定义输入框
if (props.card.answers) {
  for (const [title, raw] of Object.entries(props.card.answers)) {
    const match = raw.match(/^(.*?)（补充：(.*)）$/)
    const main = match ? match[1] : raw
    const extra = match ? match[2] : ''
    const option = props.card.questions.find((q) => q.title === title)?.options.includes(main)
    if (main && option) {
      selections[title] = main
    } else if (main) {
      customs[title] = main
    }
    if (extra) {
      customs[title] = extra
    }
  }
}
</script>

<template>
  <Card class="mt-2 gap-0 rounded-2xl border-blue-100 bg-blue-50/40 p-3.5 shadow-none">
    <Collapsible v-model:open="open">
      <!-- 折叠头部：整行可点 + 官方双 chevron -->
      <CollapsibleTrigger class="flex w-full items-center gap-1.5 text-left">
        <ListChecks :size="14" class="shrink-0 text-blue-500" />
        <span class="truncate text-[13px] font-medium text-gray-800">{{ headerText }}</span>
        <ChevronsUpDown :size="14" class="ml-auto shrink-0 text-gray-400" />
      </CollapsibleTrigger>

      <CollapsibleContent class="intent-collapsible-content overflow-hidden">
        <!-- 未确认：可编辑表单（题目标题作分组标签，选项直接铺在淡蓝底上，无子卡嵌套） -->
        <div v-if="!locked" class="mt-2.5 flex flex-col gap-3">
          <div v-for="q in card.questions" :key="q.title" class="flex flex-col gap-2">
            <p class="flex items-center gap-1.5 text-[12px] font-medium text-gray-500">
              <ListChecks :size="13" class="text-blue-400" />
              {{ q.title }}
            </p>
            <!-- 选项：Toggle pressed 态即选中（主色高亮）；再点取消由 pick() 的 toggle-off 逻辑负责 -->
            <Toggle
              v-for="opt in q.options"
              :key="opt"
              :pressed="selections[q.title] === opt"
              :disabled="locked"
              variant="outline"
              class="h-auto w-full justify-start rounded-xl border-gray-200 bg-white/60 px-3 py-1.5 text-left text-[13px] font-normal text-gray-600 hover:border-blue-300 hover:bg-white hover:text-gray-600 data-[state=on]:border-primary data-[state=on]:bg-primary-soft data-[state=on]:font-medium data-[state=on]:text-primary data-[state=on]:ring-1 data-[state=on]:ring-primary/30"
              @click="pick(q.title, opt)"
            >
              {{ opt }}
            </Toggle>
            <Input
              v-model="customs[q.title]"
              class="h-auto rounded-xl border-gray-200 bg-white py-1.5 text-[13px] text-gray-700 focus-visible:border-blue-300 focus-visible:bg-white focus-visible:ring-0"
              placeholder="或输入自定义回答…"
              :disabled="locked"
              @click.stop
            />
          </div>
          <Button
            class="rounded-xl text-[13px]"
            :disabled="!allAnswered"
            @click="confirm"
          >
            <Send :size="13" />
            {{ allAnswered ? '确认开始' : '每题选择选项或输入自定义回答后确认' }}
          </Button>
        </div>

        <!-- 已确认：只读答案摘要（不再渲染禁用表单） -->
        <dl v-else class="mt-2.5 flex flex-col gap-1.5">
          <div
            v-for="q in card.questions"
            :key="q.title"
            class="flex items-baseline gap-2 text-[13px]"
          >
            <dt class="shrink-0 text-gray-500">{{ q.title }}</dt>
            <dd class="min-w-0 break-words font-medium text-gray-800">
              {{ answerOf(q.title) || '未作答' }}
            </dd>
          </div>
        </dl>
      </CollapsibleContent>
    </Collapsible>
  </Card>
</template>

<style scoped>
/* Collapsible 展开收起高度动画（高度变量由 reka-ui 注入到 Content 元素上） */
.intent-collapsible-content[data-state='open'] {
  animation: intent-collapsible-down 0.2s ease-out;
}
.intent-collapsible-content[data-state='closed'] {
  animation: intent-collapsible-up 0.2s ease-out;
}
@keyframes intent-collapsible-down {
  from {
    height: 0;
  }
  to {
    height: var(--reka-collapsible-content-height);
  }
}
@keyframes intent-collapsible-up {
  from {
    height: var(--reka-collapsible-content-height);
  }
  to {
    height: 0;
  }
}
</style>
