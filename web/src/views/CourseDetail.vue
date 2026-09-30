<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { Download, ArrowLeft, CircleCheck, LoaderCircle, Pencil, Save, X } from 'lucide-vue-next'
import { getCourseDetail, updateCourse } from '../api/course'
import type { CourseDetailData } from '../api/course'
import { updateNoteContent } from '../api/note'
import { SUBJECTS } from '../constants/subjects'
import { renderMarkdown } from '../utils/markdown'

/**
 * 网课详情（PRD §4.1 + §3.2 时间戳同步观看）：
 * 笔记在左（宽，整页左对齐不留白）、视频在右（宽列，方便观看）；三标签：AI 笔记 / 转写对照 / 关键帧识别。
 */
const route = useRoute()
const courseId = Number(route.params.id)

const data = ref<CourseDetailData | null>(null)
const loading = ref(true)
const error = ref('')
const activeTab = ref<'note' | 'transcript' | 'frames'>('note')
const videoRef = ref<HTMLVideoElement | null>(null)

onMounted(async () => {
  try {
    data.value = await getCourseDetail(courseId)
    // 支持从笔记页时间戳跳转进入：/courses/1?t=08:24 → 加载后自动 seek
    const t = route.query.t
    if (t && videoRef.value) {
      const seek = () => seekTo(parseTs(String(t)))
      videoRef.value.addEventListener('loadedmetadata', seek, { once: true })
    }
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

/**
 * 块级空行归一化：LLM 输出的标题 / 列表前常缺空行，
 * 不补空行的话 Markdown 会把它们当普通段落渲染（# 与 - 直接显示、右边界参差）
 */
function normalizeBlocks(md: string): string {
  return md
    .replace(/(?<=\S)\n(#{1,6} )/g, '\n\n$1')
    .replace(/(?<=\S)\n(- )/g, '\n\n$1')
}

/**
 * 笔记渲染：归一化 → Markdown+KaTeX → [mm:ss] 时间戳转为可点击胶囊
 */
const noteHtml = computed(() => {
  const note = data.value?.note
  if (!note) {
    return ''
  }
  return renderMarkdown(normalizeBlocks(note.content))
    .replace(/\[(\d{1,2}:[0-5]\d(?::\d{2})?)\]/g, '<span class="ts-chip" data-ts="$1">$1</span>')
})

function onNoteClick(e: MouseEvent) {
  const chip = (e.target as HTMLElement).closest('[data-ts]')
  if (chip) {
    seekTo(parseTs(chip.getAttribute('data-ts') || ''))
  }
}

function onTabChange(tab: 'note' | 'transcript' | 'frames') {
  activeTab.value = tab
}

// 在线编辑（标题 / 学科）
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
</script>

<template>
  <div class="h-full overflow-y-auto">
    <div class="px-4 py-6">
      <!-- 加载 / 错误 -->
      <div v-if="loading" class="flex h-64 items-center justify-center text-[14px] text-ink-2">加载中…</div>
      <div v-else-if="error" class="flex h-64 items-center justify-center text-[14px] text-red-500">{{ error }}</div>

      <template v-else-if="data">
        <!-- 返回 + 标题 + 操作 -->
        <div class="flex items-center gap-3">
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
          </div>
        </div>

        <div class="mt-5 flex flex-col-reverse gap-5 lg:h-[calc(100%-72px)] lg:flex-row">
          <!-- 左：AI 笔记（主区，宽） -->
          <div class="flex min-w-0 flex-1 flex-col">
            <div class="flex shrink-0 items-center gap-2">
              <div class="flex flex-1 gap-1 rounded-xl bg-panel p-1">
                <button
                  v-for="tab in [
                    { key: 'note', label: 'AI 笔记' },
                    { key: 'transcript', label: '转写对照' },
                    { key: 'frames', label: '关键帧识别' },
                  ]"
                  :key="tab.key"
                  class="flex-1 rounded-lg py-1.5 text-[14px] transition-colors"
                  :class="activeTab === tab.key ? 'bg-white font-medium text-ink shadow-sm' : 'text-ink-2 hover:text-ink'"
                  @click="onTabChange(tab.key as 'note' | 'transcript' | 'frames')"
                >
                  {{ tab.label }}
                </button>
              </div>
              <!-- AI 笔记在线编辑（Markdown 源码） -->
              <template v-if="activeTab === 'note' && data.note">
                <button
                  v-if="!noteEditing"
                  class="flex shrink-0 items-center gap-1.5 rounded-xl border border-line bg-white px-3 py-1.5 text-[13px] text-ink hover:bg-panel"
                  @click="startNoteEdit"
                >
                  <Pencil :size="14" />
                  编辑笔记
                </button>
                <template v-else>
                  <button
                    class="flex shrink-0 items-center gap-1.5 rounded-xl bg-primary px-3 py-1.5 text-[13px] text-white hover:opacity-90 disabled:opacity-50"
                    :disabled="noteSaving"
                    @click="saveNoteEdit"
                  >
                    <Save :size="14" />
                    保存
                  </button>
                  <button
                    class="flex shrink-0 items-center gap-1.5 rounded-xl border border-line bg-white px-3 py-1.5 text-[13px] text-ink hover:bg-panel"
                    @click="noteEditing = false"
                  >
                    <X :size="14" />
                    取消
                  </button>
                </template>
              </template>
            </div>

            <div class="mt-4 min-h-0 flex-1 overflow-y-auto lg:pr-2">
              <!-- AI 笔记：编辑态为 Markdown 源码，阅读态渲染并转换时间戳胶囊 -->
              <template v-if="activeTab === 'note'">
                <div v-if="noteEditing" class="rounded-2xl border border-primary bg-white p-6">
                  <p class="mb-2 text-[12px] text-ink-2">Markdown 源码编辑；[mm:ss] 时间戳保存后仍可点击跳转原片段</p>
                  <p v-if="noteError" class="mb-2 text-[12px] text-red-600">{{ noteError }}</p>
                  <textarea
                    v-model="noteDraft"
                    class="h-[60vh] w-full resize-y rounded-xl border border-line p-3 font-mono text-[13px] leading-6 text-ink outline-none focus:border-primary"
                  ></textarea>
                </div>
                <div v-else class="note-view rounded-2xl border border-line bg-white p-6 text-[14px] leading-7 text-ink" v-html="noteHtml" @click="onNoteClick" />
              </template>

              <!-- 转写对照 -->
              <div v-else-if="activeTab === 'transcript'" class="flex flex-col gap-3 rounded-2xl border border-line bg-white p-6">
                <div v-for="seg in data.transcript" :key="seg.id" class="flex gap-3 text-[14px] leading-7">
                  <button class="shrink-0 pt-0.5 text-[12px] text-primary hover:underline" @click="seekTo(seg.startSec)">
                    {{ formatTs(seg.startSec) }}
                  </button>
                  <p class="text-ink">{{ seg.text }}</p>
                </div>
                <p v-if="data.transcript.length === 0" class="text-[13px] text-ink-2">未获得语音转写结果</p>
              </div>

              <!-- 关键帧识别 -->
              <div v-else class="grid grid-cols-1 gap-4 xl:grid-cols-2">
                <div v-for="frame in data.frames" :key="frame.id" class="overflow-hidden rounded-2xl border border-line bg-white">
                  <button class="relative block w-full" @click="seekTo(frame.timeSec)">
                    <img :src="frame.ossKey" :alt="'第 ' + frame.timeSec + 's 画面'" class="aspect-video w-full object-cover" />
                    <span class="absolute bottom-1.5 left-1.5 rounded-md bg-black/60 px-1.5 py-0.5 text-[11px] text-white">
                      {{ formatTs(frame.timeSec) }}
                    </span>
                  </button>
                  <div class="px-3.5 py-3">
                    <p class="line-clamp-4 whitespace-pre-wrap text-[13px] leading-6 text-ink">
                      {{ frame.ocrStatus === 'SUCCESS' ? frame.ocrText : '（该帧识别失败）' }}
                    </p>
                  </div>
                </div>
                <p v-if="data.frames.length === 0" class="col-span-full rounded-2xl border border-dashed border-line py-10 text-center text-[13px] text-ink-2">
                  未获得关键帧识别结果
                </p>
              </div>
            </div>
          </div>

          <!-- 右：视频 + 期望（右对齐） -->
          <div class="course-video-col w-full shrink-0">
            <div class="lg:sticky lg:top-0 lg:pb-4">
              <video
                v-if="data.course.videoOssKey"
                ref="videoRef"
                class="w-full rounded-2xl border border-line bg-black"
                controls
                preload="metadata"
                :src="data.course.videoOssKey"
              />
              <div v-else class="flex aspect-video w-full items-center justify-center rounded-2xl border border-line bg-panel text-[14px] text-ink-2">
                视频处理中，稍后可在线观看
              </div>

              <!-- 用户的特别要求 -->
              <div v-if="data.course.expectations" class="mt-3 rounded-2xl border border-line bg-panel px-4 py-3">
                <p class="text-[13px] font-semibold text-ink">您希望的内容</p>
                <p class="mt-1.5 text-[13px] leading-6 text-ink-2">{{ data.course.expectations }}</p>
              </div>
              <div class="mt-3 flex items-center gap-1.5 rounded-xl border border-line bg-primary-soft/60 px-3 py-2 text-[12px] text-ink-2">
                <template v-if="data.course.status === 'SUCCESS'">
                  <CircleCheck :size="14" class="shrink-0 text-green-600" />
                  AI 笔记由转写与画面识别生成，点击时间戳可跳转原片段核对。
                </template>
                <template v-else>
                  <LoaderCircle :size="14" class="animate-spin text-amber-600" />
                  网课正在流水线处理中，完成后可在线观看并生成 AI 笔记。
                </template>
              </div>
            </div>
          </div>
        </div>
      </template>
    </div>

    <!-- 编辑弹窗（标题 / 学科） -->
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
</template>

<style scoped>
@media (min-width: 64rem) {
  .course-video-col {
    width: 560px;
    margin-left: auto;
  }
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
