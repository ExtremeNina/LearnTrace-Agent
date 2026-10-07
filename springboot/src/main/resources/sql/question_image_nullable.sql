-- B26 阶段 4：QuizAgent 出题落 question_record 无图片，image_oss_key 改为可空

ALTER TABLE question_record MODIFY COLUMN image_oss_key VARCHAR(255) NULL COMMENT '题目图片 OSS 对象键（拍照题；QuizAgent 出题无图片）';
