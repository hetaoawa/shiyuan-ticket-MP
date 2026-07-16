package top.hetao.shiyuanticketmp.tenant.integration;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.context.SaTokenContext;
import cn.dev33.satoken.context.SaTokenContextForThreadLocal;
import cn.dev33.satoken.context.SaTokenContextForThreadLocalStorage;
import cn.dev33.satoken.context.model.SaRequest;
import cn.dev33.satoken.context.model.SaResponse;
import cn.dev33.satoken.context.model.SaStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;
import top.hetao.shiyuanticketmp.audit.service.AuditLogService;
import top.hetao.shiyuanticketmp.tenant.service.TenantLifecycleGuard;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantIntegrationServiceTest {
    private final TenantIntegrationMapper mapper = mock(TenantIntegrationMapper.class);
    private final TenantIntegrationResolver resolver = mock(TenantIntegrationResolver.class);
    private final IntegrationSecretCrypto crypto = mock(IntegrationSecretCrypto.class);
    private final TenantLifecycleGuard lifecycleGuard = mock(TenantLifecycleGuard.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TenantIntegrationService service = new TenantIntegrationService(
            mapper, resolver, crypto, objectMapper, lifecycleGuard, auditLogService);

    private SysTenantIntegration existing;
    private SaTokenContext originalSaTokenContext;

    @BeforeEach
    void setUp() {
        originalSaTokenContext = SaManager.getSaTokenContext();
        SaManager.setSaTokenContext(new SaTokenContextForThreadLocal());
        SaTokenContextForThreadLocalStorage.setBox(
                mock(SaRequest.class), mock(SaResponse.class), mock(SaStorage.class));
        existing = new SysTenantIntegration();
        existing.setId(7L);
        existing.setTenantId(100L);
        existing.setIntegrationType(IntegrationType.AI.name());
        existing.setEnabled(true);
        existing.setPublicConfigJson("""
                {"apiUrl":"https://old.example/v1/chat/completions","model":"old-model","legacyOption":"legacy-value"}
                """);
        existing.setSecretConfigJson("{}");
        existing.setConfigVersion(3L);
        when(mapper.selectOne(any())).thenReturn(existing);
    }

    @AfterEach
    void tearDown() {
        SaTokenContextForThreadLocalStorage.clearBox();
        SaManager.setSaTokenContext(originalSaTokenContext);
    }

    @Test
    void updateAcceptsUnchangedLegacyFieldEchoAndRemovesItFromStoredAndReturnedConfig() throws Exception {
        when(mapper.updateCas(anyLong(), anyLong(), anyLong(), anyBoolean(), anyString(), anyString()))
                .thenReturn(1);
        ObjectNode request = updateRequest();
        ((ObjectNode) request.get("config"))
                .put("apiUrl", "https://new.example/v1/chat/completions")
                .put("model", "new-model")
                .put("legacyOption", "legacy-value");

        Map<String, Object> result = service.update(100L, IntegrationType.AI, request);

        ArgumentCaptor<String> publicJson = ArgumentCaptor.forClass(String.class);
        verify(mapper).updateCas(eq(7L), eq(100L), eq(3L), eq(true),
                publicJson.capture(), anyString());
        assertThat(objectMapper.readTree(publicJson.getValue()).has("legacyOption")).isFalse();
        assertThat(config(result))
                .containsEntry("model", "new-model")
                .doesNotContainKey("legacyOption");
    }

    @Test
    void getDoesNotExposeUnknownStoredPublicFieldsToUpdateClients() {
        when(resolver.resolve(100L, IntegrationType.AI)).thenReturn(new ResolvedIntegration(
                100L, IntegrationType.AI, true, 3L,
                Map.of("apiUrl", "https://example/v1/chat/completions",
                        "model", "model", "legacyOption", "legacy-value"),
                Map.of()));

        Map<String, Object> result = service.get(100L, IntegrationType.AI);

        assertThat(config(result))
                .containsEntry("model", "model")
                .doesNotContainKey("legacyOption");
    }

    @Test
    void newUnknownFieldKeepsSpecificValidationMessage() {
        ObjectNode request = updateRequest();
        ((ObjectNode) request.get("config")).put("typoField", "value");

        assertThatThrownBy(() -> service.update(100L, IntegrationType.AI, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown public field: typoField");
    }

    @Test
    void missingEncryptionRootKeyIsNotMaskedAsInvalidIntegrationConfiguration() {
        TenantIntegrationService serviceWithoutKey = new TenantIntegrationService(
                mapper, resolver, new IntegrationSecretCrypto(new MockEnvironment()), objectMapper,
                lifecycleGuard, auditLogService);
        ObjectNode request = updateRequest();
        ((ObjectNode) request.get("secrets")).putObject("apiKey").put("value", "secret-value");

        assertThatThrownBy(() -> serviceWithoutKey.update(100L, IntegrationType.AI, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("TENANT_INTEGRATION_ROOT_KEY or PLATFORM_SSL_ROOT_KEY must be configured")
                .hasCauseInstanceOf(IntegrationSecretCrypto.ConfigurationException.class);
    }

    @Test
    void publicOnlyUpdateDoesNotRequireEncryptionRootKey() {
        when(mapper.updateCas(anyLong(), anyLong(), anyLong(), anyBoolean(), anyString(), anyString()))
                .thenReturn(1);
        existing.setSecretConfigJson("""
                {"apiKey":{"ciphertext":"stored-ciphertext","nonce":"stored-nonce"}}
                """);
        TenantIntegrationService serviceWithoutKey = new TenantIntegrationService(
                mapper, resolver, new IntegrationSecretCrypto(new MockEnvironment()), objectMapper,
                lifecycleGuard, auditLogService);
        ObjectNode request = updateRequest();
        ((ObjectNode) request.get("config"))
                .put("apiUrl", "https://new.example/v1/chat/completions")
                .put("model", "new-model");

        Map<String, Object> result = serviceWithoutKey.update(100L, IntegrationType.AI, request);

        assertThat(config(result)).containsEntry("model", "new-model");
        assertThat(secretConfigured(result)).containsEntry("apiKey", true);
    }

    private ObjectNode updateRequest() {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("configVersion", 3L);
        request.put("enabled", true);
        request.putObject("config");
        request.putObject("secrets");
        return request;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> config(Map<String, Object> response) {
        return (Map<String, Object>) response.get("config");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Boolean> secretConfigured(Map<String, Object> response) {
        return (Map<String, Boolean>) response.get("secretConfigured");
    }
}
