<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  Download, ArrowLeft, CircleCheck, LoaderCircle, Pencil, Save, X,
  Bold, Italic, Underline, Clock, Trash2,
} from 'lucide-vue-next'
import { getCourseDetail, updateCourse, deleteCourse } from '../api/course'
import type { CourseDetailData } from '../api/course'
import { updateNoteContent } from '../api/note'
import { renderNoteHtml } from '../utils/markdown'
import DOMPurify from 'dompurify'
import { SUBJECTS } from '../constants/subjects'
import MdSourceEditor from '../components/notes/MdSourceEditor.vue'

/**
 * 网课详情（PRD §4.1 + §3.2 时间戳同步观看）：
 * 左列视频 + 学习笔记（用户随想，工具栏含时间戳插入）；右列 AI 笔记 / 转写对照（整合关键帧与时间轴）。
 * 色彩与字号沿用全局规范，仅调整布局。
 */
const route = useRoute()
const router = useRouter()
const courseId = Number(route.params.id)

const data = ref<CourseDetailData | null>(null)
const loading = ref(true)
const error = ref('')
const activeTab = ref<'note' | 'transcript'>('note')
const videoRef = ref<HTMLVideoElement | null>(null)
/** 视频元数据时长（老数据 duration 字段可能为空，用播放器时长兜底） */
const videoDuration = ref(0)

