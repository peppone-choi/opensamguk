-- OPENSAM-131: order-preserving world_version CAS + writer_epoch fence on world_state.
ALTER TABLE world_state
    ADD COLUMN IF NOT EXISTS world_version bigint NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS writer_epoch bigint NOT NULL DEFAULT 0;

COMMENT ON COLUMN world_state.world_version IS 'Monotonic per-world flush version; CAS target (OPENSAM-131)';
COMMENT ON COLUMN world_state.writer_epoch IS 'Active writer fence epoch; stale writers fail CAS (OPENSAM-131)';
