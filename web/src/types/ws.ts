/**
 * Agent WS 事件协议（切片一基础版 + B11 视频转写 / 课程任务进度）
 */
export interface ClientMessage {
  type: 'chat.send' | 'chat.stop'
  conversationId: number
  content?: string
  imageUrl?: string
  /** B11：对话视频（上传接口产出的本地临时路径与 ffprobe 时长） */
  videoTempPath?: string
  videoDurationSec?: number
  /** 课程详情 AI 问答：跳转视频的时间戳（秒） */
  currentTimeSec?: number
}

export interface ServerMessage {
  type: 'DELTA' | 'COMPLETE' | 'ERROR' | 'STOP' | 'TRANSCRIBE' | 'COURSE'
  turnId: string | null
  text?: string
  messageId?: number
  code?: string
  message?: string
  /** 仅 TRANSCRIBE/COURSE：任务状态（COURSE 为阶段名 PENDING/UPLOADING/…/COMPLETED/FAILED） */
  status?: string
  /** 仅 TRANSCRIBE：已完成分片数 */
  done?: number
  /** 仅 TRANSCRIBE：总分片数 */
  total?: number
  /** 仅 COMPLETE：消息 payload JSON（意图确认大卡片等结构化扩展） */
  payload?: string
}
