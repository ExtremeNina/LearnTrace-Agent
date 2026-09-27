import { defineStore } from 'pinia'
import { ref } from 'vue'
import * as conversationApi from '../api/conversation'
import { uploadImage } from '../api/upload'
import type { ConversationInfo } from '../types/api'
import type { ServerMessage } from '../types/ws'
import * as agentSocket from '../ws/agentSocket'
import { useAuthStore } from './auth'

/**
 * 对话区一条消息（streaming = 助手回复生成中；imageUrl = 用户消息附图）
 */
export interface ChatMsg {
  id?: number
  role: 'user' | 'assistant'
  content: string
  imageUrl?: string
  streaming?: boolean
  /** 解答类回答（跟在带图消息后），底部展示"保存到拍照记录"引导 */
  fromQuestion?: boolean
}

export const useAgentStore = defineStore('agent', () => {
  /** 本地记住当前会话：刷新页面后恢复到同一会话 */
  const ACTIVE_KEY = 'xj_active_conversation'

  const conversations = ref<ConversationInfo[]>([])
  const activeId = ref<number | null>(null)
  const messages = ref<ChatMsg[]>([])
  const streaming = ref(false)
  const uploading = ref(false)
  /** 输入框上方待发送的图片（已上传到 OSS 的 URL），对应截图的预览位 */
  const pendingImage = ref('')
  const error = ref('')

  function rememberActive(id: number | null) {
    if (id === null) {
      localStorage.removeItem(ACTIVE_KEY)
    } else {
      localStorage.setItem(ACTIVE_KEY, String(id))
    }
  }

  async function loadConversations() {
    conversations.value = await conversationApi.listConversations()
  }

  async function createConversation(title?: string) {
    const conversation = await conversationApi.createConversation(title)
    conversations.value.unshift(conversation)
    activeId.value = conversation.id
    rememberActive(conversation.id)
    messages.value = []
    return conversation
  }

  async function openConversation(id: number) {
    const list = await conversationApi.listMessages(id)
    activeId.value = id
    rememberActive(id)
    let prevImageUrl: string | undefined
    messages.value = list.map((m): ChatMsg => {
      const role = m.role as 'user' | 'assistant'
      const item: ChatMsg = {
        id: m.id,
        role,
        content: m.content,
        imageUrl: parsePayloadImageUrl(m.payload),
      }
      if (role === 'user') {
        prevImageUrl = item.imageUrl
      } else if (prevImageUrl) {
        // 历史消息：跟在带图消息后的助手回复视为解答类回答
        item.fromQuestion = true
        prevImageUrl = undefined
      }
      return item
    })
  }

  function parsePayloadImageUrl(payload: string | null): string | undefined {
    if (!payload) {
      return undefined
    }
    try {
      const obj = JSON.parse(payload)
      return typeof obj.imageUrl === 'string' ? obj.imageUrl : undefined
    } catch {
      return undefined
    }
  }

  async function removeConversation(id: number) {
    await conversationApi.deleteConversation(id)
    conversations.value = conversations.value.filter((c) => c.id !== id)
    if (activeId.value === id) {
      activeId.value = null
      rememberActive(null)
      messages.value = []
    }
  }

  /**
   * 开启新对话：重置会话状态，下一条消息会创建新会话
   */
  function startNew() {
    activeId.value = null
    rememberActive(null)
    messages.value = []
    error.value = ''
    pendingImage.value = ''
  }

  /**
   * 刷新后恢复上次会话：本地记录的会话仍存在则重新加载，否则静默回到新对话
   */
  async function restoreLastConversation() {
    const saved = localStorage.getItem(ACTIVE_KEY)
    if (!saved) {
      return
    }
    try {
      await openConversation(Number(saved))
    } catch {
      rememberActive(null)
    }
  }

  /**
   * 上传图片（+ 按钮），成功后进入待发送预览位
   */
  async function uploadPendingImage(file: File) {
    uploading.value = true
    error.value = ''
    try {
      pendingImage.value = await uploadImage(file)
    } catch (e) {
      error.value = e instanceof Error ? e.message : '图片上传失败'
    } finally {
      uploading.value = false
    }
  }

  function clearPendingImage() {
    pendingImage.value = ''
  }

  /**
   * 发送一条用户消息：无活动会话时先创建；经 WS 发起回合（可附图）
   */
  async function send(text: string) {
    if (streaming.value) {
      return
    }
    error.value = ''
    if (activeId.value === null) {
      await createConversation()
    }
    const imageUrl = pendingImage.value || undefined
    messages.value.push({ role: 'user', content: text, imageUrl })
    messages.value.push({ role: 'assistant', content: '', streaming: true, fromQuestion: !!imageUrl })
    streaming.value = true
    pendingImage.value = ''
    agentSocket.sendMessage({
      type: 'chat.send',
      conversationId: activeId.value!,
      content: text,
      imageUrl,
    })
  }

  function stop() {
    if (activeId.value !== null) {
      agentSocket.sendMessage({ type: 'chat.stop', conversationId: activeId.value })
    }
  }

  /**
   * WS 事件分发：DELTA 追加到流式占位气泡，COMPLETE 落定，ERROR 提示
   */
  function handleEvent(msg: ServerMessage) {
    const placeholder = messages.value[messages.value.length - 1]
    switch (msg.type) {
      case 'DELTA': {
        if (placeholder && placeholder.streaming) {
          placeholder.content += msg.text ?? ''
        }
        break
      }
      case 'COMPLETE': {
        if (placeholder && placeholder.streaming) {
          placeholder.id = msg.messageId
          placeholder.streaming = false
        }
        streaming.value = false
        loadConversations()
        break
      }
      case 'ERROR': {
        if (placeholder && placeholder.streaming) {
          placeholder.streaming = false
          placeholder.content = placeholder.content || `出错了：${msg.message ?? '未知错误'}`
        }
        streaming.value = false
        error.value = msg.message ?? '生成失败'
        break
      }
      case 'STOP': {
        if (placeholder && placeholder.streaming) {
          placeholder.streaming = false
        }
        streaming.value = false
        break
      }
    }
  }

  function ensureSocketConnected() {
    const auth = useAuthStore()
    if (auth.isLoggedIn) {
      agentSocket.connect(auth.token, handleEvent)
    }
  }

  return {
    conversations,
    activeId,
    messages,
    streaming,
    uploading,
    pendingImage,
    error,
    loadConversations,
    createConversation,
    openConversation,
    removeConversation,
    startNew,
    restoreLastConversation,
    uploadPendingImage,
    clearPendingImage,
    send,
    stop,
    handleEvent,
    ensureSocketConnected,
  }
})
