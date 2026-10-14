import {
  AlignmentType,
  BorderStyle,
  Document,
  HeadingLevel,
  LevelFormat,
  Packer,
  Paragraph,
  ShadingType,
  Table,
  TableCell,
  TableRow,
  TextRun,
  WidthType,
} from 'docx'
import { marked } from 'marked'
import type { Token, Tokens } from 'marked'
import type { CourseDetailData } from '../api/course'

/**
 * 课程详情页导出工具（纯前端）：AI 笔记 / 课后习题导出为 Markdown 与 Word（.docx）。
 * Word 导出用 docx 库生成**真实 OOXML 正文**（w:p 段落）——
 * 此前的 html-docx-js-typescript 是 altChunk/HTML 包装型伪 docx：桌面 Word 能容错渲染，
 * 但 QQ 内置查看器 / WPS 部分版本 / Word Online 不支持 altChunk，打开只显示空白页（2026-10-14 反馈）。
 * Markdown → docx 采用 marked.lexer 分词逐块映射；打印 / 存为 PDF 仍走 HTML 打印窗口。
 */

/** 正文基础样式：中文字体 12pt；标题 18/15/13pt 与旧 HTML 样式一致 */
const BODY_FONT = 'Microsoft YaHei'
const MONO_FONT = 'Consolas'
const TEXT_COLOR = '222222'
const BASE_SIZE = 24 // docx 字号为半磅：24 = 12pt

