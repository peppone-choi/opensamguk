-- V2-FORWARD-ONLY: rollback is a new compensating V900+ migration.
CREATE TABLE v2_sandbox_probe (
    world_id integer NOT NULL REFERENCES world_state(id),
    PRIMARY KEY (world_id)
);
