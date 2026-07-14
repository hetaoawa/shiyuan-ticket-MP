package top.hetao.shiyuanticketmp.tenant.integration;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.hetao.shiyuanticketmp.audit.entity.SysAuditLog;
import top.hetao.shiyuanticketmp.audit.service.AuditLogService;
import top.hetao.shiyuanticketmp.common.context.TenantContext;
import top.hetao.shiyuanticketmp.tenant.service.TenantLifecycleGuard;

import java.util.*;

@Service
public class TenantIntegrationService {
    private static final Map<IntegrationType, Set<String>> PUBLIC_FIELDS = Map.of(
            IntegrationType.DINGTALK, Set.of("workOrderDetailBaseUrl"),
            IntegrationType.CARGO_OWNER, Set.of("url", "workOrderDetailBaseUrl", "receiveAppId", "externalInboundEnabled", "externalCloseCallbackEnabled"),
            IntegrationType.S3, Set.of("endpoint", "region", "bucket", "pathStyle", "presignExpireSeconds"),
            IntegrationType.EXPRESS, Set.of("apiUrl", "timeoutSeconds"),
            IntegrationType.AI, Set.of("apiUrl", "model", "timeoutSeconds"));
    private static final Map<IntegrationType, Set<String>> SECRET_FIELDS = Map.of(
            IntegrationType.DINGTALK, Set.of("accessToken", "secret"),
            IntegrationType.CARGO_OWNER, Set.of("authorization", "receivePrivateKey"),
            IntegrationType.S3, Set.of("accessKey", "secretKey"),
            IntegrationType.EXPRESS, Set.of("appcode"),
            IntegrationType.AI, Set.of("apiKey"));

    private final TenantIntegrationMapper mapper; private final TenantIntegrationResolver resolver;
    private final IntegrationSecretCrypto crypto; private final ObjectMapper objectMapper;
    private final TenantLifecycleGuard lifecycleGuard; private final AuditLogService auditLogService;
    public TenantIntegrationService(TenantIntegrationMapper mapper, TenantIntegrationResolver resolver,
            IntegrationSecretCrypto crypto, ObjectMapper objectMapper, TenantLifecycleGuard lifecycleGuard,
            AuditLogService auditLogService) {
        this.mapper=mapper; this.resolver=resolver; this.crypto=crypto; this.objectMapper=objectMapper;
        this.lifecycleGuard=lifecycleGuard; this.auditLogService=auditLogService;
    }

    public List<Map<String,Object>> list(long tenantId) {
        List<Map<String,Object>> result = new ArrayList<>();
        for (IntegrationType type : IntegrationType.values()) result.add(get(tenantId, type));
        return result;
    }
    public Map<String,Object> get(long tenantId, IntegrationType type) { return response(resolver.resolve(tenantId, type)); }

    @Transactional
    public Map<String,Object> update(long tenantId, IntegrationType type, JsonNode request) {
        if (request == null || !request.isObject()) throw new IllegalArgumentException("Request body must be an object");
        lifecycleGuard.lockWritableTenant(tenantId);
        JsonNode versionNode=request.get("configVersion"), enabledNode=request.get("enabled");
        if (versionNode == null || !versionNode.canConvertToLong() || enabledNode == null || !enabledNode.isBoolean())
            throw new IllegalArgumentException("configVersion and boolean enabled are required");
        long expected=versionNode.longValue();
        SysTenantIntegration existing;
        try (TenantContext.Scope ignored=TenantContext.useTenant(tenantId)) {
            existing=mapper.selectOne(new LambdaQueryWrapper<SysTenantIntegration>()
                    .eq(SysTenantIntegration::getIntegrationType,type.name()).last("LIMIT 1"));
        }
        if (existing == null && expected != 0) throw new IntegrationVersionConflictException();
        if (existing != null && !Objects.equals(existing.getConfigVersion(), expected)) throw new IntegrationVersionConflictException();
        try {
            Map<String,Object> pub=existing==null||existing.getPublicConfigJson()==null?new LinkedHashMap<>():
                    objectMapper.readValue(existing.getPublicConfigJson(),new TypeReference<>(){});
            Map<String,IntegrationSecretCrypto.EncryptedValue> secrets=existing==null||existing.getSecretConfigJson()==null?new LinkedHashMap<>():
                    objectMapper.readValue(existing.getSecretConfigJson(),new TypeReference<>(){});
            applyPublic(type,request.get("config"),pub); applySecrets(tenantId,type,request.get("secrets"),secrets);
            validate(type,pub);
            if(existing==null){
                SysTenantIntegration row=new SysTenantIntegration(); row.setTenantId(tenantId); row.setIntegrationType(type.name());
                row.setEnabled(enabledNode.booleanValue()); row.setPublicConfigJson(objectMapper.writeValueAsString(pub));
                row.setSecretConfigJson(objectMapper.writeValueAsString(secrets)); row.setConfigVersion(1L);
                try(TenantContext.Scope ignored=TenantContext.useTenant(tenantId)){ mapper.insert(row); } existing=row;
            } else {
                int changed;
                try(TenantContext.Scope ignored=TenantContext.useTenant(tenantId)){
                    changed=mapper.updateCas(existing.getId(),tenantId,expected,enabledNode.booleanValue(),
                            objectMapper.writeValueAsString(pub),objectMapper.writeValueAsString(secrets));
                }
                if(changed!=1) throw new IntegrationVersionConflictException(); existing.setConfigVersion(expected+1);
            }
            resolver.invalidate(tenantId,type); audit(tenantId,existing.getId(),type,enabledNode.booleanValue(),pub.keySet());
            return get(tenantId,type);
        } catch (IntegrationVersionConflictException e){throw e;} catch(Exception e){throw new IllegalArgumentException("Invalid integration configuration",e);}
    }

