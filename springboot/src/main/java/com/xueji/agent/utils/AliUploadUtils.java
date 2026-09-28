package com.xueji.agent.utils;

import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.ObjectMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import com.xueji.agent.exception.UploadException;
import jakarta.annotation.PostConstruct;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Objects;
import java.util.List;
import java.util.UUID;

/**
 * 雨纷纷旧故里草木深
 *
 * @author  @github dulaiduwang003
 * @version 1.0
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AliUploadUtils {

    @Value("${ali-oss.endpoint}")
    private String endpoint;

    @Value("${ali-oss.accessKey}")
    private String accessKey;

    @Value("${ali-oss.secretKey}")
    private String secretKey;

    @Value("${ali-oss.bucketName}")
    private String bucketName;

    /**
     * 启动期校验 OSS 配置：密钥缺失时直接启动失败，避免每次上传请求都报"服务异常"
     */
    @PostConstruct
    public void checkConfig() {
        if (accessKey == null || accessKey.isBlank() || secretKey == null || secretKey.isBlank()) {
            throw new IllegalStateException(
                    "阿里云 OSS 密钥未配置（OSS_ACCESS_KEY / OSS_SECRET_KEY 为空）："
                            + "请确认用户级环境变量已设置；若变量是后端启动后才添加的，需重启 IDE 让子进程重新继承");
        }
    }

    public String uploadFile(final MultipartFile file, final String path, final String newFileName, final boolean isImage) {
        OSS ossClient = new OSSClientBuilder()
                .build(endpoint, accessKey, secretKey);
        try (InputStream inputStream = file.getInputStream()) {
            String originalFileName = file.getOriginalFilename();

            assert originalFileName != null;
            String fileName;
            fileName = Objects.requireNonNullElseGet(newFileName, () -> UUID.randomUUID() + originalFileName.substring(originalFileName.lastIndexOf('.')));

            String filePath = path + "/" + fileName;

            if (isImage) {
                ObjectMetadata objectMetadata = new ObjectMetadata();
                objectMetadata.setContentType("image/jpg");
                ossClient.putObject(bucketName, filePath, inputStream, objectMetadata);
            } else {
                ossClient.putObject(bucketName, filePath, inputStream);
            }

            return "/" + filePath;
        } catch (IOException e) {
            log.error("无法将图片上传到阿里云。错误消息： {} 错误类： {}", e.getMessage(), e.getClass());
            throw new UploadException();
        } finally {
            ossClient.shutdown();
        }
    }

    /** 对话图片允许的扩展名 */
    private static final List<String> ALLOWED_IMAGE_EXT = List.of("jpg", "jpeg", "png", "gif", "webp", "bmp");

    /** 网课视频允许的扩展名 */
    private static final List<String> ALLOWED_VIDEO_EXT = List.of("mp4", "mov", "mkv", "avi", "webm", "m4v");

    /**
     * 网课视频上传：校验格式，返回完整访问 URL
     */
    public String uploadVideo(final MultipartFile file, final String path) {
        String ext = extractFileExtension(file.getOriginalFilename());
        if (ext == null || !ALLOWED_VIDEO_EXT.contains(ext)) {
            throw new UploadException("不支持的视频格式，仅支持 mp4 / mov / mkv / avi / webm / m4v");
        }
        try (InputStream inputStream = file.getInputStream()) {
            String filePath = path + "/video." + ext;
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentType(getContentTypeFromFileName(filePath));
            metadata.setContentLength(file.getSize());
            putObject(filePath, inputStream, metadata);
            return buildPublicUrl(filePath);
        } catch (IOException e) {
            log.error("视频上传到阿里云失败: {}", e.getMessage(), e);
            throw new UploadException();
        }
    }

    /**
     * 本地文件上传（流水线产物：音频 / 抽帧图片），返回完整访问 URL
     */
    public String uploadLocalFile(final java.nio.file.Path file, final String keyPath) {
        try (InputStream inputStream = java.nio.file.Files.newInputStream(file)) {
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentType(getContentTypeFromFileName(keyPath));
            metadata.setContentLength(java.nio.file.Files.size(file));
            putObject(keyPath, inputStream, metadata);
            return buildPublicUrl(keyPath);
        } catch (IOException e) {
            log.error("本地文件上传到阿里云失败: {} -> {}", file, keyPath, e);
            throw new UploadException();
        }
    }

    private void putObject(String filePath, InputStream inputStream, ObjectMetadata metadata) {
        OSS ossClient = new OSSClientBuilder().build(endpoint, accessKey, secretKey);
        try {
            ossClient.putObject(bucketName, filePath, inputStream, metadata);
        } finally {
            ossClient.shutdown();
        }
    }


    /**
     * 对话图片上传：校验格式，按真实类型设置 Content-Type，返回完整访问 URL
     */
    public String uploadChatImage(final MultipartFile file, final String path) {
        String ext = extractFileExtension(file.getOriginalFilename());
        if (ext == null || !ALLOWED_IMAGE_EXT.contains(ext)) {
            throw new UploadException("不支持的图片格式，仅支持 jpg / jpeg / png / gif / webp / bmp");
        }
        String fileName = UUID.randomUUID() + "." + ext;
        String filePath = path + "/" + fileName;
        OSS ossClient = new OSSClientBuilder().build(endpoint, accessKey, secretKey);
        try (InputStream inputStream = file.getInputStream()) {
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentType(getContentTypeFromFileName(fileName));
            metadata.setContentLength(file.getSize());
            ossClient.putObject(bucketName, filePath, inputStream, metadata);
        } catch (IOException e) {
            log.error("图片上传到阿里云失败: {}", e.getMessage(), e);
            throw new UploadException();
        } finally {
            ossClient.shutdown();
        }
        return buildPublicUrl(filePath);
    }

    /**
     * 拼接对象公网访问 URL（endpoint 兼容带/不带协议两种写法）
     */
    private String buildPublicUrl(String filePath) {
        String host = endpoint.replaceAll("^https?://", "");
        return "https://" + bucketName + "." + host + "/" + filePath;
    }

    /**
     * 从文件名提取小写扩展名（无扩展名返回 null）
     */
    private String extractFileExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return null;
        }
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
    }

