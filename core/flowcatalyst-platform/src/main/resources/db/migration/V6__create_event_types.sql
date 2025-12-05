-- FlowCatalyst Event Types Schema Migration
-- EventTypes are global (not tenant-scoped)

CREATE TABLE IF NOT EXISTS event_types (
    id BIGINT PRIMARY KEY,

    -- Unique event type code (format: {app}:{subdomain}:{aggregate}:{event})
    code VARCHAR(200) NOT NULL,

    -- Human-friendly name
    name VARCHAR(100) NOT NULL,

    -- Optional description
    description VARCHAR(255),

    -- Schema versions stored as JSONB array
    spec_versions JSONB DEFAULT '[]'::jsonb,

    -- Status: CURRENT or ARCHIVE
    status VARCHAR(20) NOT NULL DEFAULT 'CURRENT',

    -- Timestamps
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),

    -- Unique constraint on code (globally unique, not tenant-scoped)
    CONSTRAINT unique_event_type_code UNIQUE (code),
    CONSTRAINT chk_event_type_status CHECK (status IN ('CURRENT', 'ARCHIVE'))
);

-- Indexes for common queries
CREATE INDEX idx_event_type_code ON event_types(code);
CREATE INDEX idx_event_type_status ON event_types(status);

-- GIN index for spec_versions JSONB queries
CREATE INDEX idx_event_type_spec_versions_gin ON event_types USING gin (spec_versions);

COMMENT ON TABLE event_types IS 'Global event type definitions with versioned schemas';
COMMENT ON COLUMN event_types.code IS 'Format: {app}:{subdomain}:{aggregate}:{event}';
COMMENT ON COLUMN event_types.spec_versions IS 'JSONB array of versioned schema specifications';
