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

