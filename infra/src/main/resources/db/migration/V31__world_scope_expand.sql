LOCK TABLE
    world_state,
    nation, city, general, troop, general_turn, nation_turn,
    diplomacy, diplomacy_letter, rank_data, hall, ng_games,
    ng_old_nations, ng_old_generals, yearbook_history, event,
    log_entry, board_post, board_comment, vote_poll, vote,
    vote_comment, nation_env, message, ng_betting, ng_auction,
    ng_auction_bid, statistic, select_pool, general_access_log, emperior
IN SHARE ROW EXCLUSIVE MODE;

DO $$
DECLARE
    world_count integer;
    canonical_world_id integer;
    legacy_table text;
    has_legacy_data boolean;
    legacy_tables text[] := ARRAY[
        'nation', 'city', 'general', 'troop', 'general_turn', 'nation_turn',
        'diplomacy', 'diplomacy_letter', 'rank_data', 'hall', 'ng_games',
        'ng_old_nations', 'ng_old_generals', 'yearbook_history', 'event',
        'log_entry', 'board_post', 'board_comment', 'vote_poll', 'vote',
        'vote_comment', 'nation_env', 'message', 'ng_betting', 'ng_auction',
        'ng_auction_bid', 'statistic', 'select_pool', 'general_access_log', 'emperior'
    ];
BEGIN
    SELECT count(*) INTO world_count FROM world_state;

    IF world_count = 0 THEN
        FOREACH legacy_table IN ARRAY legacy_tables LOOP
            EXECUTE format('SELECT EXISTS (SELECT 1 FROM %I)', legacy_table) INTO has_legacy_data;
            IF has_legacy_data THEN
                RAISE EXCEPTION 'V31 requires exactly one positive world_state row before legacy world-owned data exists in %', legacy_table;
            END IF;
        END LOOP;
    ELSIF world_count <> 1 THEN
        RAISE EXCEPTION 'V31 requires exactly one positive world_state row; found %', world_count;
    ELSE
        SELECT id INTO canonical_world_id FROM world_state;
        IF canonical_world_id <= 0 THEN
            RAISE EXCEPTION 'V31 requires a positive world_state.id; found %', canonical_world_id;
        END IF;
    END IF;
END $$;

ALTER TABLE nation ADD COLUMN world_id integer;
ALTER TABLE city ADD COLUMN world_id integer;
ALTER TABLE general ADD COLUMN world_id integer;
ALTER TABLE general_turn ADD COLUMN world_id integer;
ALTER TABLE nation_turn ADD COLUMN world_id integer;

UPDATE nation SET world_id = (SELECT id FROM world_state);
UPDATE city SET world_id = (SELECT id FROM world_state);
UPDATE general SET world_id = (SELECT id FROM world_state);
UPDATE general_turn SET world_id = (SELECT id FROM world_state);
UPDATE nation_turn SET world_id = (SELECT id FROM world_state);

DO $$
DECLARE
    cohort_table text;
    has_null_world_id boolean;
BEGIN
    FOREACH cohort_table IN ARRAY ARRAY['nation', 'city', 'general', 'general_turn', 'nation_turn'] LOOP
        EXECUTE format('SELECT EXISTS (SELECT 1 FROM %I WHERE world_id IS NULL)', cohort_table) INTO has_null_world_id;
        IF has_null_world_id THEN
            RAISE EXCEPTION 'V31 cannot resolve world_id for existing % rows', cohort_table;
        END IF;
    END LOOP;
END $$;

ALTER TABLE nation ALTER COLUMN world_id SET NOT NULL;
ALTER TABLE city ALTER COLUMN world_id SET NOT NULL;
ALTER TABLE general ALTER COLUMN world_id SET NOT NULL;
ALTER TABLE general_turn ALTER COLUMN world_id SET NOT NULL;
ALTER TABLE nation_turn ALTER COLUMN world_id SET NOT NULL;

ALTER TABLE nation
    ADD CONSTRAINT nation_world_id_fkey FOREIGN KEY (world_id) REFERENCES world_state(id),
    ADD CONSTRAINT nation_world_id_id_key UNIQUE (world_id, id);

ALTER TABLE city
    ADD CONSTRAINT city_world_id_fkey FOREIGN KEY (world_id) REFERENCES world_state(id),
    ADD CONSTRAINT city_world_id_id_key UNIQUE (world_id, id);

ALTER TABLE general
    ADD CONSTRAINT general_world_id_fkey FOREIGN KEY (world_id) REFERENCES world_state(id),
    ADD CONSTRAINT general_world_id_id_key UNIQUE (world_id, id);

ALTER TABLE general_turn
    DROP CONSTRAINT general_turn_general_id_turn_idx_key,
    ADD CONSTRAINT general_turn_world_id_fkey FOREIGN KEY (world_id) REFERENCES world_state(id),
    ADD CONSTRAINT general_turn_world_id_general_id_turn_idx_key UNIQUE (world_id, general_id, turn_idx);

ALTER TABLE nation_turn
    DROP CONSTRAINT nation_turn_nation_id_officer_level_turn_idx_key,
    ADD CONSTRAINT nation_turn_world_id_fkey FOREIGN KEY (world_id) REFERENCES world_state(id),
    ADD CONSTRAINT nation_turn_world_id_nation_id_officer_level_turn_idx_key UNIQUE (world_id, nation_id, officer_level, turn_idx);
