import type { ConversationInfo, MessageInfo } from '../types/api'
import { request } from './http'

export function createConversation(title?: string): Promise<ConversationInfo> {
  return request<ConversationInfo>({
    method: 'POST',
    url: '/conversations',
    data: title ? { title } : {},
  })
}

export function listConversations(): Promise<ConversationInfo[]> {
  return request<ConversationInfo[]>({ method: 'GET', url: '/conversations' })
}

export function listMessages(conversationId: number): Promise<MessageInfo[]> {
  return request<MessageInfo[]>({
    method: 'GET',
    url: `/conversations/${conversationId}/messages`,
  })
}

export function deleteConversation(conversationId: number): Promise<void> {
  return request<void>({
    method: 'DELETE',
    url: `/conversations/${conversationId}`,
  })
}
