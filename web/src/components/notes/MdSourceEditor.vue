<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { Bold, Clock, Eye, Heading2, Italic, List, Pencil } from 'lucide-vue-next'
import { renderNoteHtml } from '../../utils/markdown'

/**
 * AI 笔记 Markdown 编辑器（方案 A：存储契约保持 Markdown，语法门槛用按钮消除）。
 * 选中文字点按钮即在光标处插入/包裹对应 Markdown 标记，无需记忆语法；
 * 预览态与阅读态走同一渲染管线（renderNoteHtml），时间戳胶囊可点击（emit chip 由父页面处理跳转）。
 */
const props = defineProps<{
  modelValue: string
  /** 文本域/预览区高度 class，默认 h-[60vh] */
  heightClass?: string
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void
  (e: 'chip', ts: string): void
}>()

const taRef = ref<HTMLTextAreaElement | null>(null)
const preview = ref(false)

/** 光标处插入/包裹标记，保留选区并让插入结果保持选中 */
function insert(prefix: string, suffix = '') {
  const el = taRef.value
  if (!el) {
    return
  }
  const start = el.selectionStart
  const end = el.selectionEnd
  const selected = props.modelValue.slice(start, end)
  emit('update:modelValue', props.modelValue.slice(0, start) + prefix + selected + suffix + props.modelValue.slice(end))
  nextTick(() => {
    el.focus()
    el.setSelectionRange(start + prefix.length, end + prefix.length)
  })
}

/** 行首插入前缀（标题 / 列表）：该行已有同前缀时不重复插 */
function insertLinePrefix(prefix: string) {
  const el = taRef.value
  if (!el) {
    return
  }
  const pos = el.selectionStart
  const lineStart = props.modelValue.lastIndexOf('\n', pos - 1) + 1
  if (props.modelValue.slice(lineStart).startsWith(prefix)) {
    el.focus()
    return
  }
  emit('update:modelValue', props.modelValue.slice(0, lineStart) + prefix + props.modelValue.slice(lineStart))
  nextTick(() => {
    el.focus()
    el.setSelectionRange(pos + prefix.length, pos + prefix.length)
  })
}

function onBold() {
  insert('**', '**')
}

function onItalic() {
  insert('*', '*')
}

function onTimestamp() {
  const ts = window.prompt('输入时间戳（分:秒 或 时:分:秒），如 02:27')
  if (ts == null) {
    return
  }
  const trimmed = ts.trim()
  if (/^\d{1,2}:[0-5]\d(:[0-5]\d)?$/.test(trimmed)) {
    insert(`[${trimmed}]`)
  } else {
    window.alert('时间戳格式应为 分:秒，如 02:27')
  }
}

const previewHtml = computed(() => renderNoteHtml(props.modelValue))

function onPreviewClick(e: MouseEvent) {
  const chip = (e.target as HTMLElement).closest('[data-ts]')
  if (chip) {
    emit('chip', chip.getAttribute('data-ts') || '')
  }
}
</script>

<template>
  <div>
    <div class="mb-2 flex flex-wrap items-center gap-1.5 rounded-xl border border-line bg-panel px-2.5 py-2">
      <button
        class="flex h-8 items-center gap-1 rounded-lg px-2 text-[13px] text-ink hover:bg-line/60"
        title="当前行设为标题"
        @mousedown.prevent
        @click="insertLinePrefix('## ')"
      >
        <Heading2 :size="15" />
        标题
      </button>
      <button
        class="flex h-8 w-8 items-center justify-center rounded-lg text-ink hover:bg-line/60"
        title="加粗（选中文字后点此）"
        @mousedown.prevent
        @click="onBold"
      >
        <Bold :size="15" />
      </button>
      <button
        class="flex h-8 w-8 items-center justify-center rounded-lg text-ink hover:bg-line/60"
        title="斜体（选中文字后点此）"
        @mousedown.prevent
        @click="onItalic"
      >
        <Italic :size="15" />
      </button>
      <button
        class="flex h-8 items-center gap-1 rounded-lg px-2 text-[13px] text-ink hover:bg-line/60"
        title="当前行设为列表项"
        @mousedown.prevent
        @click="insertLinePrefix('- ')"
      >
        <List :size="15" />
        列表
      </button>
      <button
        class="flex h-8 items-center gap-1 rounded-lg px-2 text-[13px] text-ink hover:bg-line/60"
        title="插入时间戳（保存后可点击跳转视频）"
        @mousedown.prevent
        @click="onTimestamp"
      >
        <Clock :size="15" />
        时间戳
      </button>
      <span class="mx-1 h-5 w-px bg-line" />
      <!-- 保存 / 取消等页面级操作由父页面提供 -->
      <slot name="actions" />
      <button
        class="ml-auto flex h-8 items-center gap-1 rounded-lg px-2 text-[13px] hover:bg-line/60"
        :class="preview ? 'text-primary' : 'text-ink'"
        title="切换预览与编辑"
        @click="preview = !preview"
      >
        <Pencil v-if="preview" :size="15" />
        <Eye v-else :size="15" />
        {{ preview ? '继续编辑' : '预览' }}
      </button>
    </div>

    <textarea
      v-if="!preview"
      ref="taRef"
      :value="modelValue"
      class="w-full resize-y rounded-2xl border border-primary bg-white p-4 font-mono text-[13px] leading-6 text-ink outline-none"
      :class="heightClass || 'h-[60vh]'"
      @input="emit('update:modelValue', ($event.target as HTMLTextAreaElement).value)"
    ></textarea>
    <div
      v-else
      class="note-view w-full overflow-y-auto rounded-2xl border border-primary bg-white p-4 text-[14px] leading-7 text-ink"
      :class="heightClass || 'h-[60vh]'"
      v-html="previewHtml"
      @click="onPreviewClick"
    ></div>
  </div>
</template>
