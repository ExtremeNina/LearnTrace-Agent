# M2 题目记录与相似题 RAG

## 职责与业务
拍照题从对话确认保存（AI 自动分类学科）→ 列表（日期 + 学科筛选、分页）→ 详情 / 编辑 / 删除；题目向量化入库（题干 + 作答 + 错因，剥 Markdown 修饰符保留公式 $）；rag_search 工具按用户召回学习片段，支撑对话内相似题生成。

## 边界（详细）

**输入**
- 保存：仅经 Agent 工具 QuestionSaveTool（用户确认后调用；强依赖会话内最近一条带图消息——纯文字相似题保存断裂见 backlog B03）
- 编辑 / 删除：REST `PUT/DELETE /question/{id}`（仅本人，逻辑删除；编辑同步重建向量）
- 列表：`GET /question/list`（date + subject + 分页，均为本人）

**输出**
- question_record 表（is_wrong 列存在但零写入——错题本视图不可用，见 backlog B04）
- 向量：redis-stack 6380，索引 xueji-rag-idx，key `rag:question:{id}`，元数据 userId（tag）/ subject（tag）/ isWrong
- rag_search 返回：编号文本块（题目原文 / 我的作答 / 错因 / 学科），userId 硬过滤 + 可选学科过滤

**依赖**
- 百炼 text-embedding-v3（1024 维，OpenAI 兼容端点；embeddingsPath 显式覆盖避免 /v1 叠加）
- redis-stack 容器 6380（RediSearch；应用主 Redis 6379 与其隔离）
- courseExecutor 线程池（异步向量化，失败仅告警不阻塞保存）
- PaddleOCR（带图消息的前置识别，同步轮询最长 120s——见 backlog B08）

**不做（边界外）**
- 笔记 / 网课转写向量化（backlog B10 二期）
- 学习轨迹召回排序（依赖 learning_record，未实现）
- 错题本视图（B04）
- 纯文字题保存（B03）

**约束与已知偏差**
- 向量化失败不阻塞保存（仅告警日志，该题搜不到；兜底补漏为二期）
- 学科枚举预置 11 项（constants/subjects.ts 与 AgentPrompts 描述一致）
- 向量与元数据同 key 存储；userId 硬过滤保证多账号隔离

## 测试方法
- 单元（Mockito）：QuestionServiceImplTest——保存字段与向量化钩子（ingestAsync verify）/ 越权 404 / 空值不保存不触发向量化 / 日期学科分页 / 编辑仅改提供字段（改后重建向量）/ 删除逻辑删
- RagSearchToolTest：召回格式化、空结果文案、userId+subject 过滤组合
- 待补：QuestionVectorStoreService 的 buildText / ingest 专项单测（backlog B09 记录）
- 人工验证：保存题目后 Redis 查向量键（rag:question:*）；对话出相似题观察 rag_search 调用与召回质量
