-- 相似题存储模型修正（B03 v2）：
-- 1. 相似题回到独立的 similar_question 表（source_question_id 改可空——对话流保存时无确定来源，
--    靠 conversation_id 溯源；题目详情页入口（PRD §8）上线后由前端显式传入）
-- 2. similar_question 补 subject 列，与拍照记录在合并列表中按学科筛选对齐
-- 3. question_record 回归纯拍照记录语义，image_oss_key 恢复 NOT NULL

ALTER TABLE similar_question
    MODIFY COLUMN source_question_id bigint unsigned NULL COMMENT '来源题目（对话流保存时为空）';

ALTER TABLE similar_question
    ADD COLUMN subject VARCHAR(32) NULL COMMENT '学科（AI 保存时分类，可编辑）' AFTER analysis;

ALTER TABLE question_record
    MODIFY COLUMN image_oss_key varchar(255) NOT NULL COMMENT '原始图片 OSS key';
