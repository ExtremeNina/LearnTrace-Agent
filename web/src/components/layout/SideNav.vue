<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { ChevronsUpDown, Home, MonitorPlay, NotebookPen, Camera, Settings } from 'lucide-vue-next'
import {
  SidebarContent, SidebarFooter, SidebarGroup, SidebarGroupContent, SidebarGroupLabel,
  SidebarHeader, SidebarMenu, SidebarMenuButton, SidebarMenuItem, useSidebar,
} from '@/components/ui/sidebar'
import type { UserInfo } from '@/types/api'

/**
 * 主导航侧栏（shadcn-vue Sidebar 体系，sidebar-07 风格）：
 * 品牌 mark + 内容导航 + 底部用户卡片（点击打开个人页面）。
 * 折叠（icon 模式收起留图标列）/ 移动端抽屉行为由外层 Sidebar 组件托管。
 */
const emit = defineEmits<{ navigate: []; 'open-profile': [] }>()

/** 今日到期复习卡数（>0 时「首页」项显示红点角标） + 当前登录用户（底部卡片） */
const props = defineProps<{ dueCount?: number; user?: UserInfo | null }>()

const route = useRoute()
const sidebar = useSidebar()

const items = [
  { path: '/', label: '首页', icon: Home },
  { path: '/courses', label: '课程', icon: MonitorPlay },
  { path: '/notes', label: '笔记', icon: NotebookPen },
  { path: '/questions', label: '题目', icon: Camera },
]

const activePath = computed(() => route.path)

function isActive(path: string): boolean {
  return path === '/' ? activePath.value === '/' : activePath.value.startsWith(path)
}

/** 展示名：昵称优先，其次用户名 */
const displayName = computed(() => props.user?.nickname || props.user?.username || '')

/** 头像兜底：取展示名首字符 */
const initial = computed(() => displayName.value.charAt(0).toUpperCase() || '?')

/** 导航后收起移动端抽屉（桌面无副作用），并向上冒泡 navigate 事件 */
function onNavigate() {
  sidebar.setOpenMobile(false)
  emit('navigate')
}

/** 打开个人页面：同样先收起移动端抽屉 */
function onOpenProfile() {
  sidebar.setOpenMobile(false)
  emit('open-profile')
}
</script>

<template>
  <!-- 品牌区：mark 常驻（icon 折叠时居中显示），文字仅展开态显示 -->
  <SidebarHeader class="p-2 pt-3">
    <div class="flex min-w-0 items-center gap-2.5 px-1 group-data-[collapsible=icon]:justify-center group-data-[collapsible=icon]:px-0">
      <span class="brand-mark flex h-9 w-9 shrink-0 items-center justify-center rounded-xl text-[16px] font-bold text-white">L</span>
      <span class="min-w-0 flex-1 group-data-[collapsible=icon]:hidden">
        <p class="brand-title text-[16px] leading-5">LearnTrace</p>
      </span>
    </div>
  </SidebarHeader>

  <SidebarContent class="px-1">
    <!-- 内容导航 -->
    <SidebarGroup>
      <SidebarGroupContent>
        <SidebarMenu class="gap-1">
          <SidebarMenuItem v-for="item in items" :key="item.path">
            <SidebarMenuButton
              as-child
              :is-active="isActive(item.path)"
              :tooltip="item.label"
              class="h-10 text-[14px] ring-primary/30 hover:bg-primary-soft/60 hover:text-ink data-[active=true]:bg-primary-soft data-[active=true]:text-primary data-[active=true]:ring-2"
            >
              <RouterLink :to="item.path" @click="onNavigate">
                <component :is="item.icon" />
                <span>{{ item.label }}</span>
                <!-- 复习到期红点：挂在「首页」项（今日复习卡片所在页） -->
                <span
                  v-if="item.path === '/' && (props.dueCount ?? 0) > 0"
                  class="ml-auto h-2 w-2 shrink-0 rounded-full bg-red-500"
                  :title="`${props.dueCount} 张卡片待复习`"
                />
              </RouterLink>
            </SidebarMenuButton>
          </SidebarMenuItem>
        </SidebarMenu>
      </SidebarGroupContent>
    </SidebarGroup>

    <!-- 我的 -->
    <SidebarGroup class="border-t border-sidebar-border pt-2">
      <SidebarGroupLabel class="text-[11px] text-muted-foreground">我的</SidebarGroupLabel>
      <SidebarGroupContent>
        <SidebarMenu class="gap-1">
          <SidebarMenuItem>
            <SidebarMenuButton
              tooltip="设置"
              class="h-10 text-[14px] hover:bg-primary-soft/60 hover:text-ink"
              @click="onOpenProfile"
            >
              <Settings />
              <span>设置</span>
            </SidebarMenuButton>
          </SidebarMenuItem>
        </SidebarMenu>
      </SidebarGroupContent>
    </SidebarGroup>
  </SidebarContent>

  <!-- 底部用户卡片（sidebar-07 风格）：点击打开个人页面；icon 折叠时仅显示头像 -->
  <SidebarFooter v-if="props.user">
    <SidebarMenu>
      <SidebarMenuItem>
        <SidebarMenuButton
          size="lg"
          :tooltip="displayName"
          class="data-[state=open]:bg-sidebar-accent"
          @click="onOpenProfile"
        >
          <img
            v-if="props.user.avatarUrl"
            :src="props.user.avatarUrl"
            :alt="displayName"
            class="size-8 shrink-0 rounded-lg object-cover"
          >
          <span
            v-else
            class="flex size-8 shrink-0 items-center justify-center rounded-lg bg-primary text-[13px] font-semibold text-primary-foreground"
          >{{ initial }}</span>
          <span class="grid min-w-0 flex-1 text-left leading-tight group-data-[collapsible=icon]:hidden">
            <span class="truncate text-[13px] font-semibold">{{ displayName }}</span>
            <span class="truncate text-[11px] text-muted-foreground">{{ props.user.username }}</span>
          </span>
          <ChevronsUpDown class="ml-auto size-4 shrink-0 text-muted-foreground group-data-[collapsible=icon]:hidden" />
        </SidebarMenuButton>
      </SidebarMenuItem>
    </SidebarMenu>
  </SidebarFooter>
</template>

<style scoped>
/* 品牌艺术字：手写体 + 渐变填充（系统字体栈，不依赖外部字体源） */
.brand-title {
  font-family: 'Segoe Script', 'Brush Script MT', 'Lucida Handwriting', 'Savoye LET', cursive;
  font-weight: 700;
  letter-spacing: 0.5px;
  background: linear-gradient(90deg, #2563eb 0%, #6366f1 55%, #a855f7 100%);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}

/* 品牌 mark：与艺术字同款渐变的圆角方块 */
.brand-mark {
  background: linear-gradient(135deg, #2563eb 0%, #6366f1 55%, #a855f7 100%);
}
</style>
