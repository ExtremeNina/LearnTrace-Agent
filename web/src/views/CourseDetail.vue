<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { Download, ArrowLeft, CircleCheck, AlertCircle, LoaderCircle } from 'lucide-vue-next'
import { getCourseDetail } from '../api/course'
import type { CourseDetailData } from '../api/course'
import { renderMarkdown } from '../utils/markdown'

/**
 * 网课详情（PRD §4.1 + §3.2 时间戳同步观看）：
 * 播放器（OSS 直链）+ AI 笔记 / 转写对照 / 关键帧识别 三标签，时间戳点击 seek。
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

function seekTo(sec: number) {
  if (videoRef.value) {
    videoRef.value.currentTime = sec
    videoRef.value.play()
  }
}

function parseTs(ts: string): number {
  const parts = ts.split(":").map(Number)
  return parts.length === 3
    ? parts[0] * 3600 + parts[1] * 60 + parts[2]
    : parts[0] * 60 + parts[1]
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

const noteHtml = computed(() =>
  data.value?.note ? renderMarkdown(data.value.note.content) : ''
)

function onTabChange(tab: 'note' | 'transcript' | 'frames') {
  activeTab.value = tab
}
</script>

<template>
  <div class="h-full overflow-y-auto">
    <div class="mx-auto max-w-5xl px-4 py-6">
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
            <p class="mt-0.5 text-[12px] text-ink-2">
              {{ data.course.createdAt }} · {{ formatSize(data.course.videoSize) }}
              <span v-if="data.course.expectations" class="ml-2 text-primary">已注入你的特别要求</span>
            </p>
          </div>
          <div class="ml-auto flex shrink-0 items-center gap-2">
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

        <div class="mt-5 flex flex-col gap-5 lg:flex-row">
          <!-- 左：播放器 + 期望 -->
          <div class="w-full shrink-0 lg:w-[46%]">
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

          <!-- 右：标签内容 -->
          <div class="min-w-0 flex-1">
            <div class="flex gap-1 rounded-xl bg-panel p-1">
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

            <!-- AI 笔记 -->
            <div v-if="activeTab === 'note'" class="mt-4 rounded-2xl border border-line bg-white p-5">
              <div v-if="data.note" class="note-view text-[14px] leading-7 text-ink" v-html="noteHtml" />
              <div v-else class="flex flex-col items-center gap-2 py-10 text-center">
                <AlertCircle :size="22" class="text-ink-2/60" />
                <p class="text-[14px] text-ink-2">
                  {{ data.course.status === 'SUCCESS' ? 'AI 笔记生成失败：' + (data.course.errorMsg || '未知原因') : 'AI 笔记将在流水线处理完成后生成' }}
                </p>
              </div>
            </div>

            <!-- 转写对照 -->
            <div v-else-if="activeTab === 'transcript'" class="mt-4 flex flex-col gap-3 rounded-2xl border border-line bg-white p-5">
              <div v-for="seg in data.transcript" :key="seg.id" class="flex gap-3 text-[14px] leading-7">
                <button class="shrink-0 pt-0.5 text-[12px] text-primary hover:underline" @click="seekTo(seg.startSec)">
                  {{ formatTs(seg.startSec) }}
                </button>
                <p class="text-ink">{{ seg.text }}</p>
              </div>
              <p v-if="data.transcript.length === 0" class="text-[13px] text-ink-2">未获得语音转写结果</p>
            </div>

            <!-- 关键帧识别 -->
            <div v-else class="grid grid-cols-1 gap-4 sm:grid-cols-2">
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
      </template>
    </div>
  </div>
</template>
