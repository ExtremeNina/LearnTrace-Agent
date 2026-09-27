/**
 * Agent WS 事件协议（切片一基础版）
 */
export interface ClientMessage {
  type: 'chat.send' | 'chat.stop'
  conversationId: number
  content?: string
}

export interface ServerMessage {
  type: 'DELTA' | 'COMPLETE' | 'ERROR' | 'STOP'
  turnId: string | null
  text?: string
  messageId?: number
  code?: string
  message?: string
}
