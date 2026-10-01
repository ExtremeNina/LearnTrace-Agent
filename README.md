# 学迹 Agent

AI 个人学习工作台：学习资产（网课 / 题目 / 笔记）+ 知识点页 + Human-in-the-loop 的 Agent 对话。记录你与知识发生过什么，并把它们连成网。

![技术栈](https://img.shields.io/badge/Vue_3-TypeScript-42b883) ![后端](https://img.shields.io/badge/Spring_Boot_3.4-MyBatis--Plus-6db33f) ![存储](https://img.shields.io/badge/MySQL_8-Redis-RabbitMQ-4479a1) ![AI](https://img.shields.io/badge/Spring_AI-DeepSeek-8b5cf6)

## 界面预览

| Agent 对话 | 笔记整理 |
| --- | --- |
| ![Agent 对话](docs/images/demo-agent.jpg) | ![笔记整理](docs/images/demo-notes.jpg) |
| **网课记录** | **网课详情 · AI 笔记** |
| ![网课记录](docs/images/demo-courses.jpg) | ![网课详情](docs/images/demo-course-detail.jpg) |

## 技术栈

- **前端**：Vue 3 + TypeScript + Tailwind CSS（Vite 构建，桌面 / 移动端响应式）
- **后端**：Spring Boot 3.4 + MyBatis-Plus + MySQL 8（库名 `xueji`）+ Redis + RabbitMQ + Sa-Token
- **AI**：Spring AI 接 DeepSeek（对话与笔记生成）；语音转写 Qwen-Audio ASR（阿里云百炼）；OCR 百度 PaddleOCR（AI Studio 异步任务）；图片存储阿里云 OSS

## 功能现状

### 会话
- 多轮流式对话（WebSocket，Redis 记忆滑窗）、会话历史与标题、对话图片上传（OSS）
- 拍照解题：图片 OCR → 文本格式化 → AI 整理解答 → 确认后保存进拍照记录
- 消息级操作条（复制可用）、解答回复带「保存到拍照记录」引导

### 拍照记录
- 题目分页列表（每页 10 条）、详情、编辑（题目 / 解答 / 我的作答 / 笔记）、逻辑删除
- 按日期 + 学科筛选（学科由 AI 保存题目时自动分类，可手动修改）
- 保存内容由模型先整理（清洗「说明：…补全为…」等元叙述），imageUrl 由服务端确定性获取
- 学科选项：数学 / 语文 / 英语 / 物理 / 化学 / 生物 / 历史 / 地理 / 政治 / 计算机 / 其他

### 网课
- 上传视频 → RabbitMQ 流水线：FFmpeg 抽音频与关键帧 → ASR 分片转写（长音频 280s 分片 + 句级时间戳合并）→ 批量帧 OCR → LLM 生成 AI 笔记入库
- AI 笔记固定结构：课程概览（一句话概括 + 主要内容）→ 章节时间线（表格，时间戳可点击跳转视频）→ 知识点 → 总结
- 列表（标题搜索 / 状态 / 日期 / 学科筛选）、详情三标签（AI 笔记 / 转写对照 / 关键帧识别）、在线编辑标题与学科
- 视频在线播放，笔记与转写中的时间戳点击即跳转对应片段

### 笔记整理
- OneNote 式分层树：自定义分组最多 5 层，新建 / 重命名 / 移动 / 删除（分组删除递归级联，带确认警告）
- 双轨编辑：手动笔记富文本、AI 笔记 Markdown 源码（含工具按钮 / 预览 / 编辑与预览滚动同步）；点击空白区域即保存
- 知识联系右侧边栏：卡片展示关联的网课 / 题目 / 笔记及一句关联说明；弹窗聚合搜索挂链；点击卡片跳转（网课带时间戳、题目自动弹出详情）
- 页面切换后保留上次打开的笔记与树状态（KeepAlive）

## 架构与核心链路

**对话与拍照解题**（`ws/` + `ai/`）：

```text
用户消息 ──WebSocket(/ws/agent)──► AgentWebSocketHandler
                                     │ 回合互斥（conversationId 粒度）
                                     ▼
                         AgentChatService（Spring AI ChatClient 流式）
                                     │ 带图消息：OCR 前置流水线
                                     │   OcrTool（PaddleOCR 异步任务）→ OcrTextFormatter → 拼入 prompt
                                     │ 工具：QuestionSaveTool（ToolContext 携带 userId/conversationId）
                                     ▼
                    ChatEvent 流（DELTA / COMPLETE / STOP / ERROR）
                                     │
                    message 表双写（事实源） + Redis 会话记忆（advisor 自动读写）
```

**网课处理流水线**（`mq/` + `service/`）：

```text
上传视频 ──► RabbitMQ(CourseProcessConsumer) ──► CoursePipelineService
    ├─ FFmpeg 抽音频（280s 分片）→ QwenAsrTool 转写（句级时间戳）
    ├─ FFmpeg 场景抽帧（fps_mode vfr）→ OcrTool 批量帧 OCR
    └─ 转写 + 帧 OCR + 用户期望 → NoteGenerationService 生成 AI 笔记 → 落库
```

## 目录结构

```text
LJ-Agent/
├── README.md / 学迹PRD.md / agent.md   # 项目说明 / 产品需求 / 开发规范与交接状态
├── springboot/                          # 后端（Spring Boot 3.4，端口 9090）
│   └── src/main/java/com/xueji/agent/
│       ├── ai/        # Spring AI 编排：对话、笔记生成、工具（OCR/ASR/保存题目）
│       ├── controller/# REST 接口（auth / conversations / question / courses / notes / file）
│       ├── service/   # 业务逻辑（对话、题目、网课流水线、笔记树与知识联系）
│       ├── mq/        # RabbitMQ 消费者（网课流水线入口）
│       ├── ws/        # WebSocket 处理器与握手鉴权
│       ├── config/    # 线程池 / MQ / Sa-Token / CORS / Spring AI / WebSocket 配置
│       ├── domain/    # entity / dto / vo / enums
│       ├── mapper/    # MyBatis-Plus Mapper
│       ├── utils/     # OSS 上传、Redis 工具
│       └── common/ exception/  # 统一响应 / 键名收口 / 业务异常
└── web/                                 # 前端（Vue 3 + Vite，端口 5173）
    └── src/
        ├── api/     # 后端接口封装（统一 Result 解包与 token 注入）
        ├── stores/  # Pinia：agent（对话）/ auth / ui
        ├── ws/      # WebSocket 封装（指数退避自动重连）
        ├── utils/   # Markdown + KaTeX 渲染（DOMPurify 消毒）
        └── views/   # Agent / History / Questions / Courses / CourseDetail / Notes / Login / Register
```

## 测试

```bash
cd springboot && mvn test   # 63 个单元测试（9 个测试类，含 FFmpeg 真实调用用例）
cd web && npm run build     # 前端类型检查与构建
```
