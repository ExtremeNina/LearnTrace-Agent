# 学迹 Agent · 项目地图

> 模块索引用途：新会话快速定位“某功能在哪个模块、依赖什么、边界文档在哪”。各模块的详细边界与测试方法见 `docs/modules/`。待办与优化项见 `docs/backlog/`。

## 顶层结构

```text
LJ-Agent/
├── 学迹PRD.md            # 产品需求（规格来源）
├── agent.md              # 开发规范 / 硬性规范 / 经验坑 / 交接状态
├── docs/                 # 本目录：项目地图 / 模块文档 / 优化清单
├── springboot/           # 后端 Spring Boot 3.4（端口 9090）
└── web/                  # 前端 Vue 3 + Vite（端口 5173）
```

## 后端模块索引（springboot/src/main/java/com/xueji/agent/）

| 模块 | 主要文件 | 业务 | 模块文档 | 优化清单 |
| --- | --- | --- | --- | --- |
| 会话与 Agent 对话 | ws/AgentWebSocketHandler、service/impl/AgentChatServiceImpl、ai/memory/*、ConversationServiceImpl、CleanupScheduler | WS 流式对话、回合互斥、消息双写、会话 CRUD/自动标题/100 轮上限/30 天清理、Redis 会话记忆 | docs/modules/agent-chat.md | B01/B05 |
| 题目记录与相似题 RAG | QuestionController/ServiceImpl、ai/tool/QuestionSaveTool、RagSearchTool、impl/QuestionVectorStoreService | 拍照题保存（AI 分类学科）、列表筛选、编辑删除、向量入库、rag_search 召回出题 | docs/modules/questions-rag.md | B03/B04/B08/B10 |
| 视频转写流水线 | CourseController/ServiceImpl、impl/CoursePipelineService、mq/CourseProcessConsumer、ai/tool/QwenAsrTool、PaddleOcrTool、ai/NoteGenerationService | 上传→MQ→FFmpeg→ASR→帧 OCR→LLM 笔记；失败重试；标题/学科/学习笔记编辑 | docs/modules/video-pipeline.md | B01/B02/B06 |
| 笔记整理与知识联系 | NoteController/ServiceImpl | 5 层分组树、双轨编辑（AI=md / 手动=HTML）、知识联系挂链与说明、级联删除 | docs/modules/notes-wiki.md | B01/B07 |
| 基础设施 | AuthController/AuthServiceImpl、FileController、config/SpringAIConfig 等、utils/* | 登录注册（Sa-Token）、图片上传 OSS、AI 装配（ChatClient/记忆/工具/Embedding/VectorStore）、线程池、CORS/WS 配置 | docs/modules/infrastructure.md | B06 |
| 提示词收口 | ai/prompt/AgentPrompts.java | 基础人设 / 拍照解题 / 网课转写三套提示词 | （并入各模块文档） | — |

## 前端模块索引（web/src/）

| 模块 | 文件 | 业务 |
| --- | --- | --- |
| 页面 | views/Agent.vue、Questions.vue、Courses.vue、CourseDetail.vue、Notes.vue、Login/Register.vue | 对话 / 拍照记录 / 网课列表与详情 / 笔记整理 / 登录注册 |
| 接口层 | api/*.ts | 后端接口封装（统一 Result 解包、sa-token 注入） |
| 状态 | stores/agent.ts、auth.ts、ui.ts | 对话流式状态 / 登录态 / 侧栏 UI |
| 渲染 | utils/markdown.ts、components/notes/* | Markdown+KaTeX 渲染与时间戳胶囊、笔记树、md 编辑器、知识联系面板 |
| 通信 | ws/agentSocket.ts | WebSocket 封装（指数退避自动重连） |
| 常量 | constants/subjects.ts | 学科预置列表（表单 / 筛选 / AI 分类共用） |

## 数据表（MySQL xueji）

| 表 | 用途 | 删除语义 |
| --- | --- | --- |
| conversation / message | 会话与消息 | 物理删除（会话属临时数据，30 天定时清理） |
| question_record | 拍照题目 | 逻辑删除（deleted） |
| course | 网课 | 逻辑删除（deleted） |
| course_transcript_segment / course_frame | 转写分段 / 关键帧 | 随重试清理重建 |
| note / note_link | 笔记树与知识联系 | 笔记逻辑删除；note_link 物理删除（分组级联时双向清理） |

## 外部服务

| 服务 | 用途 |
| --- | --- |
| DeepSeek（OpenAI 兼容端点） | 对话流式、AI 笔记生成 |
| 百炼 text-embedding-v3 | RAG 向量化（1024 维） |
| Qwen-Audio ASR（专用部署端点） | 网课音频转写 |
| PaddleOCR（AI Studio） | 题目与关键帧 OCR |
| 阿里云 OSS（武汉 lr） | 图片 / 视频 / 帧图存储 |

## 测试

后端 65 个单元测试（9 个测试类：Mockito 单测 + FFmpeg 真实调用用例）；前端 `npm run build` 类型检查。全链路人工验证：网课流水线 3 个真实视频、WS 对话（web/test-ws.mjs）、RAG 相似题闭环。各模块使用的测试方法详见 docs/modules/。
