/**
 * 后端统一响应结构（PRD §14）
 */
export interface Result<T = unknown> {
  code: number
  message: string
  data: T
}

/** Sa-Token 登录成功返回的令牌信息 */
export interface TokenInfo {
  tokenName: string
  tokenValue: string
  isLogin: boolean
  loginId: string | number
}

/** 当前登录用户（对应后端 domain/entity/User） */
export interface UserInfo {
  id: number
  username: string
  nickname: string | null
  phone: string | null
  email: string | null
  avatarUrl: string | null
  bio?: string | null
  /** 界面主题：LIGHT / DARK */
  theme?: 'LIGHT' | 'DARK'
  /** 任务完成/失败通知开关 */
  notifyTaskEnabled?: boolean
  status: number
  deleted?: number
  createdAt: string
  updatedAt: string
}

/** 个人页面资料（GET/PUT /users/me 返回，对应后端 UserProfileVO） */
export interface UserProfileInfo {
  id: number
  username: string
  nickname: string | null
  email: string | null
  bio: string | null
  avatarUrl: string | null
  theme: 'LIGHT' | 'DARK'
  notifyTaskEnabled: boolean
  createdAt: string
}

/** 用户自建模型配置（对应后端 AiModelVO；apiKey 一律脱敏返回） */
export interface AiModelConfigInfo {
  id: number
  name: string
  baseUrl: string
  apiKeyMasked: string
  model: string
  apiFormat: string
  createdAt: string
  updatedAt: string
}

/** 按模块的模型偏好：module → 模型配置 ID（null = 系统默认） */
export type ModelModulePrefsInfo = Record<string, number | null>

/** 每日学习简报（对应后端 BriefingVO） */
export interface BriefingInfo {
  briefDate: string
  content: string
  stats: Record<string, number | string | unknown[]>
  generatedAt: string
}

/** 复习卡（对应后端 ReviewCardVO；内容按 cardType 实时组装） */
export interface ReviewCardInfo {
  id: number
  cardType: 'question' | 'similar' | 'note'
  refId: number
  frontText: string | null
  backText: string | null
  analysis: string | null
  imageUrl: string | null
  dueAt: string
  intervalDays: number
  reps: number
  lapses: number
}

/** 复习统计（对应后端 /review/stats） */
export interface ReviewStatsInfo {
  dueCount: number
  total: number
  reviewedToday: number
}



/** 会话（对应后端 domain/entity/Conversation） */
export interface ConversationInfo {
  id: number
  userId: number
  title: string
  lastActiveAt: string
  createdAt: string
  updatedAt: string
}

/** 会话消息（对应后端 domain/entity/Message） */
export interface MessageInfo {
  id: number
  conversationId: number
  role: 'user' | 'assistant' | 'tool'
  msgType: string
  content: string
  payload: string | null
  createdAt: string
}

/** 对话视频上传结果（B11，对应后端 ChatVideoUploadVO） */
export interface ChatVideoUploadInfo {
  tempPath: string
  durationSec: number
}

/** 题目记录统一视图（拍照题目 + AI 生成的相似题合并列表，source 区分；相似题无图 / 无作答字段） */
export interface QuestionItemInfo {
  id: number
  source: 'photo' | 'similar_ai'
  questionText: string | null
  subject: string | null
  imageOssKey: string | null
  correctAnswer: string | null
  analysis: string | null
  isWrong: number | null
  userAnswer: string | null
  userNote: string | null
  createdAt: string
  updatedAt: string
}

/** 通用分页结果（对应后端 PageVO） */
export interface PageInfo<T> {
  list: T[]
  total: number
  page: number
  size: number
  pages: number
}

/** 批量加入复习结果（对应后端 ReviewBatchAddVO） */
export interface ReviewBatchAddResult {
  addedCount: number
  skippedCount: number
}
