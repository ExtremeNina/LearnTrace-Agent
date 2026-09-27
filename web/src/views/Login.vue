<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const auth = useAuthStore()

const username = ref('')
const password = ref('')
const error = ref('')
const loading = ref(false)

async function onSubmit() {
  if (!username.value || !password.value) {
    error.value = '请输入用户名和密码'
    return
  }
  error.value = ''
  loading.value = true
  try {
    await auth.login(username.value, password.value)
    await router.push('/')
  } catch (e) {
    error.value = e instanceof Error ? e.message : '登录失败，请稍后重试'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="flex h-full items-center justify-center bg-panel px-4">
    <div class="w-full max-w-sm rounded-3xl border border-line bg-white p-8">
      <h1 class="text-center text-[22px] font-semibold">登录学迹</h1>
      <p class="mt-1 text-center text-[12px] text-ink-2">记录你与知识发生过什么</p>

      <form class="mt-6 flex flex-col gap-4" @submit.prevent="onSubmit">
        <div>
          <label class="mb-1 block text-[12px] text-ink-2" for="username">用户名</label>
          <input
            id="username"
            v-model="username"
            type="text"
            autocomplete="username"
            class="w-full rounded-xl border border-line px-3 py-2 text-[15px] outline-none focus:border-primary"
            placeholder="输入用户名"
          />
        </div>
        <div>
          <label class="mb-1 block text-[12px] text-ink-2" for="password">密码</label>
          <input
            id="password"
            v-model="password"
            type="password"
            autocomplete="current-password"
            class="w-full rounded-xl border border-line px-3 py-2 text-[15px] outline-none focus:border-primary"
            placeholder="输入密码"
          />
        </div>

        <p v-if="error" class="text-[12px] text-red-600">{{ error }}</p>

        <button
          type="submit"
          :disabled="loading"
          class="rounded-xl bg-primary py-2.5 text-[15px] text-white hover:opacity-90 disabled:opacity-50"
        >
          {{ loading ? '登录中…' : '登录' }}
        </button>
      </form>

      <p class="mt-4 text-center text-[12px] text-ink-2">
        还没有账号？
        <RouterLink to="/register" class="text-primary hover:underline">注册</RouterLink>
      </p>
    </div>
  </div>
</template>
