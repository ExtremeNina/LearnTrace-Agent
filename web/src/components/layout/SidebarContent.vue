<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  ChevronsLeft, SquarePen, MonitorPlay, Camera, NotebookPen, Trash2,
  GraduationCap, LoaderCircle, X,
} from 'lucide-vue-next'
import { useAgentStore } from '../../stores/agent'
import * as reviewApi from '../../api/review'
import type { ReviewCardInfo, ReviewStatsInfo } from '../../types/api'

/**
 * 侧栏内容（三模式）：
 * chat = 新对话与会话历史（默认）；assets = 学习资产三入口；tasks = 复习任务（数量与今日队列）。
 * 桌面端 collapsible = true 时显示收缩按钮；移动端抽屉传 false。
 */
const props = withDefaults(defineProps<{ mode?: 'chat' | 'assets' | 'tasks'; collapsible?: boolean }>(), {
  mode: 'chat',
  collapsible: false,
})

const emit = defineEmits<{ navigate: []; collapse: [] }>()

const agent = useAgentStore()
const router = useRouter()

onMounted(() => {
  agent.loadConversations()
})

async function openConversation(id: number) {
  await agent.openConversation(id)
  router.push('/')
  showHistory.value = false
}

function startNew() {
  agent.startNew()
  router.push('/')
  showHistory.value = false
}

async function removeConversation(id: number) {
  await agent.removeConversation(id)
}

// ---- 任务模式：统计与今日队列（进入侧栏时加载） ----
const tasksStats = ref<ReviewStatsInfo | null>(null)
const tasksQueue = ref<ReviewCardInfo[]>([])
const tasksLoading = ref(false)

async function loadTasks() {
  tasksLoading.value = true
  try {
    tasksStats.value = await reviewApi.getReviewStats()
    tasksQueue.value = await reviewApi.getTodayQueue()
  } catch {
    // 任务侧栏加载失败静默（复习页可重试）
  } finally {
    tasksLoading.value = false
  }
}

watch(
  () => props.mode,
  (mode) => {
    if (mode === 'tasks') {
      loadTasks()
    }
  },
  { immediate: true }
)

function typeLabel(cardType: string): string {
  switch (cardType) {
    case 'note':
      return '笔记'
    case 'similar':
      return 'AI 相似题'
    default:
      return '拍照题目'
  }
}

// ---- 查看全部历史（弹窗） ----
const showHistory = ref(false)

const activeRoute = computed(() => router.currentRoute.value.path)
</script>

