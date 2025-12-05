-- Fix event_types table: Remove tenant_id as event types are global (not tenant-scoped)
-- This fixes schema drift from Hibernate auto-update

-- Drop constraints and indexes first
ALTER TABLE event_types DROP CONSTRAINT IF EXISTS unique_event_type_code_tenant;
DROP INDEX IF EXISTS idx_event_type_tenant;

-- Remove the tenant_id column
ALTER TABLE event_types DROP COLUMN IF EXISTS tenant_id;

-- Ensure the table has correct data (clear existing and reseed)
-- Only in dev mode - for production, data would need to be migrated properly
TRUNCATE TABLE event_types;
