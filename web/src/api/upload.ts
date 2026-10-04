import { request } from './http'
import type { ChatVideoUploadInfo } from '../types/api'

/**
 * 上传对话图片，返回可访问的图片 URL
 */
export function uploadImage(file: File): Promise<string> {
  const form = new FormData()
  form.append('file', file)
  return request<string>({
    method: 'POST',
    url: '/upload/image',
    data: form,
    timeout: 30000,
  })
}

/**
 * 上传对话视频（B11，≤30 分钟）：服务端同步 ffprobe 校验时长，
 * 返回本地临时路径（随 WS 消息回传转写链路）与时长
 */
export function uploadChatVideo(file: File): Promise<ChatVideoUploadInfo> {
  const form = new FormData()
  form.append('file', file)
  return request<ChatVideoUploadInfo>({
    method: 'POST',
    url: '/upload/chat-video',
    data: form,
    timeout: 600000,
  })
}
