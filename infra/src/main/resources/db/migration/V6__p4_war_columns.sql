-- V6 — P4 battle/conquest column surface (AREA F7 / Task FU3).
--
-- The ConquerCity side-effect set (process_war.php:705-808) and the city.conflict ordered-map (A4)
-- write four logical columns. `city.conflict` ALREADY exists in V1__baseline.sql:56 (jsonb DEFAULT
-- '{}'), and `city.front_state` (V1:40) IS the PHP `city.front` (the SetNationFront 0/1/2/3 target),
-- so neither is re-created here. This migration adds ONLY the three genuinely-new scalar columns:
--   - city.term         — owner-tenure turn counter; ConquerCity resets to 0 (process_war.php:779)
--   - city.officer_set  — officer-assignment seq;    ConquerCity resets to 0 (process_war.php:785)
--   - general.officer_city — the city a 태수/군사/종사 governs; ConquerCity demotes the governor to
--                            재야 (officer_city=0, officer_level=1) — process_war.php:705-708.
--
-- V1-V5 are frozen; this migration touches nothing else.

ALTER TABLE city    ADD COLUMN term         integer NOT NULL DEFAULT 0;
ALTER TABLE city    ADD COLUMN officer_set  integer NOT NULL DEFAULT 0;
ALTER TABLE general ADD COLUMN officer_city integer NOT NULL DEFAULT 0;
