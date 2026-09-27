import { request } from './http'

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
