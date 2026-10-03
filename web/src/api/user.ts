import { request } from './http'
import type { UserProfileInfo } from '../types/api'

/**
 * 个人页面接口：资料 / 偏好设置 / 修改密码 / 注销账号
 */
export function getMe(): Promise<UserProfileInfo> {
  return request<UserProfileInfo>({ method: 'GET', url: '/users/me' })
}

export function updateMe(data: {
  nickname?: string
  email?: string
  bio?: string
  avatarUrl?: string
}): Promise<UserProfileInfo> {
  return request<UserProfileInfo>({ method: 'PUT', url: '/users/me', data })
}

export function updatePreferences(data: { theme?: 'LIGHT' | 'DARK'; notifyTaskEnabled?: boolean }): Promise<UserProfileInfo> {
  return request<UserProfileInfo>({ method: 'PUT', url: '/users/me/preferences', data })
}

export function changePassword(data: { oldPassword: string; newPassword: string }): Promise<void> {
  return request<void>({ method: 'PUT', url: '/users/me/password', data })
}

export function deleteAccount(password: string): Promise<void> {
  return request<void>({ method: 'POST', url: '/users/me/delete', data: { password } })
}
