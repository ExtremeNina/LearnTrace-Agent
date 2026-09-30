<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import {
  Bold, Clock, Code, Eye, Heading2, Italic, List, ListOrdered, Minus, Pencil, Quote, Strikethrough,
} from 'lucide-vue-next'
import { renderNoteHtml } from '../../utils/markdown'

/**
 * AI 笔记 Markdown 编辑器（方案 A：存储契约保持 Markdown，语法门槛用按钮消除）。
 * 加粗 / 斜体 / 删除线 / 行内代码为切换式：选中已标记的文字再点一次即取消标记；
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

function setValue(value: string, selStart: number, selEnd: number) {
  emit('update:modelValue', value)
  nextTick(() => {
    taRef.value?.focus()
    taRef.value?.setSelectionRange(selStart, selEnd)
  })
}

/** 光标处插入/包裹标记，保留选区并让插入结果保持选中 */
function insert(prefix: string, suffix = '') {
  const el = taRef.value
  if (!el) {
    return
  }
  const start = el.selectionStart
  const end = el.selectionEnd
  const selected = props.modelValue.slice(start, end)
  setValue(props.modelValue.slice(0, start) + prefix + selected + suffix + props.modelValue.slice(end), start + prefix.length, end + prefix.length)
}

/** 行首插入前缀（标题 / 列表 / 引用）：该行已有同前缀时不重复插 */
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
  setValue(props.modelValue.slice(0, lineStart) + prefix + props.modelValue.slice(lineStart), pos + prefix.length, pos + prefix.length)
}

/**
 * 成对标记的切换（对齐 Word 习惯）：
 * 1) 选区/光标紧邻同标记 → 取消标记（单字符标记先排除外层还有一层星号的情况，**粗体** 内点斜体不误拆）；
 * 2) 选区自带完整标记 → 剥掉；
 * 3) 选区在某个同标记区间内部（只选中了其中一段）→ 取消整个区间的标记；
 * 4) 否则 → 包上标记
 */
function toggleWrap(marker: string) {
  const el = taRef.value
  if (!el) {
    return
  }
  const start = el.selectionStart
  const end = el.selectionEnd
  const value = props.modelValue
  const before = value.slice(start - marker.length, start)
  const after = value.slice(end, end + marker.length)
  if (before === marker && after === marker) {
    if (marker.length === 1) {
      const outerBefore = value.slice(start - 2, start - 1)
      const outerAfter = value.slice(end + 1, end + 2)
      if (outerBefore === marker && outerAfter === marker) {
        insert(marker, marker)
        return
      }
    }
    setValue(value.slice(0, start - marker.length) + value.slice(start, end) + value.slice(end + marker.length), start - marker.length, end - marker.length)
    return
  }
  const selected = value.slice(start, end)
  if (selected.length >= marker.length * 2 && selected.startsWith(marker) && selected.endsWith(marker)) {
    const inner = selected.slice(marker.length, selected.length - marker.length)
    setValue(value.slice(0, start) + inner + value.slice(end), start, start + inner.length)
    return
  }
  // 选区在标记区间内部：向外找最近的同标记对，整体取消
  const left = value.lastIndexOf(marker, start - 1)
  const right = value.indexOf(marker, end)
  if (left >= 0 && right >= 0) {
    const outerSingle = marker.length === 1 && (value[left - 1] === marker || value[right + 1] === marker)
    if (!outerSingle) {
      const inner = value.slice(left + marker.length, right)
      setValue(value.slice(0, left) + inner + value.slice(right + marker.length), left, left + inner.length)
      return
    }
  }
  insert(marker, marker)
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

/** Windows 文本框的 CRLF 换行统一为 LF 后再进 v-model，避免库内混入 \r */
function onInput(e: Event) {
  emit('update:modelValue', (e.target as HTMLTextAreaElement).value.replace(/\r\n/g, '\n'))
}

const previewHtml = computed(() => renderNoteHtml(props.modelValue))

function onPreviewClick(e: MouseEvent) {
  const chip = (e.target as HTMLElement).closest('[data-ts]')
  if (chip) {
    emit('chip', chip.getAttribute('data-ts') || '')
  }
}

const heightClass = computed(() => props.heightClass || 'h-[60vh]')
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
        title="加粗（再点一次取消）"
        @mousedown.prevent
        @click="toggleWrap('**')"
      >
        <Bold :size="15" />
      </button>
      <button
        class="flex h-8 w-8 items-center justify-center rounded-lg text-ink hover:bg-line/60"
        title="斜体（再点一次取消）"
        @mousedown.prevent
        @click="toggleWrap('*')"
      >
        <Italic :size="15" />
      </button>
      <button
        class="flex h-8 w-8 items-center justify-center rounded-lg text-ink hover:bg-line/60"
        title="删除线（再点一次取消）"
        @mousedown.prevent
        @click="toggleWrap('~~')"
      >
        <Strikethrough :size="15" />
      </button>
      <button
        class="flex h-8 w-8 items-center justify-center rounded-lg text-ink hover:bg-line/60"
        title="行内代码（再点一次取消）"
        @mousedown.prevent
        @click="toggleWrap('`')"
      >
        <Code :size="15" />
      </button>
      <button
        class="flex h-8 items-center gap-1 rounded-lg px-2 text-[13px] text-ink hover:bg-line/60"
        title="无序列表"
        @mousedown.prevent
        @click="insertLinePrefix('- ')"
      >
        <List :size="15" />
        列表
      </button>
      <button
        class="flex h-8 items-center gap-1 rounded-lg px-2 text-[13px] text-ink hover:bg-line/60"
        title="有序列表"
        @mousedown.prevent
        @click="insertLinePrefix('1. ')"
      >
        <ListOrdered :size="15" />
        序号
      </button>
      <button
        class="flex h-8 items-center gap-1 rounded-lg px-2 text-[13px] text-ink hover:bg-line/60"
        title="引用"
        @mousedown.prevent
        @click="insertLinePrefix('> ')"
      >
        <Quote :size="15" />
        引用
      </button>
      <button
        class="flex h-8 w-8 items-center justify-center rounded-lg text-ink hover:bg-line/60"
        title="分隔线"
        @mousedown.prevent
        @click="insert('\n\n---\n\n')"
      >
        <Minus :size="15" />
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
      :class="heightClass"
      @input="onInput"
    ></textarea>
    <div
      v-else
      class="note-view w-full overflow-y-auto rounded-2xl border border-primary bg-white p-4 text-[14px] leading-7 text-ink"
      :class="heightClass"
      v-html="previewHtml"
      @click="onPreviewClick"
    ></div>
  </div>
</template>
