package top.hetao.shiyuanticketmp.file.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import top.hetao.shiyuanticketmp.common.config.S3Config;
import top.hetao.shiyuanticketmp.common.context.TenantContext;
import top.hetao.shiyuanticketmp.file.entity.SysFile;
import top.hetao.shiyuanticketmp.file.mapper.SysFileMapper;
import top.hetao.shiyuanticketmp.tenant.service.TenantLifecycleGuard;
import top.hetao.shiyuanticketmp.workorder.exception.WorkOrderException;

import java.time.Duration;
import java.time.LocalDateTime;
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
    private final TenantLifecycleGuard tenantLifecycleGuard;

    @Value("${s3.presign-expire-seconds:3600}")
    private long presignExpireSeconds;

    public FileService(S3Client s3Client, S3Presigner s3Presigner, S3Config s3Config,
                       TenantLifecycleGuard tenantLifecycleGuard) {
        this.s3Client = s3Client;
        this.s3Presigner = s3Presigner;
        this.s3Config = s3Config;
        this.tenantLifecycleGuard = tenantLifecycleGuard;
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
        tenantLifecycleGuard.lockWritableTenant(TenantContext.requireTenantId());
        String storageKey = buildStorageKey(originalName);

        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(s3Config.getBucket())
                .key(storageKey)
                .contentType(contentType)
                .contentLength(fileSize)
                .build();

        PresignedPutObjectRequest presigned = s3Presigner.presignPutObject(r -> r
                .signatureDuration(Duration.ofSeconds(presignExpireSeconds))
                .putObjectRequest(putRequest));

        String uploadUrl = presigned.url().toString();

        SysFile sysFile = new SysFile();
        sysFile.setOriginalName(originalName);
        sysFile.setStorageKey(storageKey);
        sysFile.setFileSize(fileSize);
        sysFile.setContentType(contentType);
        sysFile.setBizType(bizType);
        sysFile.setBizId(bizId);
        sysFile.setUploaderId(uploaderId);
        sysFile.setUploadStatus(SysFile.UPLOAD_STATUS_PENDING);
        save(sysFile);
        log.info("[S3] upload URL issued fileId={} storageKey={} expireSeconds={}",
                sysFile.getId(), storageKey, presignExpireSeconds);

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
    @Transactional
    public String generateDownloadUrl(Long fileId) {
        SysFile sysFile = getById(fileId);
        if (sysFile == null) {
            throw new WorkOrderException("文件不存在");
        }
        if (!SysFile.UPLOAD_STATUS_CONFIRMED.equals(sysFile.getUploadStatus())) {
            throw new WorkOrderException("文件尚未确认上传");
        }

        verifyConfirmedObject(sysFile);

        GetObjectRequest.Builder getRequest = GetObjectRequest.builder()
                .bucket(s3Config.getBucket())
                .key(sysFile.getStorageKey());
        if (hasText(sysFile.getObjectVersionId())) {
            getRequest.versionId(sysFile.getObjectVersionId());
        }

        PresignedGetObjectRequest presigned = s3Presigner.presignGetObject(r -> r
                .signatureDuration(Duration.ofSeconds(presignExpireSeconds))
                .getObjectRequest(getRequest.build()));

        return presigned.url().toString();
    }

    /**
     * 根据业务类型和业务ID查询关联文件列表。
     */
    public List<SysFile> listByBiz(String bizType, Long bizId) {
        return list(new LambdaQueryWrapper<SysFile>()
                .eq(SysFile::getBizType, bizType)
                .eq(SysFile::getBizId, bizId)
                .eq(SysFile::getUploadStatus, SysFile.UPLOAD_STATUS_CONFIRMED)
                .orderByDesc(SysFile::getCreatedAt));
    }

    /**
     * 确认上传完成。
     */
    @Transactional
    public void confirmUpload(Long fileId) {
        SysFile sysFile = getById(fileId);
        if (sysFile == null) {
            throw new WorkOrderException("文件不存在");
        }

        if (SysFile.UPLOAD_STATUS_CONFIRMED.equals(sysFile.getUploadStatus())) {
            verifyConfirmedObject(sysFile);
            return;
        }
        if (!SysFile.UPLOAD_STATUS_PENDING.equals(sysFile.getUploadStatus())) {
            throw new WorkOrderException("文件上传状态无效");
        }

        HeadObjectResponse head = headObject(sysFile, false);
        validateUploadedObject(sysFile, head);
        String etag = requireEtag(head);
        LocalDateTime confirmedAt = LocalDateTime.now();
        int updated = getBaseMapper().update(null, new LambdaUpdateWrapper<SysFile>()
                .eq(SysFile::getId, fileId)
                .eq(SysFile::getUploadStatus, SysFile.UPLOAD_STATUS_PENDING)
                .set(SysFile::getUploadStatus, SysFile.UPLOAD_STATUS_CONFIRMED)
                .set(SysFile::getConfirmedAt, confirmedAt)
                .set(SysFile::getEtag, etag)
                .set(SysFile::getObjectVersionId, blankToNull(head.versionId())));
        if (updated != 1) {
            SysFile current = getById(fileId);
            if (current != null && SysFile.UPLOAD_STATUS_CONFIRMED.equals(current.getUploadStatus())) {
                verifyConfirmedObject(current);
                return;
            }
            throw new WorkOrderException("文件上传确认失败，请重试");
        }

        sysFile.setUploadStatus(SysFile.UPLOAD_STATUS_CONFIRMED);
        sysFile.setConfirmedAt(confirmedAt);
        sysFile.setEtag(etag);
        sysFile.setObjectVersionId(blankToNull(head.versionId()));
        log.info("[S3] 确认上传完成: fileId={}, storageKey={}", fileId, sysFile.getStorageKey());
    }

    private HeadObjectResponse headObject(SysFile sysFile, boolean useConfirmedVersion) {
        try {
            HeadObjectRequest.Builder request = HeadObjectRequest.builder()
                    .bucket(s3Config.getBucket())
                    .key(sysFile.getStorageKey());
            if (useConfirmedVersion && hasText(sysFile.getObjectVersionId())) {
                request.versionId(sysFile.getObjectVersionId());
            }
            return s3Client.headObject(request.build());
        } catch (Exception e) {
            throw new WorkOrderException("无法确认已上传文件");
        }
    }

    private void validateUploadedObject(SysFile sysFile, HeadObjectResponse head) {
        Long contentLength = head.contentLength();
        String contentType = head.contentType();
        if (contentLength == null || contentLength <= 0 || contentLength > 10L * 1024 * 1024
                || !contentLength.equals(sysFile.getFileSize())) {
            throw new WorkOrderException("已上传文件大小与声明不一致或超过 10MB");
        }
        if (contentType == null || !contentType.startsWith("image/")
                || sysFile.getContentType() == null
                || !contentType.equalsIgnoreCase(sysFile.getContentType())) {
            throw new WorkOrderException("已上传文件类型与声明不一致或不是图片");
        }
    }

    private void verifyConfirmedObject(SysFile sysFile) {
        HeadObjectResponse head = headObject(sysFile, true);
        validateUploadedObject(sysFile, head);
        String currentEtag = requireEtag(head);

        if (!hasText(sysFile.getEtag())) {
            bindLegacyConfirmedMetadata(sysFile, head, currentEtag);
            return;
        }
        if (!sysFile.getEtag().equals(currentEtag)) {
            throw new WorkOrderException("文件内容已发生变化，请重新上传");
        }
        if (hasText(sysFile.getObjectVersionId())
                && !sysFile.getObjectVersionId().equals(head.versionId())) {
            throw new WorkOrderException("文件版本已发生变化，请重新上传");
        }
    }

    /**
     * V23 前的文件没有确认 ETag。首次下载时在完成 HEAD 校验后补齐，
     * 后续下载即可检测未启用版本控制的对象被覆盖。
     */
    private void bindLegacyConfirmedMetadata(SysFile sysFile, HeadObjectResponse head, String etag) {
        LocalDateTime confirmedAt = sysFile.getConfirmedAt() == null
                ? LocalDateTime.now() : sysFile.getConfirmedAt();
        String versionId = blankToNull(head.versionId());
        int updated = getBaseMapper().update(null, new LambdaUpdateWrapper<SysFile>()
                .eq(SysFile::getId, sysFile.getId())
                .eq(SysFile::getUploadStatus, SysFile.UPLOAD_STATUS_CONFIRMED)
                .isNull(SysFile::getEtag)
                .set(SysFile::getConfirmedAt, confirmedAt)
                .set(SysFile::getEtag, etag)
                .set(SysFile::getObjectVersionId, versionId));
        if (updated != 1) {
            SysFile current = getById(sysFile.getId());
            if (current == null || !etag.equals(current.getEtag())) {
                throw new WorkOrderException("文件确认元数据已变化，请重试");
            }
            sysFile.setConfirmedAt(current.getConfirmedAt());
            sysFile.setEtag(current.getEtag());
            sysFile.setObjectVersionId(current.getObjectVersionId());
            return;
        }
        sysFile.setConfirmedAt(confirmedAt);
        sysFile.setEtag(etag);
        sysFile.setObjectVersionId(versionId);
    }

    private String requireEtag(HeadObjectResponse head) {
        if (!hasText(head.eTag())) {
            throw new WorkOrderException("对象存储未返回文件 ETag，无法确认上传");
        }
        return head.eTag();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String blankToNull(String value) {
        return hasText(value) ? value : null;
    }

    /**
     * 删除文件（逻辑删除数据库记录 + 物理删除 S3 对象）。
     *
     * @param fileId 文件ID
     */
    @Transactional
    public void deleteFile(Long fileId) {
        tenantLifecycleGuard.lockWritableTenant(TenantContext.requireTenantId());
        SysFile sysFile = getById(fileId);
        if (sysFile == null) {
            throw new WorkOrderException("文件不存在");
        }

        // 物理删除 S3 对象。版本化桶必须删除确认时固定的版本，否则只会写入 delete marker。
        try {
            log.info("[S3] 删除文件: fileId={}, storageKey={}", fileId, sysFile.getStorageKey());
            DeleteObjectRequest.Builder deleteRequest = DeleteObjectRequest.builder()
                    .bucket(s3Config.getBucket())
                    .key(sysFile.getStorageKey());
            if (hasText(sysFile.getObjectVersionId())) {
                deleteRequest.versionId(sysFile.getObjectVersionId());
            }
            s3Client.deleteObject(deleteRequest.build());
        } catch (Exception e) {
            if (isMissingObject(e)) {
                log.info("[S3] 待删除对象已不存在: fileId={}, storageKey={}",
                        fileId, sysFile.getStorageKey());
            } else {
                // 保留数据库记录才能安全重试；运行时异常会使 @Transactional 回滚逻辑删除。
                log.error("[S3] 删除S3对象失败: fileId={}, storageKey={}",
                        fileId, sysFile.getStorageKey(), e);
                throw new WorkOrderException("删除对象存储文件失败，请重试", e);
            }
        }

        // 逻辑删除数据库记录
        removeById(fileId);
    }

    private boolean isMissingObject(Exception exception) {
        if (!(exception instanceof S3Exception s3Exception)
                || s3Exception.awsErrorDetails() == null) {
            return false;
        }
        String errorCode = s3Exception.awsErrorDetails().errorCode();
        return "NoSuchKey".equals(errorCode) || "NoSuchVersion".equals(errorCode);
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
