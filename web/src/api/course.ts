import { request } from './http'

export interface CourseInfo {
  id: number
  userId: number
  title: string
  expectations?: string | null
  videoOssKey?: string | null
  videoSize?: number | null
  duration?: number | null
  status: 'PENDING' | 'PROCESSING' | 'SUCCESS' | 'FAILED'
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

export interface CourseDetailData {
  course: CourseInfo
  transcript: TranscriptSegmentInfo[]
  frames: FrameInfo[]
  note: NoteInfo | null
}

export function listCourses(): Promise<CourseInfo[]> {
  return request<CourseInfo[]>({ method: 'GET', url: '/courses' })
}

export function uploadCourse(file: File, title?: string, expectations?: string): Promise<CourseInfo> {
  const form = new FormData()
  form.append('file', file)
  if (title) {
    form.append('title', title)
  }
  if (expectations) {
    form.append('expectations', expectations)
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

export function retryCourse(id: number): Promise<void> {
  return request<void>({ method: 'POST', url: `/courses/${id}/retry` })
}
