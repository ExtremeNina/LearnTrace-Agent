-- 网课详情页学习笔记：用户在视频下方的随想记录（富文本 HTML）
ALTER TABLE course
    ADD COLUMN study_note MEDIUMTEXT NULL COMMENT '用户学习笔记（网课页随想，富文本 HTML）' AFTER expectations;
