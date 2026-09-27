import axios from 'axios'
import type { AxiosRequestConfig, InternalAxiosRequestConfig } from 'axios'
import type { Result } from '../types/api'

/**
 * HTTP 封装：直连后端 9090（后端 CORS 已放行 5173），
 * 请求头按 Sa-Token 的 token-name 携带令牌。
 */
export const http = axios.create({
  baseURL: 'http://localhost:9090',
  timeout: 10000,
})

const TOKEN_KEY = 'xj_token'

http.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = localStorage.getItem(TOKEN_KEY)
  if (token) {
    config.headers['sa-token'] = token
  }
  return config
})

/**
 * 发起请求并解包 Result：code !== 200 时抛出业务错误
 */
export async function request<T>(config: AxiosRequestConfig): Promise<T> {
  const res = await http.request<Result<T>>(config)
  const body = res.data
  if (body.code !== 200) {
    throw new Error(body.message || '请求失败')
  }
  return body.data
}
