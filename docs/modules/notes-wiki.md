# M4 笔记整理与知识联系

## 职责与业务
OneNote 式分层笔记：分组树（最多 5 层）+ 笔记叶子；双轨编辑——手动笔记富文本（HTML 存储）、AI 笔记 Markdown（转写产物，源码编辑器）；原地编辑交互（点击正文即改、点空白放弃、保存按钮脏态）；知识联系侧栏（挂链网课带时间戳 / 题目 / 其他笔记，带一句关联说明）；分组删除递归级联（子树软删 + 双向知识联系清理）。

## 边界（详细）

**输入**
- REST：树 / 详情 / 新建分组与笔记 / 重命名 / 移动（5 层 + 环校验）/ 删除 / 正文保存（`PUT /notes/{id}/content`）/ 知识联系增（POST，含 remark）/ 删 / 说明改（PUT）
- 前端契约：AI 笔记（source_type=1）编辑走 Markdown 源码（MdSourceEditor），保存内容必须是 Markdown（渲染管线依赖）；手动笔记（source_type=0）富文本 HTML
- 渲染分流：AI → renderMarkdown + [mm:ss] 时间戳胶囊；手动 → DOMPurify 直渲染（looksLikeMarkdown 兼容历史 md 形态内容，首次编辑保存后定型为 HTML）

**输出**
- note 表（node_type 分组 / 笔记，parent_id 5 层，deleted 软删）+ note_link 表（link_type course/question/note + title 服务端解析 + remark 说明 + ts_sec）
- 分组删除：递归收集子树全部软删，note_link 双向清理（被删笔记身上的联系 + 其他笔记指向被删笔记的 note 型联系），前端确认弹窗列出影响范围（子分组数 / 笔记数）
- 知识联系卡片点击跳转：网课带时间戳、笔记互跳、题目跳拍照记录自动弹详情

**依赖**
- 无外部服务；仅 MySQL（note / note_link）
- 前端渲染管线 utils/markdown.ts（renderNoteHtml：归一化 → Markdown+KaTeX → 时间戳胶囊）

**不做（边界外）**
- 知识树 / AI 提取树（PRD 后期，parent_id 已预留）
- 反向知识联系展示、AI 建议挂链（backlog 二期池）
- AI 润色（已从笔记页移除；AI 笔记定位 = 转写产物，手动笔记 = 用户文档）
- 笔记向量化（B10 二期）

**约束与已知偏差**
- note_link 删除为物理删除；分组级联删除同理（被删笔记的联系一并物理清）
- 首次编辑会把 Markdown 形态历史内容定型为 HTML（显示效果保留，源形态不保留）
- 分组删除要求前端确认弹窗必须列出影响范围（子分组数 / 笔记数 / 不可恢复提示）——PRD 无此细节，用户明确要求
- NoteController 收参用 Map 而非 DTO（backlog B09 已记录）

## 测试方法
- 单元（Mockito）：NoteServiceImplTest——buildTree 嵌套组装、5 层限制、环校验、越权 404、重命名、移动、级联删除（子树全软删 + note_link 双向清理验证）、知识联系归属校验与 remark 保存（trim / 空清除）
- 手动：原地编辑排版一致性（点击前后同一 DOM、排版不变）、时间戳胶囊跳转、移动到分组、点空白保存/放弃、学习笔记框对比度
- 回归点：AI 笔记（md）与手动笔记（HTML）双轨渲染互不污染
