-- Baseline campaign state, not v2_lab-only: ordinary Han V3 worlds also read water-aware supply.
-- Empty by design. Never infer/backfill water ownership from scenario nations or coastal cities.
-- UPGRADE GATE: a sandbox with V901 already applied must have its lower-version upgrade reviewed
-- separately. Do not enable global outOfOrder, repair Flyway history, or reset that database.
CREATE TABLE water_zone_control (
    world_id INTEGER NOT NULL REFERENCES world_state(id) ON DELETE CASCADE,
    water_zone_id TEXT NOT NULL CHECK (length(btrim(water_zone_id)) > 0),
    topology_revision TEXT NOT NULL CHECK (length(btrim(topology_revision)) > 0),
    topology_hash TEXT NOT NULL CHECK (topology_hash ~ '^[0-9a-f]{64}$'),
    controlling_nation_id BIGINT CHECK (controlling_nation_id > 0),
    contesting_nation_ids JSONB NOT NULL CHECK (jsonb_typeof(contesting_nation_ids) = 'array'),
    blockade_state TEXT NOT NULL CHECK (blockade_state IN ('OPEN', 'CONTESTED', 'BLOCKED')),
    revision BIGINT NOT NULL CHECK (revision > 0),
    PRIMARY KEY (world_id, water_zone_id)
);
