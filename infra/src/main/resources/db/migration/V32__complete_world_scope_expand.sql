LOCK TABLE
    world_state,
    nation, city, general, general_turn, nation_turn,
    troop, diplomacy, diplomacy_letter, rank_data, hall, ng_games,
    ng_old_nations, ng_old_generals, yearbook_history, event, log_entry,
    board_post, board_comment, vote_poll, vote, vote_comment, nation_env,
    message, ng_betting, ng_auction, ng_auction_bid, statistic, select_pool,
    general_access_log, emperior, general_owner, inheritance_result,
    select_npc_token,
    game_kv
IN SHARE ROW EXCLUSIVE MODE;

DO $$
DECLARE
    world_count integer;
    canonical_world_id integer;
    legacy_table text;
    has_legacy_data boolean;
    strict_world_tables text[] := ARRAY[
        'nation', 'city', 'general', 'general_turn', 'nation_turn',
        'troop', 'diplomacy', 'diplomacy_letter', 'rank_data', 'hall', 'ng_games',
        'ng_old_nations', 'ng_old_generals', 'yearbook_history', 'event', 'log_entry',
        'board_post', 'board_comment', 'vote_poll', 'vote', 'vote_comment', 'nation_env',
        'message', 'ng_betting', 'ng_auction', 'ng_auction_bid', 'statistic', 'select_pool',
        'general_access_log', 'emperior', 'general_owner', 'inheritance_result',
        'select_npc_token'
    ];
BEGIN
    SELECT count(*) INTO world_count FROM world_state;

    IF world_count = 0 THEN
        FOREACH legacy_table IN ARRAY strict_world_tables LOOP
            EXECUTE format('SELECT EXISTS (SELECT 1 FROM %I)', legacy_table) INTO has_legacy_data;
            IF has_legacy_data THEN
                RAISE EXCEPTION
                    'V32 requires exactly one positive world_state row before legacy world-owned data exists in %',
                    legacy_table;
            END IF;
        END LOOP;

        SELECT EXISTS (
            SELECT 1
              FROM game_kv
             WHERE "table" <> 'inheritance'
        ) INTO has_legacy_data;
        IF has_legacy_data THEN
            RAISE EXCEPTION
                'V32 requires exactly one positive world_state row before non-inheritance game_kv data exists';
        END IF;
    ELSIF world_count <> 1 THEN
        RAISE EXCEPTION 'V32 requires exactly one positive world_state row; found %', world_count;
    ELSE
        SELECT id INTO canonical_world_id FROM world_state;
        IF canonical_world_id <= 0 THEN
            RAISE EXCEPTION 'V32 requires a positive world_state.id; found %', canonical_world_id;
        END IF;
    END IF;
END $$;

ALTER TABLE world_state
    ADD CONSTRAINT world_state_id_positive_check CHECK (id > 0);

ALTER TABLE troop ADD COLUMN world_id integer;
ALTER TABLE diplomacy ADD COLUMN world_id integer;
ALTER TABLE diplomacy_letter ADD COLUMN world_id integer;
ALTER TABLE rank_data ADD COLUMN world_id integer;
ALTER TABLE hall ADD COLUMN world_id integer;
ALTER TABLE ng_games ADD COLUMN world_id integer;
ALTER TABLE ng_old_nations ADD COLUMN world_id integer;
ALTER TABLE ng_old_generals ADD COLUMN world_id integer;
ALTER TABLE yearbook_history ADD COLUMN world_id integer;
ALTER TABLE event ADD COLUMN world_id integer;
ALTER TABLE log_entry ADD COLUMN world_id integer;
ALTER TABLE board_post ADD COLUMN world_id integer;
ALTER TABLE board_comment ADD COLUMN world_id integer;
ALTER TABLE vote_poll ADD COLUMN world_id integer;
ALTER TABLE vote ADD COLUMN world_id integer;
ALTER TABLE vote_comment ADD COLUMN world_id integer;
ALTER TABLE nation_env ADD COLUMN world_id integer;
ALTER TABLE message ADD COLUMN world_id integer;
ALTER TABLE ng_betting ADD COLUMN world_id integer;
ALTER TABLE ng_auction ADD COLUMN world_id integer;
ALTER TABLE ng_auction_bid ADD COLUMN world_id integer;
ALTER TABLE statistic ADD COLUMN world_id integer;
ALTER TABLE select_pool ADD COLUMN world_id integer;
ALTER TABLE general_access_log ADD COLUMN world_id integer;
ALTER TABLE emperior ADD COLUMN world_id integer;
ALTER TABLE general_owner ADD COLUMN world_id integer;
ALTER TABLE inheritance_result ADD COLUMN world_id integer;
ALTER TABLE select_npc_token ADD COLUMN world_id integer;
ALTER TABLE game_kv ADD COLUMN world_id integer;

