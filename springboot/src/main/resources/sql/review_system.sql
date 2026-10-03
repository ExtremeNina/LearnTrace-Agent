-- 复习系统（路线图 P0-1）：间隔重复调度状态与评分流水
-- review_card：每张复习卡一行（统一四种来源：question / similar / note），只存调度状态不复制内容
CREATE TABLE IF NOT EXISTS review_card (
    id bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id bigint unsigned NOT NULL COMMENT '所属用户',
    card_type varchar(16) NOT NULL COMMENT '卡片类型：question / similar / note',
    ref_id bigint unsigned NOT NULL COMMENT '来源实体主键（question_record / similar_question / note）',
    due_at datetime NOT NULL COMMENT '下次到期时间',
    interval_days int NOT NULL DEFAULT 0 COMMENT '当前间隔（天）',
    ease decimal(4,2) NOT NULL DEFAULT 2.50 COMMENT '容易度（SM-2 简化，1.30~3.00）',
    reps int NOT NULL DEFAULT 0 COMMENT '复习次数',
    lapses int NOT NULL DEFAULT 0 COMMENT '生疏次数',
    deleted tinyint NOT NULL DEFAULT 0 COMMENT '0 队列中 / 1 已移出',
    created_at datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_card (user_id, card_type, ref_id),
    KEY idx_due (user_id, due_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='复习卡（间隔重复调度状态）';

-- review_log：每次评分一行（历史正确率统计 + 将来 FSRS 升级的数据基础）
CREATE TABLE IF NOT EXISTS review_log (
    id bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id bigint unsigned NOT NULL COMMENT '所属用户',
    card_id bigint unsigned NOT NULL COMMENT '复习卡 ID',
    grade tinyint NOT NULL COMMENT '评分：0 生疏 / 1 模糊 / 2 熟练',
    interval_after int NOT NULL COMMENT '评分后间隔（天）',
    reviewed_at datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '复习时间',
    PRIMARY KEY (id),
    KEY idx_user_time (user_id, reviewed_at),
    KEY idx_card (card_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='复习评分流水';
