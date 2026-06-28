ALTER TABLE log_entry
    ADD COLUMN IF NOT EXISTS phase integer NOT NULL DEFAULT 1;

ALTER TABLE log_entry
    ADD CONSTRAINT log_entry_phase_chk CHECK (phase BETWEEN 1 AND 3);
