<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Camera, ChevronDown, ChevronUp, LoaderCircle, LogOut, Moon, Sun, Trash2 } from 'lucide-vue-next'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogTitle } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import * as userApi from '../api/user'
import { uploadImage } from '../api/upload'
import { logout as logoutApi } from '../api/auth'
import { useAuthStore } from '../stores/auth'
import { useUserStore } from '../stores/user'
import { useToastStore } from '../stores/toast'

/**
 * 个人页面弹窗：资料（头像/昵称/邮箱/简介）、偏好（主题/任务通知）、
 * 账号（修改密码 / 退出登录 / 注销账号）
 */
const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{ (e: 'update:open', value: boolean): void }>()

const auth = useAuthStore()
const userStore = useUserStore()
const toast = useToastStore()
const router = useRouter()

const savingProfile = ref(false)
const profileError = ref('')
const avatarUploading = ref(false)
const avatarInput = ref<HTMLInputElement | null>(null)
const form = reactive({ nickname: '', email: '', bio: '', avatarUrl: '' })

// 偏好开关由 store 直接持久化，弹窗内即时生效
const darkMode = computed(() => userStore.theme === 'DARK')
const notifyEnabled = computed(() => userStore.profile?.notifyTaskEnabled !== false)

// 修改密码
const pwdOpen = ref(false)
const pwdForm = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })
const pwdSaving = ref(false)
const pwdError = ref('')

// 注销账号
const deleteOpen = ref(false)
const deletePassword = ref('')
const deleteConfirmText = ref('')
const deleteSubmitting = ref(false)
const deleteError = ref('')

watch(
  () => props.open,
  async (open) => {
    if (!open) {
      return
    }
    profileError.value = ''
    pwdError.value = ''
    deleteError.value = ''
    const profile = await userStore.loadMe(true)
    form.nickname = profile?.nickname ?? ''
    form.email = profile?.email ?? ''
    form.bio = profile?.bio ?? ''
    form.avatarUrl = profile?.avatarUrl ?? ''
  }
)

function close() {
  emit('update:open', false)
  pwdOpen.value = false
  deleteOpen.value = false
}

/** Dialog 打开态回写父组件（ESC / 点遮罩 / 关闭按钮统一走这里） */
function onOpenChange(value: boolean) {
  if (!value) {
    close()
  }
}

async function onAvatarChange(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) {
    return
  }
  avatarUploading.value = true
  profileError.value = ''
  try {
    form.avatarUrl = await uploadImage(file)
  } catch (err) {
    profileError.value = err instanceof Error ? err.message : '头像上传失败'
  } finally {
    avatarUploading.value = false
  }
}

async function saveProfile() {
  if (!form.nickname.trim()) {
    profileError.value = '昵称不能为空'
    return
  }
  savingProfile.value = true
  profileError.value = ''
  try {
    const vo = await userStore.saveProfile({
      nickname: form.nickname,
      email: form.email,
      bio: form.bio,
      avatarUrl: form.avatarUrl,
    })
    // 同步弹层（IconRail）展示的昵称与头像
    if (auth.user) {
      auth.user.nickname = vo.nickname
      auth.user.avatarUrl = vo.avatarUrl
    }
    toast.push('资料已保存')
  } catch (err) {
    profileError.value = err instanceof Error ? err.message : '保存失败，请稍后重试'
  } finally {
    savingProfile.value = false
  }
}

async function toggleTheme() {
  try {
    await userStore.setTheme(darkMode.value ? 'LIGHT' : 'DARK')
  } catch (err) {
    toast.push(err instanceof Error ? err.message : '主题切换失败', 'error')
  }
}

async function toggleNotify() {
  try {
    await userStore.setNotifyTaskEnabled(!notifyEnabled.value)
  } catch (err) {
    toast.push(err instanceof Error ? err.message : '通知设置失败', 'error')
  }
}


async function savePassword() {
  if (!pwdForm.oldPassword || !pwdForm.newPassword) {
    pwdError.value = '请填写原密码与新密码'
    return
  }
  if (pwdForm.newPassword.length < 6) {
    pwdError.value = '新密码至少 6 位'
    return
  }
  if (pwdForm.newPassword !== pwdForm.confirmPassword) {
    pwdError.value = '两次输入的新密码不一致'
    return
  }
  pwdSaving.value = true
  pwdError.value = ''
  try {
    await userApi.changePassword({ oldPassword: pwdForm.oldPassword, newPassword: pwdForm.newPassword })
    toast.push('密码已修改')
    pwdOpen.value = false
    pwdForm.oldPassword = ''
    pwdForm.newPassword = ''
    pwdForm.confirmPassword = ''
  } catch (err) {
    pwdError.value = err instanceof Error ? err.message : '修改失败，请稍后重试'
  } finally {
    pwdSaving.value = false
  }
}

