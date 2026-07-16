package top.hetao.shiyuanticketmp.file.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import top.hetao.shiyuanticketmp.common.entity.BaseEntity;

import java.time.LocalDateTime;

/**
 * 文件元数据实体，对应数据库表 {@code sys_file}。
 *
 * <p>记录上传到 S3/OSS 的文件信息，不存储实际文件内容。
 * 实际文件通过预签名 URL 直接上传到对象存储。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_file")
public class SysFile extends BaseEntity {

    public static final String UPLOAD_STATUS_PENDING = "PENDING";
    public static final String UPLOAD_STATUS_CONFIRMED = "CONFIRMED";

    /** 原始文件名 */
    private String originalName;

    /** S3 存储 Key（含目录路径，如 images/2026/05/12/xxx.jpg） */
    private String storageKey;

    /** 文件大小（字节） */
    private Long fileSize;

    /** MIME 类型（如 image/jpeg） */
    private String contentType;

    /** 业务类型（如 WORK_ORDER_IMAGE） */
    private String bizType;

    /** 关联业务ID（如工单ID） */
    private Long bizId;

    /** 上传人ID */
    private Long uploaderId;

    /** 下载地址（可选，缓存用） */
    private String downloadUrl;

    /** 上传生命周期状态：PENDING / CONFIRMED */
    private String uploadStatus;

    /** 服务端完成对象校验并确认的时间 */
    private LocalDateTime confirmedAt;

    /** 确认时对象存储返回的 ETag，用于检测确认后的覆盖 */
    private String etag;

    /** 启用对象版本控制时的版本 ID；未启用时为空 */
    private String objectVersionId;
}
