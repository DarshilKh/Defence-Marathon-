-- =====================================================
-- MIGRATION SCRIPT v2.0
-- Indian Defence Marathon - GPS-Free Master-Slave Mode
-- =====================================================

-- Run this script on your existing database before deploying new code

-- 1. Add new columns to checkpoint_readings
ALTER TABLE checkpoint_readings 
ADD COLUMN device_id VARCHAR(50) NULL AFTER race_id;

-- 2. Add new reader ports to checkpoints (for 5 reader support)
ALTER TABLE checkpoints 
ADD COLUMN reader_c_com_port VARCHAR(20) NULL AFTER reader_b_com_port;

ALTER TABLE checkpoints 
ADD COLUMN reader_d_com_port VARCHAR(20) NULL AFTER reader_c_com_port;

ALTER TABLE checkpoints 
ADD COLUMN reader_e_com_port VARCHAR(20) NULL AFTER reader_d_com_port;

-- 3. Add reader status columns
ALTER TABLE checkpoints 
ADD COLUMN reader_c_status VARCHAR(20) DEFAULT 'DISCONNECTED' AFTER reader_b_status;

ALTER TABLE checkpoints 
ADD COLUMN reader_d_status VARCHAR(20) DEFAULT 'DISCONNECTED' AFTER reader_c_status;

ALTER TABLE checkpoints 
ADD COLUMN reader_e_status VARCHAR(20) DEFAULT 'DISCONNECTED' AFTER reader_d_status;

-- 4. Add manual time columns to races
ALTER TABLE races 
ADD COLUMN manual_start_time DATETIME NULL AFTER end_time;

ALTER TABLE races 
ADD COLUMN manual_end_time DATETIME NULL AFTER manual_start_time;

ALTER TABLE races 
ADD COLUMN master_device_id VARCHAR(50) NULL AFTER manual_end_time;

ALTER TABLE races 
ADD COLUMN calculation_mode VARCHAR(20) DEFAULT 'AUTO' AFTER master_device_id;

ALTER TABLE races 
ADD COLUMN required_checkpoints INT DEFAULT 2 AFTER calculation_mode;

-- 5. Create system_config table (NEW)
CREATE TABLE IF NOT EXISTS system_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    device_id VARCHAR(50) UNIQUE NOT NULL,
    device_role VARCHAR(20) NOT NULL,
    checkpoint_name VARCHAR(100),
    is_first_run BOOLEAN DEFAULT TRUE,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- 6. Add indexes for better performance
CREATE INDEX idx_checkpoint_readings_device 
ON checkpoint_readings(device_id);

CREATE INDEX idx_checkpoint_readings_uploaded 
ON checkpoint_readings(uploaded_to_server);

-- 7. Verify migration
SELECT 'Migration completed successfully!' AS status;

-- Check new columns
SHOW COLUMNS FROM checkpoint_readings LIKE 'device_id';
SHOW COLUMNS FROM checkpoints LIKE 'reader_c_com_port';
SHOW COLUMNS FROM races LIKE 'manual_start_time';

-- Check new table
SELECT COUNT(*) as system_config_table_exists 
FROM information_schema.tables 
WHERE table_schema = DATABASE() 
AND table_name = 'system_config';

-- =====================================================
-- END OF MIGRATION SCRIPT
-- =====================================================
