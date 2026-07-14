package top.hetao.shiyuanticketmp.tenant.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class TenantIntegrationSecurityTest {
    @Test
    void secretsAreBoundToTenantTypeAndFieldByAad() {
        String key= Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
        IntegrationSecretCrypto crypto=new IntegrationSecretCrypto(new MockEnvironment()
                .withProperty("TENANT_INTEGRATION_ROOT_KEY",key));
        var encrypted=crypto.encrypt(100,IntegrationType.AI,"apiKey","secret-value");
        assertEquals("secret-value",crypto.decrypt(100,IntegrationType.AI,"apiKey",encrypted));
        assertThrows(IllegalStateException.class,()->crypto.decrypt(101,IntegrationType.AI,"apiKey",encrypted));
        assertThrows(IllegalStateException.class,()->crypto.decrypt(100,IntegrationType.EXPRESS,"apiKey",encrypted));
    }

    @Test
    void missingIntegrationIsDisabledAndContainsNoSecrets() {
        TenantIntegrationMapper mapper=mock(TenantIntegrationMapper.class);
        IntegrationSecretCrypto crypto=mock(IntegrationSecretCrypto.class);
        TenantIntegrationResolver resolver=new TenantIntegrationResolver(mapper,crypto,new ObjectMapper(),mock(StringRedisTemplate.class));
        ResolvedIntegration result=resolver.resolve(100,IntegrationType.DINGTALK);
        assertFalse(result.enabled()); assertEquals(0,result.configVersion()); assertTrue(result.secrets().isEmpty());
    }
}
