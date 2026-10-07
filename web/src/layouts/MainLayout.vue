<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { LoaderCircle, Bell, Menu, Search } from 'lucide-vue-next'
import { useUiStore } from '../stores/ui'
import { useUserStore } from '../stores/user'
import { useAuthStore } from '../stores/auth'
import { useToastStore } from '../stores/toast'
import { globalSearch } from '../api/search'
import type { SearchResultItem } from '../api/search'
import * as reviewApi from '../api/review'
import SideNav from '../components/layout/SideNav.vue'
import ProfileModal from '../components/ProfileModal.vue'
import ToastHost from '../components/ToastHost.vue'

/**
 * 全局布局（B25 导航重构）：主 SideNav（桌面常驻 / 移动抽屉）+ 顶栏（全局搜索 / 用户入口）。
 * 会话侧栏不再挂布局层——对话页 /chat 内嵌自己的会话栏。
 */
const ui = useUiStore()
const userStore = useUserStore()
const auth = useAuthStore()
const toast = useToastStore()
const router = useRouter()
const route = useRoute()
const showProfile = ref(false)
/** 顶栏通知红点：有到期复习卡时亮起（与主动提醒同一份数据） */
const bellDue = ref(0)

// ---- 全局搜索（B15）：语义检索本人学习片段，300ms 防抖 + Ctrl+K 聚焦 ----
const searchQuery = ref('')
const searchFocused = ref(false)
const searchLoading = ref(false)
const searchResults = ref<SearchResultItem[]>([])
const searchInputRef = ref<HTMLInputElement | null>(null)
let searchTimer: ReturnType<typeof setTimeout> | null = null
let searchSeq = 0

const groupedResults = computed(() => {
  const groups: { label: string; items: SearchResultItem[] }[] = [
    { label: '网课', items: [] },
    { label: '笔记', items: [] },
    { label: '题目', items: [] },
  ]
  for (const item of searchResults.value) {
    if (item.type === 'transcript') groups[0].items.push(item)
    else if (item.type === 'note') groups[1].items.push(item)
    else if (item.type === 'question') groups[2].items.push(item)
  }
  return groups.filter((g) => g.items.length > 0)
})

function onSearchInput() {
  if (searchTimer) clearTimeout(searchTimer)
  const q = searchQuery.value.trim()
  if (!q) {
    searchResults.value = []
    searchLoading.value = false
    return
  }
  searchLoading.value = true
  searchTimer = setTimeout(() => runSearch(q), 300)
}

async function runSearch(q: string) {
  const seq = ++searchSeq
  try {
    const results = await globalSearch(q)
    if (seq === searchSeq) {
      searchResults.value = results
      searchLoading.value = false
    }
  } catch {
    if (seq === searchSeq) {
      searchResults.value = []
      searchLoading.value = false
    }
  }
}

function onSearchKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter') {
    if (searchTimer) clearTimeout(searchTimer)
    const q = searchQuery.value.trim()
    if (q) {
      runSearch(q)
    }
  } else if (e.key === 'Escape') {
    closeSearch()
  }
}

/** 网课结果秒数 → m:ss 跳转时间戳 */
function formatSearchTs(sec: number): string {
  const m = Math.floor(sec / 60)
  const s = String(sec % 60).padStart(2, '0')
  return `${m}:${s}`
}

function gotoResult(item: SearchResultItem) {
  closeSearch()
  if (item.type === 'transcript' && item.courseId != null) {
    router.push(item.tsSec != null ? `/courses/${item.courseId}?t=${formatSearchTs(item.tsSec)}` : `/courses/${item.courseId}`)
  } else if (item.type === 'note') {
    router.push(`/notes?open=${item.refId}`)
  } else if (item.type === 'question') {
    router.push(`/questions?open=${item.refId}`)
  }
}

function closeSearch() {
  searchQuery.value = ''
  searchResults.value = []
  searchInputRef.value?.blur()
}

onMounted(async () => {
  // 个人页面数据 + 网课任务状态轮询（任务完成 / 失败右上角通知）
  userStore.loadMe()
  auth.loadUser()
  toast.startTaskWatcher()
  // 主动复习提醒：有到期卡且当天未提醒过时轻推一次（受个人页面的任务通知开关控制）
  await remindReview()
  window.addEventListener('keydown', onGlobalKeydown)
})

onUnmounted(() => {
  toast.stopTaskWatcher()
  window.removeEventListener('keydown', onGlobalKeydown)
  if (searchTimer) clearTimeout(searchTimer)
})

function openProfile() {
  if (auth.isLoggedIn) {
    showProfile.value = true
  }
}

/** 快捷键：Ctrl+K / Cmd+K 聚焦搜索，Ctrl+, / Cmd+, 打开个人页面 */
function onGlobalKeydown(e: KeyboardEvent) {
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
    e.preventDefault()
    searchInputRef.value?.focus()
    return
  }
  if ((e.ctrlKey || e.metaKey) && e.key === ',') {
    e.preventDefault()
    openProfile()
  }
}

