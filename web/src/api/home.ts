import { request } from './http'

/** 首页仪表盘聚合（B25 工单 3）：GET /home/overview */
export interface HomeCourseInfo {
  id: number
  title: string
  subject?: string | null
  duration?: number | null
  lastPositionSec?: number | null
  progressPct?: number | null
  lastStudiedAt?: string | null
  updatedAt: string
}

export interface HomeQueueCard {
  cardType: string
  frontText: string
}

export interface HomeOverview {
  nickname: string | null
  continueCourse: HomeCourseInfo | null
  recentCourses: HomeCourseInfo[]
  todayQueue: HomeQueueCard[]
  stats: {
    dueCount: number
    reviewedToday: number
    totalCards: number
    coursesTotal: number
    notesTotal: number
    questionsTotal: number
  }
  week: {
    newNotes: number
    reviewed: number
  }
}

export function getHomeOverview(): Promise<HomeOverview> {
  return request<HomeOverview>({ method: 'GET', url: '/home/overview' })
}
