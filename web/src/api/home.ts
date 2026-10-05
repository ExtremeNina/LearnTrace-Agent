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
  /** 继续学习课程的「本课重点」（最新 AI 笔记知识点小节，最多 4 条） */
  keyPoints: string[]
  recentCourses: HomeCourseInfo[]
  todayQueue: HomeQueueCard[]
  stats: {
    dueCount: number
    reviewedToday: number
    totalCards: number
    coursesTotal: number
    notesTotal: number
    questionsTotal: number
    todayStudyMinutes: number
  }
  week: {
    newNotes: number
    reviewed: number
  }
}

export function getHomeOverview(): Promise<HomeOverview> {
  return request<HomeOverview>({ method: 'GET', url: '/home/overview' })
}

/** 学习时长心跳：本次在站秒数（前端每 60 秒上报，后端截断单次 ≤300s） */
export function heartbeatStudyTime(seconds: number): Promise<void> {
  return request<void>({ method: 'POST', url: '/home/study-time', data: { seconds } })
}
