<script setup lang="ts">
import { NotebookPen } from 'lucide-vue-next'
</script>

<template>
  <div class="h-full overflow-y-auto">
    <div class="mx-auto max-w-3xl px-4 py-8">
      <h1 class="flex items-center gap-2 text-[18px] font-semibold">
        <NotebookPen :size="20" class="text-ink-2" />
        笔记整理
      </h1>
      <div class="mt-8 rounded-xl border border-dashed border-line py-16 text-center">
        <p class="text-[14px] text-ink-2">知识点页将随切片一后续工单开放</p>
        <p class="mt-1 text-[12px] text-ink-2">OneNote 式页面管理，Markdown 存储，页内知识联系</p>
      </div>
    </div>
  </div>
</template>
