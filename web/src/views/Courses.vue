<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { MonitorPlay, Plus, AlertCircle, LoaderCircle, CircleCheck } from 'lucide-vue-next'

/**
 * 网课记录列表（PRD §3.2）。
 * TODO(切片三)：数据接入后端 /courses 接口，当前为假数据审阅版
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
    date: '2026-09-25 14:30',
    error: '语音转写服务超时，可重试处理',
  },
  {
    id: 4,
    title: '高等数学（上）第 11 讲：函数的单调性与极值',
    duration: '1:51:05',
    size: '502MB',
    status: 'SUCCESS',
    date: '2026-09-24 19:40',
    chapters: 9,
  },
])

function openCourse(c: CourseItem) {
  if (c.status === 'SUCCESS') {
    router.push(`/courses/${c.id}`)
  }
}
</script>

<template>
  <div class="h-full overflow-y-auto">
    <div class="mx-auto max-w-4xl px-4 py-8">
      <div class="flex items-center justify-between">
        <h1 class="flex items-center gap-2 text-[18px] font-semibold">
          <MonitorPlay :size="20" class="text-ink-2" />
          网课记录
        </h1>
        <button
          class="flex items-center gap-1.5 rounded-xl bg-primary px-3.5 py-2 text-[14px] text-white hover:opacity-90"
        >
          <Plus :size="16" />
          上传网课
        </button>
      </div>

      <div class="mt-6 flex flex-col gap-4">
        <div
          v-for="c in courses"
          :key="c.id"
          class="flex cursor-pointer gap-4 rounded-2xl border border-line bg-white p-4 transition-shadow hover:shadow-md"
          @click="openCourse(c)"
        >
          <!-- 封面占位 -->
          <div class="relative flex h-24 w-40 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br from-panel to-primary-soft">
            <MonitorPlay :size="32" class="text-ink-2/50" />
            <span class="absolute bottom-1.5 right-1.5 rounded-md bg-black/60 px-1.5 py-0.5 text-[11px] text-white">
              {{ c.duration }}
            </span>
          </div>

          <div class="flex min-w-0 flex-1 flex-col">
            <p class="truncate text-[15px] font-medium text-ink">{{ c.title }}</p>
            <p class="mt-1 text-[12px] text-ink-2">{{ c.date }} · {{ c.size }}<span v-if="c.chapters"> · {{ c.chapters }} 个章节</span></p>

            <!-- 状态区 -->
            <div class="mt-auto pt-2">
              <div v-if="c.status === 'SUCCESS'" class="flex items-center gap-1.5 text-[13px] text-green-600">
                <CircleCheck :size="15" />
                处理完成，AI 笔记已生成
              </div>
              <div v-else-if="c.status === 'PROCESSING'" class="text-[13px] text-amber-600">
                <div class="flex items-center gap-1.5">
                  <LoaderCircle :size="15" class="animate-spin" />
                  {{ c.stage }}
                </div>
                <div class="mt-1.5 h-1.5 w-full overflow-hidden rounded-full bg-line">
                  <div class="h-full rounded-full bg-amber-500" :style="{ width: c.progress + '%' }" />
                </div>
              </div>
              <div v-else class="flex items-center gap-1.5 text-[13px] text-red-500">
                <AlertCircle :size="15" />
                {{ c.error }}
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
