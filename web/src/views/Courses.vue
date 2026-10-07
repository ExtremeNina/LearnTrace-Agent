<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { AlertCircle, Check, LoaderCircle, RotateCcw, Search, Trash2 } from 'lucide-vue-next'
import { listCourses, retryCourse, batchDeleteCourses } from '../api/course'
import type { CourseInfo } from '../api/course'
import { SUBJECTS } from '../constants/subjects'

/**
 * 网课记录列表（PRD §3.2）：视频库式竖向卡片网格。
 * 数据来自后端 /courses；支持标题关键词、状态、日期与学科筛选，处理中的课程定时轮询状态。
 * 支持批量管理模式：勾选多门网课一次性删除（处理中的不可选）。
 * 上传入口已收敛到 AI 对话（B11 分流），本页只做管理：列表 / 进度 / 播放 / 删除 / 重试。
 */
const router = useRouter()
const courses = ref<CourseInfo[]>([])
const keyword = ref('')
const statusFilter = ref<'ALL' | 'SUCCESS' | 'PROCESSING' | 'FAILED'>('ALL')
/** 按日期筛选（yyyy-MM-dd），空为全部 */
const dateFilter = ref('')
/** 按学科筛选，空为全部 */
const subjectFilter = ref('')

// 批量删除
const selectMode = ref(false)
const selectedIds = ref<number[]>([])
const batchDeleting = ref(false)

let pollTimer: number | null = null

async function load() {
  courses.value = await listCourses()
  schedulePollIfNeeded()
}

function schedulePollIfNeeded() {
  if (pollTimer !== null) {
    window.clearTimeout(pollTimer)
    pollTimer = null
  }
  const processing = courses.value.some((c) => c.status === 'PENDING' || c.status === 'PROCESSING')
  if (processing) {
    pollTimer = window.setTimeout(async () => {
      await load()
    }, 8000)
  }
}

onMounted(load)
onUnmounted(() => {
  if (pollTimer !== null) {
    window.clearTimeout(pollTimer)
  }
})

async function retry(c: CourseInfo) {
  await retryCourse(c.id)
  await load()
}

function relativeTime(date: string): string {
  const then = new Date(date.replace(' ', 'T')).getTime()
  const diff = Date.now() - then
  const minutes = Math.floor(diff / 60000)
  if (minutes < 1) {
    return '刚刚'
  }
  if (minutes < 60) {
    return minutes + ' 分钟前'
  }
  const hours = Math.floor(minutes / 60)
  if (hours < 24) {
    return hours + ' 小时前'
  }
  return Math.floor(hours / 24) + ' 天前'
}

function formatDuration(sec: number | null | undefined): string {
  if (!sec) {
    return '--:--'
  }
  const h = Math.floor(sec / 3600)
  const m = Math.floor((sec % 3600) / 60)
  const s = sec % 60
  const mm = String(m).padStart(2, '0')
  const ss = String(s).padStart(2, '0')
  return h > 0 ? `${h}:${mm}:${ss}` : `${m}:${ss}`
}

const generatedCount = computed(() => courses.value.filter((c) => c.status === 'SUCCESS').length)

/** 流水线细分阶段 → 展示文案（B26 阶段 1） */
const STAGE_LABELS: Record<string, string> = {
  UPLOADING: '正在上传视频到云存储',
  EXTRACTING: '正在提取音频与关键帧',
  TRANSCRIBING: '正在转写语音',
  ANALYZING: '正在识别画面关键帧',
  UNDERSTANDING: '正在进行内容理解',
  REVIEWING: '正在多角色评审',
  NOTE_GENERATING: '正在生成 AI 笔记',
}

function stageLabel(stage?: string | null): string {
  if (!stage) {
    return '流水线处理中'
  }
  return STAGE_LABELS[stage] ?? '流水线处理中'
}

const filtered = computed(() =>
  courses.value.filter((c) => {
    if (statusFilter.value !== 'ALL' && c.status !== statusFilter.value) {
      return false
    }
    if (keyword.value && !c.title.includes(keyword.value.trim())) {
      return false
    }
    if (dateFilter.value && !c.createdAt.startsWith(dateFilter.value)) {
      return false
    }
    if (subjectFilter.value && c.subject !== subjectFilter.value) {
      return false
    }
    return true
  })
)

function openCourse(c: CourseInfo) {
  if (c.status === 'SUCCESS') {
    router.push(`/courses/${c.id}`)
  }
}

// ---- 批量删除 ----

/** 只有处理完结（SUCCESS / FAILED）的网课可删，处理中的会与流水线并发冲突 */
function selectable(c: CourseInfo): boolean {
  return c.status === 'SUCCESS' || c.status === 'FAILED'
}

