# 学迹 Agent · 项目地图

> 本文件是**业务与运维入口**：项目当前状态、模块索引、数据表、数据库操作、环境重建。
> 模块详细边界与测试方法见 `docs/modules/`；待办与优化项见 `docs/backlog/`；编码规范、经验坑、长期规划见根 `agent.md`。

## 顶层结构

```text
LJ-Agent/
├── 学迹PRD.md            # 产品需求（规格来源；§7/§10 确认环节已标注后期功能）
├── agent.md              # 文档地图 / 经验坑 / 硬性规范 / 工单流程 / 长期规划
├── docs/                 # 本目录：项目地图 / 模块文档 / backlog 优化清单
├── springboot/           # 后端 Spring Boot 3.4（端口 9090）
└── web/                  # 前端 Vue 3 + Vite（端口 5173）
```

## 当前状态（2026-10-03）

- 切片一（会话骨架）**完成**：多轮流式对话、自动标题、100 轮上限、30 天清理、Redis 记忆 + MySQL 重建兜底。
- 切片二（题目记录）**完成**：OCR 前置流水线（弹性线程执行，默认 45s 超时降级）、保存（AI 分类学科）、合并列表（拍照题 + AI 相似题）、相似题全流程（题目详情页生成入口 → 对话生成 → 确认保存入 similar_question 表）。
- 切片三（网课）**基本完成**：上传→MQ→FFmpeg→ASR 分片转写→批量帧 OCR→LLM 笔记生成入库全链路已用 3 个真实视频（约 10 分钟/个）验证 SUCCESS；列表/详情三标签/在线编辑；网课删除（批量）与处理超时自愈。AI 笔记确认环节搁置（后期功能）。
- 横向能力：RAG 统一向量化（题目 q: / 相似题 sq: / 笔记分块 / 转写分段，带来源标记）+ 水位补漏定时任务，真实回填 32 条验证；个人页面（头像/昵称/邮箱/简介、浅色深色主题、任务完成失败通知、改密/登出/注销）端到端冒烟 14 断言通过。
- 复习系统 MVP 完成（2026-10-03）：统一复习队列（题目/相似题/笔记）+ SM-2 简化版调度 + 今日待复习独立入口（/review），端到端冒烟 20 断言通过。
- 模型管理完成（2026-10-04）：用户自建 OpenAI 兼容模型配置（Base URL / API 格式 / API Key / 模型名，测试连接）+ 对话输入框模型切换器与管理弹窗 + 按模块的模型偏好（对话/网课笔记/简报，个人页面配置）+ ChatClientFactory（DB 驱动构建缓存，配置变更失效）；系统默认模型（部署者配置的 DeepSeek）为内置兜底；注销物理删除配置。冒烟 16 断言通过。
- 每日简报完成（2026-10-03）：惰性生成（当天首次访问触发 LLM 并落库缓存）+ /review 页顶部简报卡 + 启动时复习提醒 toast（每天一次，受通知偏好控制）+ Agent 工具 get_learning_status；真实 LLM 冒烟 8 断言通过（生成 350 字 / 缓存 0.01s）。
- 模型管理完成（2026-10-04）：用户自建 OpenAI 兼容模型配置（管理弹窗：Base URL / API 格式 / API Key / 模型名 + 测试连接）+ 对话输入框当前模型指示器与切换器 + 按模块的模型偏好（对话 / 网课笔记 / 简报）+ ChatClientFactory（DB 驱动构建缓存，配置变更失效）；系统默认模型（部署者配置的 DeepSeek）为内置兜底；注销物理删除配置。冒烟 16 断言通过。
- 已知遗留：Redis db1 与其他项目共用且 sa-token 键前缀相同（`sa-token:`），他项目 token 可通过本系统鉴权——B06 搁置期间接受，公开部署前改 `token-name` 隔离；对话图片上传的 OSS 配置走 git 忽略的本地配置文件方案（endpoint=武汉 lr 区）；`uploadChatImage` 只捕获 IOException、前端 Agent.vue 未渲染上传失败提示（早期记录，未复核）。

## 后端模块索引（springboot/src/main/java/com/xueji/agent/）

