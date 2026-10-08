package com.xueji.agent.service.impl;

import com.xueji.agent.domain.vo.ChatVideoUploadVO;
import com.xueji.agent.exception.BusinessException;
import com.xueji.agent.service.ChatVideoService;
import com.xueji.agent.utils.MediaUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * 对话视频上传实现：复用网课的格式白名单；
 * 时长仅探测不拒绝（≤30min 轻量转写 / >30min 课程流水线，分流在 TranscribeVideoTool）
 */
@Slf4j
@Service
public class ChatVideoServiceImpl implements ChatVideoService {

    /** 与 AliUploadUtils 的网课视频白名单一致 */
    private static final List<String> ALLOWED_VIDEO_EXT = List.of("mp4", "mov", "mkv", "avi", "webm", "m4v");

    @Override
    public ChatVideoUploadVO upload(MultipartFile file) {
        String original = file.getOriginalFilename();
        String ext = extOf(original);
        if (!ALLOWED_VIDEO_EXT.contains(ext)) {
            throw new BusinessException("不支持的视频格式，仅支持 mp4 / mov / mkv / avi / webm / m4v");
        }
        if (file.getSize() > MAX_CHAT_VIDEO_SIZE) {
            throw new BusinessException("视频大小不能超过 1GB");
        }

        Path temp = null;
        try {
            // 保留原始文件名（+随机后缀保唯一）：下游建课标题兜底取文件名，
            // 无语义的 xj-chat-video-<随机数> 会导致课程标题失去可读性（B27 反馈）
            temp = Files.createTempFile(baseName(original) + "-", "." + ext);
            file.transferTo(temp);
            int durationSec = MediaUtils.ffprobeDurationSec(temp);
            log.info("对话视频上传完成, 时长={}s, 大小={}B", durationSec, file.getSize());
            return new ChatVideoUploadVO().setTempPath(temp.toString()).setDurationSec(durationSec);
        } catch (BusinessException e) {
            cleanupQuietly(temp);
            throw e;
        } catch (IOException | InterruptedException e) {
            cleanupQuietly(temp);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("对话视频保存失败", e);
            throw new BusinessException("视频上传失败，请稍后重试");
        }
    }

    private String extOf(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    /** 原始文件名去扩展名做临时文件基名；清洗非法路径字符，无有效名回退默认（公开静态便于单测） */
    static String baseName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "xj-chat-video";
        }
        String name = fileName;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        name = name.replaceAll("[\\\\/:*?\"<>|\\s]", "_").trim();
        return name.isBlank() ? "xj-chat-video" : name;
    }

    private void cleanupQuietly(Path file) {
        if (file != null) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
            }
        }
    }
}