UPDATE troop SET world_id = (SELECT id FROM world_state);
UPDATE diplomacy SET world_id = (SELECT id FROM world_state);
UPDATE diplomacy_letter SET world_id = (SELECT id FROM world_state);
UPDATE rank_data SET world_id = (SELECT id FROM world_state);
UPDATE hall SET world_id = (SELECT id FROM world_state);
UPDATE ng_games SET world_id = (SELECT id FROM world_state);
UPDATE ng_old_nations SET world_id = (SELECT id FROM world_state);
UPDATE ng_old_generals SET world_id = (SELECT id FROM world_state);
UPDATE yearbook_history SET world_id = (SELECT id FROM world_state);
UPDATE event SET world_id = (SELECT id FROM world_state);
UPDATE log_entry SET world_id = (SELECT id FROM world_state);
UPDATE board_post SET world_id = (SELECT id FROM world_state);
UPDATE board_comment SET world_id = (SELECT id FROM world_state);
UPDATE vote_poll SET world_id = (SELECT id FROM world_state);
UPDATE vote SET world_id = (SELECT id FROM world_state);
UPDATE vote_comment SET world_id = (SELECT id FROM world_state);
UPDATE nation_env SET world_id = (SELECT id FROM world_state);
UPDATE message SET world_id = (SELECT id FROM world_state);
UPDATE ng_betting SET world_id = (SELECT id FROM world_state);
UPDATE ng_auction SET world_id = (SELECT id FROM world_state);
UPDATE ng_auction_bid SET world_id = (SELECT id FROM world_state);
UPDATE statistic SET world_id = (SELECT id FROM world_state);
UPDATE select_pool SET world_id = (SELECT id FROM world_state);
UPDATE general_access_log SET world_id = (SELECT id FROM world_state);
UPDATE emperior SET world_id = (SELECT id FROM world_state);
UPDATE general_owner SET world_id = (SELECT id FROM world_state);
UPDATE inheritance_result SET world_id = (SELECT id FROM world_state);
UPDATE select_npc_token SET world_id = (SELECT id FROM world_state);
UPDATE game_kv
   SET world_id = (SELECT id FROM world_state)
 WHERE "table" <> 'inheritance';

DO $$
DECLARE
    strict_world_table text;
    has_null_world_id boolean;
BEGIN
    FOREACH strict_world_table IN ARRAY ARRAY[
        'troop', 'diplomacy', 'diplomacy_letter', 'rank_data', 'hall', 'ng_games',
        'ng_old_nations', 'ng_old_generals', 'yearbook_history', 'event', 'log_entry',
        'board_post', 'board_comment', 'vote_poll', 'vote', 'vote_comment', 'nation_env',
        'message', 'ng_betting', 'ng_auction', 'ng_auction_bid', 'statistic', 'select_pool',
        'general_access_log', 'emperior', 'general_owner', 'inheritance_result',
        'select_npc_token'
    ] LOOP
        EXECUTE format(
            'SELECT EXISTS (SELECT 1 FROM %I WHERE world_id IS NULL)',
            strict_world_table
        ) INTO has_null_world_id;
        IF has_null_world_id THEN
            RAISE EXCEPTION 'V32 cannot resolve world_id for existing % rows', strict_world_table;
        END IF;
    END LOOP;
END $$;

