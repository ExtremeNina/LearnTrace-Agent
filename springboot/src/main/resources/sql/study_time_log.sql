-- 今日学习时长打点（B25 首页「学习数据 · 今日学习时长」）：前端心跳上报，一天一行累计
CREATE TABLE IF NOT EXISTS study_time_log (
    id bigint unsigned NOT NULL AUTO_INCREMENT,
    user_id bigint unsigned NOT NULL,
    study_date date NOT NULL,
    duration_sec int NOT NULL DEFAULT 0 COMMENT '当日累计学习秒数',
    created_at datetime NULL,
    updated_at datetime NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_date (user_id, study_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='学习时长日志（一天一行，前端心跳累计）';
