package com.xueji.agent.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 网课（视频存阿里云 OSS，状态机见 PRD §3.2）
 */
@Data
@TableName("course")
@Accessors(chain = true)
public class Course {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String title;

    /** 学科（上传时选择） */
    private String subject;

    /** 用户期望（上传时填写，注入笔记生成提示词） */
    private String expectations;

    /** 用户学习笔记（网课页随想，富文本 HTML，可选） */
    private String studyNote;

    /** 视频在 OSS 上的对象键 */
    private String videoOssKey;

    /** 视频大小（字节） */
    private Long videoSize;

    /** 视频时长（秒） */
    private Integer duration;

    /** 处理状态：PENDING / PROCESSING / SUCCESS / FAILED */
    private String status;

    private String errorMsg;

    /** 逻辑删除：0 否 / 1 是 */
    private Integer deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
