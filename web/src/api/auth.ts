import { request } from './http'
import type { TokenInfo, UserInfo } from '../types/api'

export function login(username: string, password: string): Promise<TokenInfo> {
  return request<TokenInfo>({
    method: 'POST',
    url: '/auth/login',
    data: { username, password },
  })
}

export function register(username: string, password: string): Promise<void> {
  return request<void>({
    method: 'POST',
    url: '/auth/register',
    data: { username, password },
  })
}

export function logout(): Promise<void> {
  return request<void>({ method: 'POST', url: '/auth/logout' })
}

export function getInfo(): Promise<UserInfo> {
  return request<UserInfo>({ method: 'GET', url: '/auth/info' })
}