ALTER TABLE troop ALTER COLUMN world_id SET NOT NULL;
ALTER TABLE diplomacy ALTER COLUMN world_id SET NOT NULL;
ALTER TABLE diplomacy_letter ALTER COLUMN world_id SET NOT NULL;
ALTER TABLE rank_data ALTER COLUMN world_id SET NOT NULL;
ALTER TABLE hall ALTER COLUMN world_id SET NOT NULL;
ALTER TABLE ng_games ALTER COLUMN world_id SET NOT NULL;
ALTER TABLE ng_old_nations ALTER COLUMN world_id SET NOT NULL;
ALTER TABLE ng_old_generals ALTER COLUMN world_id SET NOT NULL;
ALTER TABLE yearbook_history ALTER COLUMN world_id SET NOT NULL;
ALTER TABLE event ALTER COLUMN world_id SET NOT NULL;
ALTER TABLE log_entry ALTER COLUMN world_id SET NOT NULL;
ALTER TABLE board_post ALTER COLUMN world_id SET NOT NULL;
ALTER TABLE board_comment ALTER COLUMN world_id SET NOT NULL;
ALTER TABLE vote_poll ALTER COLUMN world_id SET NOT NULL;
ALTER TABLE vote ALTER COLUMN world_id SET NOT NULL;
ALTER TABLE vote_comment ALTER COLUMN world_id SET NOT NULL;
ALTER TABLE nation_env ALTER COLUMN world_id SET NOT NULL;
ALTER TABLE message ALTER COLUMN world_id SET NOT NULL;
ALTER TABLE ng_betting ALTER COLUMN world_id SET NOT NULL;
ALTER TABLE ng_auction ALTER COLUMN world_id SET NOT NULL;
ALTER TABLE ng_auction_bid ALTER COLUMN world_id SET NOT NULL;
ALTER TABLE statistic ALTER COLUMN world_id SET NOT NULL;
ALTER TABLE select_pool ALTER COLUMN world_id SET NOT NULL;
ALTER TABLE general_access_log ALTER COLUMN world_id SET NOT NULL;
ALTER TABLE emperior ALTER COLUMN world_id SET NOT NULL;
ALTER TABLE general_owner ALTER COLUMN world_id SET NOT NULL;
ALTER TABLE inheritance_result ALTER COLUMN world_id SET NOT NULL;
ALTER TABLE select_npc_token ALTER COLUMN world_id SET NOT NULL;

ALTER TABLE board_comment DROP CONSTRAINT board_comment_post_id_fkey;
ALTER TABLE vote DROP CONSTRAINT vote_vote_id_fkey;
ALTER TABLE vote_comment DROP CONSTRAINT vote_comment_vote_id_fkey;

ALTER TABLE nation
    DROP CONSTRAINT nation_pkey,
    DROP CONSTRAINT nation_world_id_id_key,
    ADD CONSTRAINT nation_pkey PRIMARY KEY (world_id, id);
ALTER TABLE city
    DROP CONSTRAINT city_pkey,
    DROP CONSTRAINT city_world_id_id_key,
    ADD CONSTRAINT city_pkey PRIMARY KEY (world_id, id);
ALTER TABLE general
    DROP CONSTRAINT general_pkey,
    DROP CONSTRAINT general_world_id_id_key,
    ADD CONSTRAINT general_pkey PRIMARY KEY (world_id, id);
ALTER TABLE general_turn
    DROP CONSTRAINT general_turn_pkey,
    ADD CONSTRAINT general_turn_pkey PRIMARY KEY (world_id, id);
ALTER TABLE nation_turn
    DROP CONSTRAINT nation_turn_pkey,
    ADD CONSTRAINT nation_turn_pkey PRIMARY KEY (world_id, id);

ALTER TABLE troop
    DROP CONSTRAINT troop_pkey,
    ADD CONSTRAINT troop_pkey PRIMARY KEY (world_id, troop_leader);
ALTER TABLE diplomacy
    DROP CONSTRAINT diplomacy_pkey,
    ADD CONSTRAINT diplomacy_pkey PRIMARY KEY (world_id, id);
ALTER TABLE diplomacy_letter
    DROP CONSTRAINT diplomacy_letter_pkey,
    ADD CONSTRAINT diplomacy_letter_pkey PRIMARY KEY (world_id, id);
ALTER TABLE rank_data
    DROP CONSTRAINT rank_data_pkey,
    ADD CONSTRAINT rank_data_pkey PRIMARY KEY (world_id, id);
