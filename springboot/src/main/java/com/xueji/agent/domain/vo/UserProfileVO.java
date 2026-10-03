package com.xueji.agent.domain.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 当前用户资料视图：个人页面展示与编辑的数据形状（不含密码哈希）
 */
@Data
@Accessors(chain = true)
public class UserProfileVO {

    private Long id;

    private String username;

    private String nickname;

    private String email;

    private String bio;

    private String avatarUrl;

    /** 界面主题：LIGHT / DARK */
    private String theme;

    /** 任务完成/失败通知开关 */
    private Boolean notifyTaskEnabled;

    private LocalDateTime createdAt;
}
