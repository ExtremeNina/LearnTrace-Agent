-- B26 阶段 3：学习者画像（评审与笔记生成的难度适配输入）

CREATE TABLE IF NOT EXISTS user_profile (
    user_id BIGINT PRIMARY KEY,
    grade_level VARCHAR(50) NULL COMMENT '学段（如初中 / 高中 / 大学）',
    level VARCHAR(50) NULL COMMENT '自评水平（入门 / 进阶）',
    goal VARCHAR(255) NULL COMMENT '学习目标',
    note VARCHAR(500) NULL COMMENT '补充说明（偏好、薄弱点等）',
    updated_at DATETIME NOT NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '学习者画像（B26 阶段 3）';
