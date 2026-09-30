-- 学科筛选：拍照记录与网课增加学科列（拍照 = AI 保存时分类、可编辑；网课 = 上传时选择）
ALTER TABLE question_record
    ADD COLUMN subject VARCHAR(32) NULL COMMENT '学科（AI 保存时分类，可编辑）' AFTER question_text;

ALTER TABLE course
    ADD COLUMN subject VARCHAR(32) NULL COMMENT '学科（上传时选择）' AFTER title;
