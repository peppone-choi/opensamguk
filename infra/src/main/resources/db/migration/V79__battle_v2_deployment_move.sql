-- Deployment state and its first ACK commit in the same battle session transaction.
ALTER TABLE battle_command_receipt ADD COLUMN deployment_revision_after bigint
    CHECK (deployment_revision_after >= 0);

CREATE TABLE battle_v2_deployment (
    world_id integer NOT NULL,
    battle_id varchar(128) NOT NULL,
    initial_sha256 char(64) NOT NULL,
    deployment_bytes bytea NOT NULL,
    PRIMARY KEY (world_id, battle_id),
    FOREIGN KEY (world_id, battle_id) REFERENCES battle_session(world_id, battle_id)
);
