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
  const html = marked.parse(source, { async: false }) as string
  return DOMPurify.sanitize(html)
}
