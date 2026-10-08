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
 * 意图确认选项卡片（B27 视频分流）：把助手消息中的 [chip:选项文本] 标记渲染为可点击按钮。
 * 事件经容器 click 委托读取 data-chip 上报（v-html 内容无法直接绑事件）；
 * DOMPurify 默认允许 button，但为防 sanitize 吞属性，本函数在 sanitize 之后调用
 */
export function renderIntentChips(html: string): string {
  return html.replace(
    /\[chip:([^\]]+)\]/g,
    '<button type="button" class="intent-chip" data-chip="$1">$1</button>'
  )
}

/**
 * 笔记正文统一渲染管线（AI 笔记阅读态与编辑预览共用）：
 * 块级空行归一化（LLM 输出的标题/列表前常缺空行）→ 按空行分块渲染 → [mm:ss] 转时间戳胶囊。
 * 表格块单独直渲染：防粘连规则会在「中文 - xxx」单元格内断行、破坏表格结构
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
  return normalized
    .split(/\n{2,}/)
    .map((block) => renderNoteBlock(block.trim()))
    .join('\n')
}

/** 单块渲染：表格块跳过防粘连规则，其余块走 renderMarkdown；时间戳统一转可点击胶囊 */
function renderNoteBlock(block: string): string {
  const html = block.startsWith('|')
    ? DOMPurify.sanitize(marked.parse(block, { async: false }) as string)
    : renderMarkdown(block)
  return html.replace(
    /\[(\d{1,2}:[0-5]\d(?::\d{2})?)\]/g,
    '<span class="ts-chip" data-ts="$1">$1</span>'
  )
}
