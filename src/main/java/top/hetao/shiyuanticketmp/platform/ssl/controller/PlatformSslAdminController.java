package top.hetao.shiyuanticketmp.platform.ssl.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import top.hetao.shiyuanticketmp.platform.ssl.service.CertificateImportResult;
import top.hetao.shiyuanticketmp.platform.ssl.service.DeployTokenResult;
import top.hetao.shiyuanticketmp.platform.ssl.service.PlatformSslException;
import top.hetao.shiyuanticketmp.platform.ssl.service.PlatformSslService;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/platform/ssl")
@SaCheckPermission("platform:ssl:manage")
public class PlatformSslAdminController {

    private final PlatformSslService service;

    public PlatformSslAdminController(PlatformSslService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> status() {
        return success(service.status(), "Platform TLS status");
    }

    @PutMapping("/state")
    public ResponseEntity<Map<String, Object>> changeState(@RequestBody StateRequest request) {
        long operationId = service.requestStateChange(request.enabled(), actorId());
        return ResponseEntity.accepted().body(success(Map.of("operationId", operationId), "Transition queued"));
    }

    @PostMapping(value = "/certificates", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> upload(
            @RequestPart("certificate") MultipartFile certificate,
            @RequestPart("privateKey") MultipartFile privateKey,
            @RequestParam(required = false) String domain) {
        CertificateImportResult result = service.importCertificate(read(certificate), read(privateKey),
                domain, "ADMIN_UPLOAD", actorId(), false);
        return ResponseEntity.accepted().body(success(result, "Certificate validation passed; install queued"));
    }

    @PostMapping(value = "/legacy-import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> legacyImport(
            @RequestPart("certificate") MultipartFile certificate,
            @RequestPart("privateKey") MultipartFile privateKey,
            @RequestParam(required = false) String domain) {
        CertificateImportResult result = service.importCertificate(read(certificate), read(privateKey),
                domain, "LEGACY_GLOBAL", actorId(), true);
        return ResponseEntity.accepted().body(success(result, "One-time legacy import queued"));
    }

    @PostMapping("/deploy-tokens")
    public Map<String, Object> createDeployToken(@RequestBody DeployTokenRequest request) {
        DeployTokenResult result = service.createDeployToken(request.name(), request.expiresInMinutes(), actorId());
        return success(result, "Token is shown once and stored only as SHA-256");
    }

    @GetMapping("/operations")
    public Map<String, Object> operations(@RequestParam(defaultValue = "20") int limit) {
        return success(service.recentOperations(limit), "Recent platform TLS operations");
    }

    private byte[] read(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new PlatformSslException("Unable to read uploaded PEM", e);
        }
    }

    private long actorId() {
        return Long.parseLong(StpUtil.getLoginId().toString());
    }

    private <T> Map<String, Object> success(T data, String message) {
        return Map.of("code", 200, "message", message, "data", data);
    }

    public record StateRequest(boolean enabled) { }

    public record DeployTokenRequest(String name, int expiresInMinutes) { }
}
