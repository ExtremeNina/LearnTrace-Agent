import { defineStore } from 'pinia'
import { ref } from 'vue'
import * as conversationApi from '../api/conversation'
import { uploadChatVideo, uploadImage } from '../api/upload'
import { getTodayBriefing } from '../api/briefing'
import type { ConversationInfo } from '../types/api'
import type { ServerMessage } from '../types/ws'
import * as agentSocket from '../ws/agentSocket'
import { useAuthStore } from './auth'

/**
 * 对话区一条消息（streaming = 助手回复生成中；imageUrl = 用户消息附图；
 * video = 用户消息附视频；transcribe = 视频转写进度 / 结果，B11）
 */
export interface ChatMsg {
  id?: number
  role: 'user' | 'assistant'
  content: string
  imageUrl?: string
  streaming?: boolean
  /** 解答类回答（跟在带图消息后），底部展示"保存到题目管理"引导 */
  fromQuestion?: boolean
  /** 用户消息附带视频（B11） */
  video?: { durationSec?: number }
  /** 视频转写状态（assistant 的 video_transcript 消息） */
  transcribe?: { status: 'processing' | 'done' | 'failed'; done: number; total: number }
  /** 课程流水线任务状态（assistant 的 course_task 消息，B11 分流） */
  courseTask?: { status: 'processing' | 'done' | 'failed'; stage: string; text?: string; link?: string }
  /** 今日简报气泡（本地消息，不落库；每日首次打开首页时加载） */
  briefing?: boolean
}