async function doLogout() {
  try {
    await logoutApi()
  } catch {
    // 服务端登出失败不阻断本地清理
  }
  finishExit()
}

async function doDeleteAccount() {
  if (!deletePassword.value) {
    deleteError.value = '请输入密码确认注销'
    return
  }
  if (deleteConfirmText.value !== '注销') {
    deleteError.value = '请输入「注销」确认'
    return
  }
  deleteSubmitting.value = true
  deleteError.value = ''
  try {
    await userApi.deleteAccount(deletePassword.value)
    finishExit()
  } catch (err) {
    deleteError.value = err instanceof Error ? err.message : '注销失败，请稍后重试'
  } finally {
    deleteSubmitting.value = false
  }
}

function finishExit() {
  toast.stopTaskWatcher()
  auth.logout()
  userStore.clearProfile()
  userStore.applyTheme('LIGHT')
  close()
  router.push('/login')
}

const initial = computed(() => (form.nickname || auth.user?.username || '?').slice(0, 1).toUpperCase())
</script>

<template>
  <Dialog :open="open" @update:open="onOpenChange">
    <DialogContent
      class="z-[70] block max-h-[85vh] w-full max-w-2xl gap-0 overflow-y-auto rounded-3xl border-line bg-surface p-6 shadow-xl sm:max-w-2xl"
    >
      <DialogTitle class="text-[16px] font-semibold text-ink">个人设置</DialogTitle>
      <DialogDescription class="sr-only">修改个人资料、偏好设置与账号安全选项</DialogDescription>

      <!-- 个人资料 -->
      <h3 class="mt-5 text-[13px] font-semibold text-ink-2">个人资料</h3>
      <div class="mt-3 flex items-center gap-4">
        <div class="relative h-16 w-16 shrink-0">
          <img
            v-if="form.avatarUrl"
            :src="form.avatarUrl"
            alt="头像"
            class="h-16 w-16 rounded-2xl border border-line object-cover"
          />
          <div
            v-else
            class="flex h-16 w-16 items-center justify-center rounded-2xl bg-primary-soft text-[22px] font-semibold text-primary"
          >
            {{ initial }}
          </div>
          <button
            class="absolute -bottom-1 -right-1 flex h-7 w-7 items-center justify-center rounded-full border border-line bg-surface text-ink-2 shadow-sm hover:text-primary"
            title="更换头像"
            :disabled="avatarUploading"
            @click="avatarInput?.click()"
          >
            <LoaderCircle v-if="avatarUploading" :size="13" class="animate-spin" />
            <Camera v-else :size="13" />
          </button>
          <input ref="avatarInput" type="file" accept="image/*" class="hidden" @change="onAvatarChange" />
        </div>
        <p class="text-[12px] leading-5 text-ink-2">
          支持上传头像图片；<br />未上传时显示昵称首字。
        </p>
      </div>

      <div class="mt-4 flex flex-col gap-3">
        <label class="block">
          <span class="mb-1 block text-[12px] text-ink-2">昵称</span>
          <Input v-model="form.nickname" class="rounded-xl text-[14px]" placeholder="你的昵称" />
        </label>
        <label class="block">
          <span class="mb-1 block text-[12px] text-ink-2">邮箱（选填）</span>
          <Input v-model="form.email" type="email" class="rounded-xl text-[14px]" placeholder="you@example.com" />
        </label>
        <label class="block">
          <span class="mb-1 block text-[12px] text-ink-2">个人简介（选填）</span>
          <Textarea
            v-model="form.bio"
            :rows="3"
            class="resize-none rounded-xl text-[14px]"
            placeholder="介绍一下自己，比如正在学什么"
          />
        </label>
      </div>
      <p v-if="profileError" class="mt-2 text-[12px] text-red-600">{{ profileError }}</p>
      <Button class="mt-3 rounded-xl text-[14px]" :disabled="savingProfile" @click="saveProfile">
        {{ savingProfile ? '保存中…' : '保存资料' }}
      </Button>

      <!-- 偏好设置 -->
      <h3 class="mt-7 text-[13px] font-semibold text-ink-2">偏好设置</h3>
      <div class="mt-3 flex flex-col divide-y divide-line rounded-2xl border border-line">
        <div class="flex items-center justify-between px-4 py-3">
          <span class="flex items-center gap-2.5 text-[14px] text-ink">
            <Sun v-if="!darkMode" :size="15" class="text-ink-2" />
            <Moon v-else :size="15" class="text-ink-2" />
            {{ darkMode ? '深色主题' : '浅色主题' }}
          </span>
          <button
            class="relative h-6 w-11 rounded-full transition-colors"
            :class="darkMode ? 'bg-primary' : 'bg-line'"
            title="浅色 / 深色切换"
            @click="toggleTheme"
          >
            <span
              class="absolute top-0.5 h-5 w-5 rounded-full bg-white shadow transition-all"
              :class="darkMode ? 'left-[22px]' : 'left-0.5'"
            ></span>
          </button>
        </div>
        <div class="flex items-center justify-between px-4 py-3">
          <span class="text-[14px] text-ink">任务通知</span>
          <button
            class="relative h-6 w-11 rounded-full transition-colors"
            :class="notifyEnabled ? 'bg-primary' : 'bg-line'"
            title="任务完成 / 失败时通知我"
            @click="toggleNotify"
          >
            <span
              class="absolute top-0.5 h-5 w-5 rounded-full bg-white shadow transition-all"
              :class="notifyEnabled ? 'left-[22px]' : 'left-0.5'"
            ></span>
          </button>
        </div>
        <p class="px-4 pb-3 pt-1 text-[12px] text-ink-2">开启后，网课转写与笔记生成任务完成或失败时会在右上角提醒你</p>
      </div>

      <!-- 账号 -->
      <h3 class="mt-7 text-[13px] font-semibold text-ink-2">账号</h3>
      <div class="mt-3 flex flex-col divide-y divide-line rounded-2xl border border-line">
        <!-- 修改密码 -->
        <div class="px-4 py-1">
          <button
            class="flex w-full items-center justify-between py-2.5 text-[14px] text-ink"
            @click="pwdOpen = !pwdOpen"
          >
            修改密码
            <ChevronUp v-if="pwdOpen" :size="15" class="text-ink-2" />
            <ChevronDown v-else :size="15" class="text-ink-2" />
          </button>
          <div v-if="pwdOpen" class="flex flex-col gap-2.5 pb-3">
            <Input v-model="pwdForm.oldPassword" type="password" class="rounded-xl text-[14px]" placeholder="原密码" />
            <Input
              v-model="pwdForm.newPassword"
              type="password"
              class="rounded-xl text-[14px]"
              placeholder="新密码（至少 6 位）"
            />
            <Input
              v-model="pwdForm.confirmPassword"
              type="password"
              class="rounded-xl text-[14px]"
              placeholder="确认新密码"
            />
            <p v-if="pwdError" class="text-[12px] text-red-600">{{ pwdError }}</p>
            <Button size="sm" class="self-start rounded-xl text-[13px]" :disabled="pwdSaving" @click="savePassword">
              {{ pwdSaving ? '保存中…' : '确认修改' }}
            </Button>
          </div>
        </div>
        <!-- 退出登录 -->
        <button
          class="flex items-center justify-between px-4 py-3 text-[14px] text-ink hover:bg-panel"
          @click="doLogout"
        >
          退出登录
          <LogOut :size="15" class="text-ink-2" />
        </button>
        <!-- 注销账号 -->
        <div class="px-4 py-1">
          <button
            class="flex w-full items-center justify-between py-2.5 text-[14px] text-red-500"
            @click="deleteOpen = !deleteOpen"
          >
            <span class="flex items-center gap-2.5">
              <Trash2 :size="15" />
              删除账号
            </span>
            <ChevronUp v-if="deleteOpen" :size="15" class="text-ink-2" />
            <ChevronDown v-else :size="15" class="text-ink-2" />
          </button>
          <div v-if="deleteOpen" class="flex flex-col gap-2.5 pb-3">
            <p class="text-[12px] leading-5 text-ink-2">注销后无法再登录本账号，学习资料仍保留在系统中。此操作不可恢复，请谨慎。</p>
            <Input
              v-model="deletePassword"
              type="password"
              class="rounded-xl text-[14px] focus-visible:ring-red-400"
              placeholder="输入登录密码确认"
            />
            <Input
              v-model="deleteConfirmText"
              class="rounded-xl text-[14px] focus-visible:ring-red-400"
              placeholder="输入「注销」确认"
            />
            <p v-if="deleteError" class="text-[12px] text-red-600">{{ deleteError }}</p>
            <Button
              variant="destructive"
              size="sm"
              class="self-start rounded-xl text-[13px]"
              :disabled="deleteSubmitting"
              @click="doDeleteAccount"
            >
              {{ deleteSubmitting ? '注销中…' : '确认注销账号' }}
            </Button>
          </div>
        </div>
      </div>
    </DialogContent>
  </Dialog>
</template>
