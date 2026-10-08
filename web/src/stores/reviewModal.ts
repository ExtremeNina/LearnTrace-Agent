import { defineStore } from 'pinia'
import { ref } from 'vue'

/**
 * 今日复习弹窗（复习由独立 /review 页改为全局弹窗）：
 * 挂载于 MainLayout，任意入口（首页复习区块等）经 open() 唤起；
 * 关闭时由监听 isOpen 的页面（如首页）刷新待复习计数。
 */
export const useReviewModalStore = defineStore('reviewModal', () => {
  const isOpen = ref(false)

  function open() {
    isOpen.value = true
  }

  function close() {
    isOpen.value = false
  }

  return { isOpen, open, close }
})
