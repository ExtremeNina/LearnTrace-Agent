<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ChevronDown, SquarePen, MonitorPlay, Camera, NotebookPen, Trash2 } from 'lucide-vue-next'
import { useAgentStore } from '../../stores/agent'

defineEmits<{ navigate: [] }>()

const agent = useAgentStore()
const router = useRouter()

onMounted(() => {
  agent.loadConversations()
})

async function openConversation(id: number) {
  await agent.openConversation(id)
  router.push('/')
}

async function removeConversation(id: number) {
  await agent.removeConversation(id)
}
</script>

<template>
  <div class="flex h-full flex-col">
    <!-- 品牌区 -->
    <div class="flex items-center gap-1.5 px-5 pt-5 pb-3">
      <span class="text-[20px] font-semibold tracking-tight">学迹</span>
      <ChevronDown :size="16" class="text-ink-2" />
    </div>

    <!-- 新对话 -->
    <div class="px-3">
      <RouterLink
        to="/"
        class="flex items-center gap-3 rounded-2xl px-3.5 py-2.5 text-[15px] text-ink hover:bg-line/50"
        @click="$emit('navigate')"
      >
        <SquarePen :size="18" class="text-ink-2" />
        新对话
      </RouterLink>
    </div>

    <!-- 学习资产 -->
    <div class="mt-7 px-3">
      <p class="px-3.5 pb-2 text-[14px] text-ink-2">学习资产</p>
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
  </div>
</template>
