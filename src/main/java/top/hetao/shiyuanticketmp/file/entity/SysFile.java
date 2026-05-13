package top.hetao.shiyuanticketmp.file.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import top.hetao.shiyuanticketmp.common.entity.BaseEntity;

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
}
