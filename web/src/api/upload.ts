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

/**
 * B 站链接视频拉取（yt-dlp 下载到服务端临时目录）：
 * 返回与文件上传同构的临时路径与时长，后续走同一条意图卡/建课链路
 */
export function fetchBilibiliVideo(url: string): Promise<ChatVideoUploadInfo> {
  return request<ChatVideoUploadInfo>({
    method: 'POST',
    url: '/upload/chat-video-url',
    params: { url },
    timeout: 600000,
  })
}

/** B 站视频页链接检测（BV 号），用于输入框发送前识别 */
export function isBilibiliVideoUrl(text: string): boolean {
  return /https?:\/\/(www\.)?bilibili\.com\/video\/BV[0-9A-Za-z]{8,12}/.test(text)
}
