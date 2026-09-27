<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { History, Trash2 } from 'lucide-vue-next'
import { useAgentStore } from '../stores/agent'

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
  <div class="h-full overflow-y-auto">
    <div class="mx-auto max-w-3xl px-4 py-8">
      <h1 class="flex items-center gap-2 text-[18px] font-semibold">
        <History :size="20" class="text-ink-2" />
        会话历史
      </h1>
      <p class="mt-1 text-[13px] text-ink-2">保存最近 30 天的会话，到期自动删除；删除会话不影响学习资产</p>

      <p v-if="agent.conversations.length === 0" class="mt-8 rounded-xl border border-dashed border-line py-16 text-center text-[14px] text-ink-2">
        暂无会话记录
      </p>

      <ul v-else class="mt-6 flex flex-col gap-1">
        <li
          v-for="c in agent.conversations"
          :key="c.id"
          class="group flex cursor-pointer items-center justify-between rounded-xl px-4 py-3 hover:bg-panel"
          @click="openConversation(c.id)"
        >
          <div class="min-w-0">
            <p class="truncate text-[15px] text-ink">{{ c.title }}</p>
            <p class="text-[12px] text-ink-2">{{ c.lastActiveAt.replace('T', ' ').slice(0, 16) }}</p>
          </div>
          <button
            class="hidden shrink-0 text-ink-2 hover:text-red-500 group-hover:block"
            title="删除会话"
            @click.stop="removeConversation(c.id)"
          >
            <Trash2 :size="16" />
          </button>
        </li>
      </ul>
    </div>
  </div>
</template>