ALTER TABLE hall
    DROP CONSTRAINT hall_pkey,
    ADD CONSTRAINT hall_pkey PRIMARY KEY (world_id, id);
ALTER TABLE ng_games
    DROP CONSTRAINT ng_games_pkey,
    ADD CONSTRAINT ng_games_pkey PRIMARY KEY (world_id, id);
ALTER TABLE ng_old_nations
    DROP CONSTRAINT ng_old_nations_pkey,
    ADD CONSTRAINT ng_old_nations_pkey PRIMARY KEY (world_id, id);
ALTER TABLE ng_old_generals
    DROP CONSTRAINT ng_old_generals_pkey,
    ADD CONSTRAINT ng_old_generals_pkey PRIMARY KEY (world_id, id);
ALTER TABLE yearbook_history
    DROP CONSTRAINT yearbook_history_pkey,
    ADD CONSTRAINT yearbook_history_pkey PRIMARY KEY (world_id, id);
ALTER TABLE event
    DROP CONSTRAINT event_pkey,
    ADD CONSTRAINT event_pkey PRIMARY KEY (world_id, id);
ALTER TABLE log_entry
    DROP CONSTRAINT log_entry_pkey,
    ADD CONSTRAINT log_entry_pkey PRIMARY KEY (world_id, id);
ALTER TABLE board_post
    DROP CONSTRAINT board_post_pkey,
    ADD CONSTRAINT board_post_pkey PRIMARY KEY (world_id, id);
ALTER TABLE board_comment
    DROP CONSTRAINT board_comment_pkey,
    ADD CONSTRAINT board_comment_pkey PRIMARY KEY (world_id, id);
ALTER TABLE vote_poll
    DROP CONSTRAINT vote_poll_pkey,
    ADD CONSTRAINT vote_poll_pkey PRIMARY KEY (world_id, id);
ALTER TABLE vote
    DROP CONSTRAINT vote_pkey,
    ADD CONSTRAINT vote_pkey PRIMARY KEY (world_id, id);
ALTER TABLE vote_comment
    DROP CONSTRAINT vote_comment_pkey,
    ADD CONSTRAINT vote_comment_pkey PRIMARY KEY (world_id, id);
ALTER TABLE nation_env
    DROP CONSTRAINT nation_env_pkey,
    ADD CONSTRAINT nation_env_pkey PRIMARY KEY (world_id, id);
ALTER TABLE message
    DROP CONSTRAINT message_pkey,
    ADD CONSTRAINT message_pkey PRIMARY KEY (world_id, id);
ALTER TABLE ng_betting
    DROP CONSTRAINT ng_betting_pkey,
    ADD CONSTRAINT ng_betting_pkey PRIMARY KEY (world_id, id);
ALTER TABLE ng_auction
    DROP CONSTRAINT ng_auction_pkey,
    ADD CONSTRAINT ng_auction_pkey PRIMARY KEY (world_id, id);
ALTER TABLE ng_auction_bid
    DROP CONSTRAINT ng_auction_bid_pkey,
    ADD CONSTRAINT ng_auction_bid_pkey PRIMARY KEY (world_id, no);
ALTER TABLE statistic
    DROP CONSTRAINT statistic_pkey,
    ADD CONSTRAINT statistic_pkey PRIMARY KEY (world_id, id);
ALTER TABLE select_pool
    DROP CONSTRAINT select_pool_pkey,
    ADD CONSTRAINT select_pool_pkey PRIMARY KEY (world_id, id);
ALTER TABLE general_access_log
    DROP CONSTRAINT general_access_log_pkey,
    ADD CONSTRAINT general_access_log_pkey PRIMARY KEY (world_id, id);
ALTER TABLE emperior
    DROP CONSTRAINT emperior_pkey,
    ADD CONSTRAINT emperior_pkey PRIMARY KEY (world_id, id);
ALTER TABLE general_owner
    DROP CONSTRAINT pk_general_owner,
    ADD CONSTRAINT pk_general_owner PRIMARY KEY (world_id, general_id);
ALTER TABLE inheritance_result
    DROP CONSTRAINT inheritance_result_pkey,
    ADD CONSTRAINT inheritance_result_pkey PRIMARY KEY (world_id, id);
