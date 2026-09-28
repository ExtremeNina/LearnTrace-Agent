<script setup lang="ts">
import { nextTick, ref } from 'vue'
import { Download, PencilLine, ArrowLeft, CircleCheck, Bold, Italic, Underline, Paintbrush, Highlighter, Eraser, Save, X } from 'lucide-vue-next'

/**
 * 网课详情（PRD §4.1 资源详情页 + §3.2 时间戳同步观看）。
 * TODO(切片三)：按 route.params.id 调取真实课程数据，当前为假数据审阅版。
 * 时间戳点击 → 播放器 seek 对应秒数继续播放；编辑模式为富文本在线编辑（变相笔记）。
 */
const videoRef = ref<HTMLVideoElement | null>(null)
const activeTab = ref<'note' | 'chapters' | 'transcript'>('note')

const course = {
  title: '高等数学（上）第 12 讲：微分中值定理',
  date: '2026-09-26 20:15',
  size: '486MB',
  intro: '本讲是微分中值定理的核心讲次。老师从费马引理出发，逐步推导罗尔定理、拉格朗日中值定理与柯西中值定理，并结合 3 道典型例题演示定理的使用方式与常见陷阱。建议先完整观看一遍，再对照右侧 AI 笔记进行课后复习；对笔记内容有疑问时，点击时间戳回看对应的视频片段核对。',
}

const chapters = [
  { title: '回顾：可导与连续的关系', start: '00:00', sec: 0 },
  { title: '费马引理与极值', start: '05:12', sec: 312 },
  { title: '罗尔定理', start: '08:24', sec: 504 },
  { title: '拉格朗日中值定理', start: '21:40', sec: 1300 },
  { title: '柯西中值定理', start: '35:06', sec: 2106 },
  { title: '典型例题精讲', start: '52:18', sec: 3138 },
  { title: '易错点与考试提醒', start: '1:32:40', sec: 5560 },
  { title: '本讲小结与预习', start: '1:42:05', sec: 6125 },
]

const transcript = [
  { ts: '00:01', sec: 1, text: '上次课我们讲了函数可导与连续的关系，今天接着往下推进。' },
  { ts: '05:12', sec: 312, text: '先看费马引理：如果函数在某点取得极值并且可导，那么这点的导数一定为零。' },
  { ts: '08:24', sec: 504, text: '罗尔定理三个条件缺一不可：闭区间连续、开区间可导、端点函数值相等。' },
  { ts: '11:02', sec: 662, text: '几何上讲，就是这段曲线上至少存在一条水平切线。' },
  { ts: '21:40', sec: 1300, text: '把罗尔定理的条件放宽，不需要端点等高，就得到了拉格朗日中值定理。' },
  { ts: '38:16', sec: 2296, text: '考试里最常见的错误就是忽略端点条件直接套定理，大家一定要逐条检查。' },
]

interface NoteItem {
  text: string
  ts?: string
  sec?: number
}
interface NoteSection {
  heading: string
  items: NoteItem[]
}

const note: NoteSection[] = [
  {
    heading: '本讲概览',
    items: [{ text: '本讲围绕微分中值定理展开：从费马引理出发，依次推导罗尔定理、拉格朗日中值定理与柯西中值定理，最后通过典型例题巩固。', ts: '00:45', sec: 45 }],
  },
  {
    heading: '罗尔定理',
    items: [
      { text: '条件：f(x) 在 [a,b] 上连续，在 (a,b) 内可导，且 f(a)=f(b)。结论：存在 ξ∈(a,b) 使 f\'(ξ)=0。', ts: '08:24', sec: 504 },
      { text: '几何意义：两端等高的连续光滑曲线上，至少存在一点切线水平。', ts: '11:02', sec: 662 },
    ],
  },
  {
    heading: '拉格朗日中值定理',
    items: [
      { text: '去掉 f(a)=f(b) 的限制：f(b)-f(a)=f\'(ξ)(b-a)，ξ∈(a,b)。', ts: '21:40', sec: 1300 },
      { text: '它是罗尔定理的推广，也是连接函数值与导数的桥梁，后续单调性判定全部基于它。', ts: '26:35', sec: 1595 },
    ],
  },
  {
    heading: '易错点',
    items: [
      { text: '使用罗尔定理前必须逐条验证三个条件，尤其是端点函数值相等这一条经常被忽略。', ts: '38:16', sec: 2296 },
      { text: 'ξ 只是存在性结论，题目若要求具体值需另解方程，不能默认 ξ 是中点。', ts: '47:50', sec: 2870 },
    ],
  },
]

