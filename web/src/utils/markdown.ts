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
  // 防御：历史消息中存在 "##标题" 粘连形式（# 后无空格），CommonMark 不识别为标题，
  // 渲染前统一补空格，避免 # 号原样显示
  const normalized = source.replace(/^(#{1,6})(?=\S)/gm, '$1 ')
  const html = marked.parse(normalized, { async: false }) as string
  return DOMPurify.sanitize(html)
}
