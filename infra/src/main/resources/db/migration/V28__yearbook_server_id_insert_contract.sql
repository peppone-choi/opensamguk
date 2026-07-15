ALTER TABLE yearbook_history ADD COLUMN IF NOT EXISTS server_id text;

DO $$
DECLARE
    active_server_id text;
BEGIN
    SELECT NULLIF(meta ->> 'serverId', '')
      INTO active_server_id
      FROM world_state
     WHERE meta ? 'serverId'
     ORDER BY id ASC
     LIMIT 1;

    IF active_server_id IS NOT NULL THEN
        IF NOT EXISTS (SELECT 1 FROM ng_games WHERE server_id = active_server_id) THEN
            RAISE EXCEPTION 'yearbook_history server_id backfill refused: world_state.meta serverId % is absent from ng_games',
                active_server_id;
        END IF;
    ELSE
        SELECT server_id
          INTO active_server_id
          FROM ng_games
         ORDER BY id ASC
         LIMIT 1;

        IF (SELECT count(*) FROM ng_games) <> 1 THEN
            IF EXISTS (SELECT 1 FROM yearbook_history WHERE server_id IS NULL) THEN
                RAISE EXCEPTION 'yearbook_history server_id backfill refused: no authoritative active server_id and ng_games row count is %',
                    (SELECT count(*) FROM ng_games);
            END IF;
        END IF;
    END IF;

    UPDATE yearbook_history
       SET server_id = active_server_id
     WHERE server_id IS NULL
       AND active_server_id IS NOT NULL;
END $$;

ALTER TABLE yearbook_history ALTER COLUMN server_id SET NOT NULL;

ALTER TABLE yearbook_history
    DROP CONSTRAINT IF EXISTS yearbook_history_profile_name_year_month_key;

CREATE INDEX IF NOT EXISTS yearbook_history_server_idx
    ON yearbook_history (server_id, year, month, id);
