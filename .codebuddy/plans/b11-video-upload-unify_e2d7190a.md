---
name: b11-video-upload-unify
overview: 统一视频上传入口到 AI 对话：LLM 按意图分流（≤30min 默认轻量转写并明示默认方针，>30min/「做成课程」走课程流水线），新增 CreateCourseFromVideoTool，课程流水线各阶段进度经 AgentEventPushService 回流对话占位气泡，移除视频管理页上传按钮（保留列表/播放/删除）。
todos:
  - id: backend-upload-validate
    content: 后端：ChatVideoService 放开 30min 拒绝（保留 500MB/白名单），TranscribeVideoTool 增加 >30min 返回 SUBMIT_TOO_LONG 引导课程分支
    status: completed
  - id: backend-course-tool
    content: 后端：CourseService.uploadFromLocal（抽取建课+MQ 投递复用）+ CreateCourseFromVideoTool（创建 course_task 占位消息）+ SpringAIConfig 注册
    status: completed
  - id: backend-course-push
    content: 后端：ChatEvent.COURSE 事件 + CoursePipelineService 各阶段（上传 OSS/抽音频/转写/帧 OCR/笔记生成/完成/失败）更新占位消息并经 AgentEventPushService 推送
    status: completed
  - id: backend-prompt-test
    content: 后端：VIDEO_PROMPT 分流规则与默认方针话术，新增单测（TranscribeVideoTool 30min 分支 / CreateCourseFromVideoTool / uploadFromLocal / ChatVideoServiceImpl 调整），mvn test 全量回归
    status: completed
  - id: frontend-course-event
    content: 前端：types/ws.ts 与 stores/agent.ts 处理 COURSE 事件（进度气泡复用转写渲染模式+课程链接），Agent.vue/Home.vue 上传文案更新（>30min 自动按网课处理），Courses.vue 移除上传按钮与弹窗
    status: completed
  - id: smoke-docs-commit
    content: 前端构建 + 真实视频冒烟（video4 验证默认转写与「做成课程」两分支及进度回流）+ 重启后端 + 文档同步（B11 补记 / agent-chat.md / video-pipeline.md / backlog README）+ 分批提交
    status: completed
---

## 产品概述
调整 B26 工单（视频内容理解分层架构）的阶段结构：把「引入 SAA Graph（流水线 Graph 化）」从原阶段 2 的前置项独立为单独的**阶段 3**，原「阶段 3 内容工具与多 Agent 委派」顺延为**阶段 4**，形成四阶段结构，并同步更新阶段间依赖、Graph 演进锚点表与 backlog README 总表。

## 核心调整点
1. **阶段 2（ContentDocument 落地）**：移除「SAA 兼容性 spike 前置」表述；保留并强化「节点函数化（process() 拆独立阶段方法）」作为阶段 2 的前置/组成部分——这是后续任何 Graph 方案（SAA 或自研）的共同前置
2. **新增阶段 3「引入 SAA Graph（流水线 Graph 化）」**：
   - 第一步：SAA 兼容性 spike（graph-core 双节点 demo，验证 SAA 1.1.x ↔ Spring AI 1.1.8 + DeepSeek OpenAI 兼容端点共存）
   - spike 通过：graph-core 正式落地——流水线/内容理解链路迁移为 StateGraph（节点=阶段 2 已拆好的阶段函数），补条件边（产物校验 QA 回退循环）、并行边（音频 ‖ 画面通道真并行），进度事件沿用 AgentEventPushService
   - spike 失败：回退自研轻量状态机（course 表 stage 字段 + 幂等阶段方法）
   - 可选延展（原锚点时机不变）：人工中断（笔记确认环节重启，需用户重新拍板）、Checkpointer（规模化后）
3. **原阶段 3 → 阶段 4「内容工具与多 Agent 委派」**：内容不变；依赖改为「依赖阶段 2（ContentDocument）与阶段 3（Graph 编排）」；QuizAgent 等子代理可复用阶段 3 的 Graph 节点（出题链路本身可编排）
4. **演进锚点表更新**：SAA spike 时机改为「阶段 3 第一步」；人工中断/Checkpointer 锚到阶段 3 可选延展；Agent 节点锚到阶段 4
5. **依赖与成本小节同步**：阶段 3 为中大型改造（SAA 依赖树引入 + 流水线迁移全量回归）
6. **README 总表**：B26 条目改为四阶段摘要

## 验收标准
- B26 工单呈四阶段结构，阶段编号、交叉引用、依赖描述全部一致
- README 总表 B26 条目与工单同步
- docs 类型单独提交

## 技术方案

### 涉及文件（纯文档任务）
```
docs/backlog/B26-视频内容理解分层架构.md   # [MODIFY] 主编辑：阶段重排 + 演进锚点表 + 依赖/成本同步
docs/backlog/README.md                     # [MODIFY] B26 条目四阶段摘要
```

### 实现要点
- B26 工单当前结构（84 行）：标题/状态行 → 核心思想 → 分析结论 → 阶段 1/2/3 → Graph 演进路线小节 → 复用与依赖 → 成本与风险
- 重排方式：
  1. 标题行「产物校验 / ContentDocument / Graph 演进 / 多 Agent 委派」已兼容四阶段，状态行补「2026-10-06 二次调整：SAA Graph 引入独立为阶段 3」
  2. 阶段 2 删「SAA spike 前置」句，保留「节点函数化」并注明其为阶段 3 两种方案（SAA / 自研）的共同前置
  3. 在阶段 2 之后插入「阶段 3：引入 SAA Graph（流水线 Graph 化）」小节（spike → 落地/回退 → 可选延展）
  4. 原「阶段 3：内容工具与多 Agent 委派」标题改为「阶段 4」，依赖句改「依赖阶段 2 与阶段 3（子代理可复用 Graph 节点）」
  5. Graph 演进路线小节的演进锚点表：SAA spike 行时机列改「阶段 3 第一步」；人工中断/Checkpointer 行锚「阶段 3 可选延展」；Agent 节点行锚「阶段 4」
  6. 复用与依赖、成本与风险小节：阶段 3 定性为中大型改造（依赖树 + 流水线迁移回归），依赖链改为 1→2→3→4
- README B26 条目改为四阶段一句话摘要（阶段 3 = SAA Graph 引入含 spike 回退；阶段 4 = 内容工具与多 Agent 委派）
- 提交规范：`docs: 中文描述`，单次提交

### 一致性核对清单（防编号残留）
- 全文搜索「阶段 3」确认每处指向正确；「阶段 2 动工前」字样清除；README 与工单阶段数一致
