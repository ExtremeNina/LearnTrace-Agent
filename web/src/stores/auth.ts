import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import * as authApi from '../api/auth'
import type { UserInfo } from '../types/api'

const TOKEN_KEY = 'xj_token'

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string>(localStorage.getItem(TOKEN_KEY) ?? '')
  const user = ref<UserInfo | null>(null)
  const isLoggedIn = computed(() => token.value !== '')

  async function login(username: string, password: string) {
    const info = await authApi.login(username, password)
    token.value = info.tokenValue
    localStorage.setItem(TOKEN_KEY, info.tokenValue)
  }

  async function register(username: string, password: string) {
    await authApi.register(username, password)
  }

  /**
   * 拉取当前用户信息（已加载则跳过）
   */
  async function loadUser() {
    if (user.value !== null) {
      return
    }
    user.value = await authApi.getInfo()
  }

  function setToken(newToken: string) {
    token.value = newToken
    localStorage.setItem(TOKEN_KEY, newToken)
  }

  function logout() {
    token.value = ''
    user.value = null
    localStorage.removeItem(TOKEN_KEY)
  }

  return { token, user, isLoggedIn, login, register, loadUser, setToken, logout }
})
