package com.xueji.agent.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 异步任务（视频处理 / 转写 / OCR 等 RabbitMQ 长任务）
 */
@Data
@TableName("async_task")
@Accessors(chain = true)
public class AsyncTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 任务类型：transcribe / ocr / ai_note 等 */
    private String taskType;

    /** 业务类型：course / question 等 */
    private String bizType;

    private Long bizId;

    /** 任务状态：PENDING / PROCESSING / SUCCESS / FAILED */
    private String status;

    /** 进度百分比 0~100 */
    private Integer progress;

    private String errorMsg;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
