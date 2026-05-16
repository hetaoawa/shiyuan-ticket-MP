# HTTPS 证书目录

将 SSL 证书文件放置在此目录下。

## 使用方式

1. 将 `keystore.p12` 文件放入本目录
2. 编辑 `config/application.yml`，取消 SSL 配置注释并填入密码：
   ```yaml
   server:
     ssl:
       enabled: true
       key-store: config/certs/keystore.p12
       key-store-password: ${SSL_KEY_STORE_PASSWORD}
       key-store-type: PKCS12
   ```
3. 设置环境变量 `SSL_KEY_STORE_PASSWORD` 或直接写入密码
4. 重启服务

## 生成自签名证书（测试用）

```bash
keytool -genkeypair -alias shiyuan-ticket -keyalg RSA -keysize 2048 \
  -storetype PKCS12 -keystore keystore.p12 -validity 3650 \
  -storepass your-password
```
