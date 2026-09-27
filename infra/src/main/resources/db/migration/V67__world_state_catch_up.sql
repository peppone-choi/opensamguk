-- Operational wall-clock pacing; gameplay meta/config and the golden world hash remain unchanged.
-- Existing worlds keep NULL, which the loader interprets as no active catch-up plan.
ALTER TABLE world_state ADD COLUMN catch_up jsonb;