ALTER TABLE select_npc_token
    DROP CONSTRAINT select_npc_token_pkey,
    ADD CONSTRAINT select_npc_token_pkey PRIMARY KEY (world_id, id);

ALTER TABLE troop ADD CONSTRAINT troop_world_id_fkey FOREIGN KEY (world_id) REFERENCES world_state(id);
ALTER TABLE diplomacy ADD CONSTRAINT diplomacy_world_id_fkey FOREIGN KEY (world_id) REFERENCES world_state(id);
ALTER TABLE diplomacy_letter ADD CONSTRAINT diplomacy_letter_world_id_fkey FOREIGN KEY (world_id) REFERENCES world_state(id);
ALTER TABLE rank_data ADD CONSTRAINT rank_data_world_id_fkey FOREIGN KEY (world_id) REFERENCES world_state(id);
ALTER TABLE hall ADD CONSTRAINT hall_world_id_fkey FOREIGN KEY (world_id) REFERENCES world_state(id);
ALTER TABLE ng_games ADD CONSTRAINT ng_games_world_id_fkey FOREIGN KEY (world_id) REFERENCES world_state(id);
ALTER TABLE ng_old_nations ADD CONSTRAINT ng_old_nations_world_id_fkey FOREIGN KEY (world_id) REFERENCES world_state(id);
ALTER TABLE ng_old_generals ADD CONSTRAINT ng_old_generals_world_id_fkey FOREIGN KEY (world_id) REFERENCES world_state(id);
ALTER TABLE yearbook_history ADD CONSTRAINT yearbook_history_world_id_fkey FOREIGN KEY (world_id) REFERENCES world_state(id);
ALTER TABLE event ADD CONSTRAINT event_world_id_fkey FOREIGN KEY (world_id) REFERENCES world_state(id);
ALTER TABLE log_entry ADD CONSTRAINT log_entry_world_id_fkey FOREIGN KEY (world_id) REFERENCES world_state(id);
ALTER TABLE board_post ADD CONSTRAINT board_post_world_id_fkey FOREIGN KEY (world_id) REFERENCES world_state(id);
ALTER TABLE board_comment ADD CONSTRAINT board_comment_world_id_fkey FOREIGN KEY (world_id) REFERENCES world_state(id);
ALTER TABLE vote_poll ADD CONSTRAINT vote_poll_world_id_fkey FOREIGN KEY (world_id) REFERENCES world_state(id);
ALTER TABLE vote ADD CONSTRAINT vote_world_id_fkey FOREIGN KEY (world_id) REFERENCES world_state(id);
ALTER TABLE vote_comment ADD CONSTRAINT vote_comment_world_id_fkey FOREIGN KEY (world_id) REFERENCES world_state(id);
ALTER TABLE nation_env ADD CONSTRAINT nation_env_world_id_fkey FOREIGN KEY (world_id) REFERENCES world_state(id);
ALTER TABLE message ADD CONSTRAINT message_world_id_fkey FOREIGN KEY (world_id) REFERENCES world_state(id);
ALTER TABLE ng_betting ADD CONSTRAINT ng_betting_world_id_fkey FOREIGN KEY (world_id) REFERENCES world_state(id);
ALTER TABLE ng_auction ADD CONSTRAINT ng_auction_world_id_fkey FOREIGN KEY (world_id) REFERENCES world_state(id);
ALTER TABLE ng_auction_bid ADD CONSTRAINT ng_auction_bid_world_id_fkey FOREIGN KEY (world_id) REFERENCES world_state(id);
ALTER TABLE statistic ADD CONSTRAINT statistic_world_id_fkey FOREIGN KEY (world_id) REFERENCES world_state(id);
ALTER TABLE select_pool ADD CONSTRAINT select_pool_world_id_fkey FOREIGN KEY (world_id) REFERENCES world_state(id);
ALTER TABLE general_access_log ADD CONSTRAINT general_access_log_world_id_fkey FOREIGN KEY (world_id) REFERENCES world_state(id);
ALTER TABLE emperior ADD CONSTRAINT emperior_world_id_fkey FOREIGN KEY (world_id) REFERENCES world_state(id);
ALTER TABLE general_owner ADD CONSTRAINT general_owner_world_id_fkey FOREIGN KEY (world_id) REFERENCES world_state(id);
ALTER TABLE inheritance_result ADD CONSTRAINT inheritance_result_world_id_fkey FOREIGN KEY (world_id) REFERENCES world_state(id);
ALTER TABLE select_npc_token ADD CONSTRAINT select_npc_token_world_id_fkey FOREIGN KEY (world_id) REFERENCES world_state(id);
ALTER TABLE game_kv ADD CONSTRAINT game_kv_world_id_fkey FOREIGN KEY (world_id) REFERENCES world_state(id);

