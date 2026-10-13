<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { useUserStore } from '../stores/user'
import { useAuthStore } from '../stores/auth'
import { useToastStore } from '../stores/toast'
import * as reviewApi from '../api/review'
import SideNav from '../components/layout/SideNav.vue'
import ProfileModal from '../components/ProfileModal.vue'
import ReviewModal from '../components/ReviewModal.vue'
import ToastHost from '../components/ToastHost.vue'
import {
  Sidebar, SidebarInset, SidebarProvider, SidebarRail, SidebarTrigger,
} from '@/components/ui/sidebar'

/**
 * 全局布局（shadcn-vue Sidebar 体系）：SidebarProvider 托管折叠状态（cookie 记忆 / Ctrl+B），
 * 桌面端常驻侧栏 + 边缘热区展开（SidebarRail），移动端自动转为 Sheet 抽屉（SidebarTrigger 开启）。
 * 会话侧栏不挂布局层——对话页 /chat 内嵌自己的会话栏。
 */
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
  <!-- 侧栏宽度沿用设计稿 w-52（13rem）；折叠状态由 cookie 记忆，Ctrl+B 可切换 -->
  <SidebarProvider
    class="h-full min-h-0 overflow-hidden bg-surface text-ink"
    :style="{ '--sidebar-width': '13rem' }"
  >
    <!-- 主导航侧栏（sidebar-07 风格）：icon 折叠收起留图标列 + inset 悬浮变体 / 移动端 Sheet 抽屉 -->
    <Sidebar collapsible="icon" variant="inset">
      <SideNav :due-count="bellDue" :user="auth.user" @open-profile="openProfile" />
      <SidebarRail />
    </Sidebar>

    <!-- 右列：移动端极简顶栏（桌面无顶栏） + 主区（inset 变体下呈圆角卡片） -->
    <SidebarInset class="overflow-hidden bg-surface rounded-none md:rounded-xl">
      <header class="flex h-10 shrink-0 items-center gap-2 border-b border-gray-200 bg-panel px-3 md:hidden">
        <SidebarTrigger class="-ml-1" />
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
    </SidebarInset>

    <!-- 个人页面弹窗：资料 / 偏好 / 账号 -->
    <ProfileModal v-model:open="showProfile" />
    <!-- 今日复习弹窗（复习由独立页改为全局弹窗，入口：首页复习区块） -->
    <ReviewModal />
    <!-- 全局轻提示：任务完成 / 失败通知 -->
    <ToastHost />
  </SidebarProvider>
</template>
