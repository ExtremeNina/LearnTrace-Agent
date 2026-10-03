-- 个人页面（用户资料 / 偏好 / 注销）：user 表补充字段
ALTER TABLE user
    ADD COLUMN bio varchar(500) NULL COMMENT '个人简介（选填）' AFTER avatar_url;

ALTER TABLE user
    ADD COLUMN theme varchar(16) NOT NULL DEFAULT 'LIGHT' COMMENT '界面主题：LIGHT / DARK' AFTER bio;

ALTER TABLE user
    ADD COLUMN notify_task_enabled tinyint NOT NULL DEFAULT 1 COMMENT '任务完成/失败通知开关：0 关 / 1 开' AFTER theme;

ALTER TABLE user
    ADD COLUMN deleted tinyint NOT NULL DEFAULT 0 COMMENT '0 正常 / 1 已注销' AFTER status;
