-- 网课表补充：用户期望（上传时填写，注入笔记生成提示词）
ALTER TABLE course
    ADD COLUMN expectations VARCHAR(1000) NULL COMMENT '用户期望（上传时填写，注入笔记生成提示词）' AFTER title;
