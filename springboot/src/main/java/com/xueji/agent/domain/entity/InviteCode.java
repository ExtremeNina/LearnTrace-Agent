package com.xueji.agent.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 邀请码（控制注册量；MVP 初期暂不启用，表结构已预留）
 */
@Data
@TableName("invite_code")
@Accessors(chain = true)
public class InviteCode {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String code;

    private Integer maxUses;

    private Integer usedCount;

    private LocalDateTime expiresAt;

    private String remark;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
