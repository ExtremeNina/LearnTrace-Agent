package com.xueji.agent.domain.dto;

import lombok.Data;

/**
 * 注销账号入参：需密码确认
 */
@Data
public class DeleteAccountDto {

    private String password;
}
