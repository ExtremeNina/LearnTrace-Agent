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
三项修改：修复模型下拉菜单错位、顶栏搜索框收敛到首页、移除两个与 AI 对话能力重复的旧入口（练习/测验模式、生成相似题），精简产品结构。

## 核心功能
1. **模型菜单错位修复**：ModelPicker 下拉支持向上/向下弹出方向，课程页 AI 问答工具条使用向下弹出，不再溢出到顶栏
2. **搜索框收敛**：顶栏全局搜索框（Ctrl+K）仅在首页（/）展示，视频详情页等其他页面隐藏
3. **移除练习/测验模式**：/quiz 页、路由、侧栏「练习测验」入口、复习页组卷按钮、后端 GET /quiz/pick 接口全部移除；练习主场由「课后习题」tab 承担（课程绑定 + 时间戳跳转 + 三 Agent 质检）
4. **移除生成相似题入口**：题目详情「生成相似题」按钮与会话选择弹窗移除（以后直接在 AI 对话中生成并保存题目）；similar_question 存量数据与复习队列不受影响

## 技术方案
### 现状依据（已探查）
- **ModelPicker.vue**（web/src/components/chat/ModelPicker.vue）：下拉固定 `bottom-[calc(100%+8px)]` 向上弹（Agent.vue 输入框下方场景正确）；CourseDetail.vue ask 工具条位于页面上部 → 向上弹溢出与顶栏搜索框重叠（截图 bug）；已有 tone prop
- **MainLayout.vue**：顶栏全局搜索框（B15，Ctrl+K）在所有页面渲染
- **练习/测验模式**：后端 QuizController（/quiz/pick，GET）+ QuizService/QuizServiceImpl + QuizPickVO；前端 Quiz.vue、router 'quiz'（L27）、web/src/api/quiz.ts、学习台侧栏「练习测验」入口、Review.vue 组卷按钮、types QuizPickInfo
- **生成相似题**：Questions.vue（generateSimilar 函数 + 详情按钮 + 会话选择弹窗）、跨页种子消息机制（agent.applySeed，通用能力保留）、题目详情合并列表的 source=similar_ai 存量展示与复习队列不受影响

### 实施要点
1. **ModelPicker 方向 prop**：`direction?: 'up' | 'down'`（默认 up 向上弹保持对话页行为）；down 用 `top-[calc(100%+8px)]`；CourseDetail ask 工具条传 `direction="down"`
2. **搜索框按路由收敛**：MainLayout 用 `useRoute()` 判断 `route.path === '/'` 时才渲染顶栏搜索框（Ctrl+K 快捷键监听一并收敛；全局搜索弹窗逻辑保留在首页场景）
3. **移除练习/测验模式**：
   - 前端：删 Quiz.vue、router 条目、api/quiz.ts、SidebarContent「练习测验」入口、Review.vue 组卷按钮、types QuizPickInfo
   - 后端：删 QuizController、QuizService/QuizServiceImpl、QuizPickVO（无其他引用）
4. **移除生成相似题入口**：Questions.vue 删 generateSimilar、详情按钮、会话选择弹窗；保留相似题存量展示与删除/编辑；applySeed 机制保留（通用）；BASE_PROMPT 中相似题引导改为「在对话中直接生成并保存题目」（已有 saveQuestion 链路，仅微调文案）
5. **验证**：npm run build + mvn test 全量回归；git status 确认无残留引用（grep quiz/similar 引用清点）

### 性能与边界
- 纯删减 + 两处小改，无新增依赖
- 存量数据不动：similar_question 表、question_record、复习队列照常
