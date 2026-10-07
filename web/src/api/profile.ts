import { request } from './http'
import type { UserProfileInfo } from '../types/api'

/** 获取学习者画像（未填写返回 null） */
export function getProfile(): Promise<UserProfileInfo | null> {
  return request<UserProfileInfo | null>({ method: 'GET', url: '/profile' })
}

/** 保存学习者画像（B26 阶段 3：多角色评审与笔记生成的难度适配输入） */
export function saveProfile(profile: Partial<UserProfileInfo>): Promise<UserProfileInfo> {
  return request<UserProfileInfo>({ method: 'PUT', url: '/profile', data: profile })
}
