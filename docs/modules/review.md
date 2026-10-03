# M6 复习系统（间隔重复）

## 职责与业务
把散在学习资产里的错题 / 相似题 / 笔记 / 知识点收进统一复习队列，按 SM-2 简化版间隔重复算法调度：复习时三档评分（生疏 / 模糊 / 熟练）→ 推进或重置下次到期时间 → 到期卡进入「今日待复习」独立入口（/review，IconRail 带角标）。复习卡只存调度状态，不复制内容——两面（正面 / 背面）按 cardType 实时组装。

## 边界（详细）

**输入**
- 加卡：`POST /review/cards`（cardType + refId；校验来源实体归属与存在，笔记仅叶子节点，去重：已在队列拒绝、已移出则恢复并重置状态）
- 评分：`POST /review/cards/{id}/review`（grade：0 生疏 / 1 模糊 / 2 熟练；非法评分拒绝）
- 移出：`DELETE /review/cards/{id}`（逻辑删除，不影响来源实体）
- 队列与统计：`GET /review/today`（due_at ≤ 今天末尾，按到期排序，上限 20）、`GET /review/stats`（dueCount / total / reviewedToday）、`GET /review/status`（详情页按钮态）

**输出**
- review_card 表（due_at / interval_days / ease 1.30~3.00 / reps / lapses；UNIQUE(user_id, card_type, ref_id)）
- review_log 表（每次评分一行：grade + interval_after，历史正确率统计与将来 FSRS 升级的数据基础）
- 来源实体删除的级联（各模块调用 removeBySource）：删除拍照题 → question 卡移出；删除相似题 → similar 卡移出；删除笔记（含分组级联）→ note 卡移出；删除网课 → 其 AI 笔记的 note 卡移出

**依赖**
- question_record / similar_question / note（卡片内容实时组装；来源被删的卡不出现在队列）
- 无 LLM / 无外部服务——调度是纯确定性查询（`WHERE due_at <= 今天`），算法为纯函数 ReviewScheduler
- 前端 /review 页（三态：统计首屏 → 卡片流 → 完成态；空格翻面，1/2/3 评分快捷键）

**不做（边界外）**
- LLM 预生成笔记提问、错题变体防背题、复习时针对性讲解（二期增强——review_log 数据攒够后接入）
- 到点提醒推送（toast 轮询框架已就位，加一条每日提醒即可）
- FSRS 完整算法（简化 SM-2 先行，表结构已留升级位）
- 练习模式自动入队（依赖练习模式立项）

**约束与已知偏差**
- 评分后 due_at = 现在 + 新间隔（生疏 = 明天再来，不做当日内重复排队）
- 卡片来源被删除后不可再加卡（来源不存在拒绝）
- 队列上限 20、间隔封顶 180 天（防积压爆炸 / 防永久淡出）
- 复习评分不改来源实体（is_wrong 等字段与复习解耦）

## 测试方法
- 单元：ReviewSchedulerTest（三档推进 / 首卡起算 1 天 / ease 触底与上限 / 间隔封顶 / 非法评分）
- 单元：ReviewServiceImplTest（加卡去重与恢复重置 / 分组拒绝 / 非法类型 / 今日队列组装含来源已删跳过 / 评分更新 + 流水 / 统计 / 移除 / 级联）
- 端到端冒烟：`node web/test-review-smoke.mjs`（一次性账号全流程 20 断言：加卡 / 去重 / 队列组装 / 评分推进与重置 / 统计 / 删除级联 / 移出恢复）
- 人工：复习页刷卡全流程（键盘快捷键）、删除笔记后复习角标与队列变化
