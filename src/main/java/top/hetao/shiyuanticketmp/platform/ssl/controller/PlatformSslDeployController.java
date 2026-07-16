package top.hetao.shiyuanticketmp.platform.ssl.controller;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import top.hetao.shiyuanticketmp.platform.ssl.service.CertificateImportResult;
import top.hetao.shiyuanticketmp.platform.ssl.service.PlatformSslException;
import top.hetao.shiyuanticketmp.platform.ssl.service.PlatformSslService;

import java.io.IOException;
import java.util.Map;

@RestController
public class PlatformSslDeployController {

    private final PlatformSslService service;

    public PlatformSslDeployController(PlatformSslService service) {
        this.service = service;
    }

    @PostMapping(value = "/api/platform/ssl/deploy/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> deploy(
            @RequestHeader("X-Deploy-Token") String token,
            @RequestPart("certificate") MultipartFile certificate,
            @RequestPart("privateKey") MultipartFile privateKey,
            @RequestParam(required = false) String domain) {
        CertificateImportResult result = service.importFromDeployToken(
                token, read(certificate), read(privateKey), domain);
        return ResponseEntity.accepted().body(Map.of(
                "code", 200,
                "message", "Certificate accepted; install queued",
                "data", result));
    }

    private byte[] read(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new PlatformSslException("Unable to read uploaded PEM", e);
        }
    }
}
