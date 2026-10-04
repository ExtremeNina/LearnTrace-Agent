import { request } from './http'
import type { QuizPickInfo } from '../types/api'
import type { ReviewCardType } from './review'

/**
 * 练习 / 测验模式：从题库随机抽题组卷（无状态，作答与评分在前端本地完成）
 */
export function pickQuiz(params: {
  count: number
  subject?: string
  period?: '7d' | '30d' | 'all'
  sources?: ReviewCardType[]
}): Promise<QuizPickInfo[]> {
  return request<QuizPickInfo[]>({
    method: 'GET',
    url: '/quiz/pick',
    params: {
      count: params.count,
      subject: params.subject || undefined,
      period: params.period || 'all',
      sources: params.sources?.join(','),
    },
  })
}
