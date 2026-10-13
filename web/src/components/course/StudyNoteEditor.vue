<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { Bold, Clock, Italic, Save, Underline } from 'lucide-vue-next'
import DOMPurify from 'dompurify'
import { updateCourse } from '../../api/course'
import { formatTs, parseTs } from '../../utils/format'

const props = defineProps<{
  courseId: number
  /** 初始内容（课程 studyNote，仅挂载时灌入，不随 reload 重置） */
  initialContent: string
  /** 是否允许插入时间戳（取决于视频是否可用） */
  canInsertTs: boolean
  /** 取当前播放秒数（父层注入，时间戳插入用） */
  getCurrentSec: () => number
}>()

const emit = defineEmits<{ seek: [sec: number]; saved: [html: string] }>()

const studyRef = ref<HTMLDivElement | null>(null)
const studyDirty = ref(false)
let studySavedHtml = ''

onMounted(initStudyBox)

function initStudyBox() {
  if (!studyRef.value) {
    return
  }
  studySavedHtml = DOMPurify.sanitize(props.initialContent ?? '')
  studyRef.value.innerHTML = studySavedHtml
  studyDirty.value = false
}

function onStudyInput() {
  if (studyRef.value) {
    studyDirty.value = studyRef.value.innerHTML !== studySavedHtml
  }
}

async function saveStudyNote() {
  if (!studyRef.value || !studyDirty.value) {
    return
  }
  const html = studyRef.value.innerHTML
  try {
    const updated = await updateCourse(props.courseId, { studyNote: html })
    studySavedHtml = updated.studyNote ?? html
    studyDirty.value = false
    emit('saved', html)
  } catch (e) {
    window.alert(e instanceof Error ? e.message : '保存失败，请稍后重试')
  }
}

function execStudy(command: string) {
  document.execCommand('styleWithCSS', false, 'true')
  document.execCommand(command, false)
  studyRef.value?.focus()
}

/** 在光标处插入当前播放进度的时间戳胶囊（保存后可点击跳回该片段） */
function insertStudyTs() {
  const box = studyRef.value
  if (!box) {
    return
  }
  const ts = formatTs(props.getCurrentSec())
  box.focus()
  document.execCommand('insertHTML', false, `<span class="ts-chip" data-ts="${ts}">${ts}</span>&nbsp;`)
  onStudyInput()
}

/** 学习笔记内点击时间戳胶囊 → 跳回视频对应位置 */
function onStudyClick(e: MouseEvent) {
  const chip = (e.target as HTMLElement).closest('[data-ts]')
  if (chip) {
    emit('seek', parseTs(chip.getAttribute('data-ts') || ''))
  }
}
</script>

<template>
  <div class="mt-4 flex min-h-[180px] flex-1 flex-col rounded-xl bg-surface shadow-card">
    <div class="flex flex-wrap items-center gap-2 border-b border-line px-3 py-2">
      <p class="text-[13px] font-semibold text-ink">学习笔记</p>
      <div class="ml-auto flex items-center gap-1">
        <button class="flex h-7 w-7 items-center justify-center rounded-lg text-ink hover:bg-line/60" title="加粗" @mousedown.prevent @click="execStudy('bold')">
          <Bold :size="14" />
        </button>
        <button class="flex h-7 w-7 items-center justify-center rounded-lg text-ink hover:bg-line/60" title="斜体" @mousedown.prevent @click="execStudy('italic')">
          <Italic :size="14" />
        </button>
        <button class="flex h-7 w-7 items-center justify-center rounded-lg text-ink hover:bg-line/60" title="下划线" @mousedown.prevent @click="execStudy('underline')">
          <Underline :size="14" />
        </button>
        <button
          class="flex h-7 items-center gap-1 rounded-lg px-2 text-[12px] text-ink hover:bg-line/60"
          title="插入当前播放进度的时间戳"
          :disabled="!canInsertTs"
          @mousedown.prevent
          @click="insertStudyTs"
        >
          <Clock :size="14" />
          时间戳
        </button>
        <button
          class="ml-1 flex items-center gap-1 rounded-lg px-2.5 py-1.5 text-[12px]"
          :class="studyDirty ? 'bg-primary text-white hover:opacity-90' : 'border border-line text-ink-2 opacity-50 cursor-not-allowed'"
          :disabled="!studyDirty"
          @click="saveStudyNote"
        >
          <Save :size="13" />
          保存
        </button>
      </div>
    </div>
    <div
      ref="studyRef"
      class="study-view min-h-0 flex-1 overflow-y-auto px-4 py-3 text-[14px] leading-7 text-ink outline-none"
      contenteditable="true"
      @input="onStudyInput"
      @click="onStudyClick"
    ></div>
    <p class="border-t border-line px-4 py-1.5 text-[11px] text-ink-2">
      记录观看感想；点「时间戳」插入当前进度，保存后点击胶囊可跳回该片段。
    </p>
  </div>
</template>

<style scoped>
.study-view :deep(.ts-chip) {
  display: inline-flex;
  align-items: center;
  margin-left: 0.25rem;
  padding: 0.05rem 0.4rem;
  border-radius: 0.375rem;
  background: #eff6ff;
  color: #2563eb;
  font-size: 12px;
  cursor: pointer;
  vertical-align: middle;
}
.study-view :deep(.ts-chip:hover) {
  text-decoration: underline;
}
.study-view:empty::before {
  content: '记录这一节课的感想与疑问…';
  color: #9ca3af;
}
</style>
