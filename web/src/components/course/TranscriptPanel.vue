<script setup lang="ts">
import { computed } from 'vue'
import type { FrameInfo, TranscriptSegmentInfo } from '../../api/course'
import { formatTs } from '../../utils/format'

/**
 * 转写对照面板（课程详情右列 tab）：时间线刻度条 + 转写段落（含自动修正标注与
 * SUGGESTED 疑似错误提示）+ 关键帧横向缩略图。纯展示组件，所有跳转通过 emit('seek') 上抛。
 */
const props = defineProps<{
  transcript: TranscriptSegmentInfo[]
  frames: FrameInfo[]
  duration: number
}>()

const emit = defineEmits<{ seek: [sec: number] }>()

/** 转写段落 + 落在该时间段内的关键帧（未落段的帧挂到最后一段） */
const segmentsWithFrames = computed(() => {
  const frames = [...props.frames].sort((a, b) => a.timeSec - b.timeSec)
  const used = new Set<number>()
  const rows = props.transcript.map((seg) => {
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

/** 转写修正元数据解析（B26 阶段 1 修正管线） */
function correctionOf(meta: string | null | undefined): { status?: string; suggestion?: string; original?: string } | null {
  if (!meta) {
    return null
  }
  try {
    return JSON.parse(meta)
  } catch {
    return null
  }
}

/** SUGGESTED（未确认建议）计数：转写对照页顶部的批量确认入口 */
const suggestedCount = computed(
  () => props.transcript.filter((s) => correctionOf(s.correctionMeta)?.status === 'SUGGESTED').length
)

/** 时间轴刻度：转写段起点 + 关键帧位置 */
const timelineTicks = computed(() => {
  const total = props.duration
  if (!total) {
    return []
  }
  const ticks: { t: number; kind: 'transcript' | 'frame'; pct: number }[] = []
  for (const seg of props.transcript) {
    if (seg.startSec <= total) {
      ticks.push({ t: seg.startSec, kind: 'transcript', pct: (seg.startSec / total) * 100 })
    }
  }
  for (const f of props.frames) {
    if (f.timeSec <= total) {
      ticks.push({ t: f.timeSec, kind: 'frame', pct: (f.timeSec / total) * 100 })
    }
  }
  return ticks
})
</script>

<template>
  <div v-if="timelineTicks.length" class="mb-3 px-1 pt-1">
    <div class="relative h-2 rounded-full bg-line/70">
      <button
        v-for="(tick, i) in timelineTicks"
        :key="i"
        class="absolute top-1/2 h-3 w-[3px] -translate-y-1/2 rounded-full"
        :class="tick.kind === 'frame' ? 'bg-primary' : 'bg-ink-2/60'"
        :style="{ left: tick.pct + '%' }"
        :title="formatTs(tick.t) + (tick.kind === 'frame' ? ' · 关键帧' : ' · 转写段')"
        @click="emit('seek', tick.t)"
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

  <div class="flex flex-col gap-7">
    <!-- B26 阶段 1：未确认转写修正批量入口 -->
    <div v-if="suggestedCount > 0" class="flex items-center gap-2 rounded-xl bg-amber-50 px-3 py-2 text-[12px] text-amber-600">
      ⚠️ 有 {{ suggestedCount }} 处疑似转写错误（下方已标注建议写法），确认无误可忽略
    </div>
    <div v-for="row in segmentsWithFrames" :key="row.seg.id">
      <div class="flex gap-4">
        <button class="w-12 shrink-0 pt-0.5 text-left text-[12px] font-medium text-primary hover:underline" @click="emit('seek', row.seg.startSec)">
          {{ formatTs(row.seg.startSec) }}
        </button>
        <p class="min-w-0 flex-1 text-[13px] leading-6.5 text-ink">
          <template v-if="row.seg.textCorrected">
            {{ row.seg.textCorrected }}
            <span
              class="cursor-help text-[11px] text-ink-2 line-through decoration-ink-2/40"
              :title="'已自动修正（原文：' + (correctionOf(row.seg.correctionMeta)?.original || row.seg.text) + '）'"
            >{{ row.seg.text }}</span>
          </template>
          <template v-else>{{ row.seg.text }}</template>
          <!-- SUGGESTED：未确认建议，内联标注 -->
          <span
            v-if="correctionOf(row.seg.correctionMeta)?.status === 'SUGGESTED'"
            class="ml-1 cursor-help border-b border-dashed border-amber-500 text-amber-600"
            :title="'疑似转写错误，建议写作：' + (correctionOf(row.seg.correctionMeta)?.suggestion || '')"
          >{{ row.seg.text }}</span>
        </p>
      </div>
      <div v-if="row.frames.length" class="mt-2 flex gap-2 overflow-x-auto pb-1">
        <button
          v-for="f in row.frames"
          :key="f.id"
          class="relative shrink-0"
          :title="f.ocrText || '关键帧 ' + formatTs(f.timeSec)"
          @click="emit('seek', f.timeSec)"
        >
          <img :src="f.ossKey" :alt="'第 ' + f.timeSec + 's 画面'" class="h-14 w-24 rounded-lg border border-line object-cover" />
          <span class="absolute bottom-0.5 right-0.5 rounded bg-black/60 px-1 text-[10px] text-white">{{ formatTs(f.timeSec) }}</span>
        </button>
      </div>
    </div>
    <p v-if="!(transcript.length || frames.length)" class="rounded-xl border border-dashed border-line py-8 text-center text-[13px] text-ink-2">
      未获得转写与关键帧数据
    </p>
  </div>
</template>
