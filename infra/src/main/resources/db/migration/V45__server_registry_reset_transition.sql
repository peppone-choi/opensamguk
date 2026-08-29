ALTER TABLE game_server_registry_transition
    DROP CONSTRAINT game_server_registry_transition_action_check;

ALTER TABLE game_server_registry_transition
    ADD CONSTRAINT game_server_registry_transition_action_check
    CHECK (action IN ('CREATE', 'CLOSE', 'RESET'));
