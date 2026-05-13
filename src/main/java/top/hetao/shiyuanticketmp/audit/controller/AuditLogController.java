package top.hetao.shiyuanticketmp.audit.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import org.springframework.web.bind.annotation.*;
import top.hetao.shiyuanticketmp.audit.entity.SysAuditLog;
import top.hetao.shiyuanticketmp.audit.service.AuditLogService;

import java.util.HashMap;
import java.util.Map;

/**
 * 审计日志 REST API 控制器。
 */
@RestController
@RequestMapping("/api/audit-logs")
public class AuditLogController {

    private final AuditLogService auditLogService;

    public AuditLogController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    /**
     * 分页查询审计日志。
     */
    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "WORK_ORDER") String bizType,
                                    @RequestParam(required = false) Long bizId,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "10") int pageSize) {
        IPage<SysAuditLog> result = auditLogService.listPage(bizType, bizId, page, pageSize);

        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("data", result.getRecords());
        response.put("total", result.getTotal());
        response.put("page", page);
        response.put("pageSize", pageSize);
        return response;
    }
}
