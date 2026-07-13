package top.hetao.shiyuanticketmp.common.version;

import cn.dev33.satoken.annotation.SaCheckLogin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/system")
public class VersionController {

    private final VersionInfoService versionInfoService;

    public VersionController(VersionInfoService versionInfoService) {
        this.versionInfoService = versionInfoService;
    }

    @SaCheckLogin
    @GetMapping("/version")
    public Map<String, Object> version() {
        Map<String, Object> data = new HashMap<>();
        data.put("version", versionInfoService.getVersion());
        data.put("commit", versionInfoService.getCommit());

        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("message", "success");
        response.put("data", data);
        return response;
    }
}
