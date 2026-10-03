package com.xueji.agent.domain.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 用户实体（对应 xueji 库 user 表）
 */
@Data
@TableName("user")
@Accessors(chain = true)
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    @TableField("password_hash")
    @JsonIgnore
    private String passwordHash;

    private String nickname;

    private String phone;

    private String email;

    private String avatarUrl;

    /** 个人简介（选填） */
    private String bio;

    /** 界面主题：LIGHT / DARK */
    private String theme;

    /** 任务完成/失败通知开关：0 关 / 1 开 */
    private Integer notifyTaskEnabled;

    private Integer status;

    /** 0 正常 / 1 已注销 */
    private Integer deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
