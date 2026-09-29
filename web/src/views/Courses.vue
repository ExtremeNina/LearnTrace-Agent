<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Plus, AlertCircle, LoaderCircle, RotateCcw, Search } from 'lucide-vue-next'

/**
 * 网课记录列表（PRD §3.2）：视频库式竖向卡片网格。
 * TODO(切片三)：数据接入后端 /courses 接口，当前为假数据审阅版。
 */
interface CourseItem {
  id: number
  title: string
  duration: string
  size: string
  status: 'SUCCESS' | 'PROCESSING' | 'FAILED'
  date: string
  chapters?: number
  progress?: number
  stage?: string
  error?: string
}

const router = useRouter()
const keyword = ref('')
const statusFilter = ref<'ALL' | 'SUCCESS' | 'PROCESSING' | 'FAILED'>('ALL')

const courses = ref<CourseItem[]>([
  {
    id: 1,
    title: '高等数学（上）第 12 讲：微分中值定理',
    duration: '1:47:32',
    size: '486MB',
    status: 'SUCCESS',
    date: '2026-09-26 20:15',
    chapters: 8,
  },
  {
    id: 2,
    title: '线性代数 第 3 讲：矩阵的秩与线性方程组',
    duration: '1:22:10',
    size: '372MB',
    status: 'PROCESSING',
    progress: 65,
    stage: '关键帧 OCR 识别中（28/43）',
    date: '2026-09-28 09:02',
  },
  {
    id: 3,
    title: '概率论 第 1 讲：随机事件与概率',
    duration: '0:58:44',
    size: '291MB',
    status: 'FAILED',
    date: '2026-09-28 08:47',
    error: '语音转写服务超时，可重试处理',
  },
  {
    id: 4,
    title: '高等数学（上）第 11 讲：函数的单调性与极值',
    duration: '1:51:05',
    size: '502MB',
    status: 'SUCCESS',
    date: '2026-09-28 08:12',
    chapters: 9,
  },
  {
    id: 5,
    title: '计算机科学 第 1 讲：计算机早期历史',
    duration: '11:53',
    size: '37MB',
    status: 'SUCCESS',
    date: '2026-09-28 07:30',
    chapters: 6,
  },
])

/** 相对时间（假数据审阅用） */
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
  const days = Math.floor(hours / 24)
  return days + ' 天前'
}


const generatedCount = computed(() => courses.value.filter((c) => c.status === 'SUCCESS').length)

const filtered = computed(() =>
  courses.value.filter((c) => {
    if (statusFilter.value !== 'ALL' && c.status !== statusFilter.value) {
      return false
    }
    if (keyword.value && !c.title.includes(keyword.value.trim())) {
      return false
    }
    return true
  })
)

function openCourse(c: CourseItem) {
  if (c.status === 'SUCCESS') {
    router.push(`/courses/${c.id}`)
  }
}

function retry(c: CourseItem) {
  // TODO(切片三)：调 POST /courses/{id}/retry
  c.status = 'PROCESSING'
  c.progress = 5
  c.stage = '排队等待处理'
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
        <button class="flex items-center gap-1.5 rounded-xl bg-primary px-3.5 py-2 text-[14px] text-white hover:opacity-90">
          <Plus :size="16" />
          上传视频
        </button>
      </div>

      <!-- 过滤行：搜索 + 状态标签 -->
      <div class="mt-5 flex flex-wrap items-center gap-3">
        <div class="relative">
          <Search :size="15" class="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-ink-2" />
          <input
            v-model="keyword"
            type="text"
            placeholder="按标题过滤…"
            class="w-64 rounded-xl border border-line bg-white py-2 pl-9 pr-3 text-[13px] text-ink outline-none focus:border-primary"
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
            :class="statusFilter === f.key ? 'bg-white font-medium text-ink shadow-sm' : 'text-ink-2 hover:text-ink'"
            @click="statusFilter = f.key as typeof statusFilter"
          >
            {{ f.label }}
          </button>
        </div>
      </div>

      <!-- 卡片网格 -->
      <div class="mt-6 grid grid-cols-1 gap-5 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
        <div
          v-for="c in filtered"
          :key="c.id"
          class="overflow-hidden rounded-2xl border border-line bg-white transition-shadow"
          :class="c.status === 'SUCCESS' ? 'cursor-pointer hover:shadow-md' : 'opacity-95'"
          @click="openCourse(c)"
        >
          <!-- 缩略图 -->
          <div class="relative aspect-video bg-gradient-to-br from-panel to-primary-soft">
            <div class="flex h-full items-center justify-center">
              <span class="text-[26px] font-semibold text-ink-2/30">{{ c.title.slice(0, 2) }}</span>
            </div>
            <span class="absolute bottom-1.5 right-1.5 rounded-md bg-black/60 px-1.5 py-0.5 text-[11px] text-white">
              {{ c.duration }}
            </span>
          </div>

          <!-- 信息区 -->
          <div class="px-3.5 py-3">
            <p class="line-clamp-2 min-h-[42px] text-[14px] leading-5 text-ink">{{ c.title }}</p>

            <!-- 成功 -->
            <div v-if="c.status === 'SUCCESS'" class="mt-2.5 flex items-center justify-between text-[12px] text-ink-2">
              <span>本地上传</span>
              <span>{{ relativeTime(c.date) }}</span>
            </div>

            <!-- 处理中 -->
            <div v-else-if="c.status === 'PROCESSING'" class="mt-2.5">
              <div class="flex items-center gap-1.5 text-[12px] text-amber-600">
                <LoaderCircle :size="13" class="animate-spin" />
                <span class="truncate">{{ c.stage }}</span>
              </div>
              <div class="mt-1.5 h-1 w-full overflow-hidden rounded-full bg-line">
                <div class="h-full rounded-full bg-amber-500" :style="{ width: c.progress + '%' }" />
              </div>
            </div>

            <!-- 失败 -->
            <div v-else class="mt-2.5">
              <div class="flex items-start gap-1.5 text-[12px] text-red-500">
                <AlertCircle :size="13" class="mt-0.5 shrink-0" />
                <span class="line-clamp-2">{{ c.error }}</span>
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
        <p class="mt-1 text-[12px] text-ink-2">调整筛选条件，或点击右上角上传新视频</p>
      </div>
    </div>
  </div>
</template>
