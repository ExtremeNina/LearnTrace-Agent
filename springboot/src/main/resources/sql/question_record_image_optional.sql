-- 相似题保存通道（B03）：纯文字题目（RAG 相似题 / 手打题）无图片，image_oss_key 允许 NULL
ALTER TABLE question_record
    MODIFY COLUMN image_oss_key varchar(255) NULL COMMENT '原始图片 OSS key（纯文字题目为 NULL）';
