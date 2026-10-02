import type { PageInfo, QuestionItemInfo } from '../types/api'
import { request } from './http'

/** 题目来源：photo = 拍照题目 / similar_ai = AI 生成的相似题 */
export type QuestionSource = 'photo' | 'similar_ai'

/**
 * 题目记录（拍照题目 + AI 相似题合并列表）：分页列表、详情、编辑与删除
 */
export function listQuestions(params: { date?: string; subject?: string; page: number; size: number }): Promise<PageInfo<QuestionItemInfo>> {
  return request<PageInfo<QuestionItemInfo>>({ method: 'GET', url: '/question/list', params })
}

export function getQuestion(id: number, source: QuestionSource = 'photo'): Promise<QuestionItemInfo> {
  return request<QuestionItemInfo>({ method: 'GET', url: `/question/${id}`, params: { source } })
}

export function updateQuestion(
  id: number,
  source: QuestionSource,
  data: { questionText?: string; userAnswer?: string; correctAnswer?: string; analysis?: string; userNote?: string; subject?: string }
): Promise<QuestionItemInfo> {
  return request<QuestionItemInfo>({ method: 'PUT', url: `/question/${id}`, params: { source }, data })
}

export function deleteQuestion(id: number, source: QuestionSource = 'photo'): Promise<void> {
  return request<void>({ method: 'DELETE', url: `/question/${id}`, params: { source } })
}
