<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  GraduationCap, MonitorPlay, Clock, NotebookPen, BookOpen,
  Play, ChevronRight, Sparkles,
} from 'lucide-vue-next'
import { getHomeOverview } from '../api/home'
import type { HomeOverview } from '../api/home'

/**
 * 首页学习仪表盘（B25 工单 3，PRD 待补章节）：
 * 问候 banner（纯 CSS 渐变）+ 继续学习 / 今日复习 / 最近学习（左主列）
 * + 学习数据 / 本周统计（右辅列，只读）。推荐学习与右栏 AI 助手不在本期范围（拍板）。
 */
defineOptions({ name: 'HomeView' })

const router = useRouter()
const overview = ref<HomeOverview | null>(null)
const loading = ref(true)
const error = ref('')

onMounted(async () => {
  try {
    overview.value = await getHomeOverview()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
})

const greeting = computed(() => {
  const h = new Date().getHours()
  if (h < 12) return '早上好'
  if (h < 18) return '下午好'
  return '晚上好'
})

const continueCourse = computed(() => overview.value?.continueCourse ?? null)
const dueCount = computed(() => overview.value?.stats.dueCount ?? 0)
/** 复习时长建议：每张卡约 3 分钟，最少 5 分钟 */
const suggestMinutes = computed(() => Math.max(5, dueCount.value * 3))

function formatTs(sec?: number | null): string {
  if (sec == null) return '0:00'
  const h = Math.floor(sec / 3600)
  const m = Math.floor((sec % 3600) / 60)
  const s = sec % 60
  const mm = String(m).padStart(2, '0')
  const ss = String(s).padStart(2, '0')
  return h > 0 ? `${h}:${mm}:${ss}` : `${mm}:${ss}`
}

function formatDuration(sec?: number | null): string {
  if (sec == null || sec <= 0) return '—'
  return formatTs(sec)
}

/** 相对时间：N 分钟 / 小时 / 天前（简单规则，不引库） */
function relativeTime(iso?: string | null): string {
  if (!iso) return ''
  const t = new Date(iso.replace(' ', 'T')).getTime()
  if (Number.isNaN(t)) return ''
  const diffMin = Math.floor((Date.now() - t) / 60000)
  if (diffMin < 1) return '刚刚'
  if (diffMin < 60) return `${diffMin} 分钟前`
  const diffHour = Math.floor(diffMin / 60)
  if (diffHour < 24) return `${diffHour} 小时前`
  return `${Math.floor(diffHour / 24)} 天前`
}

function truncate(text: string, max = 14): string {
  return text.length > max ? text.slice(0, max) + '…' : text
}
</script>

<template>
  <div class="h-full overflow-y-auto">
    <div class="mx-auto max-w-6xl px-4 py-5 md:px-6">
      <!-- 加载 / 错误 -->
      <div v-if="loading" class="flex h-64 items-center justify-center text-[13px] text-ink-2">
        加载中…
      </div>
      <div v-else-if="error" class="flex h-64 items-center justify-center text-[13px] text-ink-2">
        {{ error }}
      </div>

      <template v-else-if="overview">
        <!-- 问候 banner：纯 CSS 渐变，语义令牌配色 -->
        <div class="rounded-3xl border border-line bg-gradient-to-r from-primary-soft/70 via-panel to-panel px-6 py-6 md:px-8">
          <h1 class="text-[22px] font-semibold tracking-tight text-ink md:text-[26px]">
            {{ greeting }}，{{ overview.nickname || '同学' }} 👋
          </h1>
          <p class="mt-1.5 text-[13px] text-ink-2">今天也要加油学习呀！你的知识正在一点点积累～</p>
        </div>

        <div class="mt-5 grid grid-cols-1 items-start gap-5 lg:grid-cols-[1fr_300px]">
          <!-- 左主列 -->
          <div class="flex min-w-0 flex-col gap-5">
            <!-- 继续学习 -->
            <section v-if="continueCourse" class="rounded-3xl border border-line bg-surface p-5">
              <div class="flex items-center justify-between">
                <h2 class="flex items-center gap-1.5 text-[15px] font-semibold text-ink">
                  <MonitorPlay :size="16" class="text-primary" />
                  继续学习
                </h2>
                <RouterLink to="/courses" class="flex items-center text-[12px] text-ink-2 hover:text-ink">
                  查看全部
                  <ChevronRight :size="13" />
                </RouterLink>
              </div>
              <div class="mt-4 flex items-center gap-4">
                <div class="min-w-0 flex-1">
                  <p class="truncate text-[16px] font-medium text-ink">{{ continueCourse.title }}</p>
                  <p class="mt-1 text-[12px] text-ink-2">
                    上次学到 {{ formatTs(continueCourse.lastPositionSec) }} / {{ formatDuration(continueCourse.duration) }}
                    <template v-if="continueCourse.progressPct != null"> · 进度 {{ continueCourse.progressPct }}%</template>
                  </p>
                  <div class="mt-2 h-1.5 w-full max-w-xs overflow-hidden rounded-full bg-line/60">
                    <div class="h-full rounded-full bg-primary transition-all" :style="{ width: (continueCourse.progressPct ?? 0) + '%' }" />
                  </div>
                </div>
                <button
                  class="flex shrink-0 items-center gap-1.5 rounded-xl bg-primary px-4 py-2.5 text-[13px] text-white hover:opacity-90"
                  @click="router.push(`/courses/${continueCourse.id}`)"
                >
                  <Play :size="14" />
                  继续学习
                </button>
              </div>
            </section>

            <!-- 今日复习 -->
            <section class="rounded-3xl border border-line bg-surface p-5">
              <div class="flex items-center justify-between">
                <h2 class="flex items-center gap-1.5 text-[15px] font-semibold text-ink">
                  <GraduationCap :size="16" class="text-primary" />
                  今日复习
                </h2>
                <RouterLink to="/review" class="flex items-center text-[12px] text-ink-2 hover:text-ink">
                  查看全部
                  <ChevronRight :size="13" />
                </RouterLink>
              </div>
              <div
                v-if="dueCount > 0"
                class="mt-4 flex flex-col gap-3 rounded-2xl bg-primary-soft/50 px-4 py-4 sm:flex-row sm:items-center"
              >
                <div class="min-w-0 flex-1">
                  <p class="text-[14px] text-ink">
                    有 <span class="font-semibold text-primary">{{ dueCount }}</span> 张卡片到期，建议花
                    约 {{ suggestMinutes }} 分钟进行复习
                  </p>
                  <div v-if="overview.todayQueue.length > 0" class="mt-2 flex flex-wrap gap-1.5">
                    <span
                      v-for="card in overview.todayQueue"
                      :key="card.frontText"
                      class="rounded-full border border-line bg-surface px-2.5 py-0.5 text-[11px] text-ink-2"
                    >
                      {{ truncate(card.frontText) }}
                    </span>
                  </div>
                </div>
                <button
                  class="flex shrink-0 items-center gap-1.5 self-start rounded-xl bg-primary px-4 py-2.5 text-[13px] text-white hover:opacity-90 sm:self-center"
                  @click="router.push('/review')"
                >
                  开始复习
                  <ChevronRight :size="14" />
                </button>
              </div>
              <div v-else class="mt-4 rounded-2xl border border-dashed border-line px-4 py-6 text-center text-[13px] text-ink-2">
                今日复习已清空 ✅ 到题目 / 笔记详情页「加入复习」，卡片会按记忆曲线出现在这里
              </div>
            </section>

            <!-- 最近学习 -->
            <section class="rounded-3xl border border-line bg-surface p-5">
              <div class="flex items-center justify-between">
                <h2 class="flex items-center gap-1.5 text-[15px] font-semibold text-ink">
                  <Clock :size="16" class="text-primary" />
                  最近学习
                </h2>
                <RouterLink to="/courses" class="flex items-center text-[12px] text-ink-2 hover:text-ink">
                  查看全部
                  <ChevronRight :size="13" />
                </RouterLink>
              </div>
              <div v-if="overview.recentCourses.length > 0" class="mt-4 grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-4">
                <RouterLink
                  v-for="course in overview.recentCourses"
                  :key="course.id"
                  :to="`/courses/${course.id}`"
                  class="group rounded-2xl border border-line p-4 transition-colors hover:bg-panel"
                >
                  <p class="truncate text-[14px] font-medium text-ink">{{ course.title }}</p>
                  <p class="mt-1.5 text-[11px] text-ink-2">上次学到 {{ formatTs(course.lastPositionSec) }}</p>
                  <div class="mt-2 flex items-center gap-2">
                    <div class="h-1 flex-1 overflow-hidden rounded-full bg-line/60">
                      <div class="h-full rounded-full bg-primary" :style="{ width: (course.progressPct ?? 0) + '%' }" />
                    </div>
                    <span class="text-[11px] text-ink-2">{{ course.progressPct ?? 0 }}%</span>
                  </div>
                  <p class="mt-2 text-[11px] text-ink-2/80">{{ relativeTime(course.lastStudiedAt || course.updatedAt) }}</p>
                </RouterLink>
              </div>
              <div v-else class="mt-4 rounded-2xl border border-dashed border-line px-4 py-6 text-center text-[13px] text-ink-2">
                暂无学习记录，去「课程」上传第一门网课吧
              </div>
            </section>
          </div>

          <!-- 右辅列 -->
          <aside class="flex flex-col gap-5">
            <!-- 学习数据 -->
            <section class="rounded-3xl border border-line bg-surface p-5">
              <div class="flex items-center justify-between">
                <h2 class="flex items-center gap-1.5 text-[15px] font-semibold text-ink">
                  <BookOpen :size="16" class="text-primary" />
                  学习数据
                </h2>
                <RouterLink to="/review" class="flex items-center text-[12px] text-ink-2 hover:text-ink">
                  查看详情
                  <ChevronRight :size="13" />
                </RouterLink>
              </div>
              <div class="mt-4 grid grid-cols-2 gap-3">
                <div class="rounded-2xl border border-line px-3 py-3.5">
                  <p class="text-[20px] font-semibold text-primary">{{ overview.stats.dueCount }}</p>
                  <p class="mt-0.5 text-[11px] text-ink-2">待复习卡片</p>
                </div>
                <div class="rounded-2xl border border-line px-3 py-3.5">
                  <p class="text-[20px] font-semibold text-ink">{{ overview.stats.reviewedToday }}</p>
                  <p class="mt-0.5 text-[11px] text-ink-2">今日已复习</p>
                </div>
                <div class="rounded-2xl border border-line px-3 py-3.5">
                  <p class="text-[20px] font-semibold text-ink">{{ overview.stats.coursesTotal }}</p>
                  <p class="mt-0.5 text-[11px] text-ink-2">网课</p>
                </div>
                <div class="rounded-2xl border border-line px-3 py-3.5">
                  <p class="text-[20px] font-semibold text-ink">{{ overview.stats.notesTotal }}</p>
                  <p class="mt-0.5 text-[11px] text-ink-2">笔记</p>
                </div>
                <div class="rounded-2xl border border-line px-3 py-3.5">
                  <p class="text-[20px] font-semibold text-ink">{{ overview.stats.questionsTotal }}</p>
                  <p class="mt-0.5 text-[11px] text-ink-2">题目</p>
                </div>
                <div class="rounded-2xl border border-line px-3 py-3.5">
                  <p class="text-[20px] font-semibold text-ink">{{ overview.stats.totalCards }}</p>
                  <p class="mt-0.5 text-[11px] text-ink-2">复习队列</p>
                </div>
              </div>
            </section>

            <!-- 本周统计（只读；目标功能为后续增强） -->
            <section class="rounded-3xl border border-line bg-surface p-5">
              <h2 class="flex items-center gap-1.5 text-[15px] font-semibold text-ink">
                <Sparkles :size="16" class="text-primary" />
                本周统计
              </h2>
              <div class="mt-3 flex flex-col gap-2 text-[13px] text-ink">
                <div class="flex items-center gap-2">
                  <NotebookPen :size="15" class="text-ink-2" />
                  新增笔记 <span class="font-semibold">{{ overview.week.newNotes }}</span> 篇
                </div>
                <div class="flex items-center gap-2">
                  <GraduationCap :size="15" class="text-ink-2" />
                  完成复习 <span class="font-semibold">{{ overview.week.reviewed }}</span> 次
                </div>
              </div>
              <p class="mt-3 text-[11px] leading-4 text-ink-2">
                坚持学习，会让你看到不一样的风景
              </p>
            </section>
          </aside>
        </div>
      </template>
    </div>
  </div>
</template>