async function remindReview() {
  try {
    const today = new Date().toISOString().slice(0, 10)
    if (localStorage.getItem('xj_review_reminded') === today) {
      return
    }
    const user = await userStore.loadMe()
    if (user?.notifyTaskEnabled === false) {
      return
    }
    const stats = await reviewApi.getReviewStats()
    bellDue.value = stats.dueCount
    if (stats.dueCount > 0) {
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
      <SideNav @open-profile="openProfile" />
    </aside>

    <!-- SideNav：移动端抽屉 -->
    <Transition name="fade">
      <div v-if="ui.sidebarOpen" class="fixed inset-0 z-40 bg-black/30 md:hidden" @click="ui.closeSidebar()" />
    </Transition>
    <Transition name="slide">
      <aside v-if="ui.sidebarOpen" class="fixed inset-y-0 left-0 z-50 w-60 border-r border-gray-100 bg-[#f8f9fb] md:hidden">
        <SideNav @navigate="ui.closeSidebar()" @open-profile="ui.closeSidebar(); openProfile()" />
      </aside>
    </Transition>

    <!-- 右列：顶栏 + 主区 -->
    <div class="flex min-w-0 flex-1 flex-col">
      <header class="flex h-12 shrink-0 items-center gap-2 border-b border-gray-200 bg-panel px-3 md:h-14 md:px-5">
        <button
          class="flex h-8 w-8 items-center justify-center rounded-lg text-ink-2 hover:bg-line/60 hover:text-ink md:hidden"
          title="菜单"
          @click="ui.openSidebar()"
        >
          <Menu :size="20" />
        </button>
        <span class="text-[15px] font-medium md:hidden">学迹</span>

        <!-- 全局搜索（B15）：仅在首页展示（其他页面信息密度让位给页面主功能） -->
        <div v-if="route.path === '/'" class="relative hidden w-full max-w-md md:block">
          <Search :size="15" class="absolute left-3 top-1/2 -translate-y-1/2 text-ink-2" />
          <input
            ref="searchInputRef"
            v-model="searchQuery"
            class="w-full rounded-lg border border-line bg-line/40 py-1.5 pl-8 pr-9 text-[13px] text-ink outline-none focus:border-primary focus:bg-surface"
            placeholder="搜索课程、笔记、知识点…（Ctrl+K）"
            @input="onSearchInput"
            @keydown="onSearchKeydown"
            @focus="searchFocused = true"
            @blur="searchFocused = false"
          />
          <LoaderCircle v-if="searchLoading" :size="14" class="absolute right-3 top-1/2 -translate-y-1/2 animate-spin text-ink-2" />

          <!-- 结果下拉：mousedown.prevent 保住输入框焦点，点击项再跳转 -->
          <div
            v-if="searchFocused && searchQuery.trim()"
            class="absolute inset-x-0 top-full z-50 mt-1.5 max-h-[60vh] overflow-y-auto rounded-lg border border-line bg-surface p-2 shadow-lg"
            @mousedown.prevent
          >
            <div v-if="searchLoading && groupedResults.length === 0" class="px-3 py-4 text-center text-[12px] text-ink-2">
              搜索中…
            </div>
            <template v-else-if="groupedResults.length > 0">
              <div v-for="group in groupedResults" :key="group.label" class="mb-1">
                <p class="px-3 pb-1 pt-1.5 text-[11px] font-medium text-ink-2">{{ group.label }}</p>
                <button
                  v-for="(item, i) in group.items"
                  :key="group.label + i"
                  class="block w-full rounded-lg px-3 py-2 text-left hover:bg-line/50"
                  @click="gotoResult(item)"
                >
                  <span class="line-clamp-1 text-[13px] text-ink">{{ item.snippet }}</span>
                  <span v-if="item.type === 'transcript' && item.tsSec != null" class="ml-1.5 text-[11px] text-ink-2">
                    {{ formatSearchTs(item.tsSec) }}
                  </span>
                  <span v-else-if="item.type === 'question' && item.wrong" class="ml-1.5 text-[11px] text-red-400">错题</span>
                </button>
              </div>
            </template>
            <div v-else class="px-3 py-4 text-center text-[12px] text-ink-2">未找到相关内容</div>
          </div>
        </div>

        <!-- 通知：有到期复习卡时红点，点击进入复习 -->
        <button
          class="relative ml-auto flex h-8 w-8 shrink-0 items-center justify-center rounded-lg text-ink-2 hover:bg-line/60 hover:text-ink"
          title="今日待复习"
          @click="router.push('/review')"
        >
          <Bell :size="18" />
          <span v-if="bellDue > 0" class="absolute right-1.5 top-1.5 h-2 w-2 rounded-full bg-red-500" />
        </button>

        <!-- 用户入口：头像 + 昵称（打开个人页面） -->
        <button
          class="flex items-center gap-2 rounded-full p-1 pr-2 transition-colors hover:bg-line/50"
          title="个人页面"
          @click="openProfile"
        >
          <img
            v-if="userStore.profile?.avatarUrl"
            :src="userStore.profile.avatarUrl"
            class="h-7 w-7 rounded-full object-cover"
            alt=""
          />
          <span
            v-else
            class="flex h-7 w-7 items-center justify-center rounded-full bg-primary-soft text-[12px] font-medium text-primary"
          >
            {{ (userStore.profile?.nickname || auth.user?.username || '?').slice(0, 1) }}
          </span>
          <span class="hidden text-[13px] text-ink sm:block">
            {{ userStore.profile?.nickname || auth.user?.username || '' }}
          </span>
        </button>
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
