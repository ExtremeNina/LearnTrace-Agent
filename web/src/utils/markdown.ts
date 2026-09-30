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
  // 1) 行首 "##标题" 补空格；2) 行中 "正文###标题" 断行；3) "…；-列表项" 粘连断行
  const normalized = source
    .replace(/^(#{1,6})(?=\S)/gm, '$1 ')
    .replace(/(?<=\S)(#{1,6})(?=[^\s#])/g, '\n$1 ')
    .replace(/(?<=[一-龥，。；：,;:])\s*-(?=\S)/g, '\n- ')
  const html = marked.parse(normalized, { async: false }) as string
  return DOMPurify.sanitize(html)
}
