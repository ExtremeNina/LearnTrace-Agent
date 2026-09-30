import DOMPurify from 'dompurify'
import { marked } from 'marked'
import markedKatex from 'marked-katex-extension'
import 'katex/dist/katex.min.css'

/**
 * Markdown + LaTeX 渲染（助手消息展示用）：
 * 支持 $...$ 行内公式与 $$...$$ 块级公式（DeepSeek 输出风格），
 * HTML 经 DOMPurify 消毒后供 v-html 使用。
 */
marked.use(
  markedKatex({
    throwOnError: false,
    // 允许单个 $ 包裹行内公式
    nonStandard: true,
  })
)

export function renderMarkdown(source: string): string {
  if (!source) {
    return ''
  }
  // 防御：历史消息中的格式粘连（新输出已由提示词约束），渲染前统一规整：
  // 1) 行首 "##标题" 补空格（前瞻排除更长 # 串与空白，避免回溯把 "## " 拆成 "# # "）；
  // 2) 行中 "正文###标题" 断行；3) "…；-列表项" 粘连断行
  const normalized = source
    .replace(/^(#{1,6})(?=\r?$|[^\s#])/gm, '$1 ')
    .replace(/(?<=\S)(#{1,6})(?=[^\s#])/g, '\n$1 ')
    .replace(/(?<=[一-龥，。；：,;:])\s*-(?=\S)/g, '\n- ')
  const html = marked.parse(normalized, { async: false }) as string
  return DOMPurify.sanitize(html)
}

/**
 * 笔记正文统一渲染管线（AI 笔记阅读态与编辑预览共用）：
 * 块级空行归一化（LLM 输出的标题/列表前常缺空行）→ Markdown+KaTeX → [mm:ss] 转时间戳胶囊
 */
export function renderNoteHtml(md: string): string {
  if (!md) {
    return ''
  }
  // Windows 文本框编辑会把换行存成 CRLF，渲染前统一为 LF
  const normalized = md
    .replace(/\r\n/g, '\n')
    .replace(/(?<=\S)\n(#{1,6} )/g, '\n\n$1')
    .replace(/(?<=\S)\n(- )/g, '\n\n$1')
  return renderMarkdown(normalized).replace(
    /\[(\d{1,2}:[0-5]\d(?::\d{2})?)\]/g,
    '<span class="ts-chip" data-ts="$1">$1</span>'
  )
}