ALTER TABLE general_turn
    ADD CONSTRAINT general_turn_world_general_fkey
    FOREIGN KEY (world_id, general_id) REFERENCES general(world_id, id)
    DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE nation_turn
    ADD CONSTRAINT nation_turn_world_nation_fkey
    FOREIGN KEY (world_id, nation_id) REFERENCES nation(world_id, id)
    DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE troop
    ADD CONSTRAINT troop_world_general_fkey
        FOREIGN KEY (world_id, troop_leader) REFERENCES general(world_id, id)
        DEFERRABLE INITIALLY DEFERRED,
    ADD CONSTRAINT troop_world_nation_fkey
        FOREIGN KEY (world_id, nation) REFERENCES nation(world_id, id)
        DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE diplomacy
    ADD CONSTRAINT diplomacy_world_src_nation_fkey
        FOREIGN KEY (world_id, src_nation_id) REFERENCES nation(world_id, id)
        DEFERRABLE INITIALLY DEFERRED,
    ADD CONSTRAINT diplomacy_world_dest_nation_fkey
        FOREIGN KEY (world_id, dest_nation_id) REFERENCES nation(world_id, id)
        DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE diplomacy_letter
    ADD CONSTRAINT diplomacy_letter_world_prev_fkey
    FOREIGN KEY (world_id, prev_id) REFERENCES diplomacy_letter(world_id, id)
    DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE board_comment
    ADD CONSTRAINT board_comment_world_post_fkey
    FOREIGN KEY (world_id, post_id) REFERENCES board_post(world_id, id) ON DELETE CASCADE;
ALTER TABLE vote
    ADD CONSTRAINT vote_world_poll_fkey
    FOREIGN KEY (world_id, vote_id) REFERENCES vote_poll(world_id, id) ON DELETE CASCADE;
ALTER TABLE vote_comment
    ADD CONSTRAINT vote_comment_world_poll_fkey
    FOREIGN KEY (world_id, vote_id) REFERENCES vote_poll(world_id, id) ON DELETE CASCADE;
ALTER TABLE ng_auction_bid
    ADD CONSTRAINT ng_auction_bid_world_auction_fkey
    FOREIGN KEY (world_id, auction_id) REFERENCES ng_auction(world_id, id);

ALTER TABLE diplomacy
    DROP CONSTRAINT diplomacy_src_nation_id_dest_nation_id_key,
    ADD CONSTRAINT diplomacy_world_src_dest_key UNIQUE (world_id, src_nation_id, dest_nation_id);
ALTER TABLE rank_data
    DROP CONSTRAINT rank_data_general_id_type_key,
    ADD CONSTRAINT rank_data_world_general_type_key UNIQUE (world_id, general_id, type);
ALTER TABLE hall
    DROP CONSTRAINT hall_server_id_type_general_no_key,
    DROP CONSTRAINT hall_owner_server_id_type_key,
    ADD CONSTRAINT hall_world_server_type_general_key UNIQUE (world_id, server_id, type, general_no),
    ADD CONSTRAINT hall_world_owner_server_type_key UNIQUE (world_id, owner, server_id, type);
ALTER TABLE ng_games
    DROP CONSTRAINT ng_games_server_id_key,
    ADD CONSTRAINT ng_games_world_server_key UNIQUE (world_id, server_id);
ALTER TABLE ng_old_nations
    DROP CONSTRAINT ng_old_nations_server_id_nation_key,
    ADD CONSTRAINT ng_old_nations_world_server_nation_key UNIQUE (world_id, server_id, nation);
