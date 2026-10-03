<script setup lang="ts">
import { nextTick, onMounted, ref, watch } from 'vue'
import { ArrowUp, Check, Copy, Plus, RefreshCw, Share2, Sparkles, Square, ThumbsDown, ThumbsUp, Volume2, X } from 'lucide-vue-next'
import { useAgentStore } from '../stores/agent'
import { renderMarkdown } from '../utils/markdown'

/**
 * Agent 主区（PRD §3.1 / §5）：消息流 + 底部输入框，支持附图（截图预览位），流式渲染
 */
const agent = useAgentStore()
const draft = ref('')
const scrollBox = ref<HTMLElement | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)
/** 刚完成复制的消息下标（短暂显示对勾反馈） */
const copiedIndex = ref<number | null>(null)

async function copyMessage(index: number, content: string) {
  try {
    await navigator.clipboard.writeText(content)
    copiedIndex.value = index
    setTimeout(() => {
      if (copiedIndex.value === index) {
        copiedIndex.value = null
      }
    }, 1500)
  } catch {
    // 剪贴板不可用（非安全上下文等）时静默忽略
  }
}

onMounted(async () => {
  agent.ensureSocketConnected()
  // 刷新后恢复到上次的会话（本地无记录或会话已删除则保持新对话）
  await agent.restoreLastConversation()
  // 跨页种子消息（题目详情页「生成相似题」）：切换目标会话后自动发出
  await agent.applySeed()
})

watch(
  () => agent.messages.map((m) => m.content).join('|'),
  () => {
    nextTick(() => {
      scrollBox.value?.scrollTo({ top: scrollBox.value.scrollHeight })
    })
  }
)

function onPickImage() {
  fileInput.value?.click()
}

async function onFileChange(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (file) {
    await agent.uploadPendingImage(file)
  }
}

function onSend() {
  const text = draft.value.trim()
  if ((text === '' && !agent.pendingImage) || agent.streaming || agent.uploading) {
    return
  }
  const content = text || '请看这张图片'
  draft.value = ''
  agent.send(content)
}
</script>

