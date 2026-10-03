-- R-01: the existing TURN_CATCH_UP_FINISHED writer uses PUBLIC/WORLD with empty refs/facts.
-- Preserve the previous safe public payload allowlist and all separate target/publication checks.
-- C0 allocated V70; production migration requires a separate operating DB approval.
ALTER TABLE game_event DROP CONSTRAINT game_event_public_ck;
ALTER TABLE game_event ADD CONSTRAINT game_event_public_ck CHECK (
        (audience = 'PUBLIC' AND section = 'WORLD' AND facts = '{}'::jsonb AND (
            (kind = 'county.ownerChanged' AND refs ?& ARRAY['CITY', 'FROM_NATION', 'TO_NATION']
                AND refs - 'CITY' - 'FROM_NATION' - 'TO_NATION' = '{}'::jsonb)
            OR (kind = 'roadFort.captured' AND refs ?& ARRAY['ROAD_FORT', 'TO_NATION']
                AND refs - 'ROAD_FORT' - 'FROM_NATION' - 'TO_NATION' = '{}'::jsonb)
            OR (kind = 'yuedan.announced' AND refs = '{}'::jsonb)
            OR (kind = 'server.catchUpFinished' AND refs = '{}'::jsonb)
        ))
        OR (audience <> 'PUBLIC' AND section <> 'WORLD')
    );
