<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ChevronsLeft, SquarePen, MonitorPlay, Camera, NotebookPen, Trash2 } from 'lucide-vue-next'
import { useAgentStore } from '../../stores/agent'

/**
 * 侧栏内容（双模式）：
 * chat = 新对话与会话历史（默认）；assets = 学习资产（网课记录 / 拍照记录 / 笔记整理）。
 * 桌面端 collapsible = true 时显示收缩按钮；移动端抽屉传 false。
 */
const props = withDefaults(defineProps<{ mode?: 'chat' | 'assets'; collapsible?: boolean }>(), {
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
}

function startNew() {
  agent.startNew()
  router.push('/')
}

async function removeConversation(id: number) {
  await agent.removeConversation(id)
}
</script>

<template>
  <div class="flex h-full min-h-0 flex-col">
    <!-- 品牌区 -->
    <div class="flex items-center gap-1.5 px-5 pt-5 pb-3">
      <span v-if="mode === 'chat'" class="text-[20px] font-semibold tracking-tight">学迹</span>
      <span v-else class="text-[16px] font-semibold tracking-tight">学习资产</span>
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
        <RouterLink
          to="/history"
          class="block rounded-xl px-3.5 py-2 text-[13px] text-ink-2 hover:bg-line/50 hover:text-ink"
          @click="$emit('navigate')"
        >
          查看全部历史
        </RouterLink>
      </div>
    </template>

    <!-- 资产模式：学习资产入口 -->
    <template v-else>
      <div class="px-3 pt-1">
        <RouterLink
          to="/courses"
          class="flex items-center gap-3 rounded-2xl px-3.5 py-2.5 text-[15px] text-ink hover:bg-line/50"
          @click="$emit('navigate')"
        >
          <MonitorPlay :size="18" class="text-ink-2" />
          网课记录
        </RouterLink>
        <RouterLink
          to="/questions"
          class="flex items-center gap-3 rounded-2xl px-3.5 py-2.5 text-[15px] text-ink hover:bg-line/50"
          @click="$emit('navigate')"
        >
          <Camera :size="18" class="text-ink-2" />
          拍照记录
        </RouterLink>
        <RouterLink
          to="/notes"
          class="flex items-center gap-3 rounded-2xl px-3.5 py-2.5 text-[15px] text-ink hover:bg-line/50"
          @click="$emit('navigate')"
        >
          <NotebookPen :size="18" class="text-ink-2" />
          笔记整理
        </RouterLink>
      </div>
      <p class="px-6 pt-3 text-[12px] leading-5 text-ink-2">
        网课转写与 AI 笔记、拍照解题与错因整理、OneNote 式分层笔记，都在这里管理。
      </p>
    </template>
  </div>
</template>