    private void applyPublic(IntegrationType type, JsonNode node, Map<String,Object> target){
        if(node==null)return; if(!node.isObject())throw new IllegalArgumentException("config must be an object");
        node.fields().forEachRemaining(e->{ if(!PUBLIC_FIELDS.get(type).contains(e.getKey()))throw new IllegalArgumentException("Unknown public field: "+e.getKey());
            target.put(e.getKey(),objectMapper.convertValue(e.getValue(),Object.class)); });
    }
    private void applySecrets(long tenantId,IntegrationType type,JsonNode node,Map<String,IntegrationSecretCrypto.EncryptedValue> target){
        if(node==null)return; if(!node.isObject())throw new IllegalArgumentException("secrets must be an object");
        node.fields().forEachRemaining(e->{String field=e.getKey(); if(!SECRET_FIELDS.get(type).contains(field))throw new IllegalArgumentException("Unknown secret field: "+field);
            JsonNode patch=e.getValue(); if(!patch.isObject())throw new IllegalArgumentException("Secret patch must be an object");
            if(patch.path("clear").asBoolean(false)){target.remove(field);return;} JsonNode value=patch.get("value");
            if(value!=null){if(!value.isTextual()||value.textValue().isBlank())throw new IllegalArgumentException("Secret value must be non-blank"); target.put(field,crypto.encrypt(tenantId,type,field,value.textValue()));}
        });
    }
    private void validate(IntegrationType type,Map<String,Object> pub){
        for(String name:List.of("timeoutSeconds","presignExpireSeconds")){if(pub.containsKey(name)){int v;
            try{v=Integer.parseInt(String.valueOf(pub.get(name)));}catch(Exception e){throw new IllegalArgumentException(name+" must be an integer");}
            if(v<1||v>86400)throw new IllegalArgumentException(name+" is out of range");}}
    }
    private Map<String,Object> response(ResolvedIntegration value){
        Map<String,Object> map=new LinkedHashMap<>();map.put("type",value.type());map.put("enabled",value.enabled());
        map.put("configVersion",value.configVersion());map.put("config",value.publicConfig());
        Map<String,Boolean> configured=new LinkedHashMap<>();SECRET_FIELDS.get(value.type()).forEach(k->configured.put(k,value.secrets().containsKey(k)));
        map.put("secretConfigured",configured);return map;
    }
    private void audit(long tenantId,Long id,IntegrationType type,boolean enabled,Set<String> publicKeys)throws Exception{
        SysAuditLog log=new SysAuditLog();log.setTenantId(tenantId);log.setBizType("TENANT_INTEGRATION");log.setBizId(id);log.setAction("UPDATE");
        if(StpUtil.isLogin())log.setOperatorId(StpUtil.getLoginIdAsLong());log.setDetail(objectMapper.writeValueAsString(Map.of("type",type,"enabled",enabled,"publicFields",publicKeys)));
        auditLogService.saveForTenant(log);
    }
}
