import type { PageInfo, QuestionRecordInfo } from '../types/api'
import { request } from './http'

/**
 * 拍照题目记录：分页列表、详情、编辑与删除
 */
export function listQuestions(params: { date?: string; page: number; size: number }): Promise<PageInfo<QuestionRecordInfo>> {
  return request<PageInfo<QuestionRecordInfo>>({ method: 'GET', url: '/question/list', params })
}

export function getQuestion(id: number): Promise<QuestionRecordInfo> {
  return request<QuestionRecordInfo>({ method: 'GET', url: `/question/${id}` })
}

export function updateQuestion(
  id: number,
  data: { questionText?: string; userAnswer?: string; correctAnswer?: string; userNote?: string }
): Promise<QuestionRecordInfo> {
  return request<QuestionRecordInfo>({ method: 'PUT', url: `/question/${id}`, data })
}

export function deleteQuestion(id: number): Promise<void> {
  return request<void>({ method: 'DELETE', url: `/question/${id}` })
}
