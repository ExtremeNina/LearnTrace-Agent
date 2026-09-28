-- 网课关键帧（画面识别结果）；xueji 库
CREATE TABLE IF NOT EXISTS course_frame
(
    id         BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    course_id  BIGINT UNSIGNED NOT NULL COMMENT '网课ID',
    time_sec   INT             NOT NULL COMMENT '帧在视频中的时间（秒）',
    oss_key    VARCHAR(500)    NOT NULL COMMENT '帧图 OSS 访问 URL',
    ocr_text   MEDIUMTEXT      NULL COMMENT '该帧 OCR 识别文本',
    ocr_status VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT '识别状态：PENDING/SUCCESS/FAILED',
    created_at DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_course (course_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT = '网课关键帧';
