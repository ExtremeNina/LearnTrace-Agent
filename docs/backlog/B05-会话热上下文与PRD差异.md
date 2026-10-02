# B05 会话热上下文实现与 PRD §6 承诺不符

- 优先级：P2　|　模块：会话　|　来源：Spec 轴（PRD §6）
- 状态：已完成（2026-10-02，Redisson 按约后置）

## 问题
PRD §6 承诺：Redis List 作为热上下文缓存、读路径带一致性校验（Redis 落后时从 MySQL 重建补齐）、任何时刻清空 Redis 功能不变、会话级锁用 Redisson。当前实现：
- `RedisChatMemoryRepository` 用 String 存 JSON，非 List；直接读 Redis，无 MySQL 重建兜底
- 会话回合互斥用内存 `ConcurrentHashMap`（注释自认“Redisson 后置”，单实例部署）

## 影响
单实例本地部署下功能可用；清空 Redis 会丢 LLM 会话记忆；多实例/重启后互斥失效。

## 整改（2026-10-02）
- **MySQL 重建兜底（已落地）**：`RedisChatMemoryRepository.findByConversationId` 在缓存键缺失时从 message 表
  重建热上下文并回填 Redis（窗口与滑窗同参 max-messages）。重建排除末尾未配对用户消息（回合进行中刚落库，
  advisor 会再次写入，带上会重复）；重建内容还原带图回合的 OCR 识别附录（与原写入记忆的提示形态一致）；
  重建失败降级为无历史记忆不阻塞回合。非数字会话 ID（course-note-* 等无事实源的记忆空间）跳过重建。
  至此「任何时刻清空 Redis 功能不变」成立。
- **按架构裁剪的两项偏差（有意为之，非遗漏）**：
  - 存储维持 String JSON 不改 Redis List：List 化是纯内部结构重写、无功能差异，重建兜底已覆盖其初衷；
  - 水位比对简化为「缺失即重建」：本实现的记忆写入发生在回合完成时（advisor 先写记忆、后落库事实源），
    回合边界上不存在"MySQL 领先而 Redis 非空"的常态场景，逐条水位比对属过度设计。
- **Redisson 后置（按原建议）**：单实例内存互斥（ConcurrentHashMap）继续使用，引入 Redisson 的触发条件
  = 决定多实例部署之前。届时仅需替换 AgentWebSocketHandler 的 activeTurns/runningTurns 守卫实现。