<template>
  <div class="relative flex h-full flex-col bg-surface">
    <!-- 消息流 / 空状态 -->
    <div ref="scrollBox" class="min-h-0 flex-1 overflow-y-auto">
      <div v-if="agent.messages.length === 0" class="flex h-full flex-col items-center justify-center gap-5 px-4">
        <svg width="52" height="52" viewBox="0 0 48 48" fill="none" class="text-ink-2/60">
          <path
            d="M24 6c-5 0-9 3.4-10 8-4.2.8-7.5 4.4-7.5 9 0 5 4 9 9 9h17c5 0 9-4 9-9 0-4.6-3.3-8.2-7.5-9C33 9.4 29 6 24 6Z"
            stroke="currentColor"
            stroke-width="2.4"
            stroke-linejoin="round"
          />
          <path d="M19 27l5-5 5 5M24 22v10" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
        <h1 class="text-[28px] font-medium tracking-tight text-ink md:text-[32px]">你好，今天想学点什么？</h1>
      </div>

      <div v-else class="mx-auto flex max-w-3xl flex-col gap-5 px-4 py-6">
        <div v-for="(msg, i) in agent.messages" :key="i" class="flex gap-2" :class="msg.role === 'user' ? 'justify-end' : 'justify-start'">
          <!-- AI 标识小图标：仅助手消息显示 -->
          <div
            v-if="msg.role === 'assistant'"
            class="mt-1 flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-primary-soft text-primary"
            title="学迹 AI"
          >
            <Sparkles :size="14" />
          </div>
          <div
            class="max-w-[85%] rounded-2xl px-4 py-2.5 text-[15px] leading-7"
            :class="msg.role === 'user'
              ? 'whitespace-pre-wrap rounded-br-md bg-primary-soft text-ink'
              : 'text-ink'"
          >
            <img
              v-if="msg.imageUrl"
              :src="msg.imageUrl"
              alt="附图"
              class="mb-2 max-h-48 rounded-xl border border-line"
            />
            <!-- 助手消息：Markdown + 公式渲染；用户消息：纯文本 -->
            <template v-if="msg.role === 'assistant'">
              <div class="markdown-body" v-html="renderMarkdown(msg.content)"></div>

              <!-- 动作条：复制可用，其余为占位 -->
              <div v-if="!msg.streaming" class="mt-2.5 flex items-center gap-0.5 text-ink-2">
                <button
                  class="flex h-7 w-7 items-center justify-center rounded-md hover:bg-line/60 hover:text-ink"
                  :title="copiedIndex === i ? '已复制' : '复制'"
                  @click="copyMessage(i, msg.content)"
                >
                  <Check v-if="copiedIndex === i" :size="15" class="text-primary" />
                  <Copy v-else :size="15" />
                </button>
                <button class="flex h-7 w-7 items-center justify-center rounded-md hover:bg-line/60 hover:text-ink" title="重新生成（开发中）">
                  <RefreshCw :size="15" />
                </button>
                <button class="flex h-7 w-7 items-center justify-center rounded-md hover:bg-line/60 hover:text-ink" title="有帮助（开发中）">
                  <ThumbsUp :size="15" />
                </button>
                <button class="flex h-7 w-7 items-center justify-center rounded-md hover:bg-line/60 hover:text-ink" title="没帮助（开发中）">
                  <ThumbsDown :size="15" />
                </button>
                <button class="flex h-7 w-7 items-center justify-center rounded-md hover:bg-line/60 hover:text-ink" title="朗读（开发中）">
                  <Volume2 :size="15" />
                </button>
                <button class="flex h-7 w-7 items-center justify-center rounded-md hover:bg-line/60 hover:text-ink" title="分享（开发中）">
                  <Share2 :size="15" />
                </button>
              </div>

              <!-- 解答类回答：保存引导 -->
              <p
                v-if="msg.fromQuestion && !msg.streaming"
                class="mt-2.5 rounded-xl bg-panel px-3 py-2 text-[13px] leading-5 text-ink-2"
              >
                这道题如果值得整理，回复「保存」，我会把它收进你的拍照记录，方便考前集中复习。
              </p>
            </template>
            <template v-else>{{ msg.content }}</template>
            <span v-if="msg.streaming" class="animate-pulse text-primary">▍</span>
          </div>
        </div>
      </div>
    </div>

    <!-- 底部：居中输入框 -->
    <div class="shrink-0 px-4 pb-6">
      <div class="mx-auto w-full max-w-3xl">
        <div class="rounded-[24px] border border-line bg-surface px-4 py-3.5 shadow-sm focus-within:border-ink-2/50">
          <!-- 待发送图片预览位（截图中的图片位置） -->
          <div v-if="agent.pendingImage" class="mb-3 flex">
            <div class="relative">
              <img :src="agent.pendingImage" alt="待发送图片" class="h-20 w-20 rounded-xl border border-line object-cover" />
              <button
                class="absolute -right-1.5 -top-1.5 flex h-5 w-5 items-center justify-center rounded-full bg-ink text-white"
                title="移除图片"
                @click="agent.clearPendingImage()"
              >
                <X :size="12" />
              </button>
            </div>
          </div>
          <p v-if="agent.uploading" class="mb-3 text-[13px] text-ink-2">图片上传中…</p>

          <textarea
            v-model="draft"
            rows="2"
            class="w-full resize-none bg-transparent text-[16px] leading-7 outline-none"
            placeholder="给学迹发送消息…"
            @keydown.enter.exact.prevent="onSend"
          />
          <div class="flex items-center justify-between pt-2">
            <div class="flex items-center gap-3">
              <button
                class="flex h-8 w-8 items-center justify-center rounded-full text-ink hover:bg-line/60"
                :class="agent.uploading ? 'animate-pulse text-ink-2' : ''"
                title="添加图片"
                :disabled="agent.uploading"
                @click="onPickImage"
              >
                <Plus :size="20" />
              </button>
              <span class="text-[13px] text-ink-2">Enter 发送</span>
            </div>
            <button
              v-if="!agent.streaming"
              class="flex h-9 w-9 items-center justify-center rounded-full bg-ink text-white transition-opacity hover:opacity-80 disabled:opacity-25"
              :disabled="(draft.trim() === '' && !agent.pendingImage) || agent.uploading"
              @click="onSend"
            >
              <ArrowUp :size="18" />
            </button>
            <button
              v-else
              class="flex h-9 w-9 items-center justify-center rounded-full bg-ink text-white hover:opacity-80"
              title="停止生成"
              @click="agent.stop()"
            >
              <Square :size="14" fill="currentColor" />
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- 隐藏的图片选择器 -->
    <input
      ref="fileInput"
      type="file"
      accept="image/jpeg,image/png,image/gif,image/webp,image/bmp"
      class="hidden"
      @change="onFileChange"
    />
  </div>
</template>
