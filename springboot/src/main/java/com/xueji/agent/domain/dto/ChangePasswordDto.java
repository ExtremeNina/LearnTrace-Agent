package com.xueji.agent.domain.dto;

import lombok.Data;

/**
 * 修改密码入参
 */
@Data
public class ChangePasswordDto {

    private String oldPassword;

    private String newPassword;
}