onMounted(async () => {
  try {
    data.value = await getCourseDetail(courseId)
    // 支持从笔记页时间戳跳转进入：/courses/1?t=08:24 → 加载后自动 seek
    const t = route.query.t
    if (t && videoRef.value) {
      const seek = () => seekTo(parseTs(String(t)))
      videoRef.value.addEventListener('loadedmetadata', seek, { once: true })
    }
    videoRef.value?.addEventListener('loadedmetadata', () => {
      videoDuration.value = Math.floor(videoRef.value?.duration ?? 0)
    })
    // 学习笔记初始内容在 DOM 挂载后灌入（不绑定响应式，避免保存后光标重置）
    nextTick(() => initStudyBox())
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
})

function parseTs(ts: string): number {
  const parts = ts.split(':').map(Number)
  return parts.length === 3
    ? parts[0] * 3600 + parts[1] * 60 + parts[2]
    : parts[0] * 60 + parts[1]
}

function seekTo(sec: number) {
  if (videoRef.value) {
    videoRef.value.currentTime = sec
    videoRef.value.play()
  }
}

/** 编辑器预览里点时间戳胶囊 → 跳转视频对应位置 */
function onEditorChip(ts: string) {
  seekTo(parseTs(ts))
}

function formatTs(sec: number): string {
  const h = Math.floor(sec / 3600)
  const m = Math.floor((sec % 3600) / 60)
  const s = sec % 60
  const mm = String(m).padStart(2, '0')
  const ss = String(s).padStart(2, '0')
  return h > 0 ? `${h}:${mm}:${ss}` : `${mm}:${ss}`
}

function formatSize(bytes: number | null | undefined): string {
  if (!bytes) {
    return '--'
  }
  return Math.round(bytes / 1024 / 1024) + 'MB'
}

/** 笔记渲染：与笔记整理页同一管线（归一化 → Markdown → [mm:ss] 时间戳胶囊） */
const noteHtml = computed(() => {
  const note = data.value?.note
  return note ? renderNoteHtml(note.content) : ''
})

function onNoteClick(e: MouseEvent) {
  const chip = (e.target as HTMLElement).closest('[data-ts]')
  if (chip) {
    seekTo(parseTs(chip.getAttribute('data-ts') || ''))
  }
}

function onTabChange(tab: 'note' | 'transcript') {
  activeTab.value = tab
}

// 在线编辑标题 / 学科
const editMode = ref(false)
const editForm = reactive({ title: '', subject: '' })
const saving = ref(false)

function openEdit() {
  if (!data.value) {
    return
  }
  editForm.title = data.value.course.title
  editForm.subject = data.value.course.subject ?? ''
  saving.value = false
  editMode.value = true
}

async function saveEdit() {
  if (!data.value || !editForm.title.trim()) {
    return
  }
  saving.value = true
  try {
    data.value.course = await updateCourse(courseId, { title: editForm.title, subject: editForm.subject })
    editMode.value = false
  } finally {
    saving.value = false
  }
}

async function removeCourse() {
  if (!window.confirm('确定删除这门网课吗？AI 笔记与相关记录会一并删除，视频文件不可恢复。')) {
    return
  }
  try {
    await deleteCourse(courseId)
    router.push({ name: 'courses' })
  } catch (e) {
    error.value = e instanceof Error ? e.message : '删除失败，请稍后重试'
  }
}

// 在线编辑 AI 笔记：Markdown 源码编辑，保存回笔记正文接口。
// AI 笔记必须存 Markdown（详情页靠 renderMarkdown 渲染并转换 [mm:ss] 时间戳胶囊），不能存富文本 HTML
const noteEditing = ref(false)
const noteDraft = ref('')
const noteSaving = ref(false)
const noteError = ref('')

function startNoteEdit() {
  if (!data.value?.note) {
    return
  }
  noteDraft.value = data.value.note.content
  noteError.value = ''
  noteSaving.value = false
  noteEditing.value = true
}

async function saveNoteEdit() {
  if (!data.value?.note) {
    return
  }
  noteSaving.value = true
  noteError.value = ''
  try {
    await updateNoteContent(data.value.note.id, noteDraft.value)
    data.value.note.content = noteDraft.value
    noteEditing.value = false
  } catch (e) {
    noteError.value = e instanceof Error ? e.message : '保存失败，请稍后重试'
  } finally {
    noteSaving.value = false
  }
}

// ---- 学习笔记（视频下方随想区，富文本 + 时间戳插入） ----
const studyRef = ref<HTMLDivElement | null>(null)
const studyDirty = ref(false)
let studySavedHtml = ''

function initStudyBox() {
  if (!studyRef.value) {
    return
  }
  studySavedHtml = DOMPurify.sanitize(data.value?.course.studyNote ?? '')
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
    const updated = await updateCourse(courseId, { studyNote: html })
    if (data.value) {
      data.value.course.studyNote = html
    }
    studySavedHtml = updated.studyNote ?? html
    studyDirty.value = false
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
  const video = videoRef.value
  const box = studyRef.value
  if (!video || !box) {
    return
  }
  const ts = formatTs(video.currentTime)
  box.focus()
  document.execCommand('insertHTML', false, `<span class="ts-chip" data-ts="${ts}">${ts}</span>&nbsp;`)
  onStudyInput()
}

/** 学习笔记内点击时间戳胶囊 → 跳回视频对应位置 */
function onStudyClick(e: MouseEvent) {
  const chip = (e.target as HTMLElement).closest('[data-ts]')
  if (chip) {
    seekTo(parseTs(chip.getAttribute('data-ts') || ''))
  }
}

// ---- 转写对照：段落与关键帧整合 ----
const duration = computed(() => {
  if (data.value?.course.duration) {
    return data.value.course.duration
  }
  if (videoDuration.value) {
    return videoDuration.value
  }
  const maxSeg = Math.max(0, ...(data.value?.transcript ?? []).map((s) => s.endSec))
  const maxFrame = Math.max(0, ...(data.value?.frames ?? []).map((f) => f.timeSec))
  return Math.max(maxSeg, maxFrame)
})

/** 转写段落 + 落在该时间段内的关键帧（未落段的帧挂到最后一段） */
const segmentsWithFrames = computed(() => {
  const frames = [...(data.value?.frames ?? [])].sort((a, b) => a.timeSec - b.timeSec)
  const used = new Set<number>()
  const rows = (data.value?.transcript ?? []).map((seg) => {
    const segFrames = frames.filter((f) => f.timeSec >= seg.startSec && f.timeSec < seg.endSec && !used.has(f.id))
    segFrames.forEach((f) => used.add(f.id))
    return { seg, frames: segFrames }
  })
  const rest = frames.filter((f) => !used.has(f.id))
  if (rest.length && rows.length) {
    rows[rows.length - 1].frames.push(...rest)
  }
  return rows
})

/** 时间轴刻度：转写段起点 + 关键帧位置 */
const timelineTicks = computed(() => {
  const total = duration.value
  if (!total) {
    return []
  }
  const ticks: { t: number; kind: 'transcript' | 'frame'; pct: number }[] = []
  for (const seg of data.value?.transcript ?? []) {
    if (seg.startSec <= total) {
      ticks.push({ t: seg.startSec, kind: 'transcript', pct: (seg.startSec / total) * 100 })
    }
  }
  for (const f of data.value?.frames ?? []) {
    if (f.timeSec <= total) {
      ticks.push({ t: f.timeSec, kind: 'frame', pct: (f.timeSec / total) * 100 })
    }
  }
  return ticks
})
</script>

<template>
  <div class="h-full overflow-y-auto lg:overflow-hidden">
    <div class="flex min-h-full flex-col px-4 py-6 lg:h-full">
      <!-- 加载 / 错误 -->
      <div v-if="loading" class="flex h-64 items-center justify-center text-[14px] text-ink-2">加载中…</div>
      <div v-else-if="error" class="flex h-64 items-center justify-center text-[14px] text-red-500">{{ error }}</div>

      <template v-else-if="data">
        <!-- 返回 + 标题 + 操作 -->
        <div class="flex shrink-0 items-center gap-3">
          <RouterLink
            to="/courses"
            class="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg text-ink-2 hover:bg-line/60"
          >
            <ArrowLeft :size="18" />
          </RouterLink>
          <div class="min-w-0">
            <h1 class="truncate text-[18px] font-semibold">{{ data.course.title }}</h1>
            <p class="mt-0.5 flex items-center gap-2 text-[12px] text-ink-2">
              <span
                v-if="data.course.subject"
                class="rounded-md bg-primary-soft px-1.5 py-0.5 text-primary"
              >
                {{ data.course.subject }}
              </span>
              <span>{{ data.course.createdAt }} · {{ formatSize(data.course.videoSize) }}</span>
              <span v-if="data.course.expectations" class="text-primary">已注入你的特别要求</span>
            </p>
          </div>
          <div class="ml-auto flex shrink-0 items-center gap-2">
            <button
              class="flex items-center gap-1.5 rounded-xl border border-line px-3 py-1.5 text-[13px] text-ink hover:bg-panel"
              @click="openEdit"
            >
              <Pencil :size="15" />
              编辑
            </button>
            <button class="flex items-center gap-1.5 rounded-xl border border-line px-3 py-1.5 text-[13px] text-ink hover:bg-panel">
              <CircleCheck :size="15" />
              AI 润色
            </button>
            <button class="flex items-center gap-1.5 rounded-xl border border-line px-3 py-1.5 text-[13px] text-ink hover:bg-panel">
              <Download :size="15" />
              导出
            </button>
            <button
              class="flex items-center gap-1.5 rounded-xl border border-line px-3 py-1.5 text-[13px] text-red-500 hover:bg-red-50"
              title="删除网课"
              @click="removeCourse"
            >
              <Trash2 :size="15" />
              删除
            </button>
          </div>
        </div>

        <div class="mt-5 grid min-h-0 flex-1 grid-cols-1 gap-5 lg:grid-cols-[3fr_2fr]">
          <!-- 左：视频 + 学习笔记 -->
          <div class="flex min-h-0 flex-col">
            <video
              v-if="data.course.videoOssKey"
              ref="videoRef"
              class="aspect-video w-full shrink-0 rounded-2xl border border-line bg-black"
              controls
              preload="metadata"
              :src="data.course.videoOssKey"
            />
            <div v-else class="flex aspect-video w-full items-center justify-center rounded-2xl border border-line bg-panel text-[14px] text-ink-2">
              视频处理中，稍后可在线观看
            </div>

            <div class="mt-3 flex shrink-0 items-center gap-1.5 rounded-xl border border-line bg-primary-soft/60 px-3 py-2 text-[12px] text-ink-2">
              <template v-if="data.course.status === 'SUCCESS'">
                <CircleCheck :size="14" class="shrink-0 text-green-600" />
                AI 笔记由转写与画面识别生成，点击时间戳可跳回原片段核对。
              </template>
              <template v-else>
                <LoaderCircle :size="14" class="shrink-0 animate-spin text-amber-600" />
                网课正在流水线处理中，完成后可在线观看并生成 AI 笔记。
              </template>
            </div>

            <!-- 学习笔记（用户随想） -->
            <div class="mt-4 flex min-h-[180px] flex-1 flex-col rounded-2xl border border-ink-2/25 bg-panel">
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
                    :disabled="!data.course.videoOssKey"
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
          </div>

          <!-- 右：AI 笔记 / 转写对照 -->
          <div class="flex min-h-0 flex-col rounded-2xl border border-ink-2/25 bg-panel">
            <div class="flex shrink-0 items-center gap-1 border-b border-line p-2">
              <button
                v-for="tab in [
                  { key: 'note', label: 'AI 笔记' },
                  { key: 'transcript', label: '转写对照' },
                ]"
                :key="tab.key"
                class="flex-1 rounded-lg py-1.5 text-[14px] transition-colors"
                :class="activeTab === tab.key ? 'bg-white font-medium text-ink shadow-sm' : 'text-ink-2 hover:text-ink'"
                @click="onTabChange(tab.key as 'note' | 'transcript')"
              >
                {{ tab.label }}
              </button>
            </div>

            <div class="min-h-0 flex-1 overflow-y-auto p-3">
              <!-- AI 笔记 -->
              <template v-if="activeTab === 'note'">
                <div v-if="data.course.expectations" class="mb-3 rounded-xl border border-line bg-panel px-3 py-2">
                  <p class="text-[12px] font-semibold text-ink">您希望的内容</p>
                  <p class="mt-1 text-[12px] leading-5 text-ink-2">{{ data.course.expectations }}</p>
                </div>

                <div v-if="noteEditing" class="px-1 pb-3">
                  <p v-if="noteError" class="mb-2 text-[12px] text-red-600">{{ noteError }}</p>
                  <MdSourceEditor v-model="noteDraft" height-class="h-[420px]" @chip="onEditorChip">
                    <template #actions>
                      <button
                        class="flex items-center gap-1 rounded-lg bg-ink px-3 py-1.5 text-[13px] text-white hover:opacity-80 disabled:opacity-50"
                        :disabled="noteSaving"
                        @click="saveNoteEdit"
                      >
                        <Save :size="14" />
                        保存
                      </button>
                      <button
                        class="flex items-center gap-1 rounded-lg border border-line px-3 py-1.5 text-[13px] text-ink-2 hover:bg-line/40"
                        @click="noteEditing = false"
                      >
                        <X :size="14" />
                        取消
                      </button>
                    </template>
                  </MdSourceEditor>
                </div>
                <template v-else>
                  <button
                    v-if="data.note"
                    class="mb-3 flex w-full items-center justify-center gap-1.5 rounded-xl border border-dashed border-line py-2 text-[13px] text-ink-2 hover:border-primary hover:text-primary"
                    @click="startNoteEdit"
                  >
                    <Pencil :size="14" />
                    编辑 AI 笔记（Markdown 源码）
                  </button>
                  <div class="note-view rounded-xl border border-line bg-white p-3 text-[14px] leading-7 text-ink" v-html="noteHtml" @click="onNoteClick"></div>
                  <p v-if="!data.note" class="rounded-xl border border-dashed border-line py-8 text-center text-[13px] text-ink-2">
                    AI 笔记生成中，完成后展示在这里
                  </p>
                  <p class="mt-2 text-[11px] text-ink-2">AI 笔记由转写与画面识别生成，点击时间戳可跳回原片段核对。</p>
                </template>
              </template>

              <!-- 转写对照（整合关键帧） -->
              <template v-else>
                <div v-if="timelineTicks.length" class="mb-3 px-1 pt-1">
                  <div class="relative h-2 rounded-full bg-line/70">
                    <button
                      v-for="(tick, i) in timelineTicks"
                      :key="i"
                      class="absolute top-1/2 h-3 w-[3px] -translate-y-1/2 rounded-full"
                      :class="tick.kind === 'frame' ? 'bg-primary' : 'bg-ink-2/60'"
                      :style="{ left: tick.pct + '%' }"
                      :title="formatTs(tick.t) + (tick.kind === 'frame' ? ' · 关键帧' : ' · 转写段')"
                      @click="seekTo(tick.t)"
                    ></button>
                  </div>
                  <div class="mt-1 flex justify-between text-[11px] text-ink-2">
                    <span>00:00</span>
                    <span>{{ formatTs(duration) }}</span>
                  </div>
                  <div class="mt-1 flex gap-3 text-[11px] text-ink-2">
                    <span class="flex items-center gap-1"><span class="h-1.5 w-1.5 rounded-full bg-ink-2/60"></span>转写段落</span>
                    <span class="flex items-center gap-1"><span class="h-1.5 w-1.5 rounded-full bg-primary"></span>关键帧</span>
                    <span class="ml-auto">点击刻度跳转</span>
                  </div>
                </div>

                <div class="flex flex-col gap-2">
                  <div v-for="row in segmentsWithFrames" :key="row.seg.id" class="rounded-xl border border-line bg-white px-3 py-2.5">
                    <div class="flex gap-3">
                      <button class="shrink-0 pt-0.5 text-[12px] text-primary hover:underline" @click="seekTo(row.seg.startSec)">
                        {{ formatTs(row.seg.startSec) }}
                      </button>
                      <p class="min-w-0 flex-1 text-[13px] leading-6 text-ink">{{ row.seg.text }}</p>
                    </div>
                    <div v-if="row.frames.length" class="mt-2 flex gap-2 overflow-x-auto pb-1">
                      <button
                        v-for="f in row.frames"
                        :key="f.id"
                        class="relative shrink-0"
                        :title="f.ocrText || '关键帧 ' + formatTs(f.timeSec)"
                        @click="seekTo(f.timeSec)"
                      >
                        <img :src="f.ossKey" :alt="'第 ' + f.timeSec + 's 画面'" class="h-14 w-24 rounded-lg border border-line object-cover" />
                        <span class="absolute bottom-0.5 right-0.5 rounded bg-black/60 px-1 text-[10px] text-white">{{ formatTs(f.timeSec) }}</span>
                      </button>
                    </div>
                  </div>
                  <p v-if="!(data.transcript.length || data.frames.length)" class="rounded-xl border border-dashed border-line py-8 text-center text-[13px] text-ink-2">
                    未获得转写与关键帧数据
                  </p>
                </div>
              </template>
            </div>
          </div>
        </div>
      </template>

      <!-- 编辑标题 / 学科弹窗 -->
      <div
        v-if="editMode"
        class="fixed inset-0 z-50 flex items-center justify-center bg-ink/25 px-4 backdrop-blur-sm"
        @click.self="editMode = false"
      >
        <div class="w-full max-w-md rounded-3xl border border-line bg-white p-6 shadow-xl">
          <div class="flex items-start justify-between">
            <h2 class="text-[16px] font-semibold">编辑网课</h2>
            <button
              class="flex h-8 w-8 items-center justify-center rounded-lg text-ink-2 hover:bg-line/60 hover:text-ink"
              title="关闭"
              @click="editMode = false"
            >
              <X :size="16" />
            </button>
          </div>

          <div class="mt-4 flex flex-col gap-4">
            <label class="block">
              <span class="mb-1 block text-[12px] text-ink-2">标题</span>
              <input
                v-model="editForm.title"
                type="text"
                class="w-full rounded-xl border border-line px-3 py-2 text-[14px] outline-none focus:border-primary"
              />
            </label>
            <label class="block">
              <span class="mb-1 block text-[12px] text-ink-2">学科</span>
              <select
                v-model="editForm.subject"
                class="w-full rounded-xl border border-line bg-white px-3 py-2 text-[14px] outline-none focus:border-primary"
              >
                <option value="">不选择</option>
                <option v-for="s in SUBJECTS" :key="s" :value="s">{{ s }}</option>
              </select>
            </label>
          </div>

          <div class="mt-5 flex justify-end gap-2">
            <button
              class="rounded-xl border border-line px-4 py-2 text-[14px] text-ink hover:bg-line/60"
              @click="editMode = false"
            >
              取消
            </button>
            <button
              class="rounded-xl bg-primary px-4 py-2 text-[14px] text-white hover:opacity-90 disabled:opacity-50"
              :disabled="saving || !editForm.title.trim()"
              @click="saveEdit"
            >
              保存修改
            </button>
          </div>
        </div>
      </div>
    </div>
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

