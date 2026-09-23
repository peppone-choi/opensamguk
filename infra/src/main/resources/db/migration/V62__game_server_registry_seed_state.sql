-- A deleted final server must not be restored from an old environment snapshot
-- when gateway-api restarts. Existing account or registry data denotes a migrated
-- installation, while a fresh empty database may consume its seed once.
CREATE TABLE game_server_registry_seed_state (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    initialized BOOLEAN NOT NULL
);

INSERT INTO game_server_registry_seed_state (id, initialized)
VALUES (
    1,
    EXISTS (SELECT 1 FROM game_server)
    OR EXISTS (SELECT 1 FROM users)
    OR EXISTS (SELECT 1 FROM game_server_registry_transition)
);
