import { defineStore } from 'pinia'
import { ref } from 'vue'
import * as conversationApi from '../api/conversation'
import type { ConversationInfo } from '../types/api'
import type { ServerMessage } from '../types/ws'
import * as agentSocket from '../ws/agentSocket'
import { useAuthStore } from './auth'

/**
 * 对话区一条消息（streaming = 助手回复生成中）
 */
export interface ChatMsg {
  id?: number
  role: 'user' | 'assistant'
  content: string
  streaming?: boolean
}

export const useAgentStore = defineStore('agent', () => {
  const conversations = ref<ConversationInfo[]>([])
  const activeId = ref<number | null>(null)
  const messages = ref<ChatMsg[]>([])
  const streaming = ref(false)
  const error = ref('')

  async function loadConversations() {
    conversations.value = await conversationApi.listConversations()
  }

  async function createConversation(title?: string) {
    const conversation = await conversationApi.createConversation(title)
    conversations.value.unshift(conversation)
    activeId.value = conversation.id
    messages.value = []
    return conversation
  }

  async function openConversation(id: number) {
    const list = await conversationApi.listMessages(id)
    activeId.value = id
    messages.value = list.map((m) => ({ id: m.id, role: m.role as 'user' | 'assistant', content: m.content }))
  }

  async function removeConversation(id: number) {
    await conversationApi.deleteConversation(id)
    conversations.value = conversations.value.filter((c) => c.id !== id)
    if (activeId.value === id) {
      activeId.value = null
      messages.value = []
    }
  }

  /**
   * 开启新对话：重置会话状态，下一条消息会创建新会话
   */
  function startNew() {
    activeId.value = null
    messages.value = []
    error.value = ''
  }

  /**
   * 发送一条用户消息：无活动会话时先创建；经 WS 发起回合
   */
  async function send(text: string) {
    if (streaming.value) {
      return
    }
    error.value = ''
    if (activeId.value === null) {
      await createConversation()
    }
    messages.value.push({ role: 'user', content: text })
    messages.value.push({ role: 'assistant', content: '', streaming: true })
    streaming.value = true
    agentSocket.sendMessage({ type: 'chat.send', conversationId: activeId.value!, content: text })
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
    error,
    loadConversations,
    createConversation,
    openConversation,
    removeConversation,
    startNew,
    send,
    stop,
    handleEvent,
    ensureSocketConnected,
  }
})