.note-view :deep(h1),
.note-view :deep(h2),
.note-view :deep(h3) {
  font-weight: 600;
  font-size: 15px;
  margin: 1.25rem 0 0.5rem;
}
.note-view :deep(h1) {
  font-size: 17px;
}
.note-view :deep(h1:first-child),
.note-view :deep(h2:first-child),
.note-view :deep(h3:first-child) {
  margin-top: 0;
}
.note-view :deep(ul) {
  list-style: disc;
  padding-left: 1.4rem;
  margin: 0.25rem 0 0.75rem;
}
.note-view :deep(ol) {
  list-style: decimal;
  padding-left: 1.4rem;
  margin: 0.25rem 0 0.75rem;
}
.note-view :deep(li) {
  margin: 0.25rem 0;
}
.note-view :deep(hr) {
  margin: 1rem 0;
  border-color: #e5e7eb;
}
.note-view :deep(table) {
  width: 100%;
  border-collapse: collapse;
  margin: 0.75rem 0;
  font-size: 13px;
}
.note-view :deep(th),
.note-view :deep(td) {
  border: 1px solid #e5e7eb;
  padding: 0.4rem 0.75rem;
  text-align: left;
  vertical-align: top;
}
.note-view :deep(th) {
  background: #f9fafb;
  font-weight: 600;
}
.note-view :deep(.ts-chip) {
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
.note-view :deep(.ts-chip:hover) {
  text-decoration: underline;
}
</style>
