package com.xueji.agent.controller;

import com.xueji.agent.common.Result;
import com.xueji.agent.utils.AliUploadUtils;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件上传接口：对话附图等（PRD §3.3 图片上传）
 */
@RequestMapping("/upload")
@RestController
public class FileController {

    @Resource
    private AliUploadUtils aliUploadUtils;

    /**
     * 上传对话图片，返回可访问的图片 URL
     */
    @PostMapping("/image")
    public Result<String> uploadImage(@RequestParam("file") MultipartFile file) {
        return Result.data(aliUploadUtils.uploadChatImage(file, "chat"));
    }
}