<template>
  <div class="flex h-full min-h-0 flex-col">
    <!-- 品牌区 -->
    <div class="flex items-center gap-1.5 px-5 pt-5 pb-3">
      <span v-if="mode === 'chat'" class="text-[20px] font-semibold tracking-tight">学迹</span>
      <span v-else-if="mode === 'assets'" class="text-[16px] font-semibold tracking-tight">学习资产</span>
      <span v-else class="flex items-center gap-1.5 text-[16px] font-semibold tracking-tight">
        <GraduationCap :size="16" class="text-primary" />
        今日待复习
      </span>
      <button
        v-if="collapsible"
        class="ml-auto flex h-7 w-7 items-center justify-center rounded-lg text-ink-2 hover:bg-line/60 hover:text-ink"
        title="收起侧栏"
        @click="$emit('collapse')"
      >
        <ChevronsLeft :size="16" />
      </button>
    </div>

    <!-- 对话模式：新对话 + 会话历史 -->
    <template v-if="mode === 'chat'">
      <div class="px-3">
        <RouterLink
          to="/"
          class="flex items-center gap-3 rounded-2xl px-3.5 py-2.5 text-[15px] text-ink hover:bg-line/50"
          @click="startNew(); $emit('navigate')"
        >
          <SquarePen :size="18" class="text-ink-2" />
          新对话
        </RouterLink>
      </div>

      <!-- 会话历史 -->
      <div class="mt-7 flex-1 overflow-y-auto px-3">
        <p class="px-3.5 pb-2 text-[14px] text-ink-2">会话历史</p>
        <p v-if="agent.conversations.length === 0" class="px-3.5 py-2 text-[13px] text-ink-2">暂无会话</p>
        <RouterLink
          v-for="c in agent.conversations.slice(0, 10)"
          :key="c.id"
          to="/"
          class="group flex items-center justify-between rounded-2xl px-3.5 py-2.5 text-[14px] text-ink hover:bg-line/50"
          :class="agent.activeId === c.id ? 'bg-line/60' : ''"
          @click="openConversation(c.id)"
        >
          <span class="truncate">{{ c.title }}</span>
          <button
            class="hidden shrink-0 text-ink-2 hover:text-red-500 group-hover:block"
            title="删除会话"
            @click.prevent="removeConversation(c.id)"
          >
            <Trash2 :size="15" />
          </button>
        </RouterLink>
        <button
          class="block w-full rounded-xl px-3.5 py-2 text-left text-[13px] text-ink-2 hover:bg-line/50 hover:text-ink"
          @click="showHistory = true"
        >
          查看全部历史
        </button>
      </div>

      <!-- 查看全部历史弹窗 -->
      <div
        v-if="showHistory"
        class="fixed inset-0 z-[75] flex items-center justify-center bg-ink/25 px-4 backdrop-blur-sm"
        @click.self="showHistory = false"
      >
        <div class="max-h-[80vh] w-full max-w-lg overflow-y-auto rounded-3xl border border-line bg-surface p-6 shadow-xl">
          <div class="flex items-center justify-between">
            <h2 class="text-[16px] font-semibold text-ink">全部会话历史</h2>
            <button
              class="flex h-8 w-8 items-center justify-center rounded-lg text-ink-2 hover:bg-line/60 hover:text-ink"
              title="关闭"
              @click="showHistory = false"
            >
              <X :size="16" />
            </button>
          </div>
          <p v-if="agent.conversations.length === 0" class="mt-4 text-[13px] text-ink-2">暂无会话</p>
          <div v-else class="mt-4 flex flex-col gap-2">
            <div
              v-for="c in agent.conversations"
              :key="c.id"
              class="group flex items-center justify-between rounded-2xl border border-line px-4 py-3"
            >
              <button
                class="min-w-0 flex-1 text-left"
                @click="openConversation(c.id)"
              >
                <p class="truncate text-[14px] text-ink">{{ c.title }}</p>
                <p class="mt-0.5 text-[12px] text-ink-2">{{ c.updatedAt }}</p>
              </button>
              <button
                class="ml-2 shrink-0 text-[12px] text-ink-2 hover:text-red-500"
                title="删除会话"
                @click="removeConversation(c.id)"
              >
                删除
              </button>
            </div>
          </div>
        </div>
      </div>
    </template>

    <!-- 资产模式：学习资产入口 -->
    <template v-else-if="mode === 'assets'">
      <div class="px-3 pt-1">
        <RouterLink
          to="/courses"
          class="flex items-center gap-3 rounded-2xl px-3.5 py-2.5 text-[15px] transition-colors"
          :class="activeRoute === '/courses' ? 'bg-ink font-medium text-white' : 'text-ink hover:bg-line/50'"
          @click="$emit('navigate')"
        >
          <MonitorPlay :size="18" :class="activeRoute === '/courses' ? 'text-white' : 'text-ink-2'" />
          网课记录
        </RouterLink>
        <RouterLink
          to="/questions"
          class="mt-1 flex items-center gap-3 rounded-2xl px-3.5 py-2.5 text-[15px] transition-colors"
          :class="activeRoute === '/questions' ? 'bg-ink font-medium text-white' : 'text-ink hover:bg-line/50'"
          @click="$emit('navigate')"
        >
          <Camera :size="18" :class="activeRoute === '/questions' ? 'text-white' : 'text-ink-2'" />
          拍照记录
        </RouterLink>
        <RouterLink
          to="/notes"
          class="mt-1 flex items-center gap-3 rounded-2xl px-3.5 py-2.5 text-[15px] transition-colors"
          :class="activeRoute === '/notes' ? 'bg-ink font-medium text-white' : 'text-ink hover:bg-line/50'"
          @click="$emit('navigate')"
        >
          <NotebookPen :size="18" :class="activeRoute === '/notes' ? 'text-white' : 'text-ink-2'" />
          笔记整理
        </RouterLink>
      </div>
      <p class="px-6 pt-3 text-[12px] leading-5 text-ink-2">
        网课转写与 AI 笔记、拍照解题与错因整理、OneNote 式分层笔记，都在这里管理。
      </p>
    </template>

    <!-- 任务模式：复习数量与今日队列 -->
    <template v-else-if="mode === 'tasks'">
      <div class="px-3 pt-1">
        <div v-if="tasksLoading" class="flex items-center gap-2 rounded-2xl border border-line px-4 py-4 text-[13px] text-ink-2">
          <LoaderCircle :size="14" class="animate-spin" />
          加载中…
        </div>
        <div v-else-if="tasksStats" class="grid grid-cols-3 gap-2">
          <div class="rounded-xl border border-line bg-surface p-2.5 text-center">
            <p class="text-[18px] font-semibold text-primary">{{ tasksStats.dueCount }}</p>
            <p class="mt-0.5 text-[11px] text-ink-2">待复习</p>
          </div>
          <div class="rounded-xl border border-line bg-surface p-2.5 text-center">
            <p class="text-[18px] font-semibold text-ink">{{ tasksStats.reviewedToday }}</p>
            <p class="mt-0.5 text-[11px] text-ink-2">今日已复习</p>
          </div>
          <div class="rounded-xl border border-line bg-surface p-2.5 text-center">
            <p class="text-[18px] font-semibold text-ink">{{ tasksStats.total }}</p>
            <p class="mt-0.5 text-[11px] text-ink-2">队列总数</p>
          </div>
        </div>
      </div>

      <div class="mt-4 min-h-0 flex-1 overflow-y-auto px-3">
        <p class="px-3.5 pb-2 text-[13px] text-ink-2">今日队列</p>
        <p v-if="tasksQueue.length === 0" class="px-3.5 py-2 text-[13px] text-ink-2">今天没有到期的卡片</p>
        <div
          v-for="c in tasksQueue"
          :key="c.id"
          class="mb-2 rounded-xl border border-line px-3 py-2.5"
        >
          <p class="text-[11px] text-ink-2">{{ typeLabel(c.cardType) }}</p>
          <p class="mt-0.5 truncate text-[13px] text-ink">{{ c.frontText || '（无内容）' }}</p>
        </div>
      </div>

      <div class="shrink-0 px-3 pb-3">
        <button
          class="flex w-full items-center justify-center gap-1.5 rounded-xl bg-primary py-2.5 text-[14px] text-white hover:opacity-90 disabled:opacity-50"
          :disabled="tasksLoading"
          @click="router.push('/review')"
        >
          <GraduationCap :size="15" />
          进入复习
        </button>
      </div>
    </template>
  </div>
</template>
