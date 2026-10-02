# M5 基础设施（认证 / 上传 / 配置 / 调度）

## 职责与业务
Sa-Token 登录注册与会话鉴权（除注册 / 登录 / WS 握手外全拦截，CORS 预检放行）、对话图片上传 OSS、Spring AI 装配（ChatClient / 记忆 / 工具 / Embedding / VectorStore）、线程池、WS 握手鉴权、RabbitMQ 声明、定时清理调度。

## 边界（详细）

**输入 / 输出**
- REST `POST /auth/register`（用户名 + 密码）、`POST /auth/login`（返回 Sa-Token uuid token）、`GET /auth/info`
- REST `POST /upload/image`（对话图片 → OSS，返回 URL，随后写进消息 payload）
- WS 握手：query 参数 token → Sa-Token 校验 → userId 注入 session attributes（无 token 拒绝握手）
- 定时：CleanupScheduler 每日 3 点（会话 30 天清理，见 M1）

**依赖**
- MySQL / Redis（db1：sa-token + 会话记忆 + 缓存）/ RabbitMQ / 阿里云 OSS（武汉 lr）
- 本地私密配置 application-local.properties（git 忽略）：OSS / OCR / ASR / DeepSeek / embedding / RabbitMQ 凭据——全部经 @Value 或 spring.config.import 注入，DeepSeek 密钥已移出 application.yml（6d18610）
- 启动期密钥缺失快速失败（AliUploadUtils @PostConstruct 校验）

**不做（边界外）**
- 邀请码注册、双 Token + Refresh Token（backlog B06，PRD §18）
- 图片 10MB 上限校验（B06）；多实例部署支持（B05）
- 业务鉴权外的权限体系（无角色 / 管理员概念）

**约束与已知偏差**
- Sa-Token 与其他项目共用 db1 且键前缀相同（agent.md 已知问题：他项目 token 可通过本系统鉴权，建议改 token-name）
- SpringAIConfig：EmbeddingModel 标记 @Primary 覆盖 openai starter 默认实例（指向百炼）；embeddingsPath 显式覆盖避免 /v1 叠加 404；VectorStore 指向独立 redis-stack 6380
- OCR 双实现并存：PaddleOcrTool（默认，按 Bean 名注入）+ AliyunOcrTool（备选）——同名漂移即错注（B09 记录）
- 线程池：courseExecutor（流水线 / 向量化异步），禁 @Async

## 测试方法
- 单元：ResultTest（统一响应结构）
- 覆盖较薄（如实记录）：Auth 注册 / 登录 / 越权、FileController 上传——依赖 B06 鉴权改造时一并补
- 配置验证：启动冒烟（登录 + /courses + /notes/tree + 应用日志无 BeanCreationException）
- 手动：图片上传落 OSS、密钥缺失时启动失败提示、Redis 6380 索引自动创建（FT._LIST）
