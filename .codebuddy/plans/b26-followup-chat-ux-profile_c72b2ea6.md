---
name: b26-followup-chat-ux-profile
overview: 四处修复/增强：①首页 AI 助手加「查看历史对话」入口（弹窗选择会话，面板内直接加载）②首页 AI 面板加模型切换器（抽公共组件与对话页复用）③首页上传视频成功改为消息流气泡提示 + 下方小字加深 ④用户画像改对话采集（SaveProfileTool + prompt 注入画像状态与问询指引，移除设置弹窗画像区块）
design:
  architecture:
    framework: vue
  styleKeywords:
    - 语义令牌
    - 卡片白底
    - 胶囊时间戳
  fontSystem:
    fontFamily: PingFang SC
    heading:
      size: 16px
      weight: 600
    subheading:
      size: 14px
      weight: 500
    body:
      size: 14px
      weight: 400
  colorSystem:
    primary:
      - "#062E9A"
      - "#084DCD"
    background:
      - "#FFFFFF"
      - "#F5F6F8"
    text:
      - "#1A1A1A"
      - "#6B7280"
    functional:
      - "#D97706"
      - "#DC2626"
      - "#16A34A"
todos:
  - id: quiz-table-backend
    content: 新建 course_quiz_question 表与 course.quiz_status 列（SQL 碎片+执行）、实体/Mapper、CourseQuizService（生成/追加/落库/状态机），重构 QuizAgentService 抽出无落库出题核心（防重复注入）
    status: completed
  - id: quiz-pipeline-api
    content: 流水线末端异步自动出题接入 + detail 返回 quizQuestions + 追加出题与加入题目管理/复习计划端点
    status: completed
    dependencies:
      - quiz-table-backend
  - id: quiz-tab-frontend
    content: CourseDetail.vue 新增课后习题 tab（题目/答案两区、生成中态、追加出题、沉淀按钮）
    status: completed
    dependencies:
      - quiz-pipeline-api
  - id: quiz-test-build
    content: 单测（防重复/状态机/落库）+ mvn test 全量 + npm build
    status: completed
    dependencies:
      - quiz-tab-frontend
  - id: quiz-smoke-commit
    content: 课程 24 重新生成真实冒烟（习题产出/追加/入题目管理与复习计划）+ 文档同步 + 提交
    status: completed
    dependencies:
      - quiz-test-build
---

## 产品概述

课后习题升级为课程级产物：流水线末端在笔记完成后由 QuizAgent 自动出题（判题 Agent 把关 + 双端代码闸三明治质检），题目数量由出题 Agent 按视频时长 / 知识点 / 难度 / 画像自适应规划；视频详情页新增「课后习题」tab（题目/答案两区、追加出题、加入题目管理与复习计划）；对话委派出题链路保留。

## 核心功能

1. **流水线自动出题**：笔记渲染完成后自动触发 QuizAgent 出题（新 QUIZ_GENERATING 阶段，进度推送），产物存 course_quiz_question（课程级，与 AI 笔记并列）
2. **自适应数量**：出题 Agent 按视频时长（约每 3~5 分钟 1 题）、知识点数量与重要度、总体难度、学习者画像自主规划题目数量（合理区间 3~15），并在输出中声明数量；代码闸校验声明与实际一致
3. **三明治质检**：出题后先过 QuizQualityChecker 代码闸（幻觉词面覆盖率 / sourceSec 依据合法性 / 重复题 / 结构完整）→ 判题 Agent（reviewQuiz 结合画像整体评审，REVISE 重出 ≤1）→ 重出后天然再过代码闸；坏题剔除、数量不足带缺陷补出
4. **详情页「课后习题」tab**：题目区（编号列表 + 时间戳胶囊跳视频）+ 答案区（逐题「查看答案」展开解析，顶部「显示全部答案」开关）+「再出几道」追加出题（prompt 带已有题防重复）
5. **沉淀动作**：加入题目管理（复制入 question_record）；加入复习计划（自动先入题目管理，再走 /review/cards 入复习队列）

## 技术方案

### 现状依据（已探查）

