import { defineStore } from 'pinia'
import { ref } from 'vue'
import * as courseApi from '../api/course'
import { useAuthStore } from './auth'
import { useUserStore } from './user'

export interface ToastItem {
  id: number
  type: 'success' | 'error'
  text: string
}

/**
 * 全局轻提示（右上角堆叠，自动消失）+ 网课任务状态轮询：
 * 任务从处理中转为成功 / 失败时按用户偏好推送通知
 */
export const useToastStore = defineStore('toast', () => {
  const toasts = ref<ToastItem[]>([])
  let seq = 0
  let pollTimer: number | null = null
  /** 上一轮任务状态快照（courseId -> status），首轮只建立基线不通知 */
  const lastStatus = new Map<number, string>()

  function push(text: string, type: ToastItem['type'] = 'success') {
    const id = ++seq
    toasts.value.push({ id, type, text })
    setTimeout(() => dismiss(id), 6000)
  }

  function dismiss(id: number) {
    toasts.value = toasts.value.filter((t) => t.id !== id)
  }

  async function checkTaskStatus() {
    const auth = useAuthStore()
    const user = useUserStore()
    if (!auth.isLoggedIn || user.profile?.notifyTaskEnabled === false) {
      return
    }
    try {
      const list = await courseApi.listCourses()
      for (const c of list) {
        const prev = lastStatus.get(c.id)
        lastStatus.set(c.id, c.status)
        if (!prev || prev === c.status) {
          continue
        }
        if (c.status === 'SUCCESS') {
          push(`网课《${c.title}》处理完成，可以去看了`)
        } else if (c.status === 'FAILED') {
          push(`网课《${c.title}》处理失败：${c.errorMsg ?? '未知原因'}`, 'error')
        }
      }
    } catch {
      // 轮询失败静默，下轮重试
    }
  }

  /** 登录后启动任务状态轮询（30 秒一轮，首轮仅建立基线） */
  function startTaskWatcher() {
    if (pollTimer !== null) {
      return
    }
    lastStatus.clear()
    pollTimer = window.setInterval(checkTaskStatus, 30000)
    checkTaskStatus()
  }

  /** 退出登录 / 离开应用时停止轮询并清空基线 */
  function stopTaskWatcher() {
    if (pollTimer !== null) {
      window.clearInterval(pollTimer)
      pollTimer = null
    }
    lastStatus.clear()
  }

  return { toasts, push, dismiss, startTaskWatcher, stopTaskWatcher }
})
