# 学迹 Agent — agent.md

AI 个人学习工作台：学习资产（网课 / 题目 / 笔记）+ 知识点页 + Agent 对话。
技术栈：Vue 3 + TypeScript + Tailwind 响应式前端（桌面与手机端都要可用，移动端 Sidebar 折叠为抽屉）；Spring Boot 3 + MyBatis-Plus + MySQL + Redis + RabbitMQ 后端。AI 框架使用 Spring AI，LLM 为 DeepSeek。

> 本文件只放：**文档地图（去哪搜）+ 经验坑清单 + 编码规范与流程 + 长期规划**。
> 业务功能在哪个模块、数据表、环境重建、当前进度 → 见 `docs/project-map.md`；各模块详细边界与测试方法 → 见 `docs/modules/`；待办与优化项详情 → 见 `docs/backlog/`。

## 文档地图（遇到问题先查哪里）

| 要找什么 | 去哪 |
| --- | --- |
| 某功能在哪个模块实现、模块依赖什么 | `docs/project-map.md`（项目地图 · 模块索引） |
| 某模块的输入输出边界、约束与已知偏差、测试方法 | `docs/modules/`：`agent-chat.md`（会话与对话）/ `questions-rag.md`（题目与 RAG）/ `video-pipeline.md`(网课流水线) / `notes-wiki.md`（笔记）/ `infrastructure.md`（认证 / 上传 / 配置 / 调度） |
| 数据库表结构、SQL 执行与改表流程、环境重建、冒烟脚本 | `docs/project-map.md`（数据库操作 / 环境清单） |
| 项目当前进度、已知遗留与运行注意事项 | `docs/project-map.md`（当前状态） |
| 待办与优化项（B 编号）、状态与完成记录 | `docs/backlog/`（README 总表 + 各 B 号文件） |
| 产品行为规格、交互流程 | 《学迹PRD.md》（章节号在本文档中被频繁引用） |
| 写代码的禁令与风格、Git 提交规范、工单流程 | 本文件（硬性规范 / 工单流程节奏） |
| 踩过的坑 | 本文件（经验坑清单） |
| 领域名词 / 术语 | 术语暂以《学迹PRD.md》为准（`docs/agents/domain.md`：根 `CONTEXT.md` + `docs/adr/`，缺失时静默继续） |
| 议题 / 工单文件（`.scratch/<feature>/`，spec.md + issues/NN-slug.md，Status 行记录）与 triage 标签（needs-triage 等 5 类） | `docs/agents/issue-tracker.md`、`docs/agents/triage-labels.md` |

## 经验坑清单（注意事项，遇到同类问题先查这里）

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
* Git Bash 里 curl 发中文表单/JSON 会 GBK 乱码 → 接口测试用 Node fetch（Node 24 自带全局 WebSocket/fetch，冒烟脚本见 `docs/project-map.md` 冒烟脚本一节）

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
* 改表三步：改《数据库设计.md》→ 改 `resources/sql/` 增改幂等碎片 SQL → 对库执行并验证（命令与碎片清单见 `docs/project-map.md` 数据库操作）

## 工单流程节奏（implement-spec / 多工单任务）

每张工单的生命周期由用户控制，三道关口缺一不可：

* **开工前 · 设计关**：先向用户说明本工单的设计思路（方案、影响面、取舍），主动指出可能过度设计的地方并与用户讨论；**用户明确说出"开始执行"后才能动工**。设计以工单与 spec 为界，禁止引入超出需求的抽象、参数与扩展（避免过度设计）。
* **完成后 · 评审关**：立即对该工单产出做 code-review（Standards + Spec 两轴，范围 = 该工单的 diff），修复全部发现并回归测试通过后向用户汇报结果。
* **继续前 · 同意关**：**经用户同意后才能开始下一张工单**，禁止自动流转。

禁止把多张工单攒到最后统一审查，也禁止未经用户同意自动开工：后续工单会基于前序产出构建，问题发现得越晚，修复与返工的成本越高，还会与并行开发产生冲突。

## 初期开发策略（个人项目）

* 初期只做基础实现，验收标准是本地运行成功、基础功能完整可用；生产环境考量（性能、部署、高可用等）一律后置，待基础功能全部开发完毕后再统一进入优化阶段
* 测试只做基本验证：每个功能完成后人工跑通正常路径与关键异常路径，核对数据库 / Redis 实际状态；不搭测试脚手架、不写自动化测试套件（实际执行已超出此条：单测随功能补充，见 project-map 测试一节）
* 设计讨论中产生的方案一律按"够用即可"裁剪，禁止为未来场景预先引入复杂度（如 stub 测试层、生产级容错）

