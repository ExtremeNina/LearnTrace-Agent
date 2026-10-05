-- B11 对话创建课程任务：占位消息（msg_type='course_task'）关联网课，
-- 流水线各阶段经 course_id 定位占位消息更新进度并推送
ALTER TABLE message
    ADD COLUMN course_id BIGINT NULL COMMENT '关联网课 ID（对话课程任务占位消息）';
