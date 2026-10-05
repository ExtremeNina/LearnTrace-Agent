# M1 会话与 Agent 对话

## 职责与业务
WS 流式对话（DeepSeek 流式 + 工具调用）、回合互斥、消息双写（message 表为事实源）、会话 CRUD / 自动标题 / 100 轮上限 / 30 天定时清理、Redis 会话记忆（滑窗 100 条）+ MySQL 重建兜底（PRD §6"任何时刻清空 Redis 功能不变"）。

## 边界（详细）

**输入**
- WS `chat.send`（conversationId + content + 可选 imageUrl / videoTempPath + videoDurationSec）：握手经 WsAuthHandshakeInterceptor 鉴权（token query 参数），userId 注入 session
- WS `chat.stop`：取消当前回合
- REST：会话创建 / 列表 / 消息 / 重命名 / 删除（全部经 OwnershipCheck 校验归属）；`POST /upload/chat-video`（B11 对话视频：1GB + 白名单 + ffprobe 探时长——不做时长拒绝，分流在工具层）

**视频上传分流（B11，2026-10-05 统一入口）**
- AI 对话是唯一视频上传入口（视频管理页仅管理：列表 / 进度 / 播放 / 删除 / 重试）
- LLM 按 VIDEO_PROMPT 意图分流：≤30min 默认调 `TranscribeVideoTool` 轻量转写（话术明示默认方针）；「做成课程 / 系统学习」或 >30min 调 `CreateCourseFromVideoTool` → `CourseService.uploadFromLocal` 建课走完整流水线
- 课程流水线进度回流：`CreateCourseFromVideoTool` 创建 `course_task` 占位消息（message.course_id 关联），CoursePipelineService 各阶段更新 payload 并推 `ChatEvent.COURSE` 事件，完成附 `/courses/{id}` 链接（页面路径上传无占位消息，自动跳过推送）
- 循环依赖：createCourseFromVideoTool 的 CourseService 参数标 `@Lazy`（chatClient → 工具 → CourseService → AiModelService → chatClient）

**视频转写（B11，2026-10-05 完成）**
- 链路：视频消息走 `VIDEO_PROMPT` → LLM 调 `TranscribeVideoTool`（秒回提交，ToolContext 带 videoTempPath/durationSec）→ `TranscriptionService` 在 courseExecutor 异步执行「抽音频 → 280s 分片 → Qwen ASR → 偏移合并」（抽自 CoursePipelineService，MediaUtils 共用）→ 占位消息（msgType=video_transcript）每片更新 payload 进度并经 `AgentEventPushService`（userId→session 注册表）推送 `TRANSCRIBE` 事件 → 完成后占位消息原地更新为 `[mm:ss]` 全文 + 追加 Redis 记忆
- 原片上传 OSS 永久保留（message.video_url 回填，日后可补 LLM 整理 / 抽帧 / 重试）
- 保存：用户确认后 LLM 调 `CreateNoteTool`（分组同名复用 / 自动创建，sourceType=2 对话转写，全文从最近转写消息确定性获取）→ 落库后自动向量化可被 rag_search 检索
- 工具返回结果码：SUBMIT_OK / SUBMIT_FAILED、SAVE_SUCCESS / SAVE_NOT_FOUND / SAVE_FAILED（与 saveQuestion 同风格）

**输出**
- 下行 ChatEvent：DELTA / COMPLETE / STOP / ERROR（错误码：CONVERSATION_LIMIT / TURN_IN_PROGRESS / BAD_REQUEST / AUTH_ERROR）+ TRANSCRIBE / COURSE（B11 转写与课程流水线进度，后台任务经 AgentEventPushService 主动推送，离线时结果已落库、重进会话从 REST 补齐）
- 存储：message 表双写（用户消息先落库再渲染，回合中途崩溃不丢输入）、conversation 表（last_active_at 活跃时间 / 自动标题去重）、Redis db1 会话记忆（MessageWindowChatMemory 滑窗 100 条）
- 回合整体在 `Flux.defer(...).subscribeOn(boundedElastic)` 中执行：OCR 前置识别、消息落库、记忆读取都不占用 WS / 请求线程（PRD §16）

**依赖**
- DeepSeek OpenAI 兼容端点（经 Spring AI ChatClient，业务代码禁止直连；密钥经本地私密配置注入）
- 工具（ToolContext 携带 userId/conversationId）：QuestionSaveTool（写题目 / 相似题，用户对话确认后调用）、RagSearchTool（读向量库，见 M2）、TranscribeVideoTool / CreateNoteTool / CreateCourseFromVideoTool（B11 视频分流：轻量转写 / 保存笔记 / 建课走完整流水线）
- CleanupScheduler（task/ 包，每日 3 点清理 30 天未活跃会话）
- 前端 agentSocket：离线 outbox 暂存冲刷（跨页种子消息依赖，见 M2 生成相似题入口）

**不做（边界外）**
- 不管理学习资产本体（题目/网课/笔记的写操作全部经各自模块或 Agent 工具）
- 不做多实例回合互斥（内存 ConcurrentHashMap 守卫，Redisson 后置——多实例部署前引入，见 backlog B05）
- 不做网课处理进度推送（前端 30 秒轮询 /courses，状态变化时右上角 Toast 通知——偏好可在个人页面关闭）

**约束与已知偏差**
- 轮次上限 100：按用户消息数计，超限错误码 CONVERSATION_LIMIT
- 记忆写入发生在回合完成时（Advisor 先写记忆、事实源后落库），回合边界上不存在"MySQL 领先而 Redis 非空"的常态场景 → PRD §6 的水位比对简化为"缓存缺失即重建"（B05 有记录）
- 会话记忆实现为 String JSON 而非 PRD 所述 Redis List（存储形态无功能差异，重建兜底已覆盖初衷，B05 有记录）
- 会话与消息删除为物理删除（会话属临时数据）；学习资产不受会话删除影响
- 30 天清理按 last_active_at（最近活跃）判断，不按创建时间

## 测试方法
- 单元（Mockito）：ConversationServiceImplTest——默认标题 / 保留给定标题 / 越权 404 / 消息列表归属 / 删除连带清理消息与记忆 / 30 天清理只删过期 / 轮次计数越权
- 单元：RedisChatMemoryRepositoryTest（JSON 互转 / 键约定 / 缓存缺失自 MySQL 重建并回填 / 末尾未配对用户消息不进记忆 / 非数字会话 ID 跳过重建 / 重建失败降级 / 查询条件断言）、ChatEventTest（事件结构）
- 手动全链路：web/test-ws.mjs（WS 连接、发送、断线重连）；页面实测错误事件展示（CONVERSATION_LIMIT 文案）
- 启动冒烟：登录 + /courses + /notes/tree
