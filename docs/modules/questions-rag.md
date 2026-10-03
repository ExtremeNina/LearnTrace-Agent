# M2 题目记录与相似题 RAG

## 职责与业务
拍照题从对话确认保存（AI 自动分类学科）→ 合并列表（拍照题 + AI 相似题，日期 + 学科筛选、分页）→ 详情 / 编辑 / 删除（双表按 source 分派）；题目详情页一键生成相似题（PRD §8：当前会话继续 / 创建新会话，原题上下文经种子消息注入对话）；RAG 统一向量化（拍照题目 q: / 相似题 sq: / 笔记 / 网课转写分段），rag_search 按用户召回学习片段（带【题目 / 笔记 / 网课】来源标记）。

## 边界（详细）

**输入**
- 保存：Agent 工具 QuestionSaveTool（用户对话确认后调用）——`source=photo` 走 question_record（服务端从最近带图消息确定性取 imageUrl）；`source=text`（AI 相似题 / 手打题）走 similar_question（sourceQuestionId 可选，来自 rag_search 输出的题目 ID 或对话内标注的来源题目 ID）
- 编辑 / 删除 / 详情：REST `PUT/DELETE/GET /question/{id}`（`source` 参数分派 photo / similar_ai，仅本人，逻辑删除；编辑同步重建向量）
- 列表：`GET /question/list`（date + subject + 分页；question_record 与 similar_question UNION 合并，相似题字段映射：answer→correctAnswer，无图无作答字段）
- 生成相似题入口：前端题目详情页 → 会话选择 → 种子消息（含原题 + 来源题目 ID）→ Agent 生成 → 用户确认后调保存工具

**输出**
- question_record 表（image_oss_key NOT NULL——纯文字题不进此表）+ similar_question 表（source_question_id 可空、subject、conversation_id 溯源、is_correct 二期预留）
- 向量：redis-stack 6380，索引 xueji-rag-idx（prefix `rag:question:`，文档 ID 加前缀 q: / sq: 区分双表自增主键），元数据 userId（tag）/ type（tag）/ subject（tag）/ isWrong
- rag_search 返回：编号文本块带【题目 · 学科 · 错题 · 题目ID】/【笔记】/【网课】来源标记（题目 ID 供保存相似题回填来源；sq: 前缀的相似题 ID 不外露）

**依赖**
- 百炼 text-embedding-v3（1024 维，OpenAI 兼容端点；embeddingsPath 显式覆盖避免 /v1 叠加）
- redis-stack 容器 6380（RediSearch；应用主 Redis 6379 与其隔离）
- courseExecutor 线程池（异步向量化，失败仅告警不阻塞保存）
- ai/RagIngestService（统一摄取：相似题 / 笔记 / 转写分块的向量写入与按前缀清理）
- PaddleOCR（带图消息的前置识别——在 AgentChatServiceImpl 的 boundedElastic 线程执行，轮询上限可配置默认 45s，超时走降级提示）

**不做（边界外）**
- 学习轨迹召回排序（依赖 learning_record 打点，见 backlog 待办 4）
- 错题本视图（is_wrong 当前零写入；相似题作答结果记录二期确定）
- 混合检索（关键词 + 向量）与重排序（B10 二期）

**约束与已知偏差**
- 向量化失败不阻塞保存（仅告警日志；RagRepairScheduler 按水位补漏，最终一致）
- 相似题保存时若会话中有更早的拍照题，不关联旧图（text 来源完全不查图片消息）
- 学科枚举预置 11 项（constants/subjects.ts 与 AgentPrompts 描述一致）；similar_question 无 subject 的旧记录按"未分类"召回
- 合并列表分页在 SQL 层（UNION + LIMIT/OFFSET，QuestionRecordMapper.listMerged / countMerged）

## 测试方法
- 单元（Mockito）：QuestionServiceImplTest——photo 保存字段与向量化钩子 / 无图消息返回 false / text 保存入 similar_question（sourceQuestionId / 会话 ID / 不查图片消息）/ 空内容与越权拒绝 / 合并分页 / 双表详情 / 双表编辑（相似题忽略作答笔记字段）/ 双表删除（相似题移除 sq: 向量）
- 单元：RagSearchToolTest（错题标记 / 缺省元数据 / 笔记网课来源标记 / q: 前缀外露题目 ID 而 sq: 不外露 / 空结果与失败降级）、RagIngestServiceTest（sq: 入库元数据、removeSimilar、补漏回填）
- 人工验证：保存题目后 Redis 查向量键（rag:question:q:* / sq:*）；对话出相似题观察 rag_search 调用与召回质量；题目详情页发起生成相似题 → 会话内保存 → 合并列表出现带「AI 生成」标记的记录
