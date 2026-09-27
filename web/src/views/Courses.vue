<script setup lang="ts">
import { MonitorPlay } from 'lucide-vue-next'
</script>

<template>
  <div class="h-full overflow-y-auto">
    <div class="mx-auto max-w-3xl px-4 py-8">
      <h1 class="flex items-center gap-2 text-[18px] font-semibold">
        <MonitorPlay :size="20" class="text-ink-2" />
        网课记录
      </h1>
      <div class="mt-8 rounded-xl border border-dashed border-line py-16 text-center">
        <p class="text-[14px] text-ink-2">该模块将在切片三（网课链路）中开放</p>
        <p class="mt-1 text-[12px] text-ink-2">视频上传、转写、AI 笔记与章节</p>
      </div>
    </div>
  </div>
</template>
