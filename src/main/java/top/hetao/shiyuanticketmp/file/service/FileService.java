package top.hetao.shiyuanticketmp.file.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import top.hetao.shiyuanticketmp.common.config.S3Config;
import top.hetao.shiyuanticketmp.file.entity.SysFile;
import top.hetao.shiyuanticketmp.file.mapper.SysFileMapper;
import top.hetao.shiyuanticketmp.workorder.exception.WorkOrderException;

import java.time.Duration;
import java.util.*;

/**
 * 文件服务。
 *
 * <p>提供预签名 URL 生成和文件元数据管理功能。
 * 客户端通过预签名 URL 直接上传文件到 S3/OSS，服务器不经过文件流。
 */
@Slf4j
@Service
public class FileService extends ServiceImpl<SysFileMapper, SysFile> {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final S3Config s3Config;

    @Value("${s3.presign-expire-seconds:3600}")
    private long presignExpireSeconds;

    public FileService(S3Client s3Client, S3Presigner s3Presigner, S3Config s3Config) {
        this.s3Client = s3Client;
        this.s3Presigner = s3Presigner;
        this.s3Config = s3Config;
    }

    /**
     * 生成上传预签名 URL。
     *
     * <p>客户端拿到 URL 后直接 PUT 文件到 S3，无需经过服务器。
     */
    @Transactional
    public Map<String, Object> generateUploadUrl(String originalName, String contentType,
                                                  Long fileSize, String bizType,
                                                  Long bizId, Long uploaderId) {
        String storageKey = buildStorageKey(originalName);

        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(s3Config.getBucket())
                .key(storageKey)
                .contentType(contentType)
                .build();

        PresignedPutObjectRequest presigned = s3Presigner.presignPutObject(r -> r
                .signatureDuration(Duration.ofSeconds(presignExpireSeconds))
                .putObjectRequest(putRequest));

        String uploadUrl = presigned.url().toString();
        log.info("[S3] 生成上传预签名URL: fileName={}, contentType={}, fileSize={}, uploadUrl={}", originalName, contentType, fileSize, uploadUrl);

        SysFile sysFile = new SysFile();
        sysFile.setOriginalName(originalName);
        sysFile.setStorageKey(storageKey);
        sysFile.setFileSize(fileSize);
        sysFile.setContentType(contentType);
        sysFile.setBizType(bizType);
        sysFile.setBizId(bizId);
        sysFile.setUploaderId(uploaderId);
        save(sysFile);

        Map<String, Object> result = new HashMap<>();
        result.put("fileId", sysFile.getId());
        result.put("uploadUrl", uploadUrl);
        result.put("storageKey", storageKey);
        result.put("expireSeconds", presignExpireSeconds);
        return result;
    }

    /**
     * 生成下载预签名 URL。
     */
    public String generateDownloadUrl(Long fileId) {
        SysFile sysFile = getById(fileId);
        if (sysFile == null) {
            throw new WorkOrderException("文件不存在");
        }

        GetObjectRequest getRequest = GetObjectRequest.builder()
                .bucket(s3Config.getBucket())
                .key(sysFile.getStorageKey())
                .build();

        PresignedGetObjectRequest presigned = s3Presigner.presignGetObject(r -> r
                .signatureDuration(Duration.ofSeconds(presignExpireSeconds))
                .getObjectRequest(getRequest));

        return presigned.url().toString();
    }

    /**
     * 根据业务类型和业务ID查询关联文件列表。
     */
    public List<SysFile> listByBiz(String bizType, Long bizId) {
        return list(new LambdaQueryWrapper<SysFile>()
                .eq(SysFile::getBizType, bizType)
                .eq(SysFile::getBizId, bizId)
                .orderByDesc(SysFile::getCreatedAt));
    }

    /**
     * 确认上传完成。
     */
    public void confirmUpload(Long fileId) {
        SysFile sysFile = getById(fileId);
        if (sysFile == null) {
            throw new WorkOrderException("文件不存在");
        }
        log.info("[S3] 确认上传完成: fileId={}, storageKey={}", fileId, sysFile.getStorageKey());
    }

    /**
     * 删除文件（逻辑删除数据库记录 + 物理删除 S3 对象）。
     *
     * @param fileId 文件ID
     */
    @Transactional
    public void deleteFile(Long fileId) {
        SysFile sysFile = getById(fileId);
        if (sysFile == null) {
            throw new WorkOrderException("文件不存在");
        }

        // 物理删除 S3 对象
        try {
            log.info("[S3] 删除文件: fileId={}, storageKey={}", fileId, sysFile.getStorageKey());
            DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
                    .bucket(s3Config.getBucket())
                    .key(sysFile.getStorageKey())
                    .build();
            s3Client.deleteObject(deleteRequest);
        } catch (Exception e) {
            // S3 删除失败不影响数据库删除
            log.error("[S3] 删除S3对象失败: fileId={}, storageKey={}", fileId, sysFile.getStorageKey(), e);
        }

        // 逻辑删除数据库记录
        removeById(fileId);
    }

    private String buildStorageKey(String originalName) {
        String ext = "";
        int dotIndex = originalName.lastIndexOf('.');
        if (dotIndex > 0) {
            ext = originalName.substring(dotIndex);
        }

        java.time.LocalDate now = java.time.LocalDate.now();
        return String.format("images/%d/%02d/%02d/%s%s",
                now.getYear(), now.getMonthValue(), now.getDayOfMonth(),
                UUID.randomUUID().toString().replace("-", ""), ext);
    }
}