// ---- 编辑态（富文本在线编辑，相当于笔记功能） ----
const editing = ref(false)
const editedHtml = ref('') // 保存后的编辑结果（非空时阅读态渲染它）
const editorRef = ref<HTMLDivElement | null>(null)

function escapeHtml(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

function noteToHtml(): string {
  const parts: string[] = []
  for (const section of note) {
    parts.push(`<p><strong>${escapeHtml(section.heading)}</strong></p>`)
    for (const item of section.items) {
      const chip = item.ts
        ? `<span class="ts-chip" data-ts="${item.ts}" contenteditable="false">${item.ts}</span>`
        : ''
      parts.push(`<p>${escapeHtml(item.text)} ${chip}</p>`)
    }
  }
  return parts.join('')
}

function startEdit() {
  editedHtml.value = editedHtml.value || noteToHtml()
  editing.value = true
  nextTick(() => {
    if (editorRef.value) {
      editorRef.value.innerHTML = editedHtml.value
      editorRef.value.focus()
    }
  })
}

function cancelEdit() {
  editing.value = false
}

function saveEdit() {
  if (editorRef.value) {
    editedHtml.value = editorRef.value.innerHTML
  }
  editing.value = false
}

// ---- 富文本工具栏（execCommand，Demo 版） ----
function exec(command: string, value?: string) {
  document.execCommand('styleWithCSS', false, 'true')
  document.execCommand(command, false, value)
  editorRef.value?.focus()
}

const fontSizes = [
  { label: '小', size: '2' },
  { label: '标准', size: '3' },
  { label: '大', size: '5' },
  { label: '特大', size: '7' },
]
function onFontSizeChange(e: Event) {
  exec('fontSize', (e.target as HTMLSelectElement).value)
}

function onColorChange(e: Event) {
  exec('foreColor', (e.target as HTMLInputElement).value)
}

function onHighlightChange(e: Event) {
  exec('hiliteColor', (e.target as HTMLInputElement).value)
}

// ---- 播放器联动 ----
function parseTs(ts: string): number {
  const parts = ts.split(':').map(Number)
  return parts.length === 3
    ? parts[0] * 3600 + parts[1] * 60 + parts[2]
    : parts[0] * 60 + parts[1]
}

function seekTo(ts: string) {
  const sec = parseTs(ts)
  if (videoRef.value) {
    videoRef.value.currentTime = sec
    videoRef.value.play()
  }
}

/** 时间戳点击（事件委托，同时覆盖初始结构与编辑后的 HTML） */
function onNoteClick(e: MouseEvent) {
  const chip = (e.target as HTMLElement).closest('[data-ts]')
  if (chip) {
    seekTo(chip.getAttribute('data-ts') || '')
  }
}
</script>

<template>
  <div class="h-full overflow-y-auto lg:overflow-hidden">
    <div class="mx-auto max-w-5xl px-4 py-6 lg:h-full">
      <!-- 返回 + 标题 + 操作 -->
      <div class="flex items-center gap-3">
        <RouterLink
          to="/courses"
          class="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg text-ink-2 hover:bg-line/60"
        >
          <ArrowLeft :size="18" />
        </RouterLink>
        <div class="min-w-0">
          <h1 class="truncate text-[18px] font-semibold">{{ course.title }}</h1>
          <p class="mt-0.5 text-[12px] text-ink-2">{{ course.date }} · {{ course.size }}</p>
        </div>
        <div class="ml-auto flex shrink-0 items-center gap-2">
          <button class="flex items-center gap-1.5 rounded-xl border border-line px-3 py-1.5 text-[13px] text-ink hover:bg-panel">
            <Paintbrush :size="15" />
            AI 润色
          </button>
          <button
            class="flex items-center gap-1.5 rounded-xl border px-3 py-1.5 text-[13px] hover:bg-panel"
            :class="editing ? 'border-primary text-primary' : 'border-line text-ink'"
            @click="editing ? cancelEdit() : startEdit()"
          >
            <PencilLine :size="15" />
            {{ editing ? '取消' : '编辑' }}
          </button>
          <button class="flex items-center gap-1.5 rounded-xl border border-line px-3 py-1.5 text-[13px] text-ink hover:bg-panel">
            <Download :size="15" />
            导出
          </button>
        </div>
      </div>

      <div class="mt-5 flex flex-col gap-5 lg:h-[calc(100%-72px)] lg:flex-row">
        <!-- 左：播放器 + 简介 -->
        <div class="w-full shrink-0 lg:w-[46%] lg:overflow-y-auto lg:pr-1">
          <div class="lg:sticky lg:top-0 lg:pb-4">
            <video
              ref="videoRef"
              class="w-full rounded-2xl border border-line bg-black"
              controls
              preload="metadata"
              src="https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4"
            />
            <!-- 视频简介 -->
            <div class="mt-3 rounded-2xl border border-line bg-panel px-4 py-3">
              <p class="text-[13px] font-semibold text-ink">视频简介</p>
              <p class="mt-1.5 text-[13px] leading-6 text-ink-2">{{ course.intro }}</p>
            </div>
            <div class="mt-3 flex items-center gap-1.5 rounded-xl border border-line bg-primary-soft/60 px-3 py-2 text-[12px] text-ink-2">
              <CircleCheck :size="14" class="shrink-0 text-green-600" />
              笔记由 AI 生成，可能存在偏差。点击文中的时间戳可跳转视频原片段核对。
            </div>
          </div>
        </div>

        <!-- 右：笔记 / 章节 / 转写（独立滚动，标签常驻） -->
        <div class="flex min-w-0 flex-1 flex-col lg:h-full">
          <div class="flex shrink-0 gap-1 rounded-xl bg-panel p-1">
            <button
              v-for="tab in [
                { key: 'note', label: 'AI 笔记' },
                { key: 'chapters', label: '章节' },
                { key: 'transcript', label: '转写对照' },
              ]"
              :key="tab.key"
              class="flex-1 rounded-lg py-1.5 text-[14px] transition-colors"
              :class="activeTab === tab.key ? 'bg-white font-medium text-ink shadow-sm' : 'text-ink-2 hover:text-ink'"
              @click="activeTab = tab.key as 'note' | 'chapters' | 'transcript'"
            >
              {{ tab.label }}
            </button>
          </div>

          <!-- 滚动内容区 -->
          <div class="mt-3 min-h-0 flex-1 overflow-y-auto lg:pr-1">
            <!-- AI 笔记 -->
            <template v-if="activeTab === 'note'">
              <!-- 富文本工具栏（编辑态） -->
              <div v-if="editing" class="mb-3 flex flex-wrap items-center gap-1.5 rounded-xl border border-line bg-panel px-2.5 py-2">
                <button class="flex h-8 w-8 items-center justify-center rounded-lg text-ink hover:bg-line/60" title="加粗" @mousedown.prevent @click="exec('bold')">
                  <Bold :size="15" />
                </button>
                <button class="flex h-8 w-8 items-center justify-center rounded-lg text-ink hover:bg-line/60" title="斜体" @mousedown.prevent @click="exec('italic')">
                  <Italic :size="15" />
                </button>
                <button class="flex h-8 w-8 items-center justify-center rounded-lg text-ink hover:bg-line/60" title="下划线" @mousedown.prevent @click="exec('underline')">
                  <Underline :size="15" />
                </button>
                <select
                  class="h-8 rounded-lg border border-line bg-white px-1.5 text-[13px] text-ink outline-none"
                  title="字号"
                  @change="onFontSizeChange"
                >
                  <option v-for="f in fontSizes" :key="f.size" :value="f.size">字号 {{ f.label }}</option>
                </select>
                <label class="flex h-8 w-8 cursor-pointer items-center justify-center rounded-lg text-ink hover:bg-line/60" title="字体颜色">
                  <Paintbrush :size="15" />
                  <input type="color" class="sr-only" value="#0d0d0d" @input="onColorChange" />
                </label>
                <label class="flex h-8 w-8 cursor-pointer items-center justify-center rounded-lg text-ink hover:bg-line/60" title="背景高亮">
                  <Highlighter :size="15" />
                  <input type="color" class="sr-only" value="#fff3c4" @input="onHighlightChange" />
                </label>
                <button class="flex h-8 w-8 items-center justify-center rounded-lg text-ink hover:bg-line/60" title="清除格式" @mousedown.prevent @click="exec('removeFormat')">
                  <Eraser :size="15" />
                </button>
                <span class="mx-1 h-5 w-px bg-line" />
                <button
                  class="flex items-center gap-1 rounded-lg bg-ink px-3 py-1.5 text-[13px] text-white hover:opacity-80"
                  @click="saveEdit"
                >
                  <Save :size="14" />
                  保存
                </button>
                <button class="flex items-center gap-1 rounded-lg border border-line px-3 py-1.5 text-[13px] text-ink-2 hover:bg-line/40" @click="cancelEdit">
                  <X :size="14" />
                  放弃
                </button>
              </div>

              <!-- 阅读态 -->
              <div
                v-if="!editing"
                class="flex flex-col gap-5 rounded-2xl border border-line bg-white p-5"
                @click="onNoteClick"
              >
                <div v-if="editedHtml" class="note-view text-[14px] leading-7 text-ink" v-html="editedHtml" />
                <template v-else>
                  <div v-for="section in note" :key="section.heading">
                    <p class="text-[15px] font-semibold text-ink">{{ section.heading }}</p>
                    <ul class="mt-2 flex flex-col gap-2">
                      <li v-for="(item, i) in section.items" :key="i" class="text-[14px] leading-7 text-ink">
                        {{ item.text }}
                        <button
                          v-if="item.ts"
                          class="ml-1 inline-flex items-center rounded-md bg-primary-soft px-1.5 py-0.5 align-middle text-[12px] text-primary hover:underline"
                          @click="seekTo(item.ts!)"
                        >
                          {{ item.ts }}
                        </button>
                      </li>
                    </ul>
                  </div>
                </template>
              </div>

              <!-- 编辑态（富文本） -->
              <div
                v-else
                ref="editorRef"
                class="note-view min-h-[300px] whitespace-pre-wrap rounded-2xl border border-primary bg-white p-5 text-[14px] leading-7 text-ink outline-none"
                contenteditable="true"
                @click="onNoteClick"
              />
            </template>

            <!-- 章节 -->
            <div v-else-if="activeTab === 'chapters'" class="flex flex-col gap-1 rounded-2xl border border-line bg-white p-3">
              <button
                v-for="ch in chapters"
                :key="ch.start"
                class="flex items-center gap-3 rounded-xl px-3 py-2.5 text-left text-[14px] text-ink hover:bg-panel"
                @click="seekTo(ch.start)"
              >
                <span class="shrink-0 rounded-md bg-primary-soft px-1.5 py-0.5 text-[12px] text-primary">{{ ch.start }}</span>
                <span class="truncate">{{ ch.title }}</span>
              </button>
            </div>

            <!-- 转写对照 -->
            <div v-else class="flex flex-col gap-3 rounded-2xl border border-line bg-white p-5">
              <div v-for="line in transcript" :key="line.ts" class="flex gap-3 text-[14px] leading-7">
                <button
                  class="shrink-0 pt-0.5 text-[12px] text-primary hover:underline"
                  @click="seekTo(line.ts)"
                >
                  {{ line.ts }}
                </button>
                <p class="text-ink">{{ line.text }}</p>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
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
.note-view :deep(p) {
  margin-bottom: 0.75rem;
}
</style>
