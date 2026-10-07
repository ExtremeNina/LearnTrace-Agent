import { request } from './http'

export interface CourseInfo {
  id: number
  userId: number
  title: string
  subject?: string | null
  studyNote?: string | null
  expectations?: string | null
  videoOssKey?: string | null
  videoSize?: number | null
  duration?: number | null
  /** 笔记生成使用的模型配置（上传时选择，NULL = 系统默认） */
  modelConfigId?: number | null
  /** 上次播放位置（秒，播放器上报） */
  lastPositionSec?: number | null
  /** 观看进度百分比（0~100） */
  progressPct?: number | null
  /** 最近一次播放上报时间 */
  lastStudiedAt?: string | null
  status: 'PENDING' | 'PROCESSING' | 'SUCCESS' | 'FAILED'
  /** 处理阶段细分（PROCESSING 期间：UPLOADING/EXTRACTING/TRANSCRIBING/ANALYZING/NOTE_GENERATING） */
  stage?: string | null
  errorMsg?: string | null
  createdAt: string
  updatedAt: string
}

export interface TranscriptSegmentInfo {
  id: number
  courseId: number
  startSec: number
  endSec: number
  text: string
  /** 修正后文本（转写修正管线自动应用；NULL = 无修正） */
  textCorrected?: string | null
  /** 修正元数据 JSON（original/suggestion/evidence/status: APPLIED|SUGGESTED/source） */
  correctionMeta?: string | null
  sort: number
}

export interface FrameInfo {
  id: number
  courseId: number
  timeSec: number
  ossKey: string
  ocrText?: string | null
  ocrStatus: 'PENDING' | 'SUCCESS' | 'FAILED'
  createdAt: string
}

export interface NoteInfo {
  id: number
  title: string
  content: string
  sourceType: number
  courseId?: number | null
}

/** ContentDocument 语义层（B26 阶段 2） */
export interface ContentDocumentInfo {
  id: number
  courseId: number
  title: string
  summary?: string | null
}

export interface ContentSectionInfo {
  id: number
  documentId: number
  title: string
  summary?: string | null
  startSec: number
  endSec: number
  sort: number
}

export interface ContentKnowledgePointInfo {
  id: number
  documentId: number
  name: string
  detail?: string | null
  timeSec?: number | null
  sectionSort?: number | null
  important?: number | null
  errorProne?: number | null
}

/** 课程课后习题（B26 习题产物化，对应后端 CourseQuizQuestion） */
export interface CourseQuizQuestionInfo {
  id: number
  courseId: number
  questionText: string
  answer?: string | null
  analysis?: string | null
  sourceSec?: number | null
  sort: number
}

export interface CourseDetailData {
  course: CourseInfo
  transcript: TranscriptSegmentInfo[]
  frames: FrameInfo[]
  note: NoteInfo | null
  /** 内容理解产物（可能为 NULL：理解失败回退旧链路或尚未生成） */
  document?: ContentDocumentInfo | null
  sections?: ContentSectionInfo[]
  knowledgePoints?: ContentKnowledgePointInfo[]
  /** 课后习题（出题 Agent 产物；可能为空数组：生成中或失败） */
  quizQuestions?: CourseQuizQuestionInfo[]
}

export function listCourses(): Promise<CourseInfo[]> {
  return request<CourseInfo[]>({ method: 'GET', url: '/courses' })
}

export function uploadCourse(file: File, title?: string, expectations?: string, subject?: string, modelConfigId?: number | null): Promise<CourseInfo> {
  const form = new FormData()
  form.append('file', file)
  if (title) {
    form.append('title', title)
  }
  if (subject) {
    form.append('subject', subject)
  }
  if (expectations) {
    form.append('expectations', expectations)
  }
  if (modelConfigId != null) {
    form.append('modelConfigId', String(modelConfigId))
  }
  return request<CourseInfo>({
    method: 'POST',
    url: '/courses',
    data: form,
    timeout: 600000,
  })
}

export function getCourseDetail(id: number): Promise<CourseDetailData> {
  return request<CourseDetailData>({ method: 'GET', url: `/courses/${id}` })
}

/** 重新生成内容理解（ContentDocument）与 AI 笔记（异步，B26 阶段 2） */
export function regenerateCourseContent(id: number): Promise<void> {
  return request<void>({ method: 'POST', url: `/courses/${id}/understand` })
}

/** 追加课后习题（异步，B26 习题产物化） */
export function appendCourseQuiz(id: number, count = 5): Promise<void> {
  return request<void>({ method: 'POST', url: `/courses/${id}/quiz?count=${count}` })
}

/** 课后习题加入题目管理 */
export function quizToQuestions(quizQuestionId: number): Promise<number> {
  return request<number>({ method: 'POST', url: `/course-quiz/${quizQuestionId}/to-questions` })
}

/** 课后习题加入复习计划（自动先入题目管理） */
export function quizToReview(quizQuestionId: number): Promise<number> {
  return request<number>({ method: 'POST', url: `/course-quiz/${quizQuestionId}/to-review` })
}

export function updateCourse(id: number, data: { title?: string; subject?: string; studyNote?: string }): Promise<CourseInfo> {
  return request<CourseInfo>({ method: 'PUT', url: `/courses/${id}`, data })
}

export function retryCourse(id: number): Promise<void> {
  return request<void>({ method: 'POST', url: `/courses/${id}/retry` })
}

export function reportCourseProgress(id: number, positionSec: number): Promise<void> {
  return request<void>({ method: 'POST', url: `/courses/${id}/progress`, data: { positionSec } })
}

export function deleteCourse(id: number): Promise<void> {
  return request<void>({ method: 'DELETE', url: `/courses/${id}` })
}

export function batchDeleteCourses(ids: number[]): Promise<string> {
  return request<string>({ method: 'POST', url: '/courses/batch-delete', data: ids })
}
