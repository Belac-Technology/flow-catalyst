-- Create postbox_messages table if it doesn't exist
CREATE TABLE IF NOT EXISTS postbox_messages (
    id TEXT PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    partition_id VARCHAR(100) NOT NULL,
    type VARCHAR(20) NOT NULL,
    payload TEXT NOT NULL,
    payload_size BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    processed_at TIMESTAMP,
    retry_count INTEGER DEFAULT 0,
    error_reason TEXT,
    headers JSONB,
    CHECK (status IN ('PENDING', 'PROCESSED', 'FAILED')),
    CHECK (type IN ('EVENT', 'DISPATCH_JOB'))
);

-- Create indexes
CREATE INDEX IF NOT EXISTS idx_postbox_tenant_partition_status_created
    ON postbox_messages(tenant_id, partition_id, status, created_at);

CREATE INDEX IF NOT EXISTS idx_postbox_status_created
    ON postbox_messages(status, created_at);

CREATE INDEX IF NOT EXISTS idx_postbox_created
    ON postbox_messages(created_at);

CREATE INDEX IF NOT EXISTS idx_postbox_tenant_partition_payload_size
    ON postbox_messages(tenant_id, partition_id, payload_size);