ALTER TABLE ng_old_generals
    DROP CONSTRAINT ng_old_generals_server_id_general_no_key,
    ADD CONSTRAINT ng_old_generals_world_server_general_key UNIQUE (world_id, server_id, general_no);
ALTER TABLE vote
    DROP CONSTRAINT vote_vote_id_general_id_key,
    ADD CONSTRAINT vote_world_poll_general_key UNIQUE (world_id, vote_id, general_id);
ALTER TABLE nation_env
    DROP CONSTRAINT nation_env_namespace_key_key,
    ADD CONSTRAINT nation_env_world_namespace_key UNIQUE (world_id, namespace, key);
ALTER TABLE ng_betting
    DROP CONSTRAINT ng_betting_general_id_betting_id_betting_type_key,
    DROP CONSTRAINT ng_betting_betting_id_betting_type_general_id_key,
    ADD CONSTRAINT ng_betting_world_general_betting_key UNIQUE (world_id, general_id, betting_id, betting_type),
    ADD CONSTRAINT ng_betting_world_betting_general_key UNIQUE (world_id, betting_id, betting_type, general_id);
ALTER TABLE ng_auction_bid
    DROP CONSTRAINT ng_auction_bid_general_id_auction_id_amount_key,
    DROP CONSTRAINT ng_auction_bid_auction_id_amount_key,
    ADD CONSTRAINT ng_auction_bid_world_general_auction_amount_key UNIQUE (world_id, general_id, auction_id, amount),
    ADD CONSTRAINT ng_auction_bid_world_auction_amount_key UNIQUE (world_id, auction_id, amount);
ALTER TABLE select_pool
    DROP CONSTRAINT select_pool_unique_name,
    DROP CONSTRAINT select_pool_general_id_unique,
    ADD CONSTRAINT select_pool_world_unique_name_key UNIQUE (world_id, unique_name),
    ADD CONSTRAINT select_pool_world_general_key UNIQUE (world_id, general_id);
ALTER TABLE general_access_log
    DROP CONSTRAINT general_access_log_general_id_key,
    ADD CONSTRAINT general_access_log_world_general_key UNIQUE (world_id, general_id);
ALTER TABLE general_owner
    DROP CONSTRAINT uq_general_owner_user,
    ADD CONSTRAINT uq_general_owner_user UNIQUE (world_id, user_id);

ALTER TABLE game_kv DROP CONSTRAINT game_kv_table_namespace_key_key;
ALTER TABLE game_kv
    ADD CONSTRAINT game_kv_world_ownership_check CHECK (
        ("table" = 'inheritance' AND world_id IS NULL)
        OR
        ("table" <> 'inheritance' AND world_id IS NOT NULL)
    );
CREATE UNIQUE INDEX game_kv_inheritance_global_key_uq
    ON game_kv ("table", namespace, key)
    WHERE "table" = 'inheritance' AND world_id IS NULL;
CREATE UNIQUE INDEX game_kv_world_key_uq
    ON game_kv (world_id, "table", namespace, key)
    WHERE "table" <> 'inheritance' AND world_id IS NOT NULL;

DROP INDEX diplomacy_letter_src_dest_idx;
DROP INDEX diplomacy_letter_dest_src_idx;
DROP INDEX diplomacy_letter_state_date_idx;
CREATE INDEX diplomacy_letter_src_dest_idx ON diplomacy_letter (world_id, src_nation_id, dest_nation_id);
CREATE INDEX diplomacy_letter_dest_src_idx ON diplomacy_letter (world_id, dest_nation_id, src_nation_id);
CREATE INDEX diplomacy_letter_state_date_idx ON diplomacy_letter (world_id, state, date);

DROP INDEX rank_data_by_type;
DROP INDEX rank_data_by_nation;
CREATE INDEX rank_data_by_type ON rank_data (world_id, type, value);
CREATE INDEX rank_data_by_nation ON rank_data (world_id, nation_id, type, value);

DROP INDEX hall_server_show;
DROP INDEX hall_scenario;
CREATE INDEX hall_server_show ON hall (world_id, server_id, type, value);
CREATE INDEX hall_scenario ON hall (world_id, season, scenario, type, value);

