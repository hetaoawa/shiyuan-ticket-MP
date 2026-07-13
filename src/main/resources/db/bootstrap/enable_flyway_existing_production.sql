-- Enable Flyway for an EXISTING production database whose legacy migrations V1 through V16
-- (including V10.1) have already been applied manually.
--
-- IMPORTANT:
--   1. Back up the database before running this script.
--   2. Verify every legacy change through V16 is already present.
--   3. Run this script once, using the same schema configured for the application.
--   4. Do not run this script for a new/empty database; Flyway should create its own history there.
--
-- Client and privilege requirements:
--   * Use a MySQL client that supports DELIMITER (for example mysql CLI or MySQL Workbench).
--   * The executing account needs CREATE ROUTINE, ALTER ROUTINE, EXECUTE, and permission to
--     CREATE, INSERT into, and DROP tables in the application schema.
--
-- If CALL fails, some clients stop before the final DROP PROCEDURE. The exception handler removes
-- only a history table created by this invocation. Safely remove a leftover helper with:
--   DROP PROCEDURE IF EXISTS shiyuan_baseline_flyway_at_v16;
-- Then correct the reported cause and run the complete script again. Never drop a pre-existing
-- flyway_schema_history table: this procedure explicitly rejects one and leaves it untouched.
--
-- This file intentionally lives outside db/migration so application startup never scans it.

DELIMITER $$

DROP PROCEDURE IF EXISTS `shiyuan_baseline_flyway_at_v16`$$

CREATE PROCEDURE `shiyuan_baseline_flyway_at_v16`()
BEGIN
    DECLARE history_table_count INT DEFAULT 0;
    DECLARE history_created_by_this_run BOOLEAN DEFAULT FALSE;

    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        IF history_created_by_this_run THEN
            DROP TABLE IF EXISTS `flyway_schema_history`;
        END IF;
        RESIGNAL;
    END;

    SELECT COUNT(*)
      INTO history_table_count
      FROM information_schema.tables
     WHERE table_schema = DATABASE()
       AND table_name = 'flyway_schema_history';

    IF history_table_count > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Refusing to baseline: flyway_schema_history already exists';
    END IF;

    CREATE TABLE `flyway_schema_history` (
        `installed_rank` INT NOT NULL,
        `version` VARCHAR(50) NULL,
        `description` VARCHAR(200) NOT NULL,
        `type` VARCHAR(20) NOT NULL,
        `script` VARCHAR(1000) NOT NULL,
        `checksum` INT NULL,
        `installed_by` VARCHAR(100) NOT NULL,
        `installed_on` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        `execution_time` INT NOT NULL,
        `success` TINYINT(1) NOT NULL,
        CONSTRAINT `flyway_schema_history_pk` PRIMARY KEY (`installed_rank`),
        KEY `flyway_schema_history_s_idx` (`success`)
    ) ENGINE=InnoDB;

    SET history_created_by_this_run = TRUE;

    INSERT INTO `flyway_schema_history` (
        `installed_rank`,
        `version`,
        `description`,
        `type`,
        `script`,
        `checksum`,
        `installed_by`,
        `execution_time`,
        `success`
    ) VALUES (
        1,
        '16',
        'Existing production baseline',
        'BASELINE',
        '<< Flyway Baseline >>',
        NULL,
        CURRENT_USER(),
        0,
        1
    );
END$$

CALL `shiyuan_baseline_flyway_at_v16`()$$
DROP PROCEDURE `shiyuan_baseline_flyway_at_v16`$$

DELIMITER ;
