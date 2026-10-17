# Docker 一键部署（LearnTrace / 学迹）

本项目的部署目标是**本机 / 局域网自托管**：一条 `docker compose` 命令拉起全部依赖，不依赖公网运营设施。

## 前置要求

- Docker Desktop（含 Compose v2）
- 至少 6GB 可用内存、20GB 磁盘
- 可访问外网（DeepSeek / 阿里云百炼 / 百度 OCR / 阿里云 OSS）

## 三步启动

```bash
# 1. 复制环境变量模板
cp .env.example .env

# 2. 编辑 .env，至少填写这些（其余可留空）
#    DB_PASSWORD / RABBITMQ_PASS      数据库与 MQ 密码（自定义）
#    DEEPSEEK_API_KEY                 对话 / 笔记 / 简报
#    QWEN_ASR_API_KEY                 语音转写
#    PADDLE_OCR_TOKEN                 图片 OCR
#    OSS_ACCESS_KEY / OSS_SECRET_KEY / OSS_BUCKET   视频与图片存储
#    XJ_EMBEDDING_API_KEY             RAG 向量化

# 3. 构建并启动
docker compose up -d --build

# 打开 http://localhost:8080（端口由 .env 的 WEB_PORT 决定）
```

首次启动会构建镜像（Maven / npm 依赖下载），约 5~15 分钟；之后的启动秒级完成。

## 服务拓扑

| 服务 | 镜像 | 说明 |
|---|---|---|
| frontend | nginx:1.27-alpine | 托管前端静态资源；反代 `/api`（剥前缀）与 `/ws`（WebSocket Upgrade） |
| backend | eclipse-temurin:21-jre-jammy | Spring Boot；内置 **ffmpeg**（抽音频/关键帧）与 **yt-dlp**（B 站链接） |
| mysql | mysql:8.0.39 | 首次启动自动导入 `docker/mysql/init/01-schema.sql` |
| redis | redis:7.2-alpine | 会话记忆 / 缓存 / Sa-Token 会话 |
| redis-stack | redis/redis-stack-server:7.4.0-v1 | **RAG 向量库**（必须含 RediSearch，普通 redis 不支持） |
| rabbitmq | rabbitmq:3.13-management-alpine | 视频处理流水线消息队列 |

仅 `frontend` 暴露宿主端口；数据库/缓存/MQ 只在容器内网可达（减少端口冲突与暴露面）。

## 关键说明

### 数据库初始化只执行一次

`docker/mysql/init/*.sql` 由 MySQL 官方镜像在**数据卷为空时**执行一次。修改 schema 后不会自动生效，需要：

```bash
docker compose down -v      # 删除数据卷（会清空数据！）
docker compose up -d --build
```

日常备份：

```bash
docker compose exec mysql mysqldump -uroot -p"$DB_PASSWORD" xueji > backup.sql
```

### 时区

所有服务统一 `TZ=Asia/Shanghai`、MySQL `default-time-zone=+08:00`。每日简报、复习到期等按日期计算的逻辑依赖这一点，改成 UTC 会导致整体差 8 小时。

### B 站链接与 cookies

- 普通投稿视频：容器内 yt-dlp 以游客模式即可拉取
- 课程/课堂类视频：需要登录态。容器内无法读取浏览器 cookies，请导出 `cookies.txt` 放到 `./secrets/bili-cookies.txt`（compose 已只读挂载 `./secrets`），后续可在后端配置 `--cookies` 参数启用
- yt-dlp 会随镜像构建更新；B 站接口变化导致失败时，重新 `docker compose build backend` 即可升级

### 访问地址

- 本机：`http://localhost:8080`
- 局域网：`http://<你的内网IP>:8080`（WS 地址按页面 host 自动推导，无需改配置）

## 常见问题

| 现象 | 排查 |
|---|---|
| 8080 被占用 | 改 `.env` 的 `WEB_PORT` 后 `docker compose up -d` |
| 页面 502 | `docker compose ps` 看 backend 是否 healthy；`docker compose logs backend` |
| 对话/简报失败 | 检查 `DEEPSEEK_API_KEY` 是否有效、容器能否出网 |
| 建课卡在 PENDING | `docker compose logs backend` 看 RabbitMQ 连接与 ffmpeg 抽帧错误 |
| 视频过大上传失败 | nginx `client_max_body_size` 与后端 multipart 均为 1GB，超出需同时调整 |
| 修改 schema 不生效 | 见上文「数据库初始化只执行一次」，需 `down -v` |

## 提交远程前检查

```bash
git status                 # 不应出现 .env / docker/mysql/data / 测试视频
docker compose config      # 校验变量替换（不应出现明文密钥）
docker compose down -v && docker compose up -d --build   # 冷启动复验
```

`.env`、`secrets/`、`docker/mysql/data/` 均已在 `.gitignore` 中排除。
