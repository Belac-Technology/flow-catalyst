-- FlowCatalyst Dispatch Jobs Schema Migration
-- Creates dispatch jobs and credentials tables

-- Dispatch Credentials table
CREATE TABLE dispatch_credentials (
    id BIGINT PRIMARY KEY,
    bearer_token VARCHAR(500),
    signing_secret VARCHAR(500),
    algorithm VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

-- Dispatch Jobs table with JSONB columns for metadata, headers, and attempts
CREATE TABLE dispatch_jobs (
    id BIGINT PRIMARY KEY,
    external_id VARCHAR(100),

    -- Source & Classification
    source VARCHAR(100) NOT NULL,
    type VARCHAR(100),
    group_id VARCHAR(100),

    -- Metadata stored as JSONB
    metadata JSONB,

    -- Target Information
    target_url VARCHAR(2048) NOT NULL,
    protocol VARCHAR(50) NOT NULL,
    headers JSONB,

    -- Payload
    payload TEXT,
    payload_content_type VARCHAR(100),

    -- Credentials Reference
    credentials_id BIGINT,

    -- Execution Control
    status VARCHAR(50) NOT NULL,
    max_retries INTEGER,
    retry_strategy VARCHAR(50),
    scheduled_for TIMESTAMP,
    expires_at TIMESTAMP,

    -- Tracking & Observability
    attempt_count INTEGER,
    last_attempt_at TIMESTAMP,
    completed_at TIMESTAMP,
    duration_millis BIGINT,
    last_error TEXT,

    -- Idempotency
    idempotency_key VARCHAR(255),

    -- Attempts stored as JSONB array
    attempts JSONB,

    -- Timestamps
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,

    CONSTRAINT fk_dispatch_job_credentials FOREIGN KEY (credentials_id) REFERENCES dispatch_credentials(id)
);

CREATE INDEX idx_dispatch_job_status ON dispatch_jobs(status);
CREATE INDEX idx_dispatch_job_source ON dispatch_jobs(source);
CREATE INDEX idx_dispatch_job_external_id ON dispatch_jobs(external_id);
CREATE UNIQUE INDEX idx_dispatch_job_idempotency_key ON dispatch_jobs(idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX idx_dispatch_job_scheduled ON dispatch_jobs(scheduled_for);
CREATE INDEX idx_dispatch_job_credentials ON dispatch_jobs(credentials_id);

-- GIN index for JSONB metadata searches
CREATE INDEX idx_dispatch_job_metadata_gin ON dispatch_jobs USING gin (metadata);
