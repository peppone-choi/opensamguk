-- Server status enum: CLOSED / PRE_OPEN / OPEN
ALTER TABLE world_state
    ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'OPEN';
