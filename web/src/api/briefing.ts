import { request } from './http'
import type { BriefingInfo } from '../types/api'

/**
 * 每日学习简报：按（用户 × 会话 × 日期）惰性生成（当天首次访问触发 LLM，落库缓存幂等）。
 * conversationId 为空时表示「新对话尚未创建」的生成（后端记录未绑定归属）。
 */
export function getTodayBriefing(conversationId?: number): Promise<BriefingInfo> {
  return request<BriefingInfo>({
    method: 'GET',
    url: '/briefing/today',
    params: conversationId != null ? { conversationId } : undefined,
  })
}

export function refreshBriefing(conversationId?: number): Promise<BriefingInfo> {
  return request<BriefingInfo>({
    method: 'POST',
    url: '/briefing/refresh',
    params: conversationId != null ? { conversationId } : undefined,
  })
}

/** 查询某会话的全部简报标记（按生成时间升序），切换会话时据此恢复简报在消息流中的位置 */
export function listConversationBriefings(conversationId: number): Promise<BriefingInfo[]> {
  return request<BriefingInfo[]>({ method: 'GET', url: `/briefing/conversation/${conversationId}` })
}
