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

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Objects;
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
