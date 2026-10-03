package com.xueji.agent.domain.dto;

import lombok.Data;

/**
 * 个人资料编辑入参：昵称必填，邮箱 / 简介 / 头像选填
 */
@Data
public class UpdateProfileDto {

    private String nickname;

    private String email;

    private String bio;

    /** 头像图片 URL（复用对话图片上传接口获取） */
    private String avatarUrl;
}
