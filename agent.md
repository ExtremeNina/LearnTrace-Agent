# 学迹 Agent — agent.md

AI 个人学习工作台：学习资产（网课 / 题目 / 笔记）+ 知识点页 + Human-in-the-loop 的 Agent 对话。
技术栈：Vue 3 + TypeScript + Tailwind 响应式前端（桌面与手机端都要可用，移动端 Sidebar 折叠为抽屉）；Spring Boot 3 + MyBatis-Plus + MySQL + Redis + RabbitMQ 后端。AI 框架使用 Spring AI，LLM 为 DeepSeek，OCR 接入阿里云第三方 API。

进度以 git 提交为准（`git log --oneline` 近 30 条可完整恢复上下文）。当前（2026-09-30 交接）：切片一（会话骨架）完成；切片二（题目记录）大部分完成——OCR 前置流水线、解答与保存引导（QuestionSaveTool）、列表/详情/编辑删除/日期筛选分页已提交，剩余相似题生成与保存；切片三（网课）大部分完成——上传→RabbitMQ→FFmpeg→ASR 分片转写→批量帧 OCR→LLM 笔记生成入库全链路已用 3 个真实视频（约 10 分钟/个）验证 SUCCESS，前端列表/详情/上传弹窗已接真数据，剩余 AI 笔记的确认环节与章节生成。详细待办、决策待定事项与环境清单见下方「当前状态与待办」；踩坑记录见「经验坑清单」。切片顺序固定：一会话骨架 → 二题目记录 → 三网课。

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

**构建 / 环境**
* Lombok 需 1.18.48（pom 覆盖父 POM），否则新 JDK 报 `TypeTag :: UNKNOWN`
* Sa-Token 1.40 的 `SaHolder` 在 `cn.dev33.satoken.context` 包；CORS 预检（OPTIONS）必须在拦截器中跳过登录校验
* Spring AI 依赖加入后 javac 注解处理器发现会失效（Lombok 不运行）→ pom 已配置 `annotationProcessorPaths` 显式指定 Lombok
* IDEA 自动保存会覆盖外部修改、target 增量编译状态会脏 → 外部改文件后编译报诡异错误先 `mvn clean`；错误行号与源码对不上时怀疑 IDE 缓冲区回写
* Git Bash 里 curl 发中文表单/JSON 会 GBK 乱码 → 接口测试用 Node fetch（Node 24 自带全局 WebSocket/fetch，WS 测试脚本见 `web/test-ws.mjs`）

## 当前状态与待办（2026-09-30 交接）

**已完成并验证**：三个切片的核心链路均有真实数据验证——会话多轮流式对话；3 个约 10 分钟真实网课视频全流水线 SUCCESS（转写句级分段 9 段、关键帧 73 张识别 66 成功、AI 笔记 3 份入库且用户期望被遵守、笔记含 [mm:ss] 时间戳引用）；笔记分层树 + 知识联系前后端完整闭环。

**待办（建议顺序）**：
1. ~~AI 笔记确认环节~~ → **已搁置**（2026-10-02 用户拍板：测试期 AI 笔记直接入库，确认卡片为上线前后期功能，PRD §7/§10 已标注）
2. 相似题入口（PRD §8）：保存通道已完成（similar_question 表 + 拍照记录合并列表 + 「AI 生成」标记），剩余题目详情页「生成相似题」按钮与上下文注入
3. ~~三个小件打包：会话 100 次上限、每日 AI 配额（Redis Lua）、会话 30 天清理（Scheduler）~~ → 100 次上限与 30 天清理已完成；每日 AI 配额与 B06 一并**搁置**（测试期需要开放额度，上线前启用）
4. 学习轨迹数据层（learning_record 打点：看过网课 / 做过题）
5. ~~RAG 检索（rag_search，PRD §3.6）~~ → 已完成（题目 + 相似题 + 笔记 + 网课转写统一向量化，rag_search 带来源标记；B10 剩余：召回排序加权与混合检索）
6. 对话上传视频入口——讨论过方案 B（轻量抽帧预览 + 确认后归档），用户搁置待总体考量

**决策待定（新会话需用户重新拍板）**：上述 1 与 6 的方针；网课"章节"是否独立生成（当前并入笔记正文）。

**保留想法（用户已提出、暂不实施）**：笔记编辑器集成 Tiptap——AI 笔记所见即所得（tiptap-markdown 转换层，存储仍为 Markdown 不变）+ 时间戳胶囊做成 Tiptap 自定义节点（编辑中不可拆坏、可点击）+ 手动笔记编辑器从 execCommand 迁移到 Tiptap（存 HTML 不变）；动手前先做 md 往返转换 spike，验证表格 / 时间戳 / 加粗列表不失真。流水线 LLM 输出 JSON 化缓行：收益依赖 RAG、按块润色等尚未立项的下游，且会加重"修订初稿"的编辑难题。

**环境清单（不在 git 里，丢失按此重建，约 10 分钟）**：
* `springboot/application-local.properties`（gitignored）：OSS（endpoint=武汉 lr 区）/ OCR / Qwen ASR 的密钥与 RabbitMQ 凭据——若丢失，凭据见阿里云控制台与 AI Studio，格式参照 agent.md 历史提交
* RabbitMQ 容器 `rabbitmq`（5672）：内含用户 `xueji/xueji123`（需 `rabbitmqctl set_permissions -p / xueji ".*" ".*" ".*"`）；容器重建后重建用户
* MySQL `xueji` 库：14 张表 + 种子/测试数据（笔记分层树、3 个课程的完整流水线数据）
* 用户级环境变量（setx）：OSS_ACCESS_KEY / OSS_SECRET_KEY / OSS_BUCKET / OSS_ENDPOINT / RABBITMQ_USER / RABBITMQ_PASS（本地开发已不依赖，走本地配置文件）
* 新会话热身三步：`mvn test`（56 个）→ `npm run build`（web）→ 后端启动冒烟（登录 + /courses + /notes/tree）

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
