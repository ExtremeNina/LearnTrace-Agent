-- 网课笔记生成模型按上传时选择（存于课程上，重试沿用）
ALTER TABLE course
    ADD COLUMN model_config_id bigint unsigned NULL COMMENT '笔记生成使用的模型配置（NULL = 系统默认）' AFTER expectations;