//    public String uploadBase64(final String base64, String path) throws IOException {
//        byte[] imageBytes = Base64.getDecoder().decode(base64);
//        ByteArrayInputStream inputStream = new ByteArrayInputStream(imageBytes);
//        // 生成随机的图片文件名
//        final String fileName = UUID.randomUUID() + ".jpg";
//        MultipartFile multipartFile = new MockMultipartFile(fileName, inputStream);
//        return uploadFile(multipartFile, path, fileName, true);
//    }

    public void deleteFile(final String fileUrl) {
        OSS ossClient = new OSSClientBuilder()
                .build(endpoint, accessKey, secretKey);
        try {
            // 从URL中提取文件路径（去掉域名部分）
            String filePath;
            if (fileUrl.startsWith("https://") || fileUrl.startsWith("http://")) {
                // 找到第一个斜杠的位置，通常是域名后的路径开始位置
                int firstSlashIndex = fileUrl.indexOf("/", 8); // 8是避开"https://"的长度
                if (firstSlashIndex != -1) {
                    filePath = fileUrl.substring(firstSlashIndex + 1);
                } else {
                    // 如果没有路径，说明可能直接是bucket根目录下的文件
                    filePath = fileUrl.substring(fileUrl.indexOf("//") + 2);
                }
            } else {
                // 如果不是完整URL，则假设已经是相对路径
                filePath = fileUrl;
            }

            // 删除文件
            ossClient.deleteObject(bucketName, filePath);
        } catch (OSSException | ClientException e) {
            log.error("无法从阿里云删除图片。错误消息： {} 错误类： {}", e.getMessage(), e.getClass());
        } finally {
            ossClient.shutdown();
        }
    }

    public String uploadImageFromUrl(String imageUrl, String path, String newFileName) {
        OSS ossClient = new OSSClientBuilder().build(endpoint, accessKey, secretKey);
        try (InputStream inputStream = new URL(imageUrl).openStream()) {
            String fileName = newFileName != null ? newFileName : UUID.randomUUID().toString();
            String filePath = path + "/" + fileName;
            ObjectMetadata objectMetadata = new ObjectMetadata();
            objectMetadata.setContentType("image/jpeg"); // 根据实际情况设置图片类型
            ossClient.putObject(bucketName, filePath, inputStream, objectMetadata);
            return "/" + filePath;
        } catch (IOException e) {
            throw new UploadException();
        } finally {
            ossClient.shutdown();
        }
    }

    public String uploadFileFromUrl(String fileUrl, String path, String newFileName) {
        OSS ossClient = new OSSClientBuilder().build(endpoint, accessKey, secretKey);
        try (InputStream inputStream = new URL(fileUrl).openStream()) {
            String fileName = newFileName != null ? newFileName : UUID.randomUUID().toString();

            // 尝试从URL中获取文件扩展名
            if (newFileName == null) {
                String extension = getFileExtensionFromUrl(fileUrl);
                if (extension != null && !extension.isEmpty()) {
                    fileName = UUID.randomUUID() + "." + extension;
                } else {
                    fileName = UUID.randomUUID().toString();
                }
            }

            String filePath = path + "/" + fileName;

            // 设置文件的MIME类型
            ObjectMetadata objectMetadata = new ObjectMetadata();
            String contentType = getContentTypeFromFileName(fileName);
            objectMetadata.setContentType(contentType);

            ossClient.putObject(bucketName, filePath, inputStream, objectMetadata);
            return "/" + filePath;
        } catch (IOException e) {
            log.error("无法从URL上传文件到阿里云。错误消息：{} 错误类：{}", e.getMessage(), e.getClass());
            throw new UploadException();
        } finally {
            ossClient.shutdown();
        }
    }


    /**
     * 从URL中提取文件扩展名
     * @param url URL地址
     * @return 文件扩展名，如果没有则返回null
     */
    private String getFileExtensionFromUrl(String url) {
        try {
            String path = new URL(url).getPath();
            int lastDotIndex = path.lastIndexOf('.');
            if (lastDotIndex != -1) {
                return path.substring(lastDotIndex + 1);
            }
        } catch (Exception e) {
            log.warn("无法从URL提取文件扩展名: {}", url);
        }
        return null;
    }

    /**
     * 根据文件名获取Content-Type
     * @param fileName 文件名
     * @return Content-Type字符串
     */
    private String getContentTypeFromFileName(String fileName) {
        if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) {
            return "image/jpeg";
        } else if (fileName.endsWith(".png")) {
            return "image/png";
        } else if (fileName.endsWith(".gif")) {
            return "image/gif";
        } else if (fileName.endsWith(".webp")) {
            return "image/webp";
        } else if (fileName.endsWith(".mp4")) {
            return "video/mp4";
        } else {
            return "application/octet-stream";
        }
    }



}
