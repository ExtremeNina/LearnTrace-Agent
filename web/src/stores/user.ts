import { defineStore } from 'pinia'
import { ref } from 'vue'
import * as userApi from '../api/user'
import type { UserProfileInfo } from '../types/api'

const THEME_KEY = 'xj_theme'

export const useUserStore = defineStore('user', () => {
  const profile = ref<UserProfileInfo | null>(null)
  /** 本地优先取上次选择，登录后与服务端偏好同步（避免刷新闪白） */
  const theme = ref<'LIGHT' | 'DARK'>((localStorage.getItem(THEME_KEY) as 'LIGHT' | 'DARK') || 'LIGHT')

  function applyTheme(value: 'LIGHT' | 'DARK') {
    theme.value = value
    localStorage.setItem(THEME_KEY, value)
    document.documentElement.classList.toggle('dark', value === 'DARK')
  }

  // store 首次实例化即应用主题（main.ts 装配期，先于首帧渲染）
  applyTheme(theme.value)

  /**
   * 拉取当前用户资料（force = 编辑 / 偏好变更后强刷）
   */
  async function loadMe(force = false): Promise<UserProfileInfo | null> {
    if (profile.value && !force) {
      return profile.value
    }
    try {
      profile.value = await userApi.getMe()
      applyTheme(profile.value.theme)
    } catch {
      // 未登录 / 网络异常：保持现状，弹窗层自行提示
    }
    return profile.value
  }

  async function saveProfile(data: {
    nickname?: string
    email?: string
    bio?: string
    avatarUrl?: string
  }): Promise<UserProfileInfo> {
    profile.value = await userApi.updateMe(data)
    return profile.value
  }

  async function setTheme(value: 'LIGHT' | 'DARK') {
    profile.value = await userApi.updatePreferences({ theme: value })
    applyTheme(profile.value.theme)
  }

  async function setNotifyTaskEnabled(value: boolean) {
    profile.value = await userApi.updatePreferences({ notifyTaskEnabled: value })
  }

  function clearProfile() {
    profile.value = null
  }

  return { profile, theme, loadMe, saveProfile, setTheme, setNotifyTaskEnabled, applyTheme, clearProfile }
})
