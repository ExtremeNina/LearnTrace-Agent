<script setup lang="ts">
import { nextTick, onMounted, ref, watch } from 'vue'
import { ArrowUp, Plus, Square, X } from 'lucide-vue-next'
import { useAgentStore } from '../stores/agent'

/**
 * Agent 主区（PRD §3.1 / §5）：消息流 + 底部输入框，支持附图（截图预览位），流式渲染
 */
const agent = useAgentStore()
const draft = ref('')
const scrollBox = ref<HTMLElement | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)

onMounted(() => {
  agent.ensureSocketConnected()
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
  <div class="relative flex h-full flex-col bg-white">
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
        <div v-for="(msg, i) in agent.messages" :key="i" class="flex" :class="msg.role === 'user' ? 'justify-end' : 'justify-start'">
          <div
            class="max-w-[85%] whitespace-pre-wrap rounded-2xl px-4 py-2.5 text-[15px] leading-7"
            :class="msg.role === 'user'
              ? 'rounded-br-md bg-primary-soft text-ink'
              : 'rounded-bl-md border border-line bg-white text-ink'"
          >
            <img
              v-if="msg.imageUrl"
              :src="msg.imageUrl"
              alt="附图"
              class="mb-2 max-h-48 rounded-xl border border-line"
            />
            {{ msg.content }}<span v-if="msg.streaming" class="animate-pulse text-primary">▍</span>
          </div>
        </div>
      </div>
    </div>

    <!-- 底部：居中输入框 -->
    <div class="shrink-0 px-4 pb-6">
      <div class="mx-auto w-full max-w-3xl">
        <div class="rounded-[24px] border border-line bg-white px-4 py-3.5 shadow-sm focus-within:border-ink-2/50">
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