function toggleSelect(c: CourseInfo) {
  if (!selectable(c)) {
    return
  }
  const idx = selectedIds.value.indexOf(c.id)
  if (idx >= 0) {
    selectedIds.value.splice(idx, 1)
  } else {
    selectedIds.value.push(c.id)
  }
}

function toggleSelectMode() {
  selectMode.value = !selectMode.value
  selectedIds.value = []
}

const selectableIds = computed(() => filtered.value.filter(selectable).map((c) => c.id))
const allSelected = computed(
  () => selectableIds.value.length > 0 && selectableIds.value.every((id) => selectedIds.value.includes(id))
)

function toggleSelectAll() {
  if (allSelected.value) {
    selectedIds.value = []
  } else {
    selectedIds.value = [...selectableIds.value]
  }
}

async function batchDelete() {
  const n = selectedIds.value.length
  if (n === 0 || batchDeleting.value) {
    return
  }
  if (!window.confirm(`确定删除选中的 ${n} 门网课吗？AI 笔记与相关记录会一并删除，视频文件不可恢复。`)) {
    return
  }
  batchDeleting.value = true
  try {
    const message = await batchDeleteCourses([...selectedIds.value])
    window.alert(message)
    selectedIds.value = []
    selectMode.value = false
    await load()
  } catch (e) {
    window.alert(e instanceof Error ? e.message : '删除失败，请稍后重试')
  } finally {
    batchDeleting.value = false
  }
}
</script>

