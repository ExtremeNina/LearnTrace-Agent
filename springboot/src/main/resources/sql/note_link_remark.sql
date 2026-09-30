-- 知识联系增加可选说明：一句话标注目标与知识点的关联之处（AI 建议代填 / 手动可改）
ALTER TABLE note_link
    ADD COLUMN remark VARCHAR(255) NULL COMMENT '联系说明（这条联系相关的点）' AFTER title;
