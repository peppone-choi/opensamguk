-- Operator visibility is independent of reset validation. Closing visibility cannot
-- publish an unverified reset, and completing validation cannot reopen a private server.
ALTER TABLE game_server_publication
    ADD COLUMN publicly_visible BOOLEAN NOT NULL DEFAULT TRUE;
