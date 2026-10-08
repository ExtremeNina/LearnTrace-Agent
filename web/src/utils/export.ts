import { asBlob } from 'html-docx-js-typescript'
import type { CourseDetailData } from '../api/course'
import { renderMarkdown } from './markdown'

/**
 * 课程详情页导出工具（纯前端）：AI 笔记 / 课后习题导出为 Markdown 与 Word（.docx）。
 * Markdown 先经 renderMarkdown（DOMPurify 消毒）转 HTML，再由 html-docx-js-typescript 包装为 docx；
 * 打印 / 存为 PDF 走独立打印窗口（浏览器「另存为 PDF」兜底）。
 */

/** 文件名清洗：去掉 Windows 非法字符与首尾空白，空值兜底「未命名课程」 */
export function sanitizeFileName(title: string | null | undefined): string {
  const base = (title ?? '')
    .replace(/[\\/:*?"<>|]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
  return base || '未命名课程'
}

export function downloadBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}

/** Markdown 文本下载（UTF-8） */
export function downloadMarkdown(content: string, filename: string): void {
  downloadBlob(new Blob([content], { type: 'text/markdown;charset=utf-8' }), filename)
}

/** 秒 → [mm:ss]（超 1 小时进位 hh:mm:ss），与页面时间戳胶囊口径一致 */
export function formatTimestamp(totalSec: number): string {
  const h = Math.floor(totalSec / 3600)
  const m = Math.floor((totalSec % 3600) / 60)
  const s = Math.floor(totalSec % 60)
  const mm = String(m).padStart(2, '0')
  const ss = String(s).padStart(2, '0')
  return h > 0 ? `${h}:${mm}:${ss}` : `${mm}:${ss}`
}

/** AI 笔记 Markdown：标题 + 笔记正文（固定结构：概览 → 章节时间线 → 知识点 → 总结） */
export function buildNoteMarkdown(detail: CourseDetailData): string {
  const lines = [`# ${detail.course.title} · AI 笔记`, '']
  if (detail.note?.content) {
    lines.push(detail.note.content, '')
  }
  return lines.join('\n')
}

/** 课后习题 Markdown：按 sort 逐题输出题面；含答案模式追加参考答案 / 解析 / 依据时间点 */
export function buildQuizMarkdown(detail: CourseDetailData, withAnswers: boolean): string {
  const questions = detail.quizQuestions ?? []
  const lines = [
    `# ${detail.course.title} · 课后习题${withAnswers ? '（含答案解析）' : '（自测卷）'}`,
    '',
    `共 ${questions.length} 题`,
    '',
  ]
  questions.forEach((q, i) => {
    lines.push(`## ${i + 1}. ${q.questionText}`, '')
    if (withAnswers) {
      if (q.answer) {
        lines.push(`**参考答案**：${q.answer}`, '')
      }
      if (q.analysis) {
        lines.push(`**解析**：${q.analysis}`, '')
      }
      if (q.sourceSec != null) {
        lines.push(`**依据时间点**：[${formatTimestamp(q.sourceSec)}]`, '')
      }
    }
  })
  return lines.join('\n')
}

/** 打包为完整 HTML 文档：中文字体与表格边线内联样式（docx / 打印共用） */
function wrapHtmlDocument(title: string, bodyHtml: string): string {
  return `<!DOCTYPE html>
<html><head><meta charset="utf-8"><title>${title}</title>
<style>
  body { font-family: "Microsoft YaHei", "PingFang SC", sans-serif; font-size: 12pt; line-height: 1.7; color: #222; }
  h1 { font-size: 18pt; } h2 { font-size: 15pt; } h3 { font-size: 13pt; }
  table { border-collapse: collapse; width: 100%; margin: 8px 0; }
  th, td { border: 1px solid #999; padding: 4px 8px; font-size: 10.5pt; text-align: left; vertical-align: top; }
  code, pre { font-family: Consolas, monospace; font-size: 10.5pt; }
  blockquote { border-left: 3px solid #bbb; margin: 8px 0; padding: 2px 12px; color: #555; }
</style>
</head><body>${bodyHtml}</body></html>`
}

/** HTML → Word（.docx）下载；html 为已消毒的文档片段 */
export async function downloadDocx(title: string, bodyHtml: string): Promise<void> {
  const blob = (await asBlob(wrapHtmlDocument(title, bodyHtml), {
    orientation: 'portrait',
    margins: { top: 1134, right: 1134, bottom: 1134, left: 1134 },
  })) as Blob
  downloadBlob(blob, `${sanitizeFileName(title)}.docx`)
}

/** AI 笔记 → Word */
export async function exportNoteDocx(detail: CourseDetailData): Promise<void> {
  await downloadDocx(`${detail.course.title}-AI笔记`, renderMarkdown(buildNoteMarkdown(detail)))
}

/** 课后习题 → Word（withAnswers = 含答案解析 / 仅题面） */
export async function exportQuizDocx(detail: CourseDetailData, withAnswers: boolean): Promise<void> {
  const suffix = withAnswers ? '课后习题(含答案)' : '课后习题(仅题面)'
  await downloadDocx(`${detail.course.title}-${suffix}`, renderMarkdown(buildQuizMarkdown(detail, withAnswers)))
}

/**
 * 打印 / 存为 PDF：独立打印窗口仅承载导出内容（不打印播放器等页面元素），
 * 用户在浏览器打印对话框选择「另存为 PDF」即可
 */
export function printContent(title: string, bodyHtml: string): void {
  const win = window.open('', '_blank', 'width=880,height=960')
  if (!win) {
    throw new Error('打印窗口被浏览器拦截，请允许弹窗后重试')
  }
  win.document.write(wrapHtmlDocument(title, bodyHtml))
  win.document.close()
  win.focus()
  win.print()
}