export const useAgentStore = defineStore('agent', () => {
  /** 本地记住当前会话：刷新页面后恢复到同一会话 */
  const ACTIVE_KEY = 'xj_active_conversation'
  /** 今日简报缓存：{date, content}——刷新后恢复会话时重新注入气泡，当日始终显示 */
  const BRIEF_CACHE_KEY = 'xj_brief_cache'

  const conversations = ref<ConversationInfo[]>([])
  const activeId = ref<number | null>(null)
  const messages = ref<ChatMsg[]>([])
  const streaming = ref(false)
  const uploading = ref(false)
  /** 输入框上方待发送的图片（已上传到 OSS 的 URL），对应截图的预览位 */
  const pendingImage = ref('')
  /** 输入框上方待发送的视频（B11：已通过上传接口校验并暂存本地临时文件） */
  const pendingVideo = ref<{ tempPath: string; durationSec: number; name: string } | null>(null)
  const error = ref('')
  /** 跨页种子消息（如题目详情页「生成相似题」），Agent 页挂载时消费并自动发出 */
  const pendingSeed = ref<{ conversationId: number | null; content: string } | null>(null)

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
      const payload = parsePayload(m.payload)
      if (m.msgType === 'video') {
        // B11 视频消息：durationSec 入 payload，videoUrl 由转写任务回填（历史渲染仅需时长）
        item.video = { durationSec: typeof payload?.videoDurationSec === 'number' ? payload.videoDurationSec : undefined }
      }
      if (m.msgType === 'video_transcript' && payload?.status) {
        item.transcribe = {
          status: payload.status as 'processing' | 'done' | 'failed',
          done: typeof payload.done === 'number' ? payload.done : 0,
          total: typeof payload.total === 'number' ? payload.total : 0,
        }
      }
      if (m.msgType === 'course_task' && payload?.status) {
        item.courseTask = {
          status: payload.status as 'processing' | 'done' | 'failed',
          stage: typeof payload.stage === 'string' ? payload.stage : 'PENDING',
          link: typeof payload.link === 'string' ? payload.link : undefined,
        }
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
    const value = parsePayload(payload)?.imageUrl
    return typeof value === 'string' ? value : undefined
  }

  function parsePayload(payload: string | null): Record<string, unknown> | null {
    if (!payload) {
      return null
    }
    try {
      const obj = JSON.parse(payload)
      return typeof obj === 'object' && obj !== null ? obj : null
    } catch {
      return null
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
    pendingVideo.value = null
  }

  /**
   * 刷新后恢复上次会话：本地记录的会话仍存在则重新加载，否则静默回到新对话；
   * 恢复成功且当日简报未 dismiss 时，把缓存的简报气泡重新注入对话顶部
   */
  async function restoreLastConversation() {
    const saved = localStorage.getItem(ACTIVE_KEY)
    if (!saved) {
      return
    }
    try {
      await openConversation(Number(saved))
      injectCachedBriefing()
    } catch {
      rememberActive(null)
    }
  }

  function briefToday() {
    return new Date().toISOString().slice(0, 10)
  }

  function readBriefCache(): { date: string; content: string } | null {
    try {
      const raw = localStorage.getItem(BRIEF_CACHE_KEY)
      if (!raw) {
        return null
      }
      const obj = JSON.parse(raw)
      return obj?.date && obj?.content ? obj : null
    } catch {
      return null
    }
  }

  /**
   * 当日简报缓存存在且气泡未在列时注入对话顶部（当日始终显示，次日由新缓存替换）。
   * 供恢复会话 / 首页每日加载调用；返回是否已注入
   */
  function injectCachedBriefing() {
    const brief = readBriefCache()
    if (!brief || brief.date !== briefToday()) {
      return false
    }
    if (messages.value.some((m) => m.briefing)) {
      return true
    }
    messages.value.unshift({ id: -1, role: 'assistant', content: brief.content, briefing: true })
    return true
  }

  /**
   * 设置跨页种子消息（携带目标会话 ID，null 表示新开会话）
   */
  function setSeed(seed: { conversationId: number | null; content: string }) {
    pendingSeed.value = seed
  }

  /**
   * 消费种子消息：切换到目标会话（或新会话）后自动发出。
   * WS 未就绪时由 agentSocket 暂存，OPEN 后冲刷，不丢失
   */
  async function applySeed() {
    const seed = pendingSeed.value
    if (!seed) {
      return
    }
    pendingSeed.value = null
    if (seed.conversationId != null) {
      await openConversation(seed.conversationId)
    } else {
      startNew()
    }
    await send(seed.content)
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
   * 上传对话视频（B11）：服务端校验格式 / 大小 / 时长（>30min 秒级拒绝），
   * 本地临时文件暂存，随下一条 WS 消息进入转写链路
   */
  async function uploadPendingVideo(file: File) {
    uploading.value = true
    error.value = ''
    try {
      const info = await uploadChatVideo(file)
      pendingVideo.value = { tempPath: info.tempPath, durationSec: info.durationSec, name: file.name }
      pushUploadNotice(file.name, info.durationSec)
    } catch (e) {
      error.value = e instanceof Error ? e.message : '视频上传失败'
    } finally {
      uploading.value = false
    }
  }

  /** 上传成功 → 消息流插入本地提示气泡（不落库，切换会话自然清除；B26 反馈：上传反馈进气泡） */
  function pushUploadNotice(name: string, durationSec: number) {
    const m = Math.floor(durationSec / 60)
    const s = durationSec % 60
    messages.value.push({
      role: 'assistant',
      content: `**视频《${name}》（${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}）已上传完成**\n`
        + `发送后将按你的意图处理：默认转写语音；>30 分钟或「做成课程」自动走网课流水线。`,
    })
  }

  /**
   * 今日简报（B26 反馈：简报移入对话）——本地气泡三步：
   * showBriefingPlaceholder 插占位 → loadBriefing 完成后替换为 markdown 内容 → 失败由调用方 removeBriefingPlaceholder 静默移除
   */
  function showBriefingPlaceholder() {
    if (messages.value.some((m) => m.briefing)) {
      return
    }
    messages.value.push({
      role: 'assistant',
      content: '正在生成今日简报…',
      briefing: true,
    })
  }

  async function loadBriefing() {
    const briefing = await getTodayBriefing()
    const content = `**☀ 今日简报 · ${briefing.briefDate}**\n\n` + (briefing.content ?? '')
    const target = messages.value.find((m) => m.briefing)
    if (target) {
      target.content = content
    }
    // 成功后写当日缓存：刷新恢复会话时由 injectCachedBriefing 重新注入
    localStorage.setItem(BRIEF_CACHE_KEY, JSON.stringify({
      date: briefToday(),
      content,
    }))
  }

  function removeBriefingPlaceholder() {
    messages.value = messages.value.filter((m) => !m.briefing)
  }

  function clearPendingVideo() {
    pendingVideo.value = null
  }

  /**
   * 发送一条用户消息：无活动会话时先创建；经 WS 发起回合（可附图 / 附视频）
   */
  async function send(text: string, currentTimeSec?: number) {
    if (streaming.value) {
      return
    }
    error.value = ''
    if (activeId.value === null) {
      await createConversation()
    }
    const imageUrl = pendingImage.value || undefined
    const video = pendingVideo.value
    messages.value.push({
      role: 'user',
      content: text,
      imageUrl,
      video: video ? { durationSec: video.durationSec } : undefined,
    })
    messages.value.push({ role: 'assistant', content: '', streaming: true, fromQuestion: !!imageUrl })
    streaming.value = true
    pendingImage.value = ''
    pendingVideo.value = null
    agentSocket.sendMessage({
      type: 'chat.send',
      conversationId: activeId.value!,
      content: text,
      imageUrl,
      videoTempPath: video?.tempPath,
      videoDurationSec: video?.durationSec,
      // 网课页学习场景：随消息透传当前播放位置（秒），后端 v2 消费课程上下文
      currentTimeSec,
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
      case 'TRANSCRIBE': {
        // B11 视频转写事件：占位消息不存在时插入（本会话首次收到），存在时原地更新
        if (!msg.messageId) {
          break
        }
        let target = messages.value.find((m) => m.id === msg.messageId)
        if (!target) {
          target = { id: msg.messageId, role: 'assistant', content: '' }
          messages.value.push(target)
        }
        target.transcribe = {
          status: (msg.status as 'processing' | 'done' | 'failed') ?? 'processing',
          done: msg.done ?? 0,
          total: msg.total ?? 0,
        }
        if (msg.status === 'done' && msg.text) {
          target.content = msg.text
        }
        if (msg.status === 'done') {
          loadConversations()
        }
        break
      }
      case 'COURSE': {
        // B11 分流：课程流水线阶段进度事件（占位消息按 messageId 增改）
        if (!msg.messageId) {
          break
        }
        let target = messages.value.find((m) => m.id === msg.messageId)
        if (!target) {
          target = { id: msg.messageId, role: 'assistant', content: '' }
          messages.value.push(target)
        }
        const stage = msg.status ?? 'PENDING'
        const done = stage === 'COMPLETED'
        const failed = stage === 'FAILED'
        target.courseTask = {
          status: done ? 'done' : failed ? 'failed' : 'processing',
          stage,
          text: msg.text,
          link: done ? msg.message : undefined,
        }
        if (done && msg.text) {
          target.content = msg.text + (msg.message ? `。点击链接查看课程与 AI 笔记：${msg.message}` : '')
        }
        if (failed && msg.text) {
          target.content = msg.text
        }
        if (done) {
          loadConversations()
        }
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
    pendingVideo,
    error,
    loadConversations,
    createConversation,
    openConversation,
    removeConversation,
    startNew,
    restoreLastConversation,
    setSeed,
    applySeed,
    uploadPendingImage,
    clearPendingImage,
    uploadPendingVideo,
    clearPendingVideo,
    pushUploadNotice,
    showBriefingPlaceholder,
    loadBriefing,
    removeBriefingPlaceholder,
    injectCachedBriefing,
    send,
    stop,
    handleEvent,
    ensureSocketConnected,
  }
})
