# 学迹 Agent

AI 个人学习工作台：学习资产（网课 / 题目 / 笔记）+ 知识点页 + Human-in-the-loop 的 Agent 对话。记录你与知识发生过什么，并把它们连成网。

## 技术栈

- **前端**：Vue 3 + TypeScript + Tailwind CSS（Vite 构建，桌面 / 移动端响应式）
- **后端**：Spring Boot 3.4 + MyBatis-Plus + MySQL 8（库名 `xueji`）+ Redis + RabbitMQ
- **AI**：Spring AI 接 DeepSeek（对话与笔记生成）；语音转写 Qwen-Audio ASR；OCR 百度 PaddleOCR

## 功能现状

### 会话
- 多轮流式对话（WebSocket）、会话历史与标题、对话图片上传（阿里云 OSS）
- 拍照解题：图片 OCR → AI 整理解答 → 确认后保存进拍照记录

### 拍照记录
- 题目分页列表、详情、编辑、删除
- 按日期 + 学科筛选（学科由 AI 保存题目时自动分类，可手动修改）
- 学科选项：数学 / 语文 / 英语 / 物理 / 化学 / 生物 / 历史 / 地理 / 政治 / 计算机 / 其他

### 网课
- 上传视频 → RabbitMQ 流水线：FFmpeg 抽音频与关键帧 → ASR 分片转写 → 批量帧 OCR → LLM 生成 AI 笔记入库
- AI 笔记固定结构：课程概览（一句话概括 + 主要内容）→ 章节时间线（表格，时间戳可点击跳转视频）→ 知识点 → 总结
- 列表（标题搜索 / 状态 / 日期 / 学科筛选）、详情三标签（AI 笔记 / 转写对照 / 关键帧识别）、在线编辑标题与学科
- 视频在线播放，笔记与转写中的时间戳点击即跳转对应片段

### 笔记整理
- OneNote 式分层树：自定义分组最多 5 层，新建 / 重命名 / 移动 / 删除（分组删除递归级联，带确认警告）
- 双轨编辑：手动笔记富文本、AI 笔记 Markdown 源码（含工具按钮 / 预览 / 编辑与预览滚动同步）；点击空白区域即保存
- 知识联系右侧边栏：卡片展示关联的网课 / 题目 / 笔记及一句关联说明；弹窗聚合搜索挂链；点击卡片跳转（网课带时间戳、题目自动弹出详情）
- 页面切换后保留上次打开的笔记与树状态（KeepAlive）

### 文档与规范
- 产品需求见 `学迹PRD.md`；交接状态、硬性编码规范与踩坑记录见 `agent.md`

## 快速开始

```bash
# 后端（端口 9090；数据库 xueji，账号 root/123456）
cd springboot
mvn spring-boot:run

# 前端（端口 5173）
cd web
npm install
npm run dev
```

- 完整建表脚本尚未入库；增量 DDL 与种子数据见 `springboot/src/main/resources/sql/`（`subject_filter.sql`、`note_link_remark.sql` 等），完整库结构见本地环境或 `agent.md` 环境清单
- OSS / ASR / OCR 等第三方密钥走 git 忽略的 `springboot/application-local.properties`，重建方式见 `agent.md`
- RabbitMQ 容器内建用户 `xueji/xueji123`（容器重建后需重新授权）

## 测试

```bash
cd springboot && mvn test   # 63 个单元测试
cd web && npm run build     # 前端类型检查与构建
```
