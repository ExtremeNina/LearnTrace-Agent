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
            temp = Files.createTempFile("xj-chat-video-", "." + ext);
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

    private void cleanupQuietly(Path file) {
        if (file != null) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
            }
        }
    }
}
