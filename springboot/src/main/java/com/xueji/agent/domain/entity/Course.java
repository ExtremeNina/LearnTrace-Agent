package com.xueji.agent.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.xueji.agent.common.UserOwned;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 网课（视频存阿里云 OSS，状态机见 PRD §3.2）
 */
@Data
@TableName("course")
@Accessors(chain = true)
public class Course implements UserOwned {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String title;

    /** 学科（上传时选择） */
    private String subject;

    /** 用户期望（上传时填写，注入笔记生成提示词） */
    private String expectations;

    /** 笔记生成使用的模型配置（上传时选择，NULL = 系统默认） */
    private Long modelConfigId;

    /** 用户学习笔记（网课页随想，富文本 HTML，可选） */
    private String studyNote;

    /** 视频在 OSS 上的对象键 */
    private String videoOssKey;

    /** 视频大小（字节） */
    private Long videoSize;

    /** 视频时长（秒） */
    private Integer duration;

    /** 上次播放位置（秒，播放器定时上报） */
    private Integer lastPositionSec;

    /** 观看进度百分比（0~100，按位置 / 时长取整；无时长时为 NULL） */
    private Integer progressPct;

    /** 最近一次播放上报时间 */
    private LocalDateTime lastStudiedAt;

    /** 处理状态：PENDING / PROCESSING / SUCCESS / FAILED */
    private String status;

    private String errorMsg;

    /** 逻辑删除：0 否 / 1 是 */
    private Integer deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
