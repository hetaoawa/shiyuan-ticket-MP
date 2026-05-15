package top.hetao.shiyuanticketmp.file.controller;

import cn.dev33.satoken.stp.StpUtil;
import org.springframework.web.bind.annotation.*;
import top.hetao.shiyuanticketmp.file.entity.SysFile;
import top.hetao.shiyuanticketmp.file.service.FileService;
import top.hetao.shiyuanticketmp.workorder.exception.WorkOrderException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件管理 REST API 控制器。
 *
 * <p>采用预签名 URL 模式：
 * <ol>
 *   <li>客户端请求 {@code POST /api/files/presign} 获取上传 URL</li>
 *   <li>客户端使用返回的 uploadUrl 直接 PUT 文件到 S3</li>
 *   <li>客户端上传成功后调用 {@code POST /api/files/{id}/confirm} 确认</li>
 *   <li>需要下载时请求 {@code GET /api/files/{id}/download} 获取下载 URL</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/files")
public class FileController {

    private final FileService fileService;

    public FileController(FileService fileService) {
        this.fileService = fileService;
    }

    /**
     * 获取上传预签名 URL。
     *
     * <p>客户端拿到 URL 后直接 PUT 文件到 S3，无需经过服务器中转。
     */
    @PostMapping("/presign")
    public Map<String, Object> getUploadUrl(@RequestBody Map<String, Object> request) {
        String originalName = (String) request.get("originalName");
        String contentType = (String) request.get("contentType");
        Long fileSize = request.get("fileSize") != null ?
                Long.parseLong(request.get("fileSize").toString()) : null;
        String bizType = (String) request.get("bizType");
        Long bizId = request.get("bizId") != null ?
                Long.parseLong(request.get("bizId").toString()) : null;

        if (originalName == null || originalName.isBlank()) {
            throw new WorkOrderException("文件名不能为空");
        }
        if (contentType == null || contentType.isBlank()) {
            throw new WorkOrderException("文件类型不能为空");
        }
        if (fileSize == null || fileSize <= 0) {
            throw new WorkOrderException("文件大小必须大于0");
        }

        // 限制文件大小 10MB
        if (fileSize > 10 * 1024 * 1024) {
            throw new WorkOrderException("文件大小不能超过10MB");
        }

        // 限制文件类型
        if (!contentType.startsWith("image/")) {
            throw new WorkOrderException("仅支持图片文件");
        }

        Long uploaderId = StpUtil.getLoginIdAsLong();
        Map<String, Object> result = fileService.generateUploadUrl(
                originalName, contentType, fileSize, bizType, bizId, uploaderId);

        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("message", "预签名 URL 生成成功");
        response.put("data", result);
        return response;
    }

    /**
     * 确认上传完成。
     *
     * <p>客户端上传成功后回调，用于更新文件状态。
     */
    @PostMapping("/{id}/confirm")
    public Map<String, Object> confirmUpload(@PathVariable Long id) {
        fileService.confirmUpload(id);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "上传确认成功");
        return result;
    }

    /**
     * 获取下载预签名 URL。
     */
    @GetMapping("/{id}/download")
    public Map<String, Object> getDownloadUrl(@PathVariable Long id) {
        String downloadUrl = fileService.generateDownloadUrl(id);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", Map.of("downloadUrl", downloadUrl));
        return result;
    }

    /**
     * 查询业务关联的文件列表。
     */
    @GetMapping
    public Map<String, Object> listByBiz(@RequestParam String bizType,
                                         @RequestParam Long bizId) {
        List<SysFile> files = fileService.listByBiz(bizType, bizId);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", files);
        return result;
    }

    /**
     * 删除文件。
     */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        fileService.deleteFile(id);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "删除成功");
        return result;
    }
}
