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
  status: number
  createdAt: string
  updatedAt: string
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

/** 拍照题目记录（对应后端 domain/entity/QuestionRecord） */
export interface QuestionRecordInfo {
  id: number
  userId: number
  imageOssKey: string
  ocrText: string | null
  questionText: string | null
  userAnswer: string | null
  correctAnswer: string | null
  analysis: string | null
  isWrong: number | null
  aiStatus: string
  recordStatus: string
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
