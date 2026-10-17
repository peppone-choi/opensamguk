ALTER TABLE general_bugok
    ADD COLUMN revision bigint NOT NULL DEFAULT 1 CHECK (revision > 0);

CREATE TABLE campaign_battle_origin (
    world_id integer NOT NULL REFERENCES world_state(id) ON DELETE CASCADE,
    battle_id varchar(128) NOT NULL,
    encounter_id varchar(64) NOT NULL CHECK (encounter_id ~ '^[0-9a-f]{64}$'),
    source_bytes bytea NOT NULL,
    source_sha256 varchar(64) NOT NULL CHECK (source_sha256 ~ '^[0-9a-f]{64}$'),
    forces_snapshot_id varchar(64) NOT NULL CHECK (forces_snapshot_id ~ '^[0-9a-f]{64}$'),
    forces_json jsonb NOT NULL,
    committed_world_version bigint NOT NULL CHECK (committed_world_version > 0),
    writer_epoch bigint NOT NULL CHECK (writer_epoch >= 0),
    topology_revision text NOT NULL,
    topology_hash varchar(64) NOT NULL CHECK (topology_hash ~ '^[0-9a-f]{64}$'),
    province_key text NOT NULL,
    approach_key text NOT NULL,
    tiles_hash varchar(64) NOT NULL CHECK (tiles_hash ~ '^[0-9a-f]{64}$'),
    lock_generation bigint NOT NULL CHECK (lock_generation > 0),
    lock_set_revision bigint NOT NULL CHECK (lock_set_revision > 0),
    PRIMARY KEY (world_id, battle_id),
    UNIQUE (world_id, encounter_id)
);

CREATE TABLE campaign_battle_owner_authority (
    world_id integer NOT NULL,
    battle_id varchar(128) NOT NULL,
    owner_general_id integer NOT NULL CHECK (owner_general_id > 0),
    account_id integer CHECK (account_id > 0),
    npc_state integer NOT NULL CHECK (npc_state >= 0),
    playable boolean NOT NULL,
    side varchar(16) NOT NULL CHECK (side IN ('ATTACKER', 'DEFENDER')),
    controlled_source_sha256 varchar(64) NOT NULL CHECK (controlled_source_sha256 ~ '^[0-9a-f]{64}$'),
    revision bigint NOT NULL CHECK (revision > 0),
    status varchar(16) NOT NULL CHECK (status IN ('ACTIVE', 'REVOKED')),
    PRIMARY KEY (world_id, battle_id, owner_general_id),
    FOREIGN KEY (world_id, battle_id) REFERENCES campaign_battle_origin(world_id, battle_id) ON DELETE CASCADE,
    CHECK ((playable AND account_id IS NOT NULL AND npc_state < 2)
        OR (NOT playable AND account_id IS NULL AND npc_state >= 2))
);
