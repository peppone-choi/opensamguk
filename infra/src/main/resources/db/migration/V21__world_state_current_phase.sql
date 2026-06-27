-- Store the 삼모 ten-day phase explicitly. 1=상순, 2=중순, 3=하순.
ALTER TABLE world_state
    ADD COLUMN IF NOT EXISTS current_phase integer NOT NULL DEFAULT 1;

ALTER TABLE world_state
    ADD CONSTRAINT world_state_current_phase_chk CHECK (current_phase BETWEEN 1 AND 3);
