-- 每日学习简报（路线图 P0-2）：惰性生成（当天首次访问触发 LLM），落库缓存幂等
CREATE TABLE IF NOT EXISTS daily_briefing (
    id bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id bigint unsigned NOT NULL COMMENT '所属用户',
    brief_date date NOT NULL COMMENT '简报日期',
    stats_json mediumtext COMMENT '生成时的学习统计快照（JSON）',
    content mediumtext COMMENT '简报正文（LLM 生成）',
    created_at datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_brief (user_id, brief_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='每日学习简报（惰性生成，当天缓存）';
