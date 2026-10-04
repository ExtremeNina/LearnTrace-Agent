import { request } from './http'
import type { AiModelConfigInfo, ModelModulePrefsInfo } from '../types/api'

/**
 * 模型管理：用户自建 OpenAI 兼容模型配置的增删改查、连接测试、模块偏好
 */
export function listModels(): Promise<AiModelConfigInfo[]> {
  return request<AiModelConfigInfo[]>({ method: 'GET', url: '/models' })
}

export function addModel(data: { name: string; baseUrl: string; apiKey: string; model: string }): Promise<AiModelConfigInfo> {
  return request<AiModelConfigInfo>({ method: 'POST', url: '/models', data })
}

export function updateModel(
  id: number,
  data: { name?: string; baseUrl?: string; apiKey?: string; model?: string }
): Promise<AiModelConfigInfo> {
  return request<AiModelConfigInfo>({ method: 'PUT', url: `/models/${id}`, data })
}

export function deleteModel(id: number): Promise<void> {
  return request<void>({ method: 'DELETE', url: `/models/${id}` })
}

export function testModel(id: number): Promise<void> {
  return request<void>({ method: 'POST', url: `/models/${id}/test` })
}

export function testModelDraft(data: { baseUrl: string; apiKey: string; model: string }): Promise<void> {
  return request<void>({ method: 'POST', url: '/models/test', data })
}

export function getModulePrefs(): Promise<ModelModulePrefsInfo> {
  return request<ModelModulePrefsInfo>({ method: 'GET', url: '/models/module-preferences' })
}

export function setModulePref(module: 'chat' | 'course_note' | 'briefing', configId: number | null): Promise<void> {
  return request<void>({ method: 'PUT', url: '/models/module-preferences', params: { module, configId: configId ?? '' } })
}