/** 文件名清洗：去掉 Windows 非法字符、U+FFFD 损坏符（乱码标题防御）与首尾空白，空值兜底「未命名课程」 */
export function sanitizeFileName(title: string | null | undefined): string {
  const base = (title ?? '')
    .replace(/[\\/:*?"<>|]/g, ' ')
    .replace(/\uFFFD/g, ' ')
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

// ---- Markdown → docx 映射 ----

interface RunStyle {
  bold?: boolean
  italics?: boolean
  strike?: boolean
  color?: string
}

/** 普通文本 Run */
function plainRun(text: string, style: RunStyle = {}): TextRun {
  return new TextRun({
    text,
    size: BASE_SIZE,
    color: style.color ?? TEXT_COLOR,
    font: BODY_FONT,
    bold: style.bold,
    italics: style.italics,
    strike: style.strike,
  })
}

/** 等宽 Run（行内代码 / 代码块） */
function monoRun(text: string, style: RunStyle = {}): TextRun {
  return new TextRun({
    text,
    size: 21,
    color: style.color ?? TEXT_COLOR,
    font: MONO_FONT,
    bold: style.bold,
  })
}

/** 行内 token 递归展开为 Run 序列（bold/em/del 组合继承；未知类型回退原文，KaTeX 公式以 TeX 原文兜底） */
function inlineRuns(token: Token, style: RunStyle = {}): TextRun[] {
  switch (token.type) {
    case 'strong':
      return token.tokens?.flatMap((t) => inlineRuns(t, { ...style, bold: true })) ?? [plainRun(token.raw, { ...style, bold: true })]
    case 'em':
      return token.tokens?.flatMap((t) => inlineRuns(t, { ...style, italics: true })) ?? [plainRun(token.raw, { ...style, italics: true })]
    case 'del':
      return token.tokens?.flatMap((t) => inlineRuns(t, { ...style, strike: true })) ?? [plainRun(token.raw, { ...style, strike: true })]
    case 'codespan':
      return [monoRun(token.text, style)]
    case 'link':
      // 保留链接文本（href 不写入正文，避免导出文档噪声）
      return token.tokens?.flatMap((t) => inlineRuns(t, style)) ?? [plainRun(token.raw, style)]
    case 'br':
      return [new TextRun({ break: 1, size: BASE_SIZE, color: TEXT_COLOR, font: BODY_FONT })]
    case 'image':
      return [plainRun(token.text || token.href || '', style)]
    case 'text':
      return token.tokens?.flatMap((t) => inlineRuns(t, style)) ?? [plainRun(token.text, style)]
    case 'escape':
      return [plainRun(token.text, style)]
    default: {
      // inlineKatex 等未知/扩展 token：以原文兜底（部分 token 类型无 text 字段，走 raw）
      const loose = token as { text?: string; raw?: string }
      return [plainRun(loose.text ?? loose.raw ?? '', style)]
    }
  }
}

/** 标题级别映射：marked depth(1-6) → docx HeadingLevel */
function headingLevelOf(depth: number): (typeof HeadingLevel)[keyof typeof HeadingLevel] {
  return (HeadingLevel as Record<string, (typeof HeadingLevel)[keyof typeof HeadingLevel]>)[`HEADING_${Math.min(Math.max(depth, 1), 6)}`]
}

/** 有序列表 numbering 引用（三级嵌套缩进在 levels 中定义） */
const ORDERED_NUMBERING_REF = 'md-ordered'

/** 列表项段落：item.tokens 中 text/paragraph 为本级内容，嵌套 list 递归加深层级 */
function listParagraphs(item: Tokens.ListItem, ordered: boolean, depth: number): Paragraph[] {
  const out: Paragraph[] = []
  for (const child of item.tokens ?? []) {
    if (child.type === 'list') {
      out.push(...listParagraphsBlock(child as Tokens.List, depth + 1))
    } else if (child.type === 'text' || child.type === 'paragraph') {
      const runs = child.tokens?.flatMap((t) => inlineRuns(t)) ?? [plainRun(child.raw)]
      out.push(
        new Paragraph({
          children: runs,
          ...(ordered
            ? { numbering: { reference: ORDERED_NUMBERING_REF, level: Math.min(depth, 2) } }
            : { bullet: { level: Math.min(depth, 2) } }),
          spacing: { after: 80 },
        })
      )
    }
  }
  if (out.length === 0) {
    out.push(new Paragraph({ text: item.raw, spacing: { after: 80 } }))
  }
  return out
}

/** 整个 list token 展开（含嵌套） */
function listParagraphsBlock(token: Tokens.List, depth: number): Paragraph[] {
  return token.items.flatMap((item) => listParagraphs(item, token.ordered, depth))
}

/** 代码块：每行独立等宽段落，浅灰底色 */
function codeParagraphs(token: Tokens.Code): Paragraph[] {
  return token.text.replace(/\n$/, '').split('\n').map(
    (line) =>
      new Paragraph({
        children: [monoRun(line || ' ')],
        shading: { type: ShadingType.CLEAR, fill: 'F5F5F5' },
        spacing: { after: 40 },
      })
  )
}

/** 表格：单元格内联 token 转 Run，全边框 + 表头加粗 */
function buildTable(token: Tokens.Table): Table {
  const border = { style: BorderStyle.SINGLE, size: 4, color: '999999' }
  const borders = {
    top: border,
    bottom: border,
    left: border,
    right: border,
    insideHorizontal: border,
    insideVertical: border,
  }
  const cellParagraph = (tokens: Token[], style: RunStyle = {}) =>
    new Paragraph({ children: tokens.flatMap((t) => inlineRuns(t, style)) })
  const headerRow = new TableRow({
    tableHeader: true,
    children: token.header.map((cell) => new TableCell({ children: [cellParagraph(cell.tokens ?? [], { bold: true })] })),
  })
  const bodyRows = token.rows.map(
    (row) => new TableRow({ children: row.map((cell) => new TableCell({ children: [cellParagraph(cell.tokens ?? [])] })) })
  )
  return new Table({ rows: [headerRow, ...bodyRows], width: { size: 100, type: WidthType.PERCENTAGE }, borders })
}

/** 块级 token → Paragraph/Table 序列；quote 样式贯穿 blockquote 内部（灰字 + 左侧竖线 + 缩进） */
function blockElements(token: Token, inQuote = false): (Paragraph | Table)[] {
  switch (token.type) {
    case 'heading':
      return [
        new Paragraph({
          heading: headingLevelOf(token.depth),
          children: (token.tokens ?? []).flatMap((t) => inlineRuns(t)),
        }),
      ]
    case 'paragraph': {
      const runs = (token.tokens ?? []).flatMap((t) => inlineRuns(t, inQuote ? { color: '555555' } : {}))
      return [inQuote
        ? new Paragraph({
            children: runs,
            border: { left: { style: BorderStyle.SINGLE, size: 18, color: 'BBBBBB', space: 8 } },
            indent: { left: 360 },
            spacing: { after: 120 },
          })
        : new Paragraph({ children: runs, spacing: { after: 120 } })]
    }
    case 'list':
      return listParagraphsBlock(token as Tokens.List, 0)
    case 'code':
      return codeParagraphs(token as Tokens.Code)
    case 'blockquote':
      return (token.tokens ?? []).flatMap((t) => blockElements(t, true))
    case 'table':
      return [buildTable(token as Tokens.Table)]
    case 'hr':
      return [new Paragraph({ thematicBreak: true })]
    case 'space':
      return []
    case 'html':
      // 导出内容不应含内联 HTML；跳过（print 链路才会渲染 HTML）
      return []
    default:
      // blockKatex 等扩展 token：以原文兜底为普通段落
      return token.raw?.trim() ? [new Paragraph({ children: [plainRun(token.raw.trim())] })] : []
  }
}

/** Markdown → 真实 OOXML docx Blob（正文为 w:p 段落，QQ/WPS/Word Online 均可渲染） */
async function markdownToDocxBlob(title: string, markdown: string): Promise<Blob> {
  const blocks = marked.lexer(markdown).flatMap((token) => blockElements(token))
  const doc = new Document({
    title,
    styles: {
      default: {
        document: { run: { font: BODY_FONT, size: BASE_SIZE, color: TEXT_COLOR } },
        heading1: { run: { font: BODY_FONT, size: 36, bold: true, color: TEXT_COLOR }, paragraph: { spacing: { before: 240, after: 160 } } },
        heading2: { run: { font: BODY_FONT, size: 30, bold: true, color: TEXT_COLOR }, paragraph: { spacing: { before: 200, after: 120 } } },
        heading3: { run: { font: BODY_FONT, size: 26, bold: true, color: TEXT_COLOR }, paragraph: { spacing: { before: 160, after: 100 } } },
        heading4: { run: { font: BODY_FONT, size: 24, bold: true, color: TEXT_COLOR } },
        heading5: { run: { font: BODY_FONT, size: 24, bold: true, color: TEXT_COLOR } },
        heading6: { run: { font: BODY_FONT, size: 24, bold: true, color: TEXT_COLOR } },
      },
    },
    numbering: {
      config: [
        {
          reference: ORDERED_NUMBERING_REF,
          levels: [0, 1, 2].map((level) => ({
            level,
            format: LevelFormat.DECIMAL,
            text: `%${level + 1}.`,
            alignment: AlignmentType.LEFT,
            style: { paragraph: { indent: { left: 360 * (level + 1), hanging: 260 } } },
          })),
        },
      ],
    },
    sections: [
      {
        properties: { page: { margin: { top: 1134, right: 1134, bottom: 1134, left: 1134 } } },
        children: blocks,
      },
    ],
  })
  return Packer.toBlob(doc)
}

/** Word（.docx）下载：入口为 Markdown 源文（内部负责分词与 OOXML 组装） */
export async function downloadDocx(title: string, markdown: string): Promise<void> {
  const blob = await markdownToDocxBlob(title, markdown)
  downloadBlob(blob, `${sanitizeFileName(title)}.docx`)
}

/** AI 笔记 → Word */
export async function exportNoteDocx(detail: CourseDetailData): Promise<void> {
  await downloadDocx(`${detail.course.title}-AI笔记`, buildNoteMarkdown(detail))
}

/** 课后习题 → Word（withAnswers = 含答案解析 / 仅题面） */
export async function exportQuizDocx(detail: CourseDetailData, withAnswers: boolean): Promise<void> {
  const suffix = withAnswers ? '课后习题(含答案)' : '课后习题(仅题面)'
  await downloadDocx(`${detail.course.title}-${suffix}`, buildQuizMarkdown(detail, withAnswers))
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