| 模块 | 主要文件 | 业务 | 模块文档 |
| --- | --- | --- | --- |
| 会话与 Agent 对话 | ws/AgentWebSocketHandler、service/impl/AgentChatServiceImpl、ai/memory/*、ConversationServiceImpl | WS 流式对话（outbox 离线暂存）、回合互斥、消息双写、会话 CRUD/自动标题/100 轮上限/30 天清理、Redis 会话记忆 + MySQL 重建兜底 | docs/modules/agent-chat.md |
| 题目记录与相似题 RAG | QuestionController/ServiceImpl、ai/tool/QuestionSaveTool、RagSearchTool、impl/QuestionVectorStoreService、ai/RagIngestService | 拍照题与相似题双表存储（question_record + similar_question）、合并列表、生成相似题入口、统一向量化（q:/sq: 前缀）、rag_search 来源标记召回 | docs/modules/questions-rag.md |
| 视频转写流水线 | CourseController/ServiceImpl、impl/CoursePipelineService、mq/CourseProcessConsumer、ai/tool/QwenAsrTool、PaddleOcrTool、ai/NoteGenerationService | 上传→MQ→FFmpeg→ASR→帧 OCR→LLM 笔记；失败重试；删除（批量）与连带清理；处理超时自愈；标题/学科/学习笔记编辑 | docs/modules/video-pipeline.md |
| 笔记整理与知识联系 | NoteController/ServiceImpl | 5 层分组树、双轨编辑（AI=md / 手动=HTML）、知识联系挂链与说明、级联删除、笔记向量化钩子 | docs/modules/notes-wiki.md |
| 复习系统与每日简报 | ReviewController/ServiceImpl、ReviewScheduler（SM-2 简化版纯函数）、BriefingController/ServiceImpl、LearningStatsService、task/CourseWatchScheduler | 统一复习队列（题目/相似题/笔记）：加卡（单条/批量）、今日队列、三档评分调度、统计；来源删除级联移出；每日简报（惰性 LLM 生成）与学习状态快照（Agent 工具 get_learning_status）；处理超时自愈 | docs/modules/review.md |
| 练习 / 测验模式 | QuizController/ServiceImpl | 从题库（question_record + similar_question）按学科/时间段/来源随机抽题组卷（无状态，不建会话表）；错题经批量加卡沉淀进复习队列 | docs/modules/quiz.md |
| 模型管理 | AiModelController/ServiceImpl、config/ChatClientFactory | 用户自建 OpenAI 兼容模型配置（CRUD/脱敏/连接测试）、按模块的模型偏好（对话/网课笔记/简报）、ChatClient 按配置构建缓存与失效、解析链（用户偏好→系统默认） | docs/modules/infrastructure.md |
| 用户与个人页面 | UserController/ServiceImpl、AuthController/AuthServiceImpl | 登录注册（Sa-Token）、资料（头像/昵称/邮箱/简介）、偏好（主题/任务通知）、改密、注销（逻辑删除 + 登录拦截） | docs/modules/infrastructure.md |
| 基础设施 | FileController、config/*（SpringAIConfig 等）、utils/*、common/* | 图片上传 OSS、AI 装配（ChatClient/记忆/工具/Embedding/VectorStore）、线程池、CORS/WS 配置、键名与归属校验收口 | docs/modules/infrastructure.md |
| 定时任务 | task/CleanupScheduler、RagRepairScheduler、CourseWatchScheduler | 会话 30 天清理；RAG 水位补漏（首轮全量）；网课处理超时自愈（并入 M1/M2/M3 文档） | （并入各模块文档） |
| 提示词收口 | ai/prompt/AgentPrompts.java | 基础人设 / 拍照解题 / 网课转写三套提示词 | （并入各模块文档） |

## 前端模块索引（web/src/）

| 模块 | 文件 | 业务 |
| --- | --- | --- |
| 页面 | views/Agent.vue、Review.vue、Quiz.vue、Questions.vue、Courses.vue、CourseDetail.vue、Notes.vue、Login/Register.vue | 对话 / 复习 / 练习测验（组卷-作答-错题入队）/ 题目记录（含生成相似题）/ 网课列表（批量删除）与详情 / 笔记整理 / 登录注册 |
| 接口层 | api/*.ts | 后端接口封装（统一 Result 解包、sa-token 注入） |
| 状态 | stores/agent.ts、auth.ts、user.ts、toast.ts、ui.ts | 对话流式状态与种子消息 / 登录态 / 资料与主题 / 轻提示与任务通知轮询 / 侧栏模式与收缩 |
| 组件 | components/ProfileModal.vue、ToastHost.vue、layout/*、notes/* | 个人页面弹窗、全局轻提示、可收缩双模式侧栏（对话 / 学习资产）、笔记树与编辑器、知识联系面板 |
| 渲染 | utils/markdown.ts | Markdown+KaTeX 渲染、[mm:ss] 时间戳胶囊（DOMPurify 消毒） |
| 通信 | ws/agentSocket.ts | WebSocket 封装（指数退避重连 + 离线 outbox 暂存冲刷） |
| 主题 | style.css | Tailwind v4 @theme 语义令牌；html.dark 翻转变量实现深色主题 |
| 常量 | constants/subjects.ts | 学科预置列表（表单 / 筛选 / AI 分类共用） |

## 数据表（MySQL xueji）

| 表 | 用途 | 删除语义 |
| --- | --- | --- |
| conversation / message | 会话与消息 | 物理删除（会话属临时数据，30 天定时清理） |
| question_record | 拍照题目（image_oss_key NOT NULL） | 逻辑删除（deleted） |
| similar_question | AI 相似题（source_question_id 可空、subject、conversation_id 溯源；is_correct 二期） | 逻辑删除（deleted） |
| course | 网课（model_config_id = 上传时选择的笔记生成模型，NULL = 系统默认；last_position_sec / progress_pct / last_studied_at = 播放进度打点，播放器定时上报） | 逻辑删除（deleted；删除连带 AI 笔记 / 知识联系 / 向量 / OSS 清理） |
| course_transcript_segment / course_frame | 转写分段 / 关键帧 | 随重试清理重建；随网课删除移出向量库 |
| note / note_link | 笔记树与知识联系 | 笔记逻辑删除；note_link 物理删除（分组级联时双向清理） |
| user | 用户（bio / theme / notify_task_enabled / deleted） | 注销为逻辑删除（deleted），登录拦截 |
| review_card / review_log | 复习卡（调度状态）与评分流水 | 复习卡逻辑删除（移出队列）；来源实体删除时级联移出 |
| daily_briefing | 每日学习简报（惰性生成，当天缓存） | 物理删除不适用（随账号保留） |
| ai_model_config | 用户自建模型配置（Base URL / Key / 模型名；Key 明文落库、接口脱敏） | 注销时物理删除（含密钥） |
| user_model_pref | 用户按模块的模型偏好（chat / course_note / briefing → config_id，NULL = 系统默认） | 配置删除时级联清除（回退系统默认） |
| invite_code / learning_record / async_task 等 | 预留 | 未接线（learning_record 属学习轨迹待办） |

## 数据库操作

* 目标库 `xueji`（root / 123456，MySQL 8.0），所有建表与数据操作都在该库执行
* 执行 SQL：`mysql --default-character-set=utf8mb4 -uroot -p123456 xueji < 文件.sql`——utf8mb4 必带，否则中文默认值与注释乱码
* 改表流程：改碎片 SQL（`springboot/src/main/resources/sql/*.sql`，现有 user_profile.sql / similar_question_model.sql / question_record_image_optional.sql / subject_filter.sql / note_hierarchy.sql 等）→ 对库执行 → `SHOW CREATE TABLE` 验证（表清单以本文件数据表一节为准）

## 环境清单（不在 git 里，丢失按此重建，约 10 分钟）

* `springboot/application-local.properties`（gitignored）：OSS（endpoint=武汉 lr 区）/ OCR / Qwen ASR 的密钥与 RabbitMQ 凭据——若丢失，凭据见阿里云控制台与 AI Studio，格式参照历史提交
* RabbitMQ 容器 `rabbitmq`（5672）：内含用户 `xueji/xueji123`（需 `rabbitmqctl set_permissions -p / xueji ".*" ".*" ".*"`）；容器重建后重建用户
* Redis 向量库容器 `redis-vector`（6380，redis-stack）：RAG 索引 `xueji-rag-idx`（JSON 存储，prefix `rag:question:`，TAG 字段 userId/subject/type）；改索引 schema 需 `docker exec redis-vector redis-cli -p 6379 FT.DROPINDEX xueji-rag-idx`，应用启动自动重建
* MySQL `xueji` 库：14 张表 + 种子/测试数据（笔记分层树、3 个课程的完整流水线数据）
* 用户级环境变量（setx）：OSS_ACCESS_KEY / OSS_SECRET_KEY / OSS_BUCKET / OSS_ENDPOINT / RABBITMQ_USER / RABBITMQ_PASS（本地开发已不依赖，走本地配置文件）
* 新会话热身三步：`mvn test`（114 个）→ `npm run build`（web）→ 后端启动冒烟（登录 + /courses + /notes/tree）

## 冒烟脚本（需后端已启动）

* `node web/test-profile-smoke.mjs` —— 个人页面全生命周期（一次性账号：注册 → 资料 → 偏好 → 改密 → 注销 → 复登拒绝），14 断言
* `node web/test-ws.mjs` —— WS 对话连接与发送
* `node web/test-review-smoke.mjs` —— 复习系统全流程（加卡/评分调度/级联），20 断言
* `node web/test-briefing-smoke.mjs` —— 每日简报（惰性生成/缓存幂等/强制刷新），8 断言
* `node web/test-model-smoke.mjs` —— 模型管理（CRUD/脱敏/模块偏好/级联回退/注销清理），16 断言

## 外部服务

| 服务 | 用途 |
| --- | --- |
| DeepSeek（OpenAI 兼容端点） | 对话流式、AI 笔记生成 |
| 百炼 text-embedding-v3 | RAG 向量化（1024 维） |
| Qwen-Audio ASR（专用部署端点） | 网课音频转写 |
| PaddleOCR（AI Studio） | 题目与关键帧 OCR（轮询上限可配置，默认 45s） |
| 阿里云 OSS（武汉 lr） | 图片 / 视频 / 帧图存储 |

## 测试

后端 156 个单元测试（18 个测试类：Mockito 单测 + FFmpeg 真实调用用例）；前端 `npm run build` 类型检查；Node 冒烟脚本五条（见上）。全链路人工验证：网课流水线 3 个真实视频、WS 对话（web/test-ws.mjs）、RAG 相似题闭环、个人页面全生命周期（web/test-profile-smoke.mjs）。各模块使用的测试方法详见 docs/modules/。
