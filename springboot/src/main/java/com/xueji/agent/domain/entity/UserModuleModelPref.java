package com.xueji.agent.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 用户按模块的模型偏好：chat / course_note / briefing；config_id 为空表示使用系统默认模型
 */
@Data
@TableName("user_model_pref")
@Accessors(chain = true)
public class UserModuleModelPref {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 模块：chat / course_note / briefing */
    private String module;

    /** 模型配置 ID，NULL = 系统默认 */
    private Long configId;

    private LocalDateTime updatedAt;
}
