# M3 视频转写流水线

## 职责与业务
网课视频上传后异步处理：FFmpeg 抽音频（280s 分片）→ Qwen ASR 句级转写 → FFmpeg 场景抽帧（fps_mode vfr）→ PaddleOCR 批量帧识别 → LLM 生成结构化 AI 笔记（课程概览→章节时间线→知识点→总结）→ 全部落库。另含：失败重试、标题 / 学科 / 学习笔记在线编辑、列表多条件筛选。

## 边界（详细）

**输入**
- REST `POST /courses`（multipart 视频 ≤500MB，title / subject / expectations 可选）→ 落库 PENDING + 投递 MQ（JSON 字符串负载：courseId + tempPath）
- REST `POST /courses/{id}/retry`（重新投递，清理旧产物）——当前为 HashMap 裸负载（backlog B02）
- REST `PUT /courses/{id}`（标题 / 学科 / 学习笔记 studyNote，仅更新提供字段）
- REST `GET /courses`（标题 / 状态 / 日期 / 学科筛选）、`GET /courses/{id}`（详情聚合：course + transcript + frames + note）

**输出**
- course 表（状态机 PENDING / PROCESSING / SUCCESS / FAILED + error_msg + study_note）+ course_transcript_segment（句级 start/end/text）+ course_frame（timeSec + ossKey + ocrText）+ note（source_type=1 AI 笔记，Markdown 固定结构）
- 视频与帧图上传阿里云 OSS（武汉 lr），course.video_oss_key / frame.oss_key
- 学习笔记 study_note 经 PUT 更新（前端富文本 HTML）

**依赖**
- FFmpeg（宿主 PATH；fps_mode vfr——8.x 已移除 vsync；280s 分片抽音频）
- Qwen-Audio ASR（DashScope 专用部署端点，密钥本地配置；裸 RestClient 直连——豁免记录见 backlog B09）
- PaddleOCR AI Studio 异步任务（提交 + 轮询 NDJSON；结果签名 URL 需裸请求避免二次编码）
- DeepSeek（NoteGenerationService 生成笔记，独立 conversationId 隔离，不污染用户对话）
- RabbitMQ（CourseProcessConsumer 消费；负载一律 JSON 字符串——retry 例外见 B02）
- 阿里云 OSS（@PostConstruct 启动期密钥校验）

**不做（边界外）**
- 时长（≤2h）校验（backlog B06）；批量 OCR 每日配额与超限降级（B06）
- AI 笔记确认环节（B01）；WS 进度推送（前端轮询）
- 章节独立生成（并入笔记正文，agent.md 决策待定）

**约束与已知偏差**
- 状态机：PENDING → PROCESSING → SUCCESS / FAILED（error_msg 落库，前端可重试）
- 生成笔记直接入库，幂等：重试先删旧 AI 笔记再插入（用户已编辑的笔记会被覆盖——B01 设计前提）
- AI 笔记 Markdown 固定结构（概览→时间线表格→知识点→总结），时间戳 [mm:ss] 前端转胶囊跳转
- 老课程 duration 可能为 NULL，前端用播放器时长兜底

## 测试方法
- 单元：CoursePipelineFFmpegTest（真实 FFmpeg 小样本：抽音频 / 抽帧命令与产物断言——遵循“核心外部交互用真实调用”）；CourseServiceImplTest（编辑字段语义 / 级联删除 / 越权）；NoteGenerationServiceTest（转写 / 帧 / 期望的消息组装）
- 人工全链路：3 个约 10 分钟真实视频跑通 SUCCESS（转写句级分段、关键帧批量识别、笔记含时间戳胶囊）
- 真实闭环验证：上传 → 处理 → 详情三区（视频+学习笔记 / AI 笔记 / 转写对照）逐项核对
