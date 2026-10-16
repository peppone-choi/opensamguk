-- Deployment state and its first ACK commit in the same battle session transaction.
ALTER TABLE battle_command_receipt ADD COLUMN deployment_revision_after bigint
    CHECK (deployment_revision_after >= 0);

-- Keep the v1 accepted-event requirement. JOINING placement has a durable revision, not a combat event.
ALTER TABLE battle_command_receipt DROP CONSTRAINT battle_command_receipt_verdict_ck;
ALTER TABLE battle_command_receipt ADD CONSTRAINT battle_command_receipt_verdict_ck CHECK (
    (verdict = 'ACCEPTED' AND reason_code IS NULL AND (
        (deployment_revision_after IS NULL AND effective_tick IS NOT NULL AND event_seq IS NOT NULL)
        OR (deployment_revision_after IS NOT NULL AND server_tick = 0
            AND effective_tick IS NULL AND event_seq IS NULL)))
    OR (verdict = 'REJECTED' AND reason_code IS NOT NULL AND effective_tick IS NULL
        AND event_seq IS NULL AND deployment_revision_after IS NULL)
);

CREATE TABLE battle_v2_deployment (
    world_id integer NOT NULL,
    battle_id varchar(128) NOT NULL,
    initial_sha256 char(64) NOT NULL,
    deployment_bytes bytea NOT NULL,
    PRIMARY KEY (world_id, battle_id),
    FOREIGN KEY (world_id, battle_id) REFERENCES battle_session(world_id, battle_id)
);
