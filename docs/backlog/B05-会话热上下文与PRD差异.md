# B05 会话热上下文实现与 PRD §6 承诺不符

- 优先级：P2　|　模块：会话　|　来源：Spec 轴（PRD §6）
- 状态：待办

## 问题
PRD §6 承诺：Redis List 作为热上下文缓存、读路径带一致性校验（Redis 落后时从 MySQL 重建补齐）、任何时刻清空 Redis 功能不变、会话级锁用 Redisson。当前实现：
- `RedisChatMemoryRepository` 用 String 存 JSON，非 List；直接读 Redis，无 MySQL 重建兜底
- 会话回合互斥用内存 `ConcurrentHashMap`（注释自认“Redisson 后置”，单实例部署）

## 影响
单实例本地部署下功能可用；清空 Redis 会丢 LLM 会话记忆；多实例/重启后互斥失效。

## 建议
按分片策略后置：迁移到 Spring AI 官方 Redis ChatMemoryRepository 或补 MySQL 重建兜底；多实例部署前引入 Redisson。
