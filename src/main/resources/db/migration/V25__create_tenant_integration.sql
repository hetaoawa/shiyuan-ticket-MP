CREATE TABLE IF NOT EXISTS `sys_tenant_integration` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `tenant_id` BIGINT NOT NULL,
    `integration_type` VARCHAR(32) NOT NULL,
    `enabled` TINYINT(1) NOT NULL DEFAULT 0,
    `public_config_json` JSON NULL,
    `secret_config_json` JSON NULL COMMENT 'AES-GCM encrypted values; one nonce per field',
    `config_version` BIGINT NOT NULL DEFAULT 1,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_integration_type` (`tenant_id`, `integration_type`),
    KEY `idx_tenant_integration_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Tenant integration configuration';

CREATE TABLE IF NOT EXISTS `sys_tenant_integration_migration` (
    `migration_key` VARCHAR(64) NOT NULL,
    `completed_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `detail` VARCHAR(255) NULL,
    PRIMARY KEY (`migration_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='One-shot integration migration markers';

-- Move V19 switches into their owning integration. Existing credentials are imported by
-- the controlled legacy importer; these rows intentionally contain no secrets.
INSERT INTO `sys_tenant_integration`
    (`tenant_id`, `integration_type`, `enabled`, `public_config_json`, `config_version`, `deleted`)
SELECT t.`tenant_id`, 'DINGTALK',
       MAX(CASE WHEN t.`setting_key` = 'dingTalkPushEnabled' THEN t.`setting_value` ELSE 0 END),
       JSON_OBJECT(), 1, 0
FROM `sys_tenant_setting` t WHERE t.`deleted` = 0 GROUP BY t.`tenant_id`
ON DUPLICATE KEY UPDATE `enabled` = VALUES(`enabled`);

INSERT INTO `sys_tenant_integration`
    (`tenant_id`, `integration_type`, `enabled`, `public_config_json`, `config_version`, `deleted`)
SELECT t.`tenant_id`, 'CARGO_OWNER',
       MAX(CASE WHEN t.`setting_key` IN ('externalInboundEnabled','externalCloseCallbackEnabled')
                THEN t.`setting_value` ELSE 0 END),
       JSON_OBJECT(
         'externalInboundEnabled', MAX(CASE WHEN t.`setting_key`='externalInboundEnabled' THEN t.`setting_value` ELSE 0 END),
         'externalCloseCallbackEnabled', MAX(CASE WHEN t.`setting_key`='externalCloseCallbackEnabled' THEN t.`setting_value` ELSE 0 END)
       ), 1, 0
FROM `sys_tenant_setting` t WHERE t.`deleted` = 0 GROUP BY t.`tenant_id`
ON DUPLICATE KEY UPDATE `enabled` = VALUES(`enabled`), `public_config_json` = VALUES(`public_config_json`);
