import { request } from './http'

/** 全局语义搜索（B15）：GET /search，仅本人题目 / 笔记 / 网课转写 */
export interface SearchResultItem {
  type: 'question' | 'note' | 'transcript'
  /** question=题目 ID / note=笔记 ID / transcript=网课 ID */
  refId: number
  courseId?: number | null
  tsSec?: number | null
  subject?: string | null
  wrong?: boolean
  snippet: string
}

export function globalSearch(q: string): Promise<SearchResultItem[]> {
  return request<SearchResultItem[]>({ method: 'GET', url: '/search', params: { q } })
}
