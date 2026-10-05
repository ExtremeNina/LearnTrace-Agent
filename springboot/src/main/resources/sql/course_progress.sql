-- 网课播放进度打点（B25 首页「继续学习 / 最近学习」供数；B24 打点时机拍板为视频播放进度上报）
ALTER TABLE course
    ADD COLUMN last_position_sec int NULL COMMENT '上次播放位置（秒，播放器定时上报）' AFTER duration,
    ADD COLUMN progress_pct smallint NULL COMMENT '观看进度百分比（0~100，按位置 / 时长取整；无时长为 NULL）' AFTER last_position_sec,
    ADD COLUMN last_studied_at datetime NULL COMMENT '最近一次播放上报时间' AFTER progress_pct;
