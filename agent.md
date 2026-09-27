# 学迹 Agent — agent.md

AI 个人学习工作台：学习资产（网课 / 题目 / 笔记）+ 知识点页 + Human-in-the-loop 的 Agent 对话。
技术栈：Vue 3 + TypeScript + Tailwind 响应式前端（桌面与手机端都要可用，移动端 Sidebar 折叠为抽屉）；Spring Boot 3 + MyBatis-Plus + MySQL + Redis + RabbitMQ 后端。AI 框架使用 Spring AI，LLM 为 DeepSeek，OCR 接入阿里云第三方 API。

进度以 git 提交为准。当前：代码未搭建（上一版实现已遗弃，数据库 13 张表已建成），第一任务是切片一（会话骨架）。切片顺序固定：一会话骨架 → 二题目记录 → 三网课。

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

* 禁止 Lambda 表达式：集合处理用显式循环；MyBatis-Plus 用字符串列名的 `QueryWrapper`，不用 LambdaQueryWrapper
* 禁止 `@Async`：需要线程时使用 config 包统一定义的独立 `ThreadPoolExecutor` 显式提交；长耗时任务走 RabbitMQ
* 业务代码不直接调 LLM API，统一经 Spring AI
* Agent 涉及用户数据的创建 / 修改 / 删除 / 持久化，必须先发对话内确认卡片，用户确认后才执行
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
