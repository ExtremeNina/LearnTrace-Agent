<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ArrowUp, ChevronsRight, Film, History, Plus, Square, X } from 'lucide-vue-next'
import { useAgentStore } from '../stores/agent'
import ModelPicker from '../components/chat/ModelPicker.vue'
import SidebarContent from '../components/layout/SidebarContent.vue'
import ChatPanel from '../components/chat/ChatPanel.vue'

/**
 * Agent 主区（PRD §3.1 / §5）：消息流 + 底部输入框，支持附图（截图预览位）与附视频（B11 转写），流式渲染。
 * 输入框左下角常驻模型切换器（ModelPicker 公共组件，首页 AI 面板复用）。
 */
const agent = useAgentStore()
/** 会话侧栏（原布局侧栏下沉为页内栏，B25）：桌面内嵌可收缩，移动端抽屉 */
const chatNavOpen = ref(true)
const chatDrawerOpen = ref(false)
const draft = ref('')
const fileInput = ref<HTMLInputElement | null>(null)
const videoInput = ref<HTMLInputElement | null>(null)
const showAttachMenu = ref(false)

onMounted(async () => {
  agent.ensureSocketConnected()
  // 刷新后恢复到上次的会话（本地无记录或会话已删除则保持新对话）；
  // 简报由 openConversation 内部按后端「会话×日期」标记恢复，无需在此注入
  await agent.restoreLastConversation()
  // 跨页种子消息（题目详情页「生成相似题」）：切换目标会话后自动发出
  await agent.applySeed()
})

function onPickImage() {
  showAttachMenu.value = false
  fileInput.value?.click()
}

function onPickVideo() {
  showAttachMenu.value = false
  videoInput.value?.click()
}

async function onFileChange(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (file) {
    await agent.uploadPendingImage(file)
  }
}

/** 视频选择：客户端先做格式初筛，时长校验交给上传接口（ffprobe 同步拒绝） */
async function onVideoFileChange(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (file) {
    await agent.uploadPendingVideo(file)
  }
}

/** 视频时长（秒）→ mm:ss（待发送预览展示用；消息气泡内的同款工具在 ChatPanel） */
function formatDuration(sec?: number): string {
  if (!sec && sec !== 0) {
    return ''
  }
  const m = Math.floor(sec / 60)
  const s = sec % 60
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
}

function onSend() {
  const text = draft.value.trim()
  if ((text === '' && !agent.pendingImage && !agent.pendingVideo) || agent.streaming || agent.uploading) {
    return
  }
  const content = text || (agent.pendingVideo ? '请转写这个视频' : '请看这张图片')
  draft.value = ''
  agent.send(content)
}

/** 意图确认选项卡片点击：选项文本直接作为用户消息发送（视频经工具回查拿到，无需重传） */
function onIntentChip(text: string) {
  if (!agent.streaming && !agent.uploading) {
    agent.send(text)
  }
}
</script>

