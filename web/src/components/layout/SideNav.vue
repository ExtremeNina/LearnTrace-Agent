<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { Home, MonitorPlay, NotebookPen, Camera, Settings } from 'lucide-vue-next'
import {
  SidebarContent, SidebarGroup, SidebarGroupContent, SidebarGroupLabel,
  SidebarHeader, SidebarMenu, SidebarMenuButton, SidebarMenuItem, useSidebar,
} from '@/components/ui/sidebar'

/**
 * 主导航侧栏（B25 导航重构，shadcn-vue Sidebar 体系）：品牌区 + 内容导航 + 我的（设置）。
 * 折叠 / 移动端抽屉行为由外层 Sidebar 组件托管；点击导航后在移动端自动收起抽屉。
 */
const emit = defineEmits<{ navigate: []; 'open-profile': [] }>()

/** 今日到期复习卡数（>0 时「首页」项显示红点角标，B28 顶栏铃铛迁移至此） */
const props = defineProps<{ dueCount?: number }>()

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
  <!-- 品牌区 -->
  <SidebarHeader class="px-2 pb-1 pt-3">
    <p class="brand-title px-3 text-[20px] leading-6">LearnTrace</p>
    <p class="mt-0.5 px-3 text-[11px] text-muted-foreground">让学习更有轨迹</p>
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
              class="h-10 text-[14px] data-[active=true]:text-primary"
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
              class="h-10 text-[14px]"
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
</style>
