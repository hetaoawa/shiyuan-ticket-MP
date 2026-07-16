package top.hetao.shiyuanticketmp.common.version;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.stereotype.Service;

@Service
public class VersionInfoService {

    private static final String UNKNOWN = "unknown";
    private static final int SHORT_COMMIT_LENGTH = 7;

    private final String version;
    private final String commit;

    public VersionInfoService(ObjectProvider<BuildProperties> buildPropertiesProvider,
                              ObjectProvider<GitProperties> gitPropertiesProvider) {
        BuildProperties buildProperties = buildPropertiesProvider.getIfAvailable();
        GitProperties gitProperties = gitPropertiesProvider.getIfAvailable();

        this.version = firstNonBlank(
                buildProperties == null ? null : buildProperties.getVersion(),
                System.getenv("APP_VERSION"));
        this.commit = abbreviate(firstNonBlank(
                gitProperties == null ? null : gitProperties.getCommitId(),
                System.getenv("GIT_COMMIT")));
    }

    public String getVersion() {
        return version;
    }

    public String getCommit() {
        return commit;
    }

    private String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary.trim();
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback.trim();
        }
        return UNKNOWN;
    }

    private String abbreviate(String value) {
        return value.length() <= SHORT_COMMIT_LENGTH
                ? value
                : value.substring(0, SHORT_COMMIT_LENGTH);
    }
}
