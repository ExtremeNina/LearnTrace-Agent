/**
 * Agent WS 事件协议（切片一基础版 + B11 视频转写）
 */
export interface ClientMessage {
  type: 'chat.send' | 'chat.stop'
  conversationId: number
  content?: string
  imageUrl?: string
  /** B11：对话视频（上传接口产出的本地临时路径与 ffprobe 时长） */
  videoTempPath?: string
  videoDurationSec?: number
  // 网课页学习场景：随消息透传当前播放位置（秒）
  currentTimeSec?: number
}

export interface ServerMessage {
  type: 'DELTA' | 'COMPLETE' | 'ERROR' | 'STOP' | 'TRANSCRIBE'
  turnId: string | null
  text?: string
  messageId?: number
  code?: string
  message?: string
  /** 仅 TRANSCRIBE：任务状态 processing / done / failed */
  status?: string
  /** 仅 TRANSCRIBE：已完成分片数 */
  done?: number
  /** 仅 TRANSCRIBE：总分片数 */
  total?: number
}
