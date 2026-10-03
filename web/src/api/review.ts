import { request } from './http'
import type { ReviewCardInfo, ReviewStatsInfo } from '../types/api'

export type ReviewCardType = 'question' | 'similar' | 'note'
export type ReviewGrade = 0 | 1 | 2

/**
 * 复习系统：统计 / 今日队列 / 加卡 / 评分 / 移出 / 状态查询
 */
export function getReviewStats(): Promise<ReviewStatsInfo> {
  return request<ReviewStatsInfo>({ method: 'GET', url: '/review/stats' })
}

export function getTodayQueue(): Promise<ReviewCardInfo[]> {
  return request<ReviewCardInfo[]>({ method: 'GET', url: '/review/today' })
}

export function addReviewCard(cardType: ReviewCardType, refId: number): Promise<ReviewCardInfo> {
  return request<ReviewCardInfo>({ method: 'POST', url: '/review/cards', data: { cardType, refId } })
}

export function submitReview(id: number, grade: ReviewGrade): Promise<ReviewCardInfo> {
  return request<ReviewCardInfo>({ method: 'POST', url: `/review/cards/${id}/review`, data: { grade } })
}

export function removeReviewCard(id: number): Promise<void> {
  return request<void>({ method: 'DELETE', url: `/review/cards/${id}` })
}

export function getReviewStatus(cardType: ReviewCardType, refId: number): Promise<boolean> {
  return request<boolean>({ method: 'GET', url: '/review/status', params: { cardType, refId } })
}
