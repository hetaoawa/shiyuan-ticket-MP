-- Platform-wide TLS state. These tables deliberately have no tenant_id: TLS terminates
-- before an HTTP request can have tenant context.
CREATE TABLE `platform_ssl_config` (
    `id` TINYINT NOT NULL,
    `desired_enabled` TINYINT NOT NULL DEFAULT 0,
    `effective_enabled` TINYINT NOT NULL DEFAULT 0,
    `current_certificate_version_id` BIGINT NULL,
    `domain_name` VARCHAR(253) NULL,
    `challenge_type` VARCHAR(16) NOT NULL DEFAULT 'DNS-01',
    `legacy_import_completed` TINYINT NOT NULL DEFAULT 0,
    `last_error` VARCHAR(1000) NULL,
    `updated_by` BIGINT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    CONSTRAINT `chk_platform_ssl_singleton` CHECK (`id` = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Platform TLS singleton state';

INSERT INTO `platform_ssl_config` (`id`) VALUES (1);

CREATE TABLE `platform_ssl_certificate_version` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `encrypted_pkcs12` LONGBLOB NOT NULL,
    `encryption_nonce` VARBINARY(12) NOT NULL,
    `fingerprint_sha256` CHAR(64) NOT NULL,
    `subject_dn` VARCHAR(1000) NOT NULL,
    `sans_json` TEXT NOT NULL,
    `not_before` DATETIME NOT NULL,
    `not_after` DATETIME NOT NULL,
    `key_algorithm` VARCHAR(16) NOT NULL,
    `source` VARCHAR(24) NOT NULL,
    `status` VARCHAR(16) NOT NULL DEFAULT 'STAGED',
    `created_by` BIGINT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_platform_ssl_certificate_fingerprint` (`fingerprint_sha256`),
    KEY `idx_platform_ssl_certificate_expiry` (`not_after`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Encrypted platform TLS certificate versions';

ALTER TABLE `platform_ssl_config`
    ADD CONSTRAINT `fk_platform_ssl_current_certificate`
    FOREIGN KEY (`current_certificate_version_id`)
    REFERENCES `platform_ssl_certificate_version` (`id`);

CREATE TABLE `platform_ssl_deploy_token` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `name` VARCHAR(128) NOT NULL,
    `token_hash` BINARY(32) NOT NULL,
    `expires_at` DATETIME NOT NULL,
    `last_used_at` DATETIME NULL,
    `revoked_at` DATETIME NULL,
    `created_by` BIGINT NOT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_platform_ssl_deploy_token_hash` (`token_hash`),
    KEY `idx_platform_ssl_deploy_token_expiry` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Short-lived hashed ACME deploy tokens';

CREATE TABLE `platform_ssl_operation` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `operation_type` VARCHAR(32) NOT NULL,
    `status` VARCHAR(16) NOT NULL,
    `from_https` TINYINT NOT NULL,
    `to_https` TINYINT NOT NULL,
    `certificate_version_id` BIGINT NULL,
    `message` VARCHAR(1000) NULL,
    `operator_id` BIGINT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `completed_at` DATETIME NULL,
    PRIMARY KEY (`id`),
    KEY `idx_platform_ssl_operation_created` (`created_at`),
    CONSTRAINT `fk_platform_ssl_operation_certificate`
      FOREIGN KEY (`certificate_version_id`)
      REFERENCES `platform_ssl_certificate_version` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Platform TLS transition audit';

INSERT IGNORE INTO `sys_permission`
    (`id`, `permission_code`, `permission_name`)
VALUES
    (26001, 'platform:ssl:manage', 'Manage platform TLS');

INSERT IGNORE INTO `sys_role_permission` (`role_id`, `permission_id`)
SELECT r.`id`, p.`id`
FROM `sys_role` r
JOIN `sys_permission` p ON p.`permission_code` = 'platform:ssl:manage'
WHERE r.`tenant_id` = 0
  AND r.`role_code` = 'GLOBAL_SYSTEM_ADMIN'
  AND r.`deleted` = 0;
