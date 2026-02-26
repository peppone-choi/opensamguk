CREATE TABLE IF NOT EXISTS system_setting (
    key         VARCHAR(255) NOT NULL PRIMARY KEY,
    value       JSONB        NOT NULL DEFAULT '{}'::jsonb,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
