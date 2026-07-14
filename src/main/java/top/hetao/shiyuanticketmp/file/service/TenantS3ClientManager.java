package top.hetao.shiyuanticketmp.file.service;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import top.hetao.shiyuanticketmp.tenant.integration.IntegrationType;
import top.hetao.shiyuanticketmp.tenant.integration.TenantIntegrationResolver;

import java.net.URI;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class TenantS3ClientManager {
    private final TenantIntegrationResolver resolver;
    private final ConcurrentHashMap<Long, Resources> resources = new ConcurrentHashMap<>();
    public TenantS3ClientManager(TenantIntegrationResolver resolver){this.resolver=resolver;}

    public Resources get(long tenantId){
        var config=resolver.resolve(tenantId, IntegrationType.S3);
        if(!config.enabled())throw new IllegalStateException("S3 integration is disabled");
        Resources current=resources.get(tenantId);
        if(current!=null&&current.version()==config.configVersion())return current;
        return resources.compute(tenantId,(id,old)->{
            if(old!=null&&old.version()==config.configVersion())return old;
            Resources next=create(config); if(old!=null)old.close(); return next;
        });
    }
    private Resources create(top.hetao.shiyuanticketmp.tenant.integration.ResolvedIntegration c){
        String endpoint=required(c.text("endpoint"),"endpoint"),bucket=required(c.text("bucket"),"bucket");
        String access=required(c.secret("accessKey"),"accessKey"),secret=required(c.secret("secretKey"),"secretKey");
        String region=c.text("region");if(region==null||region.isBlank())region="us-east-1";
        boolean pathStyle=c.bool("pathStyle",true);var credentials=StaticCredentialsProvider.create(AwsBasicCredentials.create(access,secret));
        S3Client client=S3Client.builder().endpointOverride(URI.create(endpoint)).credentialsProvider(credentials)
                .region(Region.of(region)).forcePathStyle(pathStyle).build();
        S3Presigner presigner=S3Presigner.builder().endpointOverride(URI.create(endpoint)).credentialsProvider(credentials)
                .region(Region.of(region)).serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(pathStyle).build()).build();
        return new Resources(c.configVersion(),client,presigner,bucket,c.integer("presignExpireSeconds",3600));
    }
    private String required(String value,String name){if(value==null||value.isBlank())throw new IllegalStateException("S3 "+name+" is required");return value;}
    @PreDestroy public void close(){resources.values().forEach(Resources::close);resources.clear();}
    public record Resources(long version,S3Client client,S3Presigner presigner,String bucket,int presignExpireSeconds) implements AutoCloseable{
        @Override public void close(){try{presigner.close();}finally{client.close();}}
    }
}
