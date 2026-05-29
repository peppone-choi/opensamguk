-- V5 — P3 dynamic-event dispatch INDEX (AREA F2 / Task FE3).
--
-- The `event` table ALREADY exists in V1__baseline.sql:239 (id serial PK, target_code text NOT NULL,
-- priority integer NOT NULL DEFAULT 0, condition jsonb, action jsonb, meta jsonb, created_at). V5
-- does NOT re-create it — it adds ONLY the dispatch-order covering index so the dispatcher's
-- `WHERE target_code = ? ORDER BY priority DESC, id ASC` (TurnExecutionHelper.php:375 runEventHandler)
-- reads the rows pre-sorted. The baseline `priority DEFAULT 0` is moot — the F2-authored seed sets
-- priority explicitly (9000/2000/1000/5000), but the default is retained as documented.
--
-- V1-V4 are frozen; this migration touches nothing else. (V4 is the F4 calendar migration; Flyway
-- applies versions in order and tolerates a gap if F4 has not yet merged onto this branch.)

CREATE INDEX event_target_priority_id_idx ON event (target_code, priority DESC, id ASC);