<template>
  <div class="flex h-full min-h-0">
    <!-- 会话侧栏：桌面端内嵌（可收缩） -->
    <aside v-if="chatNavOpen" class="panel-gradient relative hidden w-60 shrink-0 border-r border-line md:block">
      <SidebarContent mode="chat" collapsible @collapse="chatNavOpen = false" />
    </aside>
    <button
      v-if="!chatNavOpen"
      class="panel-gradient group relative hidden w-2 shrink-0 border-r border-line transition-all hover:w-12 md:block"
      title="展开会话侧栏"
      @click="chatNavOpen = true"
    >
      <ChevronsRight :size="16" class="absolute left-1/2 top-6 -translate-x-1/2 rotate-180 text-ink-2 opacity-0 transition-opacity group-hover:opacity-100" />
    </button>

    <!-- 会话侧栏：移动端抽屉 -->
    <Transition name="fade">
      <div v-if="chatDrawerOpen" class="fixed inset-0 z-40 bg-black/30 md:hidden" @click="chatDrawerOpen = false" />
    </Transition>
    <Transition name="slide">
      <aside v-if="chatDrawerOpen" class="panel-gradient fixed inset-y-0 left-0 z-50 w-64 border-r border-line md:hidden">
        <SidebarContent mode="chat" @navigate="chatDrawerOpen = false" />
      </aside>
    </Transition>

    <div class="relative flex h-full min-w-0 flex-1 flex-col bg-surface">
      <!-- 移动端会话历史入口 -->
      <button
        class="absolute left-3 top-3 z-10 flex items-center gap-1.5 rounded-lg border border-line bg-surface px-2.5 py-1.5 text-[12px] text-ink-2 hover:text-ink md:hidden"
        @click="chatDrawerOpen = true"
      >
        <History :size="14" />
        会话
      </button>
      <ChatPanel empty-title="你好，今天想学点什么？" @intent="onIntentChip" />
    <!-- 底部：居中输入框 -->
    <div class="shrink-0 px-4 pb-6">
      <div class="mx-auto w-full max-w-3xl">
        <div class="rounded-[24px] border border-line bg-surface px-4 py-3.5 shadow-sm focus-within:border-ink-2/50">
          <!-- 待发送图片 / 视频预览位 -->
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
          <div v-if="agent.pendingVideo" class="mb-3 flex">
            <div class="relative flex items-center gap-2 rounded-xl border border-line bg-panel px-3 py-2.5 text-[13px] text-ink">
              <Film :size="16" class="text-primary" />
              <span class="max-w-48 truncate">{{ agent.pendingVideo.name }}</span>
              <span class="text-ink-2">（{{ formatDuration(agent.pendingVideo.durationSec) }}）</span>
              <button
                class="ml-1 flex h-5 w-5 items-center justify-center rounded-full bg-ink text-white"
                title="移除视频"
                @click="agent.clearPendingVideo()"
              >
                <X :size="12" />
              </button>
            </div>
          </div>
          <p v-if="agent.uploading" class="mb-3 text-[13px] text-ink-2">
            {{ agent.pendingVideo ? '视频' : '图片' }}上传中，视频需要较长时间，请稍候…
          </p>

          <textarea
            v-model="draft"
            rows="2"
            class="w-full resize-none bg-transparent text-[16px] leading-7 outline-none"
            placeholder="给学迹发送消息…"
            @keydown.enter.exact.prevent="onSend"
          />
          <div class="flex items-center justify-between pt-2">
            <div class="flex items-center gap-3">
            <!-- 当前模型指示器：点击切换模型 / 管理模型（公共组件） -->
            <ModelPicker tone="ink" />
            <div class="relative">
              <button
                class="flex h-8 w-8 items-center justify-center rounded-full text-ink hover:bg-line/60"
                :class="agent.uploading ? 'animate-pulse text-ink-2' : ''"
                title="添加图片 / 视频"
                :disabled="agent.uploading"
                @click="showAttachMenu = !showAttachMenu"
              >
                <Plus :size="20" />
              </button>
              <!-- 附件菜单：图片 / 视频（B11） -->
              <div
                v-if="showAttachMenu"
                class="absolute bottom-[calc(100%+8px)] left-0 z-50 w-56 rounded-2xl border border-line bg-surface p-2 shadow-lg"
              >
                <button
                  class="flex w-full items-center rounded-xl px-3 py-2 text-[14px] text-ink hover:bg-panel"
                  @click="onPickImage"
                >
                  图片
                </button>
                <button
                  class="flex w-full items-center rounded-xl px-3 py-2 text-left text-[14px] text-ink hover:bg-panel"
                  @click="onPickVideo"
                >
                  视频<span class="ml-1 text-[12px] text-ink-2">≤30 分钟默认仅转写语音；&gt;30 分钟自动按网课处理</span>
                </button>
              </div>
              <div v-if="showAttachMenu" class="fixed inset-0 z-40" @click="showAttachMenu = false"></div>
            </div>
            <span class="text-[13px] text-ink-2">Enter 发送</span>
          </div>
            <button
              v-if="!agent.streaming"
              class="flex h-9 w-9 items-center justify-center rounded-full bg-ink text-white transition-opacity hover:opacity-80 disabled:opacity-25"
              :disabled="(draft.trim() === '' && !agent.pendingImage && !agent.pendingVideo) || agent.uploading"
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
    <!-- 隐藏的视频选择器（B11：仅语音转写，≤30 分钟） -->
    <input
      ref="videoInput"
      type="file"
      accept="video/mp4,video/quicktime,video/x-matroska,video/webm,video/x-msvideo,.mp4,.mov,.mkv,.avi,.webm,.m4v"
      class="hidden"
      @change="onVideoFileChange"
    />

    </div>
  </div>
</template>

<style scoped>
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.15s ease;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
.slide-enter-active,
.slide-leave-active {
  transition: transform 0.2s ease;
}
.slide-enter-from,
.slide-leave-to {
  transform: translateX(-100%);
}
</style>
