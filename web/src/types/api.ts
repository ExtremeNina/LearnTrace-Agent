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
