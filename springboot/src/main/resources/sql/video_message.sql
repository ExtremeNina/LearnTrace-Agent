-- B11 对话视频转写：用户消息增加 video_url（OSS 永久地址，任务上传后回填）；
-- 转写占位消息以 msg_type='video_transcript' 标识，进度与结果记录在 payload
ALTER TABLE message
    ADD COLUMN video_url VARCHAR(512) NULL COMMENT '用户上传视频的 OSS 地址（对话视频转写）';
