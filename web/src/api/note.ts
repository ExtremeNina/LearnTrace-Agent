import { request } from './http'

export interface NoteLinkInfo {
  id: number
  linkType: 'course' | 'question' | 'note'
  targetId: number
  title: string
  tsSec?: number | null
}

export interface NoteTreeNodeInfo {
  id: number
  title: string
  type: 'group' | 'note'
  source?: string | null
  updatedAt?: string | null
  children?: NoteTreeNodeInfo[]
}

export interface NoteDetailInfo {
  id: number
  title: string
  content: string
  source: string
  updatedAt: string
  links: NoteLinkInfo[]
  courseId?: number | null
  courseTitle?: string | null
}

export function getNoteTree(): Promise<NoteTreeNodeInfo[]> {
  return request<NoteTreeNodeInfo[]>({ method: 'GET', url: '/notes/tree' })
}

export function getNoteDetail(id: number): Promise<NoteDetailInfo> {
  return request<NoteDetailInfo>({ method: 'GET', url: `/notes/${id}` })
}

export function createGroup(parentId: number | null, name: string): Promise<number> {
  return request<number>({ method: 'POST', url: '/notes/group', data: { parentId, name } })
}

export function createNote(parentId: number | null, title: string): Promise<number> {
  return request<number>({ method: 'POST', url: '/notes/note', data: { parentId, title } })
}

export function renameNote(id: number, name: string): Promise<void> {
  return request<void>({ method: 'PUT', url: `/notes/${id}/rename`, data: { name } })
}

export function moveNote(id: number, parentId: number | null): Promise<void> {
  return request<void>({ method: 'PUT', url: `/notes/${id}/move`, data: { parentId } })
}

export function deleteNote(id: number): Promise<void> {
  return request<void>({ method: 'DELETE', url: `/notes/${id}` })
}

export function updateNoteContent(id: number, content: string): Promise<void> {
  return request<void>({ method: 'PUT', url: `/notes/${id}/content`, data: { content } })
}

export function addNoteLink(id: number, linkType: string, targetId: number, tsSec?: number | null): Promise<void> {
  return request<void>({ method: 'POST', url: `/notes/${id}/links`, data: { linkType, targetId, tsSec: tsSec ?? null } })
}

export function removeNoteLink(id: number, linkId: number): Promise<void> {
  return request<void>({ method: 'DELETE', url: `/notes/${id}/links/${linkId}` })
}
