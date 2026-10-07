-- 课后习题课程级产物（B26 习题产物化）：出题 Agent 自动产出，详情页课后习题 tab 展示；
-- 用户显式操作才沉淀入题目管理（question_record）与复习计划（review_card）

CREATE TABLE IF NOT EXISTS course_quiz_question (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    course_id BIGINT NOT NULL COMMENT '归属课程',
    user_id BIGINT NOT NULL COMMENT '归属用户',
    question_text TEXT NOT NULL COMMENT '题面',
    answer TEXT NULL COMMENT '参考答案',
    analysis TEXT NULL COMMENT '解析（含依据说明）',
    source_sec INT NULL COMMENT '依据时间点（秒）',
    sort INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL,
    INDEX idx_cq_course (course_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '课程课后习题（出题 Agent 产物）';
