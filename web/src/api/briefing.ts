import { request } from './http'
import type { BriefingInfo } from '../types/api'

/**
 * 每日学习简报：惰性生成（当天首次访问触发 LLM，落库缓存幂等）
 */
export function getTodayBriefing(): Promise<BriefingInfo> {
  return request<BriefingInfo>({ method: 'GET', url: '/briefing/today' })
}

export function refreshBriefing(): Promise<BriefingInfo> {
  return request<BriefingInfo>({ method: 'POST', url: '/briefing/refresh' })
}
