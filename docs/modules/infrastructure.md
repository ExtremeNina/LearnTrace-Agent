# M5 基础设施（认证 / 用户 / 上传 / 配置 / 调度）

## 职责与业务
Sa-Token 登录注册与会话鉴权（除注册 / 登录 / WS 握手外全拦截，CORS 预检放行）、用户个人页面（资料 / 偏好 / 改密 / 注销）、对话图片上传 OSS、Spring AI 装配（ChatClient / 记忆 / 工具 / Embedding / VectorStore）、线程池、WS 握手鉴权、RabbitMQ 声明、定时任务装配（task/ 包）。

## 边界（详细）

**输入 / 输出**
- REST `POST /auth/register`（用户名 + 密码）、`POST /auth/login`（返回 Sa-Token uuid token；已注销账号拒绝登录）、`POST /auth/logout`、`GET /auth/info`
- REST `GET/PUT /users/me`（资料：头像 / 昵称 / 邮箱 / 简介，昵称必填）、`PUT /users/me/preferences`（theme LIGHT/DARK + notifyTaskEnabled）、`PUT /users/me/password`（校验原密码，新密码 ≥6 位）、`POST /users/me/delete`（密码确认注销：逻辑删除 + StpUtil.logout；学习资产保留）
- REST `POST /upload/image`（对话图片 / 头像 → OSS，返回 URL）
- WS 握手：query 参数 token → Sa-Token 校验 → userId 注入 session attributes（无 token 拒绝握手）
- 定时（task/ 包）：CleanupScheduler 每日 3 点（会话 30 天清理，见 M1）、RagRepairScheduler（启动 20s 全量回填 + 每 3 小时水位补漏，见 M2）、CourseWatchScheduler（每 10 分钟自愈超时 PROCESSING，见 M3）

**依赖**
- MySQL / Redis（db1：sa-token + 会话记忆 + 缓存）/ RabbitMQ / 阿里云 OSS（武汉 lr）
- 本地私密配置 application-local.properties（git 忽略）：OSS / OCR / ASR / DeepSeek / embedding / RabbitMQ 凭据——全部经 @Value 或 spring.config.import 注入
- 启动期密钥缺失快速失败（AliUploadUtils @PostConstruct 校验）

**不做（边界外）**
- 邀请码注册、双 Token + Refresh Token、每日 AI 配额（B06 整体**搁置**——测试期开放额度与注册，公开部署前启用）
- 图片 10MB 上限校验（随 B06 启用时补）；多实例部署支持（Redisson 随 B05 后置）
- 业务鉴权外的权限体系（无角色 / 管理员概念）

**约束与已知偏差**
- Sa-Token 与其他项目共用 db1 且键前缀相同（他项目 token 可通过本系统鉴权——B06 搁置期间接受，公开部署前改 token-name）
- 归属校验收口：`common/OwnershipCheck.requireOwned` + `common/UserOwned` 接口（实体实现，无软删位者缺省视为未删除），禁止再手写同形校验
- SpringAIConfig：EmbeddingModel 标记 @Primary 覆盖 openai starter 默认实例（指向百炼）；embeddingsPath 显式覆盖避免 /v1 叠加 404；VectorStore 指向独立 redis-stack 6380（metadataFields：userId / subject / type 三个 TAG）
- OCR 双实现：PaddleOcrTool（默认，@Primary）+ AliyunOcrTool（备选，@Lazy——无人消费不启动期构造）；切换实现用 @Resource(name="aliyunOcrTool") 显式按名注入
- 线程池：courseExecutor（流水线 / 向量化 / OSS 删除异步），禁 @Async
- 表结构演进走 `resources/sql/*.sql` 碎片（user_profile / similar_question_model / question_record_image_optional 等），流程见 project-map 数据库操作

## 测试方法
- 单元：ResultTest（统一响应结构）、UserServiceImplTest（资料 trim 与空昵称拒绝 / 偏好主题校验与通知开关 / 改密旧密码校验与 BCrypt 落库 / 注销密码确认与逻辑删除）
- 覆盖较薄（如实记录）：Auth 注册 / 登录 / 越权、FileController 上传——依赖 B06 鉴权改造时一并补
- 端到端冒烟：`node web/test-profile-smoke.mjs`（个人页面全生命周期 14 断言）
- 配置验证：启动冒烟（登录 + /courses + /notes/tree + 应用日志无 BeanCreationException）；Redis 6380 索引自动创建（FT.INFO 查 $.type TAG）
- 手动：图片上传落 OSS、密钥缺失时启动失败提示
