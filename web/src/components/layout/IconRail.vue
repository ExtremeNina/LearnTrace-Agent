<script setup lang="ts">
import { ref } from 'vue'
import { House, History, CircleHelp, Settings, LogIn } from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../../stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const showSettings = ref(false)

/** 未登录时从弹窗直达登录页（先清除失效 token，避免路由守卫拦截） */
function goLogin() {
  showSettings.value = false
  auth.logout()
  router.push('/login')
}

async function toggleSettings() {
  showSettings.value = !showSettings.value
  if (showSettings.value) {
    await auth.loadUser()
  }
}

function closeSettings() {
  showSettings.value = false
}
</script>

<template>
  <!-- 最左全局图标栏（PRD §5：桌面端显示，移动端隐藏并入抽屉） -->
  <nav class="panel-gradient relative w-14 shrink-0 flex-col items-center justify-between py-4 md:flex">
    <div class="flex flex-col items-center gap-2">
      <RouterLink
        to="/"
        class="flex h-10 w-10 items-center justify-center rounded-xl transition-colors"
        :class="route.path === '/' ? 'bg-ink text-white' : 'text-ink hover:bg-line/60'"
        title="首页 / Agent"
      >
        <House :size="20" />
      </RouterLink>
      <RouterLink
        to="/history"
        class="flex h-10 w-10 items-center justify-center rounded-xl transition-colors"
        :class="route.path === '/history' ? 'bg-ink text-white' : 'text-ink hover:bg-line/60'"
        title="会话历史"
      >
        <History :size="20" />
      </RouterLink>
    </div>
    <div class="flex flex-col items-center gap-2">
      <button
        class="flex h-10 w-10 items-center justify-center rounded-xl text-ink hover:bg-line/60"
        title="帮助"
      >
        <CircleHelp :size="20" />
      </button>
      <button
        class="flex h-10 w-10 items-center justify-center rounded-xl transition-colors"
        :class="showSettings ? 'bg-line/70 text-ink' : 'text-ink hover:bg-line/60'"
        title="设置"
        @click="toggleSettings"
      >
        <Settings :size="20" />
      </button>
    </div>

    <!-- 设置弹窗：展示当前用户信息 -->
    <template v-if="showSettings">
      <div class="fixed inset-0 z-40" @click="closeSettings" />
      <div class="absolute bottom-16 left-16 z-50 w-64 rounded-2xl border border-line bg-white p-4 shadow-lg">
        <p class="text-[16px] font-medium text-ink">
          {{ auth.user?.nickname || auth.user?.username || (auth.userLoadFailed ? '未登录' : '加载中…') }}
        </p>
        <button
          v-if="auth.userLoadFailed"
          class="mt-3 flex w-full items-center justify-center gap-2 rounded-xl bg-primary py-2 text-[14px] text-white hover:opacity-90"
          @click="goLogin"
        >
          <LogIn :size="16" />
          登录
        </button>
        <p v-if="auth.user" class="mt-0.5 text-[12px] text-ink-2">@{{ auth.user.username }}</p>
        <div class="my-3 h-px bg-line" />
        <div class="flex items-center justify-between rounded-xl px-3 py-2 text-[14px] text-ink hover:bg-panel">
          <span class="flex items-center gap-2.5">
            <Settings :size="16" class="text-ink-2" />
            设置
          </span>
          <span class="text-[12px] text-ink-2">Ctrl+,</span>
        </div>
        <div
          v-if="auth.user"
          class="flex items-center justify-between rounded-xl px-3 py-2 text-[14px] text-ink-2"
        >
          <span>用户 ID</span>
          <span class="text-[12px]">{{ auth.user.id }}</span>
        </div>
      </div>
    </template>
  </nav>
</template>
