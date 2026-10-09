<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { Menu } from 'lucide-vue-next'
import { useUiStore } from '../stores/ui'
import { useUserStore } from '../stores/user'
import { useAuthStore } from '../stores/auth'
import { useToastStore } from '../stores/toast'
import * as reviewApi from '../api/review'
import SideNav from '../components/layout/SideNav.vue'
import ProfileModal from '../components/ProfileModal.vue'
import ReviewModal from '../components/ReviewModal.vue'
import ToastHost from '../components/ToastHost.vue'

/**
 * 全局布局：主 SideNav（桌面常驻 / 移动抽屉），无桌面顶栏（移动端保留极简顶栏作抽屉入口）。
 * 会话侧栏不挂布局层——对话页 /chat 内嵌自己的会话栏。
 */
const ui = useUiStore()
const userStore = useUserStore()
const auth = useAuthStore()
const toast = useToastStore()
const showProfile = ref(false)
/** 复习到期提醒：侧栏「首页」项红点 + 每天一次 toast（与首页今日复习卡片同数据源） */
const bellDue = ref(0)

onMounted(async () => {
  // 个人页面数据 + 网课任务状态轮询（任务完成 / 失败右上角通知）
  userStore.loadMe()
  auth.loadUser()
  toast.startTaskWatcher()
  await remindReview()
  window.addEventListener('keydown', onGlobalKeydown)
})

onUnmounted(() => {
  toast.stopTaskWatcher()
  window.removeEventListener('keydown', onGlobalKeydown)
})

function openProfile() {
  if (auth.isLoggedIn) {
    showProfile.value = true
  }
}

/** 快捷键：Ctrl+, / Cmd+, 打开个人页面 */
function onGlobalKeydown(e: KeyboardEvent) {
  if ((e.ctrlKey || e.metaKey) && e.key === ',') {
    e.preventDefault()
    openProfile()
  }
}

/** 复习到期提醒：角标数据每次挂载都刷新；toast 每天最多轻推一次（受任务通知开关控制） */
async function remindReview() {
  try {
    const stats = await reviewApi.getReviewStats()
    bellDue.value = stats.dueCount
    const today = new Date().toISOString().slice(0, 10)
    const user = await userStore.loadMe()
    if (
      stats.dueCount > 0 &&
      localStorage.getItem('xj_review_reminded') !== today &&
      user?.notifyTaskEnabled !== false
    ) {
      localStorage.setItem('xj_review_reminded', today)
      toast.push(`有 ${stats.dueCount} 张卡片该复习了，要现在开始吗？`)
    }
  } catch {
    // 提醒失败静默
  }
}
</script>

<template>
  <div class="flex h-full overflow-hidden bg-surface text-ink">
    <!-- SideNav：桌面端常驻 -->
    <aside class="hidden w-52 shrink-0 border-r border-gray-100 bg-[#f8f9fb] md:block">
      <SideNav :due-count="bellDue" @open-profile="openProfile" />
    </aside>

    <!-- SideNav：移动端抽屉 -->
    <Transition name="fade">
      <div v-if="ui.sidebarOpen" class="fixed inset-0 z-40 bg-black/30 md:hidden" @click="ui.closeSidebar()" />
    </Transition>
    <Transition name="slide">
      <aside v-if="ui.sidebarOpen" class="fixed inset-y-0 left-0 z-50 w-60 border-r border-gray-100 bg-[#f8f9fb] md:hidden">
        <SideNav :due-count="bellDue" @navigate="ui.closeSidebar()" @open-profile="ui.closeSidebar(); openProfile()" />
      </aside>
    </Transition>

    <!-- 右列：移动端极简顶栏（桌面无顶栏） + 主区 -->
    <div class="flex min-w-0 flex-1 flex-col">
      <header class="flex h-10 shrink-0 items-center gap-2 border-b border-gray-200 bg-panel px-3 md:hidden">
        <button
          class="flex h-8 w-8 items-center justify-center rounded-lg text-ink-2 hover:bg-line/60 hover:text-ink"
          title="菜单"
          @click="ui.openSidebar()"
        >
          <Menu :size="20" />
        </button>
        <span class="text-[15px] font-medium">LearnTrace</span>
      </header>

      <main class="min-h-0 flex-1">
        <router-view v-slot="{ Component }">
          <!-- 笔记整理页跨页保留状态（上次打开的笔记 / 树展开） -->
          <KeepAlive include="NotesView">
            <component :is="Component" />
          </KeepAlive>
        </router-view>
      </main>
    </div>

    <!-- 个人页面弹窗：资料 / 偏好 / 账号 -->
    <ProfileModal v-model:open="showProfile" />
    <!-- 今日复习弹窗（复习由独立页改为全局弹窗，入口：首页复习区块） -->
    <ReviewModal />
    <!-- 全局轻提示：任务完成 / 失败通知 -->
    <ToastHost />
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
