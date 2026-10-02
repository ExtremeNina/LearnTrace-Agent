# M1 会话与 Agent 对话

## 职责与业务
WS 流式对话（DeepSeek 流式 + 工具调用）、回合互斥、消息双写（message 表为事实源）、会话 CRUD / 自动标题 / 100 轮上限 / 30 天定时清理、Redis 会话记忆（滑窗 100 条）。

## 边界（详细）

**输入**
- WS `chat.send`（conversationId + content + 可选 imageUrl）：握手经 WsAuthHandshakeInterceptor 鉴权（token query 参数），userId 注入 session
- WS `chat.stop`：取消当前回合
- REST：会话创建 / 列表 / 消息 / 重命名 / 删除（全部校验归属）

**输出**
- 下行 ChatEvent：DELTA / COMPLETE / STOP / ERROR（错误码：CONVERSATION_LIMIT / TURN_IN_PROGRESS / BAD_REQUEST / AUTH_ERROR）
- 存储：message 表双写（用户消息先落库再渲染，回合中途崩溃不丢输入）、conversation 表（last_active_at 活跃时间 / 自动标题去重）、Redis db1 会话记忆（MessageWindowChatMemory 滑窗 100 条）

**依赖**
- DeepSeek OpenAI 兼容端点（经 Spring AI ChatClient，业务代码禁止直连；密钥经本地私密配置注入）
- 工具（ToolContext 携带 userId/conversationId）：QuestionSaveTool（写题目，确认卡未落地见 B01）、RagSearchTool（读向量库，见 M2）
- CleanupScheduler（每日 3 点清理 30 天未活跃会话）

**不做（边界外）**
- 不管理学习资产本体（题目/网课/笔记的写操作全部经各自模块或 Agent 工具）
- 不做多实例回合互斥（内存 ConcurrentHashMap 守卫，Redisson 后置，见 backlog B05）
- 不做网课处理进度推送（前端轮询 /courses）
- 不限制用户会话总数（仅单会话轮次上限 100）

**约束与已知偏差**
- 轮次上限 100：按用户消息数计，超限错误码 CONVERSATION_LIMIT
- 会话记忆实现与 PRD §6 的差异（非 List、无水位重建、无 Redisson）记录于 backlog B05
- 会话与消息删除为物理删除（会话属临时数据）；学习资产不受会话删除影响
- 30 天清理按 last_active_at（最近活跃）判断，不按创建时间

## 测试方法
- 单元（Mockito）：ConversationServiceImplTest——默认标题 / 保留给定标题 / 越权 404 / 消息列表归属 / 删除连带清理消息与记忆 / 30 天清理只删过期（active 不删、记忆按 id clear）/ 轮次计数越权
- 单元：RedisChatMemoryRepositoryTest（记忆读写）、ChatEventTest（事件结构）
- 手动全链路：web/test-ws.mjs（WS 连接、发送、断线重连）；页面实测错误事件展示（CONVERSATION_LIMIT 文案）
- 启动冒烟：登录 + /courses + /notes/tree
