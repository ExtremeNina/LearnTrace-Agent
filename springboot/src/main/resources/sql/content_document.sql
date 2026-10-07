-- B26 阶段 2：ContentDocument 语义层（三表）
-- 边界：Raw Transcript（course_transcript_segment）永不覆盖；ContentDocument 可重生成（重生成先删旧三表数据）

CREATE TABLE IF NOT EXISTS content_document (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    course_id BIGINT NOT NULL COMMENT '归属课程',
    user_id BIGINT NOT NULL COMMENT '归属用户（OwnershipCheck 用）',
    title VARCHAR(255) NOT NULL COMMENT '内容标题（LLM 拟定）',
    summary TEXT NULL COMMENT '全局摘要',
    model_config_id BIGINT NULL COMMENT '生成使用的模型配置（重生成沿用）',
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_cd_course (course_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '内容理解语义层文档（B26）';

CREATE TABLE IF NOT EXISTS content_section (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_id BIGINT NOT NULL COMMENT '归属 ContentDocument',
    title VARCHAR(255) NOT NULL COMMENT '章节标题',
    summary TEXT NULL COMMENT '章节摘要',
    start_sec INT NOT NULL COMMENT '起始秒（来自转写时间戳）',
    end_sec INT NOT NULL COMMENT '结束秒',
    sort INT NOT NULL DEFAULT 0,
    INDEX idx_cs_doc (document_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'ContentDocument 章节（B26）';

CREATE TABLE IF NOT EXISTS content_knowledge_point (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_id BIGINT NOT NULL COMMENT '归属 ContentDocument',
    name VARCHAR(255) NOT NULL COMMENT '知识点名称',
    detail TEXT NULL COMMENT '知识点说明',
    time_sec INT NULL COMMENT '关键时间点（秒，可 NULL）',
    section_sort INT NULL COMMENT '归属章节序号（弱关联，便于排序）',
    important TINYINT NULL COMMENT '1=重点 / 0=普通',
    error_prone TINYINT NULL COMMENT '1=易错点',
    sort INT NOT NULL DEFAULT 0,
    INDEX idx_ckp_doc (document_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'ContentDocument 知识点（B26）';
