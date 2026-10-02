# 优化清单（后端 Code-Review 产物）

来源：2026-10-01 对后端核心模块的双轴 code-review（Standards 轴对照 agent.md 硬性规范 + Fowler 异味基线；Spec 轴对照 学迹PRD.md 行为承诺）。P1 = 功能缺陷/违反核心原则，P2 = 功能缺口/与 PRD 承诺不符，P3 = 规范与代码卫生。

| 编号 | 优先级 | 模块 | 标题 | 状态 |
| --- | --- | --- | --- | --- |
| B02 | P1 | 网课 | 会话重试走 MQ 裸 HashMap 负载，重试链路大概率不可用 | 已完成 |
| B03 | P1 | 题目/RAG | 相似题确认后无法保存（saveQuestion 强依赖带图消息） | 已完成 |
| B05 | P2 | 会话 | 会话热上下文实现与 PRD §6 承诺不符 | 已完成 |
| B06 | P2 | 安全/配额 | 鉴权与用量约束缺失（邀请码/双Token/每日配额/时长与图片上限） | 待办 |
| B07 | P2 | Agent | Agent 缺少查询类工具（笔记/课程/学习状态检索） | 待办 |
| B08 | P2 | 题目 | 拍照解题 OCR 在请求线程内同步轮询最长 120s | 待办 |
| B09 | P3 | 全后端 | 规范与代码卫生清理（Lambda/构造器注入/重复代码/死代码/散落常量） | 已完成 |
| B10 | P3 | RAG | 向量化范围扩展（笔记/网课转写）与混合检索 | 二期 |

状态：待办 / 进行中 / 已完成 / 不做。完成一项把状态改掉并在本文件底部记录日期。

## 备注

- Spec 轴同时确认了几处「超出 PRD 的合理延伸」（自动标题去重、五层分组树、知识联系 remark/tsSec 字段），不算问题，仅记录。
- 「题目 OCR 指定阿里云、实为 PaddleOCR」为历史上有意的前置流水线切换决策，不算缺陷；agent.md 宜补一条豁免说明（并入 B09）。

## 完成记录

- 2026-10-02：B09 完成（11 项全部整改：新增 OwnershipCheck / CourseStatus 收口与 3 个 Note DTO，补 OwnershipCheckTest、RagSearchToolTest，`mvn test` 76 个全部通过）。
- 2026-10-02：B02 完成（retry 改 JSON 字符串负载 + 负载契约回归测试）。
- 2026-10-02：B03 完成（saveQuestion 增加 photo/text 来源参数，无图分支入库 question_record；image_oss_key 改可 NULL 并已对库执行；提示词与工具描述同步；`mvn test` 85 个全部通过）。
- 2026-10-02：B05 完成（RedisChatMemoryRepository 缓存缺失时自 MySQL 重建热上下文并回填，清空 Redis 功能不变；存储形态与水位比对按架构裁剪，Redisson 按约定后置到多实例部署前）。
