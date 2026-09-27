import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import * as authApi from '../api/auth'

const TOKEN_KEY = 'xj_token'

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string>(localStorage.getItem(TOKEN_KEY) ?? '')
  const isLoggedIn = computed(() => token.value !== '')

  async function login(username: string, password: string) {
    const info = await authApi.login(username, password)
    token.value = info.tokenValue
    localStorage.setItem(TOKEN_KEY, info.tokenValue)
  }

  async function register(username: string, password: string) {
    await authApi.register(username, password)
  }

  function setToken(newToken: string) {
    token.value = newToken
    localStorage.setItem(TOKEN_KEY, newToken)
  }

  function logout() {
    token.value = ''
    localStorage.removeItem(TOKEN_KEY)
  }

  return { token, isLoggedIn, login, register, setToken, logout }
})
