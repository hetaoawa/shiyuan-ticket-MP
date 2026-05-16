# 部署说明

## 环境要求

- JDK 17+
- MySQL 8.0+
- Redis 6.0+

## 打包

```bash
# 使用 release profile 打包（不包含配置文件）
mvn clean package -P release -DskipTests
```

打包产物：`target/shiyuan-ticket-MP-1.0.0.jar`

## 目录结构

部署目录结构如下：

```
deploy/
├── shiyuan-ticket-MP-1.0.0.jar
├── config/
│   ├── application.yml          # 外部配置模板（需复制并修改）
│   └── certs/
│       └── keystore.p12         # HTTPS 证书（可选）
└── .env.local                   # 环境变量文件（可选）
```

## 运行

```bash
# 基本运行
java -jar shiyuan-ticket-MP-1.0.0.jar --spring.config.additional-location=config/

# 指定环境变量文件
java -jar shiyuan-ticket-MP-1.0.0.jar \
  --spring.config.additional-location=config/ \
  --spring.config.import=optional:file:.env.local

# 后台运行
nohup java -jar shiyuan-ticket-MP-1.0.0.jar \
  --spring.config.additional-location=config/ \
  > app.log 2>&1 &
```

## 配置说明

### 必需配置项

| 配置项 | 说明 |
|--------|------|
| `DB_HOST/DB_PORT/DB_NAME/DB_USERNAME/DB_PASSWORD` | MySQL 连接信息 |
| `REDIS_HOST/REDIS_PORT/REDIS_PASSWORD` | Redis 连接信息 |
| `internal.signature.value` | 前端请求签名密钥（前端需携带相同值） |

### 可选配置项

| 配置项 | 说明 |
|--------|------|
| `SSL_KEY_STORE_PASSWORD` | HTTPS 证书密码（启用 SSL 时必需） |
| `DINGTALK_ACCESS_TOKEN/DINGTALK_SECRET` | 钉钉 Webhook 配置 |
| `S3_ENDPOINT/S3_ACCESS_KEY/S3_SECRET_KEY/S3_BUCKET` | S3 存储配置 |
| `AI_PARSE_API_KEY` | AI 解析 API 密钥 |

## 前端请求签名

所有 `/api/**` 请求（除 `/api/webhook/**`）需携带以下 HTTP 头：

- `X-Internal-Signature`：固定签名值，与配置 `internal.signature.value` 一致
- `X-Internal-Timestamp`：ISO8601 格式时间戳，如 `2026-05-17T10:30:00Z`

时间戳与服务器时间差超过 5 分钟（可配置）将被拒绝。
