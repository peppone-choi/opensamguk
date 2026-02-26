-- V12: Add traffic tracking columns to general_access_log + traffic_snapshot table

ALTER TABLE general_access_log ADD COLUMN IF NOT EXISTS refresh INT NOT NULL DEFAULT 0;
ALTER TABLE general_access_log ADD COLUMN IF NOT EXISTS refresh_score_total INT NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS traffic_snapshot (
    id          BIGSERIAL PRIMARY KEY,
    world_id    BIGINT NOT NULL,
    year        SMALLINT NOT NULL,
    month       SMALLINT NOT NULL,
    refresh     INT NOT NULL DEFAULT 0,
    online      INT NOT NULL DEFAULT 0,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_traffic_snapshot_world ON traffic_snapshot(world_id, recorded_at DESC);
