-- Campaign-owned spatial state. Empty by design: topology data does not imply campaign state.
CREATE TABLE province_control (
    world_id INTEGER NOT NULL REFERENCES world_state(id) ON DELETE CASCADE,
    province_id TEXT NOT NULL CHECK (length(btrim(province_id)) > 0),
    topology_revision TEXT NOT NULL CHECK (length(btrim(topology_revision)) > 0),
    topology_hash TEXT NOT NULL CHECK (topology_hash ~ '^[0-9a-f]{64}$'),
    nation_id INTEGER NOT NULL CHECK (nation_id >= 0),
    revision BIGINT NOT NULL CHECK (revision > 0),
    PRIMARY KEY (world_id, province_id)
);

CREATE TABLE general_spatial_position (
    world_id INTEGER NOT NULL REFERENCES world_state(id) ON DELETE CASCADE,
    general_id INTEGER NOT NULL,
    topology_revision TEXT NOT NULL CHECK (length(btrim(topology_revision)) > 0),
    topology_hash TEXT NOT NULL CHECK (topology_hash ~ '^[0-9a-f]{64}$'),
    node_kind TEXT NOT NULL CHECK (node_kind IN ('LAND_PROVINCE', 'WATER_ZONE')),
    node_id TEXT NOT NULL CHECK (length(btrim(node_id)) > 0),
    revision BIGINT NOT NULL CHECK (revision > 0),
    PRIMARY KEY (world_id, general_id),
    CONSTRAINT general_spatial_position_world_general_fkey
        FOREIGN KEY (world_id, general_id) REFERENCES general(world_id, id) ON DELETE CASCADE
);