DROP INDEX ng_games_date_idx;
CREATE INDEX ng_games_date_idx ON ng_games (world_id, date);

DROP INDEX ng_old_generals_by_name;
DROP INDEX ng_old_generals_owner;
CREATE INDEX ng_old_generals_by_name ON ng_old_generals (world_id, server_id, name);
CREATE INDEX ng_old_generals_owner ON ng_old_generals (world_id, owner, server_id);

DROP INDEX yearbook_history_server_idx;
CREATE INDEX yearbook_history_server_idx ON yearbook_history (world_id, server_id, year, month, id);

DROP INDEX event_target_priority_id_idx;
CREATE INDEX event_target_priority_id_idx ON event (world_id, target_code, priority DESC, id ASC);

DROP INDEX log_entry_scope_idx;
DROP INDEX log_entry_general_idx;
DROP INDEX log_entry_nation_idx;
DROP INDEX log_entry_user_idx;
DROP INDEX log_entry_year_month_idx;
CREATE INDEX log_entry_scope_idx ON log_entry (world_id, scope, category, id);
CREATE INDEX log_entry_general_idx ON log_entry (world_id, general_id, category, id);
CREATE INDEX log_entry_nation_idx ON log_entry (world_id, nation_id, category, id);
CREATE INDEX log_entry_user_idx ON log_entry (world_id, user_id, category, id);
CREATE INDEX log_entry_year_month_idx ON log_entry (world_id, year, month, id);

DROP INDEX board_post_nation_idx;
DROP INDEX board_comment_post_idx;
CREATE INDEX board_post_nation_idx ON board_post (world_id, nation_id, is_secret, created_at);
CREATE INDEX board_comment_post_idx ON board_comment (world_id, post_id, created_at);

DROP INDEX vote_poll_idx;
DROP INDEX vote_comment_idx;
CREATE INDEX vote_poll_idx ON vote (world_id, vote_id);
CREATE INDEX vote_comment_idx ON vote_comment (world_id, vote_id, created_at);

DROP INDEX nation_env_by_namespace;
CREATE INDEX nation_env_by_namespace ON nation_env (world_id, namespace);

DROP INDEX message_by_mailbox;
CREATE INDEX message_by_mailbox ON message (world_id, mailbox, type, id);

DROP INDEX ng_betting_by_user;
CREATE INDEX ng_betting_by_user ON ng_betting (world_id, user_id, betting_id, betting_type);

DROP INDEX game_kv_by_namespace;

DROP INDEX ng_auction_by_close;
DROP INDEX ng_auction_by_general;
CREATE INDEX ng_auction_by_close ON ng_auction (world_id, finished, type, close_date);
CREATE INDEX ng_auction_by_general ON ng_auction (world_id, host_general_id, type, finished);

CREATE INDEX troop_world_nation_idx ON troop (world_id, nation, troop_leader);
CREATE INDEX diplomacy_world_dest_src_idx ON diplomacy (world_id, dest_nation_id, src_nation_id);
CREATE INDEX statistic_world_year_month_idx ON statistic (world_id, year, month, id);

DROP INDEX select_pool_owner_idx;
DROP INDEX select_pool_reserved_until_general_idx;
CREATE INDEX select_pool_owner_idx ON select_pool (world_id, owner);
CREATE INDEX select_pool_reserved_until_general_idx ON select_pool (world_id, reserved_until, general_id);

DROP INDEX general_access_log_user_idx;
CREATE INDEX general_access_log_user_idx ON general_access_log (world_id, user_id);

DROP INDEX emperior_server_idx;
CREATE INDEX emperior_server_idx ON emperior (world_id, server_id, id);

DROP INDEX idx_general_owner_user;

DROP INDEX inheritance_result_server_owner_idx;
CREATE INDEX inheritance_result_server_owner_idx ON inheritance_result (world_id, server_id, owner);

DROP INDEX idx_select_npc_token_owner_valid;
DROP INDEX idx_select_npc_token_valid_until;
CREATE INDEX idx_select_npc_token_owner_valid ON select_npc_token (world_id, owner_id, valid_until);
CREATE INDEX idx_select_npc_token_valid_until ON select_npc_token (world_id, valid_until);
