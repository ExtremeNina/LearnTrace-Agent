# 练习 / 测验模式（quiz）

路线图 P0-3（2026-10-04 MVP）：从题库随机抽题组卷，先做后看解析，错题沉淀进复习队列。

## 输入 / 输出边界

- `GET /quiz/pick?count=&subject=&period=&sources=`：组卷抽题。题库 = `question_record`（拍照题目）+ `similar_question`（AI 相似题），按 user_id + deleted=0 + 题干非空筛选；subject 精确匹配、period（7d/30d/all）按 created_at 过滤；`ORDER BY RAND()` 每来源最多 200 道入池，Java 侧洗牌取 count 道（1~20）。`QuizServiceImpl.shufflePick` 为纯函数，有单测
- `POST /review/cards/batch`（在复习模块）：批量加卡（ReviewService.addCardsBatch），已在队列 / 无效项 / 来源不存在计为 skipped，不中断整批；成功恢复/新建走 addCard 既有逻辑（uk_card 唯一键兜底）
- 返回 `QuizPickVO`（cardType: question/similar + refId + 题干/图/参考答案/解析/学科/时间）

## 设计决策（够用即可）

- **不建会话 / 成绩表**：组卷无状态，作答只在前端本地（textarea），计时为前端正向计时；成绩归档后置（当前待办 3 的搁置原则同源）
- **不自动判分**：题库均为文本问答形态（无选择题选项字段），结果页由用户对照参考答案自评，勾选做错的题入队
- 内容实时组装不复制：入队复用复习卡（cardType=question/similar），来源删除级联已由复习模块处理

## 前端

- `web/src/views/Quiz.vue`：三阶段（配置组卷 → 一屏一题作答 → 结果回顾与勾选入队），仿 Review.vue 的 phase 模式
- 入口：IconRail「练习测验」（ListChecks 图标，/quiz）+ /review 统计首屏「做组练习测验」按钮
- API：`web/src/api/quiz.ts`（pickQuiz）；批量加卡在 `web/src/api/review.ts`（addReviewCardsBatch）

## 测试方法

1. `mvn test`（含 QuizServiceImplTest：抽题数量封顶 / 池子不足返全部 / 非法数量返空）
2. 冒烟：登录 → /quiz 配置（选学科 / 时间段 / 来源）→ 开始练习 → 作答 / 跳过 → 交卷 → 结果页勾选若干题「加入复习」→ /review 队列总数应增加；重复加入同一题应提示已在队列
