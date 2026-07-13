-- Mark orders created by the signed cargo-owner webhook and support the bounded
-- cross-tenant scan used by the automatic assignment scheduler.
ALTER TABLE `work_order`
    ADD COLUMN `created_via_webhook` TINYINT(1) NOT NULL DEFAULT 0
        COMMENT '1 when created by the external cargo-owner webhook' AFTER `sender_staff_id`;

CREATE INDEX `idx_work_order_external_auto_assign`
    ON `work_order` (`created_via_webhook`, `status`, `assignee_id`, `assignee_role`,
                     `assigned_at`, `deleted`, `created_at`, `id`);
