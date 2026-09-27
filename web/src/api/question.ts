import type { QuestionRecordInfo } from '../types/api'
import { request } from './http'

/**
 * 拍照题目记录：列表与详情
 */
export function listQuestions(): Promise<QuestionRecordInfo[]> {
  return request<QuestionRecordInfo[]>({ method: 'GET', url: '/question/list' })
}

export function getQuestion(id: number): Promise<QuestionRecordInfo> {
  return request<QuestionRecordInfo>({ method: 'GET', url: `/question/${id}` })
}