- QuizAgentService（ai/）：resolveCourse → 出题（QUIZ_AGENT_PROMPT）→ ContentReviewService.reviewQuiz（REVISE 重出 ≤1）→ 落 question_record；parseQuestions/buildQuizUserPrompt/questionsText 纯函数可复用
- ContentReviewService.reviewQuiz/buildQuizReviewPrompt（判题 Agent，PRACTICE 维度 + 依据真实性 + 自含性）
- CoursePipelineService.understandAndRenderNote → 置 SUCCESS；pushCourseStage（stage 持久化 + COURSE 事件）；courseExecutor
- CourseDetail.vue：AI 笔记 tab（onNoteClick 时间戳胶囊 data-ts → seekTo）、「重新生成」按钮轮询模式
- 复习：POST /review/cards（cardType='question' → question_record refId）
- 前端 stage 文案：Courses.vue STAGE_LABELS + ChatPanel.vue labels
- 基线：mvn test 233 全绿；video_6 课程 id=24 可重新生成验证

### 实施要点

1. **SQL**：course_quiz_question（id/course_id/user_id/question_text/answer/analysis/source_sec/sort/created_at）+ SQL 碎片执行
2. **实体/Mapper**：CourseQuizQuestion + CourseQuizQuestionMapper
3. **QuizQualityChecker（新增，纯函数三明治代码闸）**：

- 结构完整（题面/答案/解析非空）
- sourceSec 合法性（非负且 ≤ 视频时长）
- 幻觉词面覆盖率：题面内容词（去停用词，≥2 字词）在「转写原文 + 知识点名」文本中的命中率低于阈值判脱离材料（坏题剔除）
- 重复检测：题面归一化后与已有题目相似（字符级 Jaccard ≥ 阈值）判重复
- check(questions, transcript, knowledgePoints, durationSec, existing) → 剔除 + 缺陷清单

4. **QuizAgentService 重构**：

- 抽出无落库核心 `produceQuestions(course, document, sections, points, transcript, profile, count, existing)`：出题（QUIZ_AGENT_PROMPT 改自适应数量——prompt 指示按时长/知识点/难度/画像自主规划并声明 count，区间 3~15）→ QuizQualityChecker 剔除/缺陷 → 数量不足补出一次 → 判题 Agent（reviewQuiz）REVISE 重出 ≤1（重出后再过代码闸）→ 返回题目
- 流水线路径 `generateForCourse(course, transcript, ...)`：produceQuestions → 落 course_quiz_question（重生成先删旧）
- 对话委派路径 generate(userId, courseHint, count)：produceQuestions 复用（count 仍按用户指定）→ 落 question_record（现状保留）

5. **流水线接入**：understandAndRenderNote 完成后 pushCourseStage QUIZ_GENERATING → courseExecutor 异步 generateForCourse（失败不影响课程 SUCCESS，记 errorMsg 附注）；stage 文案前端补（Courses.vue/ChatPanel.vue）
6. **API**：detail 返回 quizQuestions + quizStatus（GENERATING/READY，course 行内存态或按题目有无推断）；POST /courses/{id}/quiz?count=5（追加出题，异步 courseExecutor，prompt 带已有题防重复）；POST /course-quiz/{qid}/to-questions（入题目管理）；POST /course-quiz/{qid}/to-review（先入题目管理再 POST review/cards 逻辑复用 ReviewService.addCard）
7. **前端**：CourseDetail.vue 新增「课后习题」tab——题目区（编号 + 题面 + 时间戳胶囊 + 依据）+ 答案区（逐题展开 + 全部显示开关）+「再出几道」（loading + 轮询 detail）+ 每题「加入题目管理 / 加入复习计划」按钮；Stage 文案补 QUIZ_GENERATING
8. **测试**：QuizQualityCheckerTest（幻觉剔除/重复/数量不足补出/依据越界）；parseQuestions 适配 count 声明；mvn test 全量 + npm build

### 性能与边界

- LLM 账单不变：1 生成 + 1~2 判题（代码闸零成本）；自适应数量不额外调 LLM
- 追加出题异步执行，前端轮询 detail（与重新生成同模式）
- 坏题剔除采用逐题剔除而非整体重出，避免一次幻觉拖垮整组