<template>
  <div class="h-full overflow-y-auto">
    <div class="mx-auto max-w-5xl px-6 py-8">
      <!-- 标题行 -->
      <div class="flex items-center justify-between">
        <h1 class="text-[18px] font-semibold text-ink">
          网课记录
          <span class="ml-1 text-[13px] font-normal text-ink-2">
            {{ courses.length }} 个网课 · {{ generatedCount }} 个已生成
          </span>
        </h1>
        <div class="flex items-center gap-2">
          <button
            class="flex items-center gap-1.5 rounded-xl border px-3.5 py-2 text-[14px] transition-colors"
            :class="selectMode ? 'border-primary bg-primary-soft text-primary' : 'border-line text-ink hover:bg-panel'"
            @click="toggleSelectMode"
          >
            <Check v-if="selectMode" :size="16" />
            <Trash2 v-else :size="16" />
            {{ selectMode ? '退出批量管理' : '批量管理' }}
          </button>
        </div>
      </div>

      <!-- 过滤行 -->
      <div class="mt-5 flex flex-wrap items-center gap-3">
        <div class="relative">
          <Search :size="15" class="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-ink-2" />
          <input
            v-model="keyword"
            type="text"
            placeholder="按标题过滤…"
            class="w-64 rounded-xl border border-line bg-surface py-2 pl-9 pr-3 text-[13px] text-ink outline-none focus:border-primary"
          />
        </div>
        <div class="flex gap-1 rounded-xl bg-panel p-1">
          <button
            v-for="f in [
              { key: 'ALL', label: '全部' },
              { key: 'SUCCESS', label: '已生成' },
              { key: 'PROCESSING', label: '处理中' },
              { key: 'FAILED', label: '失败' },
            ]"
            :key="f.key"
            class="rounded-lg px-3.5 py-1.5 text-[13px] transition-colors"
            :class="statusFilter === f.key ? 'bg-surface font-medium text-ink shadow-sm' : 'text-ink-2 hover:text-ink'"
            @click="statusFilter = f.key as typeof statusFilter"
          >
            {{ f.label }}
          </button>
        </div>
        <select
          v-model="subjectFilter"
          class="rounded-xl border border-line bg-surface px-3 py-2 text-[13px] text-ink outline-none focus:border-primary"
        >
          <option value="">全部学科</option>
          <option v-for="s in SUBJECTS" :key="s" :value="s">{{ s }}</option>
        </select>
        <input
          v-model="dateFilter"
          type="date"
          class="rounded-xl border border-line bg-surface px-3 py-2 text-[13px] text-ink outline-none focus:border-primary"
        />
        <button
          v-if="dateFilter || subjectFilter"
          class="rounded-xl px-2.5 py-2 text-[13px] text-ink-2 hover:bg-line/60 hover:text-ink"
          @click="dateFilter = ''; subjectFilter = ''"
        >
          清除
        </button>
      </div>

      <!-- 卡片网格 -->
      <div class="mt-6 grid grid-cols-1 gap-5 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
        <div
          v-for="c in filtered"
          :key="c.id"
          class="relative overflow-hidden rounded-2xl border bg-surface transition-shadow"
          :class="[
            selectMode && selectable(c) && selectedIds.includes(c.id) ? 'border-primary ring-2 ring-primary/30' : 'border-line',
            c.status === 'SUCCESS' && !selectMode ? 'cursor-pointer hover:shadow-md' : 'opacity-95',
            selectMode && selectable(c) ? 'cursor-pointer' : '',
          ]"
          @click="selectMode ? toggleSelect(c) : openCourse(c)"
        >
          <!-- 批量选择勾选框 -->
          <span
            v-if="selectMode && selectable(c)"
            class="absolute right-1.5 top-1.5 z-20 flex h-6 w-6 items-center justify-center rounded-md border-2 bg-surface/90"
            :class="selectedIds.includes(c.id) ? 'border-primary bg-primary text-white' : 'border-line'"
          >
            <Check v-if="selectedIds.includes(c.id)" :size="14" />
          </span>
          <div class="relative aspect-video bg-gradient-to-br from-panel to-primary-soft">
            <span
              v-if="c.subject"
              class="absolute left-1.5 top-1.5 z-10 rounded-md bg-black/60 px-1.5 py-0.5 text-[11px] text-white"
            >
              {{ c.subject }}
            </span>
            <video
              v-if="c.videoOssKey"
              :src="c.videoOssKey"
              preload="metadata"
              muted
              class="h-full w-full object-cover"
            />
            <div v-else class="flex h-full items-center justify-center">
              <span class="text-[26px] font-semibold text-ink-2/30">{{ c.title.slice(0, 2) }}</span>
            </div>
            <span class="absolute bottom-1.5 right-1.5 rounded-md bg-black/60 px-1.5 py-0.5 text-[11px] text-white">
              {{ formatDuration(c.duration) }}
            </span>
          </div>

          <div class="px-3.5 py-3">
            <p class="line-clamp-2 min-h-[42px] text-[14px] leading-5 text-ink">{{ c.title }}</p>

            <div v-if="c.status === 'SUCCESS'" class="mt-2.5 flex items-center justify-between text-[12px] text-ink-2">
              <span>本地上传</span>
              <span>{{ relativeTime(c.updatedAt) }}</span>
            </div>

            <div v-else-if="c.status === 'PROCESSING' || c.status === 'PENDING'" class="mt-2.5">
              <div class="flex items-center gap-1.5 text-[12px] text-amber-600">
                <LoaderCircle :size="13" class="animate-spin" />
                <span class="truncate">{{ c.status === 'PENDING' ? '排队等待处理' : stageLabel(c.stage) }}</span>
              </div>
            </div>

            <div v-else class="mt-2.5">
              <div class="flex items-start gap-1.5 text-[12px] text-red-500">
                <AlertCircle :size="13" class="mt-0.5 shrink-0" />
                <span class="line-clamp-2">{{ c.errorMsg }}</span>
              </div>
              <button
                class="mt-1.5 flex items-center gap-1 text-[12px] text-ink-2 hover:text-primary"
                @click.stop="retry(c)"
              >
                <RotateCcw :size="12" />
                重试
              </button>
            </div>
          </div>
        </div>
      </div>

      <!-- 空状态 -->
      <div v-if="filtered.length === 0" class="mt-10 rounded-2xl border border-dashed border-line py-16 text-center">
        <p class="text-[14px] text-ink-2">没有符合条件的网课</p>
        <p class="mt-1 text-[12px] text-ink-2">调整筛选条件，或在 AI 对话中上传视频（发送后按意图转写或建课）</p>
      </div>
    </div>

    <!-- 批量管理操作栏 -->
    <div
      v-if="selectMode"
      class="fixed bottom-6 left-1/2 z-30 flex -translate-x-1/2 items-center gap-3 rounded-2xl border border-line bg-surface px-4 py-2.5 shadow-lg"
    >
      <button
        class="rounded-lg px-2.5 py-1.5 text-[13px] text-ink-2 hover:bg-line/60 hover:text-ink"
        @click="toggleSelectAll"
      >
        {{ allSelected ? '取消全选' : '全选（可删除项）' }}
      </button>
      <span class="text-[13px] text-ink-2">已选 {{ selectedIds.length }} 门</span>
      <button
        class="flex items-center gap-1.5 rounded-xl bg-red-500 px-3.5 py-2 text-[13px] text-white hover:opacity-90 disabled:opacity-40"
        :disabled="selectedIds.length === 0 || batchDeleting"
        @click="batchDelete"
      >
        <Trash2 :size="14" />
        {{ batchDeleting ? '删除中…' : `删除所选（${selectedIds.length}）` }}
      </button>
    </div>
  </div>
</template>