## 长期规划与待办（2026-10-03）

### 路线图（四方向 P0-P2，2026-10-03 用户制定）

总览：**学习闭环**（从「档案」变「工具」）、**效率与体验**（找得到、看得清、带得走）、**内容输入**（扩输入源）、**Agent 能力**（从被动到主动、可控）。
执行约定：本路线图是方向性规划，动工顺序由用户逐项指定；每项开工前仍走「工单流程节奏」设计关。与当前待办的衔接：学习轨迹打点（见下方当前待办 4）为复习排序与每日简报提供数据，建议先行或并行。

#### P0 学习闭环（最该补——错因、相似题、知识点页、知识联系已齐，缺「回头复习」，只差调度和入口）

**1. 复习系统（间隔重复 / 错题本）**〔成本：中，算法可先用 SM-2 简化版〕——**MVP 已完成（2026-10-03）**：review_card / review_log 两表 + ReviewScheduler 纯函数（三档评分：生疏重置 1 天 / 模糊 ×1.2 / 熟练 ×ease，间隔封顶 180 天）+ 今日待复习独立入口（/review，IconRail 角标）+ 题目 / 相似题 / 笔记详情「加入复习」+ 来源删除级联移出；模块文档 docs/modules/review.md。剩余增强：LLM 变体防背题、讲解增强、到点提醒
- 能力：每道错题 / 知识点标记掌握度（生疏 / 模糊 / 熟练）；按复习时间、错因、历史正确率排下次复习（FSRS 或简化 SM-2）；「今日待复习」独立入口；抽卡来源：题目、相似题、笔记、知识点；复习结果反向更新掌握度；可选到点提醒（"有 12 道题该复习了"）
- 复用：错因归档、相似题表、知识点页、Redis 向量库
- 新增：掌握度字段、复习记录表、调度算法、复习页

**2. 主动 Agent / 每日简报**〔成本：低；依赖 1〕——**MVP 已完成（2026-10-03）**：每日简报惰性生成（当天首次访问触发 LLM，落库缓存）+ /review 页顶部简报卡 + 启动时复习提醒 toast（每天一次，受通知偏好控制）+ Agent 工具 get_learning_status（B07 起步）；剩余：独立仪表盘页、服务端主动推送
- 能力：定时任务生成周报（本周新增知识点 / 错题 / 网课进度 + 复习建议）；结合复习系统推送（"该复习了，要现在开始吗？"）；学习状态问询独立入口（"我最近哪块最弱？"→ 仪表盘）
- 复用：RAG 学习状态问答能力、定时任务框架
- 新增：简报生成 prompt、推送入口、仪表盘页

**3. 练习 / 测验模式**〔成本：中；依赖 1〕
- 能力：从知识点 / 学科 / 时间段批量出题（5 题、10 题）；计时、先做后看解析；结果沉淀进错题本 → 进入复习队列
- 复用：相似题生成、RAG 检索、错题本
- 新增：批量出题接口、答题 UI、计时、成绩归档

#### P1 效率与体验

**4. 全局搜索**〔成本：低，ROI 最高〕
- 能力：一个搜索框搜题目 / 笔记 / 网课转写 / 知识点；支持语义搜索；结果按来源分组，点击跳转（网课带时间戳）
- 复用：rag_search 直接复用，加 /search 接口
- 新增：搜索入口（Ctrl+K）、结果聚合页

**5. 知识图谱可视化**〔成本：中高；建议先做局部图，全局图后置；放在「笔记整理」内做视图切换，不单开一级页〕
- 能力：知识点页局部关系图（1~2 跳）；全局关系图（节点 = 知识点/题目/网课/笔记，边 = 知识联系）；图上直接挂链 / 解链
- 复用：知识联系数据
- 新增：图渲染库（G6 / Cytoscape，选型动工时定）、布局与交互

**6. 导出与备份**〔成本：低到中〕
- 能力：笔记导出 Markdown / PDF（按分组或整库）；错题本导出 PDF / Anki 卡片；全量数据导出 JSON / ZIP；可选定时备份到 OSS
- 复用：OSS 已有
- 新增：导出服务、前端下载入口

**7. 标签 / 收藏 / 优先级**〔成本：低〕
- 能力：自定义标签（难点 / 考前必看 / 待整理）；收藏 / 置顶；优先级（用于复习排序）
- 复用：现有学科字段旁挂多标签表
- 新增：标签表、标签管理 UI、筛选联动

#### P2 内容输入扩展

**8. PDF / 文档 / 网页摄取**〔成本：低（PDF）/ 中（浏览器插件）；高 ROI，输入侧第一〕
- 能力：上传 PDF → 切块 → 向量化 → 可 RAG 检索 / 可生成笔记；浏览器插件 / 书签：网页一键剪藏成笔记
- 复用：现有 RAG 摄取管道，几乎零成本接入
- 新增：PDF 解析、切块规则、剪藏接口

**9. 语音笔记 / 录音转写**〔成本：低〕
- 能力：录音 / 上传音频 → 转写 → 生成笔记（适合课堂、讲座、通勤口述）
- 复用：视频 ASR 管道直接抽出来
- 新增：音频上传入口、独立流水线分支

**10. 公式 / 手写识别增强**〔成本：中，涉及第三方接入与费用〕
- 能力：集成公式识别（LaTeX-OCR / Mathpix 类）；手写笔记 OCR
- 复用：拍照解题流程
- 新增：公式识别服务替换/补充 PaddleOCR

#### P2 Agent 能力

**11. 多会话 / 分支 / 引用**〔成本：中〕
- 能力：从某条消息「分叉」出新会话；把某条 AI 回答直接存为笔记 / 知识点；会话内 @ 显式引用题目 / 笔记 / 网课（当前是隐式注入）
- 复用：会话体系、注入逻辑
- 新增：分叉、引用选择器、存为笔记动作

**12. Agent 工具扩展**〔成本：低到中，取决于依赖的模块是否就绪〕
- create_note（对话中直接建笔记 / 建分组）、schedule_review（把某题加入复习队列——依赖 1）、summarize_week（生成周报——依赖 2）、link_knowledge（基于向量相似度自动建议知识联系）
- 复用：现有工具集框架（OCR / 保存题目 / RAG 检索）；每个工具一个类 + 确认逻辑

### 当前待办（既定项）

**待办（建议顺序）**：
1. ~~AI 笔记确认环节~~ → **已搁置**（2026-10-02 用户拍板：测试期 AI 笔记直接入库，确认卡片为上线前后期功能，PRD §7/§10 已标注）
2. ~~相似题入口（PRD §8）~~ → **已完成**（2026-10-03：similar_question 表 + 拍照记录合并列表 + 「AI 生成」标记 + 题目详情页「生成相似题」按钮，支持在当前会话继续 / 创建新会话，上下文经种子消息自动注入）
3. ~~三个小件打包：会话 100 次上限、每日 AI 配额（Redis Lua）、会话 30 天清理（Scheduler）~~ → 100 次上限与 30 天清理已完成；每日 AI 配额与 B06 一并**搁置**（测试期需要开放额度，上线前启用）
4. **学习轨迹数据层**（learning_record 打点：看过网课 / 做过题）+ B07 Agent 查询工具 + B10 收尾（召回排序加权）——**动工前需用户拍板打点时机**（视频播放进度上报 / 进详情页即算 / 两者结合）；为路线图 P0 复习排序与每日简报提供数据，建议先行或并行
5. ~~RAG 检索（rag_search，PRD §3.6）~~ → 已完成（题目 + 相似题 + 笔记 + 网课转写统一向量化，rag_search 带来源标记；B10 剩余：召回排序加权与混合检索，见上条依赖）
6. 对话上传视频入口——讨论过方案 B（轻量抽帧预览 + 确认后归档），用户搁置待总体考量

**决策待定（新会话需用户重新拍板）**：学习轨迹打点时机（见当前待办 4）；对话上传视频入口（当前待办 6）的方针；网课"章节"是否独立生成（当前并入笔记正文）。

**保留想法（用户已提出、暂不实施）**：笔记编辑器集成 Tiptap——AI 笔记所见即所得（tiptap-markdown 转换层，存储仍为 Markdown 不变）+ 时间戳胶囊做成 Tiptap 自定义节点（编辑中不可拆坏、可点击）+ 手动笔记编辑器从 execCommand 迁移到 Tiptap（存 HTML 不变）；动手前先做 md 往返转换 spike，验证表格 / 时间戳 / 加粗列表不失真。流水线 LLM 输出 JSON 化缓行：收益依赖 RAG、按块润色等尚未立项的下游，且会加重"修订初稿"的编辑难题。
