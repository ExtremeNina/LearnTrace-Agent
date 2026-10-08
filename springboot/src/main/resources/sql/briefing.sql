-- 每日学习简报（路线图 P0-2）：惰性生成（当天首次访问触发 LLM），落库缓存幂等
-- 归属模型：简报按（用户 × 会话 × 日期）绑定，同一会话跨天使用会产生多条记录（每天一条）；
-- 同一天在不同会话中复用同一份内容（不重复调用 LLM）。conversation_id 为 NULL 表示旧数据未绑定，首次访问时补绑定。
CREATE TABLE IF NOT EXISTS daily_briefing (
    id bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id bigint unsigned NOT NULL COMMENT '所属用户',
    conversation_id bigint unsigned NULL COMMENT '归属会话（NULL=旧数据未绑定，首次访问时补绑定）',
    brief_date date NOT NULL COMMENT '简报日期',
    stats_json mediumtext COMMENT '生成时的学习统计快照（JSON）',
    content mediumtext COMMENT '简报正文（LLM 生成）',
    created_at datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_brief (user_id, conversation_id, brief_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='每日学习简报（惰性生成，按会话×日期缓存）';

-- 存量库迁移语句（新装库直接执行上方 CREATE TABLE 即可，无需执行以下语句）：
-- ALTER TABLE daily_briefing ADD COLUMN conversation_id bigint unsigned NULL COMMENT '归属会话（NULL=旧数据未绑定）' AFTER user_id;
-- ALTER TABLE daily_briefing DROP INDEX uk_brief, ADD UNIQUE KEY uk_brief (user_id, conversation_id, brief_date);
