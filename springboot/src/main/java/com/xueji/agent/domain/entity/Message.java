package com.xueji.agent.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * Agent 对话消息（滑窗重建以回合为最小单位；payload 存卡片 / 工具事件等业务元数据）
 */
@Data
@TableName("message")
@Accessors(chain = true)
public class Message {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long conversationId;

    /** 消息角色：user / assistant / tool */
    private String role;

    /** 消息类型：text / card / tool_call 等 */
    private String msgType;

    private String content;

    /** 业务元数据（JSON） */
    private String payload;

    /** 用户上传视频的 OSS 地址（对话视频转写，任务上传后回填） */
    private String videoUrl;

    /** 关联网课 ID（对话创建的课程任务占位消息，流水线进度回流的查询键） */
    private Long courseId;

    /** 确认卡片的确认状态：PENDING / CONFIRMED / REJECTED（仅卡片消息） */
    private String confirmStatus;

    private LocalDateTime createdAt;
}
