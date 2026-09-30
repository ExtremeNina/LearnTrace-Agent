# 学迹 Agent 后端

AI 个人学习工作台「学迹」的后端工程。

- 基于 Spring Boot 3.4 + MyBatis-Plus + MySQL 8(`xueji` 库) + Redis + RabbitMQ + Sa-Token + Spring AI(DeepSeek)
- 包路径：`com.xueji.agent`，结构对齐 PRD §13（domain 四子包收拢实体数据）
- 基路径原为模板项目（茶饮商城），业务代码已清理，仅保留基础设施配置
- 技术栈总览、功能现状与架构链路图见仓库根 `README.md`；硬性编码规范与踩坑记录见 `agent.md`

## 模块一览

| 包 | 职责 |
| --- | --- |
| `ai` | Spring AI 编排：`AgentChatServiceImpl`（流式对话与 OCR 前置流水线）、`NoteGenerationService`（网课 AI 笔记生成，独立 conversationId 隔离记忆）、提示词集中于 `ai/prompt/AgentPrompts` |
| `ai.tool` | 模型工具与识别端：`OcrTool`（百度 PaddleOCR 异步任务）/ `AliyunOcrTool`（阿里云读光，备用实现）/ `QwenAsrTool`（语音转写）/ `QuestionSaveTool`（保存题目，ToolContext 传 userId）/ `OcrTextFormatter`（OCR 文本确定性格式化） |
| `ai.memory` | `RedisChatMemoryRepository`：Spring AI ChatMemory 的 Redis 实现（滑窗记忆） |
| `controller` | REST 接口：`AuthController` / `ConversationController` / `QuestionController` / `CourseController` / `NoteController` / `FileController` |
| `service` / `service.impl` | 业务逻辑：对话（含回合编排与 message 表双写）、题目记录（保存 / 分页 / 编辑 / 逻辑删除）、网课（`CoursePipelineService` 流水线）、笔记（分层树 / 知识联系） |
| `mq` | `CourseProcessConsumer`：RabbitMQ 消费者，网课处理流水线入口（负载一律 JSON 字符串） |
| `ws` | `AgentWebSocketHandler`（上行 chat.send / chat.stop，回合互斥）+ `WsAuthHandshakeInterceptor`（握手鉴权，token 走 query 参数） |
| `config` | 线程池（网课用 `CourseExecutorConfig`）、RabbitMQ、Sa-Token（OPTIONS 预检跳过）、CORS、Redis、MyBatis-Plus、Spring AI、WebSocket |
| `domain` | entity（14 表）/ dto / vo（含通用分页 `PageVO`）/ enums |
| `mapper` | MyBatis-Plus Mapper（字符串列名 QueryWrapper，禁 LambdaQueryWrapper） |
| `utils` | `AliUploadUtils`（OSS 上传，`@PostConstruct` 密钥校验）、`RedisUtils`、`UserUtils` |
| `common` / `exception` | `Result` 统一响应、`RedisKeys` / `MqKeys` 键名收口、`BusinessException` / `UploadException` 与全局异常处理 |

## 启动前置

| 依赖 | 说明 |
| --- | --- |
| JDK 17 / Maven | Lombok 由 pom 覆盖为 1.18.48（新 JDK 必需，见 agent.md 坑清单） |
| MySQL 8 | 库 `xueji`，账号 root/123456；增量 DDL 见 `src/main/resources/sql/` |
| Redis | 默认 127.0.0.1:6379 db1（会话记忆、Sa-Token 持久化） |
| RabbitMQ | 5672，容器内用户 `xueji/xueji123`（容器重建后需重新授权） |
| FFmpeg | 需在 PATH 中（网课流水线抽音频 / 抽帧） |

第三方密钥通过 git 忽略的 **`src/main/resources/application-local.properties`**（即工作目录下的 `application-local.properties`，经 `spring.config.import` 引入）注入：OSS（endpoint / accessKey / secretKey / bucketName）、PaddleOCR 与 Qwen ASR 访问凭据、RabbitMQ 用户密码。缺失时 `AliUploadUtils` 启动期校验会直接失败并给出提示。

## 启动与测试

```bash
mvn spring-boot:run   # 端口 9090
mvn test              # 63 个单元测试（9 个测试类）
```

测试为纯单元测试（Mockito mock Mapper，不依赖数据库），其中 `CoursePipelineFFmpegTest` 会真实调用本机 FFmpeg。测试类：`NoteGenerationServiceTest`、`RedisChatMemoryRepositoryTest`、`ResultTest`、`ChatEventTest`、`CourseServiceImplTest`、`QuestionServiceImplTest`、`ConversationServiceImplTest`、`CoursePipelineFFmpegTest`、`NoteServiceImplTest`。

## 增量 SQL

`src/main/resources/sql/` 按功能命名（执行时必带 `--default-character-set=utf8mb4`，否则中文注释乱码）：

| 脚本 | 内容 |
| --- | --- |
| `note_hierarchy.sql` | 笔记分层树（自定义分组最多 5 层） |
| `note_seed.sql` | 笔记种子数据（含网课 AI 知识点） |
| `note_link_remark.sql` | 知识联系挂链与关联说明 |
| `subject_filter.sql` | 题目 / 网课学科列与筛选 |
| `course_expectations.sql` / `course_frame.sql` | 网课用户期望、关键帧表 |

完整建表脚本尚未入库，完整库结构见本地环境或 `agent.md` 环境清单。
