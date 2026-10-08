import { defineStore } from 'pinia'
import { ref } from 'vue'
import * as conversationApi from '../api/conversation'
import { uploadChatVideo, uploadImage } from '../api/upload'
import { getTodayBriefing, listConversationBriefings } from '../api/briefing'
import type { BriefingInfo, ConversationInfo } from '../types/api'
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
  /** 意图确认大卡片（B27：视频回合意图 Agent 的结构化提问，payload 随消息透出） */
  intentCard?: { message: string; questions: { title: string; options: string[] }[] }
  /** 今日简报气泡（本地消息，不落库；按后端「会话×日期」标记恢复与落位） */
  briefing?: boolean
  /** 消息创建时间（服务端时间），用于把简报按生成时间插到最后一条更早的消息之后 */
  createdAt?: string
  /** 简报生成时间（仅 briefing 气泡携带） */
  generatedAt?: string
}

export const useAgentStore = defineStore('agent', () => {
  /** 本地记住当前会话：刷新页面后恢复到同一会话 */
  const ACTIVE_KEY = 'xj_active_conversation'
  /** 旧版本简报缓存：{date, content, conversationId}——归属曾写在前端，仅用于一次性迁移到后端 */
  const BRIEF_CACHE_KEY = 'xj_brief_cache'
  /** 当日已生成标记：每日一份简报，开新对话后不再重新生成/注入 */
  const BRIEF_GENERATED_KEY = 'xj_brief_generated'

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
        createdAt: m.createdAt,
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
      // 意图确认大卡片（B27）：历史渲染（实时经 COMPLETE.payload 透出，同构）
      if (payload?.intentCard && typeof payload.intentCard === 'object') {
        const card = payload.intentCard as { message?: string; questions?: unknown }
        if (Array.isArray(card.questions)) {
          item.intentCard = {
            message: typeof card.message === 'string' ? card.message : '',
            questions: (card.questions as { title?: string; options?: string[] }[])
              .filter((q) => q && typeof q.title === 'string' && Array.isArray(q.options))
              .map((q) => ({ title: q.title!, options: q.options! })),
          }
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
    // 简报按后端「会话×日期」标记恢复：拉取该会话全部简报，按生成时间插入消息流（跨天多条各自落位）
    await syncConversationBriefings(id)
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
   * 刷新后恢复上次会话：本地记录的会话仍存在则重新加载；
   * 标记缺失/失效（如详情页程序化 startNew 清空）时回退恢复最近一次会话——历史不再丢失；
   * 恢复后当日简报缓存重新注入对话顶部（openConversation 内部已统一处理）
   */
  async function restoreLastConversation() {
    await loadConversations()
    const saved = localStorage.getItem(ACTIVE_KEY)
    const targetId = saved && conversations.value.some((c) => c.id === Number(saved))
      ? Number(saved)
      : conversations.value[0]?.id
    if (!targetId) {
      return
    }
    try {
      await openConversation(targetId)
    } catch {
      rememberActive(null)
    }
  }

  /** 本地日期（yyyy-MM-dd）：与后端 LocalDate.now()（服务器本地时区）对齐，避免 UTC 凌晨差一天 */
  function briefToday() {
    const d = new Date()
    const m = String(d.getMonth() + 1).padStart(2, '0')
    const day = String(d.getDate()).padStart(2, '0')
    return `${d.getFullYear()}-${m}-${day}`
  }

  /** 时间字符串 → 时间戳（兼容 "yyyy-MM-dd HH:mm:ss" 与 ISO），失败返回 null */
  function parseTime(value?: string): number | null {
    if (!value) {
      return null
    }
    const t = new Date(value.includes('T') ? value : value.replace(' ', 'T')).getTime()
    return Number.isNaN(t) ? null : t
  }

  /** 消息流中是否已有指定日期（按简报生成时间）的简报气泡 */
  function hasBriefingOn(date: string) {
    return messages.value.some((m) => m.briefing && (m.generatedAt?.slice(0, 10) === date || m.content.includes(date)))
  }

  /**
   * 把简报气泡插入消息流：落在最后一条 createdAt ≤ 简报生成时间的消息之后
   * （即追加在简报生成时刻该会话最后一次对话之后；无更早消息则置顶）。
   * 同一会话跨天多条简报时，各自按生成时间落位。幂等：同日期简报已在列则跳过
   */
  function insertBriefingIntoMessages(b: BriefingInfo, contentOverride?: string) {
    const content = contentOverride ?? `**☀ 今日简报 · ${b.briefDate}**\n\n` + (b.content ?? '')
    if (hasBriefingOn(b.briefDate)) {
      return
    }
    const generated = parseTime(b.generatedAt)
    let index = 0
    for (let i = 0; i < messages.value.length; i++) {
      const t = parseTime(messages.value[i].createdAt)
      if (t !== null && generated !== null && t <= generated) {
        index = i + 1
      }
    }
    messages.value.splice(index, 0, {
      id: -1,
      role: 'assistant',
      content,
      briefing: true,
      generatedAt: b.generatedAt,
    })
  }

  /**
   * 拉取会话的全部简报标记并按生成时间插入消息流（幂等）。
   * 简报归属完全由后端（用户 × 会话 × 日期）记录决定：切换会话时只加载归属当前会话的简报，
   * 未绑定旧数据（conversation_id 为 NULL）不注入，由一次性迁移补绑定
   */
  async function syncConversationBriefings(conversationId: number) {
    try {
      const briefs = await listConversationBriefings(conversationId)
      for (const b of briefs) {
        insertBriefingIntoMessages(b)
      }
    } catch {
      // 简报恢复失败静默：不影响会话消息展示
    }
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
   * 今日简报（对话气泡形态）：占位插到流式占位之前（不干扰 DELTA 追加），完成后原地替换为 markdown 内容
   */
  function showBriefingPlaceholder() {
    if (hasBriefingOn(briefToday())) {
      return
    }
    const placeholder: ChatMsg = { role: 'assistant', content: '正在生成今日简报…', briefing: true }
    const last = messages.value[messages.value.length - 1]
    if (last?.streaming) {
      messages.value.splice(messages.value.length - 1, 0, placeholder)
    } else {
      messages.value.push(placeholder)
    }
  }

  /** 生成/读取今日简报并归属当前会话（后端按 会话×日期 幂等：已有则读缓存，未绑定旧数据则补绑定） */
  async function loadBriefing() {
    if (activeId.value === null) {
      return
    }
    const briefing = await getTodayBriefing(activeId.value)
    localStorage.setItem(BRIEF_GENERATED_KEY, briefToday())
    const content = `**☀ 今日简报 · ${briefing.briefDate}**\n\n` + (briefing.content ?? '')
    const pending = messages.value.find((m) => m.briefing && m.content === '正在生成今日简报…')
    if (pending) {
      pending.content = content
      pending.generatedAt = briefing.generatedAt
    } else {
      insertBriefingIntoMessages(briefing, content)
    }
  }

  function removeBriefingPlaceholder() {
    messages.value = messages.value.filter((m) => !(m.briefing && m.content === '正在生成今日简报…'))
  }

  /**
   * 旧版本一次性迁移：归属曾写在 localStorage（xj_brief_cache），按其记录的会话幂等调后端
   * 补绑定（后端把 conversation_id 为 NULL 的当日简报绑到该会话），随后弃用该缓存键
   */
  async function migrateLegacyBriefing() {
    try {
      const raw = localStorage.getItem(BRIEF_CACHE_KEY)
      if (!raw) {
        return
      }
      const obj = JSON.parse(raw)
      localStorage.removeItem(BRIEF_CACHE_KEY)
      if (obj?.date === briefToday() && typeof obj.conversationId === 'number') {
        const b = await getTodayBriefing(obj.conversationId)
        localStorage.setItem(BRIEF_GENERATED_KEY, briefToday())
        if (obj.conversationId === activeId.value && !hasBriefingOn(briefToday())) {
          insertBriefingIntoMessages(b)
        }
      }
    } catch {
      // 迁移失败静默：展示以后端标记为准
    }
  }

  /**
   * 确保当前会话有今日简报（幂等）：
   * - 无活动会话（新对话）或当日简报已在列 → 不做任何事
   * - 当日未生成 → 占位 + 生成，归属随生成落定到当前会话（send 创建会话后 / 首页进入时触发）
   * - 当日已生成但归属其他会话 → 本会话不注入（简报只出现在生成它的会话）
   */
  async function ensureTodayBriefing() {
    if (activeId.value === null || hasBriefingOn(briefToday())) {
      return
    }
    if (localStorage.getItem(BRIEF_GENERATED_KEY) !== briefToday()) {
      showBriefingPlaceholder()
      try {
        await loadBriefing()
      } catch {
        // 简报生成失败静默：移除占位气泡，不干扰对话（下次进入重试）
        removeBriefingPlaceholder()
      }
    }
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
      // 会话已创建：当日简报若尚未生成则在此生成并归属本会话（追加在最后一次对话之后，B27）
      await ensureTodayBriefing()
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
    // 定位流式占位气泡（取最后一条 streaming 消息，避免被简报占位等插入干扰）
    const placeholder = [...messages.value].reverse().find((m) => m.streaming)
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
          // 意图确认大卡片随 COMPLETE 透出（落库 payload 同构）
          if (msg.payload) {
            try {
              const card = JSON.parse(msg.payload)
              if (Array.isArray(card.questions)) {
                placeholder.intentCard = card
              }
            } catch {
              // payload 解析失败按纯文本处理
            }
          }
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
      agentSocket.onReconnected(() => {
        // 断线期间 COMPLETE/STOP 可能丢失：复位回合状态并重新拉取当前会话消息（服务端照常落库）
        streaming.value = false
        if (activeId.value !== null) {
          openConversation(activeId.value).catch(() => undefined)
        }
      })
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
    ensureTodayBriefing,
    migrateLegacyBriefing,
    send,
    stop,
    handleEvent,
    ensureSocketConnected,
  }
})
