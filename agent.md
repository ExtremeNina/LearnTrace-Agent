# 学迹 Agent — agent.md

AI 个人学习工作台：学习资产（网课 / 题目 / 笔记）+ 知识点页 + Human-in-the-loop 的 Agent 对话。
技术栈：Vue 3 + TypeScript + Tailwind 响应式前端（桌面与手机端都要可用，移动端 Sidebar 折叠为抽屉）；Spring Boot 3 + MyBatis-Plus + MySQL + Redis + RabbitMQ 后端。AI 框架使用 Spring AI，LLM 为 DeepSeek，OCR 接入阿里云第三方 API。

进度以 git 提交为准（`git log --oneline` 近 30 条可完整恢复上下文）。当前（2026-10-03 交接）：切片一（会话骨架）完成；切片二（题目记录）**完成**——相似题闭环落地（RAG 召回 → 生成 → 确认保存入 `similar_question` 表，拍照记录列表合并展示并标「AI 生成」，题目详情页有生成入口）；切片三（网课）基本完成——全流水线已用 3 个真实视频验证 SUCCESS，AI 笔记确认环节经用户拍板搁置（上线前后期功能），新增网课删除（批量）与处理超时自愈。横向能力：RAG 统一摄取（题目 / 相似题 / 笔记分块 / 网课转写，`rag_search` 带来源标记 + 水位补漏定时任务）；会话记忆 MySQL 重建兜底（清空 Redis 功能不变）；OCR 移入弹性线程（默认 45s 超时降级）；个人页面（资料 / 浅色深色主题 / 任务通知 / 改密 / 注销）。详细待办、决策待定事项与环境清单见下方「当前状态与待办」；踩坑记录见「经验坑清单」。

已知遗留问题（部分为早期记录，动手前先复核是否仍存在）：对话图片上传曾因 OSS 配置不可用（bucket `jj-fruit-store` 实际位于武汉 region，endpoint 默认杭州；AK/SK 环境变量后端进程读不到），2026-09-27 已改用 git 忽略的本地配置文件方案（见「环境清单」）；`AliUploadUtils` 已有 `@PostConstruct` 启动期密钥校验；`uploadChatImage` 只捕获 IOException（OSSException 落全局兜底）与前端 `Agent.vue` 未渲染上传失败提示，这两项**未复核**。另：Redis db1 与其他项目共用且 sa-token 键前缀相同（`sa-token:`），他项目 token 可通过本系统鉴权，建议将 `token-name` 改为独有值隔离。

## 经验坑清单（踩坑记录，遇到同类问题先查这里）

**FFmpeg / 音视频**
* 本机 FFmpeg 为 2026 新版，已移除 `-vsync` → 抽帧一律用 `-fps_mode vfr`（旧命令整体失败且不易察觉）
* `fps=1/60` 之类滤镜对短视频会取整为 0 帧 → 抽帧回退要用 `select='eq(n,0)+not(mod(n,1500))'`（帧号采样）+ 第 0 帧兜底
* 场景检测滤镜内逗号在单引号保护下无需转义；ProcessBuilder 无 shell，参数原样传

**阿里云 ASR（Qwen-Audio-3.1-ASR-Flash，百炼专属部署）**
* 单次音频上限 300s → 长音频按 280s 分片转写（`-ss/-t` 切片），句级时间戳加偏移合并
* 实际响应不是标准多模态结构，是句级结构（顶层 `text` / `sentence.begin_time/end_time/text/words`），解析要兼容多形态
* 错误码 `ASR_RESPONSE_HAVE_NO_WORDS` = 无语音（静音/正弦音测试视频），属正常降级

**阿里云 OCR（读光 OCR，ocr_api20210707 SDK 3.1.3）**
* 必须用「OCR 统一识别」接口 `recognizeAllText`，Type 枚举值为 `General`（`BasicOcr` 无效；`RecognizeGeneral` 是旧产品线，未开通会报 401）
* 响应正文在 `body.data.getContent()`

**百度 PaddleOCR（AI Studio 星河社区，异步任务流）**
* 结果 NDJSON 在百度云 BOS **签名 URL** 上：签名对 URL 字节敏感，RestClient 的 URI 模板会二次编码（`%2F`→`%252F`）导致 SignatureDoesNotMatch → 必须用 `java.net.URI.create(url).toURL().openStream()` 裸请求
* bucket `jj-fruit-store` 实际在 `oss-cn-wuhan-lr`（不在杭州）——OSS endpoint 错了会报 AccessDenied

**RabbitMQ**
* guest 只允许容器内 localhost 登录，Docker NAT 后来源是网关 IP 会被拒 → 容器内已建用户 `xueji/xueji123`（凭据同时记在 `application-local.properties`；**容器重建会丢，需重建用户**）
* Spring AMQP 3.x 禁止 JDK 序列化反序列化（HashMap 直接被拒）→ MQ 负载一律 JSON 字符串
* 监听容器认证失败会中止启动（SimpleMessageListenerContainer fatal）→ 认证问题表现为**整个应用起不来**，不只是 MQ 不可用

**Spring AI 1.1.8**
* `MessageChatMemoryAdvisor` 在 `org.springframework.ai.chat.client.advisor` 包（不在 chat.memory）
* 笔记生成等非对话 LLM 调用：用独立 conversationId（如 `course-note-{id}`）隔离记忆，不污染用户对话
* NoteGenerationService.buildUserContent 为公开静态组装方法，配套单测

**Spring AI RedisVectorStore（RAG）**
* 元数据经 JSON.SET 全量存取、检索时完整返回，`metadataFields` 只声明**可过滤**字段；改字段清单后必须 `FT.DROPINDEX` 重建索引（`initializeSchema` 对已存在的索引只跳过不更新，不会报错也不会改 schema）
* 两张表的自增主键不能直接当文档 ID（必然冲突）→ 加业务前缀：题目 `q:`、相似题 `sq:`、笔记 `note:{id}:{块}`、转写 `transcript:c{课程ID}:{分段ID}`
* Spring AI 的 `Message` 与业务实体 `Message` 同名：同文件都要用时，其一写全限定名；冲突的编译报错会以大量"找不到符号 log/getter"的 Lombok 假错误级联出现，先查 import
* `ChatMemoryRepository.findByConversationId` 里做重建兜底时，必须排除"末尾未配对的用户消息"（回合进行中刚落库），否则记忆 Advisor 再写入会导致同一条消息在记忆里重复

**前端（Vue 3 + Tailwind v4）**
* 深色主题 = `html.dark` 翻转 @theme 同名 CSS 变量，组件不分主题写法；界面颜色一律用语义令牌（ink / ink-2 / panel / line / primary-soft / surface），**禁止 `bg-white` 和硬编码 hex**（surface 令牌即卡片表面色）
* `agentSocket.sendMessage` 在连接未 OPEN 时会把消息暂存 outbox、OPEN 后冲刷——跨页自动发送（如种子消息）依赖此机制，不要再写"连接后才许发"的前置判断
* Git Bash 里 `grep -rl | xargs sed` 在 Windows 会因路径反斜杠断掉 → 用 `find . -name "*.vue" -exec sed -i 's/…/…/g' {} +`

**构建 / 环境**
* Lombok 需 1.18.48（pom 覆盖父 POM），否则新 JDK 报 `TypeTag :: UNKNOWN`
* Sa-Token 1.40 的 `SaHolder` 在 `cn.dev33.satoken.context` 包；CORS 预检（OPTIONS）必须在拦截器中跳过登录校验
* Spring AI 依赖加入后 javac 注解处理器发现会失效（Lombok 不运行）→ pom 已配置 `annotationProcessorPaths` 显式指定 Lombok
* IDEA 自动保存会覆盖外部修改、target 增量编译状态会脏 → 外部改文件后编译报诡异错误先 `mvn clean`；错误行号与源码对不上时怀疑 IDE 缓冲区回写
* Git Bash 里 curl 发中文表单/JSON 会 GBK 乱码 → 接口测试用 Node fetch（Node 24 自带全局 WebSocket/fetch，WS 测试脚本见 `web/test-ws.mjs`）

## 当前状态与待办（2026-10-03 交接）

**已完成并验证**：三个切片核心链路 + 横向能力均有真实数据验证——会话多轮流式对话；3 个约 10 分钟真实网课视频全流水线 SUCCESS（转写句级分段 9 段、关键帧 73 张识别 66 成功、AI 笔记 3 份入库且用户期望被遵守、笔记含 [mm:ss] 时间戳引用）；笔记分层树 + 知识联系前后端完整闭环；RAG 统一向量化（题目 / 相似题 / 笔记 / 网课转写）真实回填 32 条验证；相似题全流程（生成入口 → 对话生成 → 确认保存 → 合并列表展示）；个人页面（资料 / 主题 / 通知 / 改密 / 注销）端到端冒烟 14 断言通过。

**待办（建议顺序）**：
1. ~~AI 笔记确认环节~~ → **已搁置**（2026-10-02 用户拍板：测试期 AI 笔记直接入库，确认卡片为上线前后期功能，PRD §7/§10 已标注）
2. ~~相似题入口（PRD §8）~~ → **已完成**（2026-10-03：similar_question 表 + 拍照记录合并列表 + 「AI 生成」标记 + 题目详情页「生成相似题」按钮，支持在当前会话继续 / 创建新会话，上下文经种子消息自动注入）
3. ~~三个小件打包：会话 100 次上限、每日 AI 配额（Redis Lua）、会话 30 天清理（Scheduler）~~ → 100 次上限与 30 天清理已完成；每日 AI 配额与 B06 一并**搁置**（测试期需要开放额度，上线前启用）
4. **学习轨迹数据层**（learning_record 打点：看过网课 / 做过题）+ B07 Agent 查询工具 + B10 收尾（召回排序加权）——**动工前需用户拍板打点时机**（视频播放进度上报 / 进详情页即算 / 两者结合）
5. ~~RAG 检索（rag_search，PRD §3.6）~~ → 已完成（题目 + 相似题 + 笔记 + 网课转写统一向量化，rag_search 带来源标记；B10 剩余：召回排序加权与混合检索，见上条依赖）
6. 对话上传视频入口——讨论过方案 B（轻量抽帧预览 + 确认后归档），用户搁置待总体考量

**决策待定（新会话需用户重新拍板）**：学习轨迹打点时机（见待办 4）；对话上传视频入口（待办 6）的方针；网课"章节"是否独立生成（当前并入笔记正文）。

**保留想法（用户已提出、暂不实施）**：笔记编辑器集成 Tiptap——AI 笔记所见即所得（tiptap-markdown 转换层，存储仍为 Markdown 不变）+ 时间戳胶囊做成 Tiptap 自定义节点（编辑中不可拆坏、可点击）+ 手动笔记编辑器从 execCommand 迁移到 Tiptap（存 HTML 不变）；动手前先做 md 往返转换 spike，验证表格 / 时间戳 / 加粗列表不失真。流水线 LLM 输出 JSON 化缓行：收益依赖 RAG、按块润色等尚未立项的下游，且会加重"修订初稿"的编辑难题。

**环境清单（不在 git 里，丢失按此重建，约 10 分钟）**：
* `springboot/application-local.properties`（gitignored）：OSS（endpoint=武汉 lr 区）/ OCR / Qwen ASR 的密钥与 RabbitMQ 凭据——若丢失，凭据见阿里云控制台与 AI Studio，格式参照 agent.md 历史提交
* RabbitMQ 容器 `rabbitmq`（5672）：内含用户 `xueji/xueji123`（需 `rabbitmqctl set_permissions -p / xueji ".*" ".*" ".*"`）；容器重建后重建用户
* MySQL `xueji` 库：14 张表 + 种子/测试数据（笔记分层树、3 个课程的完整流水线数据）
* 用户级环境变量（setx）：OSS_ACCESS_KEY / OSS_SECRET_KEY / OSS_BUCKET / OSS_ENDPOINT / RABBITMQ_USER / RABBITMQ_PASS（本地开发已不依赖，走本地配置文件）
* Redis 向量库容器 `redis-vector`（6380，redis-stack）：RAG 索引 `xueji-rag-idx`（JSON 存储，prefix `rag:question:`，TAG 字段 userId/subject/type）；改索引 schema 需 `docker exec redis-vector redis-cli -p 6379 FT.DROPINDEX xueji-rag-idx`，应用启动自动重建
* 新会话热身三步：`mvn test`（114 个）→ `npm run build`（web）→ 后端启动冒烟（登录 + /courses + /notes/tree）
* 可选冒烟脚本：`node web/test-profile-smoke.mjs`（个人页面全生命周期，需后端已启动）；`node web/test-ws.mjs`（WS 对话）

## 工单流程节奏（implement-spec / 多工单任务）

每张工单的生命周期由用户控制，三道关口缺一不可：

* **开工前 · 设计关**：先向用户说明本工单的设计思路（方案、影响面、取舍），主动指出可能过度设计的地方并与用户讨论；**用户明确说出"开始执行"后才能动工**。设计以工单与 spec 为界，禁止引入超出需求的抽象、参数与扩展（避免过度设计）。
* **完成后 · 评审关**：立即对该工单产出做 code-review（Standards + Spec 两轴，范围 = 该工单的 diff），修复全部发现并回归测试通过后向用户汇报结果。
* **继续前 · 同意关**：**经用户同意后才能开始下一张工单**，禁止自动流转。

禁止把多张工单攒到最后统一审查，也禁止未经用户同意自动开工：后续工单会基于前序产出构建，问题发现得越晚，修复与返工的成本越高，还会与并行开发产生冲突。

## 初期开发策略（个人项目）

* 初期只做基础实现，验收标准是本地运行成功、基础功能完整可用；生产环境考量（性能、部署、高可用等）一律后置，待基础功能全部开发完毕后再统一进入优化阶段
* 测试只做基本验证：每个功能完成后人工跑通正常路径与关键异常路径，核对数据库 / Redis 实际状态；不搭测试脚手架、不写自动化测试套件
* 设计讨论中产生的方案一律按"够用即可"裁剪，禁止为未来场景预先引入复杂度（如 stub 测试层、生产级容错）

## 硬性规范（每次写代码都适用）

* 依赖注入统一使用 `@Resource`（jakarta.annotation），不使用 `@Autowired`，不使用 Lombok 的 `@RequiredArgsConstructor` 构造器注入
* 枚举类（状态词表、可枚举取值）统一定义在 `domain/enums` 包；基础设施键名注册表（MqKeys / RedisKeys）不属于枚举，留在 `common` 包

* 禁止 Lambda 表达式：集合处理用显式循环；MyBatis-Plus 用字符串列名的 `QueryWrapper`，不用 LambdaQueryWrapper
* 禁止 `@Async`：需要线程时使用 config 包统一定义的独立 `ThreadPoolExecutor` 显式提交；长耗时任务走 RabbitMQ
* 业务代码不直接调 LLM API，统一经 Spring AI
  * 豁免：QwenAsrTool（网课语音转写）用裸 RestClient 直连百炼 DashScope 专属部署端点——ASR 是前置流水线的语音识别而非对话推理，Spring AI 1.1.8 无对应 ASR 抽象（该端点为百炼专属部署，非 OpenAI 兼容协议）
* Agent 涉及用户数据的创建 / 修改 / 删除 / 持久化，必须先获得用户在对话中的明确确认后才执行（如用户回复「保存」；确认卡片组件为后期功能，见 PRD §7，测试期以对话确认为准，不算缺陷）
* 题目 OCR 指定阿里云（PRD §3.3 原文）实为百度 PaddleOCR——历史上为版面还原（结构化 Markdown，试卷 / 表格更好）有意切换的前置流水线决策，不算偏差；实现须经 `OcrTool` 接口 + SpringAIConfig Bean 装配，换回阿里云只需切换 Bean
* Git 提交规范（Conventional Commits，中文描述）：格式 `<类型>: 中文描述`，类型包括 `feat`（新功能）/ `fix`（缺陷修复）/ `docs`（文档）/ `style`（格式调整，不改逻辑）/ `refactor`（重构，不改行为）/ `test`（测试）/ `chore`（构建与杂务）；每完成一个独立功能提交一次，单一职责
* 前端界面颜色一律使用语义令牌（ink / ink-2 / panel / line / surface / primary / primary-soft），禁止 `bg-white` 与硬编码 hex——深色主题靠 `html.dark` 翻转变量全局生效，硬编码色不随主题切换

## 数据库

* 目标库 `xueji`（root / 123456，MySQL 8.0），所有建表与数据操作都在该库执行
* 执行 SQL：`mysql --default-character-set=utf8mb4 -uroot -p123456 xueji < 文件.sql`——utf8mb4 必带，否则中文默认值与注释乱码
* 改表三步：改《数据库设计.md》→ 改 `xueji_schema.sql`（幂等，`IF NOT EXISTS`）→ 对库执行并 `SHOW TABLES` 验证

## Agent skills

### Issue tracker

议题以本地 Markdown 存于 `.scratch/<feature>/`：spec 为 `spec.md`，工单为 `issues/NN-<slug>.md`，状态用 `Status:` 行记录。见 `docs/agents/issue-tracker.md`。

### Triage labels

默认五个 triage 角色：`needs-triage` / `needs-info` / `ready-for-agent` / `ready-for-human` / `wontfix`，以 `Status:` 行写入议题文件。见 `docs/agents/triage-labels.md`。

### Domain docs

单上下文：根 `CONTEXT.md` + `docs/adr/`，缺失时静默继续；术语暂时以《学迹PRD.md》为准。见 `docs/agents/domain.md`。
