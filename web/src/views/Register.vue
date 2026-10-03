<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const auth = useAuthStore()

const username = ref('')
const password = ref('')
const confirmPassword = ref('')
const error = ref('')
const loading = ref(false)

async function onSubmit() {
  if (!username.value || !password.value) {
    error.value = '请输入用户名和密码'
    return
  }
  if (password.value.length < 6) {
    error.value = '密码长度需在 6~64 位之间'
    return
  }
  if (password.value !== confirmPassword.value) {
    error.value = '两次输入的密码不一致'
    return
  }
  error.value = ''
  loading.value = true
  try {
    await auth.register(username.value, password.value)
    // 注册成功后自动登录
    await auth.login(username.value, password.value)
    await router.push('/')
  } catch (e) {
    error.value = e instanceof Error ? e.message : '注册失败，请稍后重试'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="flex h-full items-center justify-center bg-panel px-4">
    <div class="w-full max-w-sm rounded-3xl border border-line bg-surface p-8">
      <h1 class="text-center text-[22px] font-semibold">创建账号</h1>
      <p class="mt-1 text-center text-[12px] text-ink-2">开始记录你的学习轨迹</p>

      <form class="mt-6 flex flex-col gap-4" @submit.prevent="onSubmit">
        <div>
          <label class="mb-1 block text-[12px] text-ink-2" for="username">用户名</label>
          <input
            id="username"
            v-model="username"
            type="text"
            autocomplete="username"
            class="w-full rounded-xl border border-line px-3 py-2 text-[15px] outline-none focus:border-primary"
            placeholder="2~32 位用户名"
          />
        </div>
        <div>
          <label class="mb-1 block text-[12px] text-ink-2" for="password">密码</label>
          <input
            id="password"
            v-model="password"
            type="password"
            autocomplete="new-password"
            class="w-full rounded-xl border border-line px-3 py-2 text-[15px] outline-none focus:border-primary"
            placeholder="至少 6 位密码"
          />
        </div>
        <div>
          <label class="mb-1 block text-[12px] text-ink-2" for="confirm">确认密码</label>
          <input
            id="confirm"
            v-model="confirmPassword"
            type="password"
            autocomplete="new-password"
            class="w-full rounded-xl border border-line px-3 py-2 text-[15px] outline-none focus:border-primary"
            placeholder="再次输入密码"
          />
        </div>

        <p v-if="error" class="text-[12px] text-red-600">{{ error }}</p>

        <button
          type="submit"
          :disabled="loading"
          class="rounded-xl bg-primary py-2.5 text-[15px] text-white hover:opacity-90 disabled:opacity-50"
        >
          {{ loading ? '注册中…' : '注册' }}
        </button>
      </form>

      <p class="mt-4 text-center text-[12px] text-ink-2">
        已有账号？
        <RouterLink to="/login" class="text-primary hover:underline">登录</RouterLink>
      </p>
    </div>
  </div>
</template>
