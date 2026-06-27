-- P0-A baseline: game-profile schema (mirrors core2026 game.prisma)
-- Enums --------------------------------------------------------------------
CREATE TYPE log_scope AS ENUM ('SYSTEM', 'NATION', 'GENERAL', 'USER');
CREATE TYPE log_category AS ENUM ('HISTORY', 'SUMMARY', 'ACTION', 'BATTLE_BRIEF', 'BATTLE_DETAIL', 'USER');
CREATE TYPE auction_status AS ENUM ('OPEN', 'FINALIZING', 'FINISHED', 'CANCELED');
CREATE TYPE auction_type AS ENUM ('BUY_RICE', 'SELL_RICE', 'UNIQUE_ITEM');
CREATE TYPE diplomacy_letter_state AS ENUM ('PROPOSED', 'ACTIVATED', 'CANCELLED', 'REPLACED');

-- World / core entities ----------------------------------------------------
CREATE TABLE world_state (
    id            serial PRIMARY KEY,
    scenario_code text NOT NULL,
    current_year  integer NOT NULL,
    current_month integer NOT NULL,
    current_phase integer NOT NULL DEFAULT 1,
    tick_seconds  integer NOT NULL,
    config        jsonb NOT NULL DEFAULT '{}'::jsonb,
    meta          jsonb NOT NULL DEFAULT '{}'::jsonb,
    updated_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE nation (
    id              integer PRIMARY KEY,
    name            text NOT NULL,
    color           text NOT NULL,
    capital_city_id integer,
    gold            integer NOT NULL DEFAULT 0,
    rice            integer NOT NULL DEFAULT 0,
    tech            double precision NOT NULL DEFAULT 0,
    level           integer NOT NULL DEFAULT 0,
    type_code       text NOT NULL DEFAULT 'che_중립',
    meta            jsonb NOT NULL DEFAULT '{}'::jsonb
);

CREATE TABLE city (
    id              integer PRIMARY KEY,
    name            text NOT NULL,
    level           integer NOT NULL,
    nation_id       integer NOT NULL DEFAULT 0,
    supply_state    integer NOT NULL DEFAULT 1,
    front_state     integer NOT NULL DEFAULT 0,
    pop             integer NOT NULL,
    pop_max         integer NOT NULL,
    agri            integer NOT NULL,
    agri_max        integer NOT NULL,
    comm            integer NOT NULL,
    comm_max        integer NOT NULL,
    secu            integer NOT NULL,
    secu_max        integer NOT NULL,
    trust           integer NOT NULL DEFAULT 0,
    trade           integer NOT NULL DEFAULT 100,
    def             integer NOT NULL,
    def_max         integer NOT NULL,
    wall            integer NOT NULL,
    wall_max        integer NOT NULL,
    region          integer NOT NULL,
    conflict        jsonb NOT NULL DEFAULT '{}'::jsonb,
    meta            jsonb NOT NULL DEFAULT '{}'::jsonb
);

CREATE TABLE general (
    id             integer PRIMARY KEY,
    user_id        text,
    name           text NOT NULL,
    nation_id      integer NOT NULL DEFAULT 0,
    city_id        integer NOT NULL DEFAULT 0,
    troop_id       integer NOT NULL DEFAULT 0,
    npc_state      integer NOT NULL DEFAULT 0,
    affinity       integer,
    born_year      integer NOT NULL DEFAULT 180,
    dead_year      integer NOT NULL DEFAULT 300,
    picture        text,
    image_server   integer NOT NULL DEFAULT 0,
    leadership     integer NOT NULL DEFAULT 50,
    strength       integer NOT NULL DEFAULT 50,
    intel          integer NOT NULL DEFAULT 50,
    injury         integer NOT NULL DEFAULT 0,
    experience     integer NOT NULL DEFAULT 0,
    dedication     integer NOT NULL DEFAULT 0,
    officer_level  integer NOT NULL DEFAULT 0,
    gold           integer NOT NULL DEFAULT 1000,
    rice           integer NOT NULL DEFAULT 1000,
    crew           integer NOT NULL DEFAULT 0,
    crew_type_id   integer NOT NULL DEFAULT 0,
    train          integer NOT NULL DEFAULT 0,
    atmos          integer NOT NULL DEFAULT 0,
    weapon_code    text NOT NULL DEFAULT 'None',
    book_code      text NOT NULL DEFAULT 'None',
    horse_code     text NOT NULL DEFAULT 'None',
    item_code      text NOT NULL DEFAULT 'None',
    turn_time      timestamptz NOT NULL,
    recent_war_time timestamptz,
    age            integer NOT NULL DEFAULT 20,
    start_age      integer NOT NULL DEFAULT 20,
    personal_code  text NOT NULL DEFAULT 'None',
    special_code   text NOT NULL DEFAULT 'None',
    special2_code  text NOT NULL DEFAULT 'None',
    last_turn      jsonb NOT NULL DEFAULT '{}'::jsonb,
    meta           jsonb NOT NULL DEFAULT '{}'::jsonb,
    penalty        jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE troop (
    troop_leader integer PRIMARY KEY,
    nation       integer NOT NULL,
    name         text NOT NULL
);

CREATE TABLE general_turn (
    id          serial PRIMARY KEY,
    general_id  integer NOT NULL,
    turn_idx    integer NOT NULL,
    action_code text NOT NULL,
    arg         jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (general_id, turn_idx)
);

CREATE TABLE nation_turn (
    id            serial PRIMARY KEY,
    nation_id     integer NOT NULL,
    officer_level integer NOT NULL,
    turn_idx      integer NOT NULL,
    action_code   text NOT NULL,
    arg           jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at    timestamptz NOT NULL DEFAULT now(),
    UNIQUE (nation_id, officer_level, turn_idx)
);

CREATE TABLE diplomacy (
    id              serial PRIMARY KEY,
    src_nation_id   integer NOT NULL,
    dest_nation_id  integer NOT NULL,
    state_code      integer NOT NULL,
    term            integer NOT NULL DEFAULT 0,
    is_dead         boolean NOT NULL DEFAULT false,
    is_showing      boolean NOT NULL DEFAULT true,
    meta            jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (src_nation_id, dest_nation_id)
);

CREATE TABLE diplomacy_letter (
    id             serial PRIMARY KEY,
    src_nation_id  integer NOT NULL,
    dest_nation_id integer NOT NULL,
    prev_id        integer,
    state          diplomacy_letter_state NOT NULL DEFAULT 'PROPOSED',
    text_brief     text NOT NULL,
    text_detail    text NOT NULL,
    date           timestamptz NOT NULL DEFAULT now(),
    src_signer     integer NOT NULL,
    dest_signer    integer,
    aux            jsonb NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX diplomacy_letter_src_dest_idx ON diplomacy_letter (src_nation_id, dest_nation_id);
CREATE INDEX diplomacy_letter_dest_src_idx ON diplomacy_letter (dest_nation_id, src_nation_id);
CREATE INDEX diplomacy_letter_state_date_idx ON diplomacy_letter (state, date);

-- Ranking / history --------------------------------------------------------
CREATE TABLE rank_data (
    id          serial PRIMARY KEY,
    nation_id   integer NOT NULL DEFAULT 0,
    general_id  integer NOT NULL,
    type        varchar(20) NOT NULL,
    value       integer NOT NULL DEFAULT 0,
    UNIQUE (general_id, type)
);
CREATE INDEX rank_data_by_type ON rank_data (type, value);
CREATE INDEX rank_data_by_nation ON rank_data (nation_id, type, value);

CREATE TABLE hall (
    id         serial PRIMARY KEY,
    server_id  text NOT NULL,
    season     integer NOT NULL,
    scenario   integer NOT NULL,
    general_no integer NOT NULL,
    type       varchar(20) NOT NULL,
    value      double precision NOT NULL,
    owner      text,
    aux        jsonb NOT NULL DEFAULT '{}'::jsonb,
    UNIQUE (server_id, type, general_no),
    UNIQUE (owner, server_id, type)
);
CREATE INDEX hall_server_show ON hall (server_id, type, value);
CREATE INDEX hall_scenario ON hall (season, scenario, type, value);

CREATE TABLE ng_games (
    id            serial PRIMARY KEY,
    server_id     text NOT NULL,
    date          timestamptz NOT NULL,
    winner_nation integer,
    map           text,
    season        integer NOT NULL,
    scenario      integer NOT NULL,
    scenario_name text NOT NULL,
    env           jsonb NOT NULL DEFAULT '{}'::jsonb,
    UNIQUE (server_id)
);
CREATE INDEX ng_games_date_idx ON ng_games (date);

CREATE TABLE ng_old_nations (
    id        serial PRIMARY KEY,
    server_id text NOT NULL,
    nation    integer NOT NULL DEFAULT 0,
    data      jsonb NOT NULL DEFAULT '{}'::jsonb,
    date      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (server_id, nation)
);

CREATE TABLE ng_old_generals (
    id              serial PRIMARY KEY,
    server_id       text NOT NULL,
    general_no      integer NOT NULL,
    owner           text,
    name            text NOT NULL,
    last_yearmonth  integer NOT NULL,
    turntime        timestamptz NOT NULL,
    data            jsonb NOT NULL DEFAULT '{}'::jsonb,
    UNIQUE (server_id, general_no)
);
CREATE INDEX ng_old_generals_by_name ON ng_old_generals (server_id, name);
CREATE INDEX ng_old_generals_owner ON ng_old_generals (owner, server_id);

-- Yearbook / events / logs -------------------------------------------------
CREATE TABLE yearbook_history (
    id           serial PRIMARY KEY,
    profile_name text NOT NULL,
    year         integer NOT NULL,
    month        integer NOT NULL,
    map          jsonb NOT NULL,
    nations      jsonb NOT NULL,
    hash         text NOT NULL DEFAULT '',
    created_at   timestamptz NOT NULL DEFAULT now(),
    UNIQUE (profile_name, year, month)
);

CREATE TABLE event (
    id          serial PRIMARY KEY,
    target_code text NOT NULL,
    priority    integer NOT NULL DEFAULT 0,
    condition   jsonb NOT NULL DEFAULT '{}'::jsonb,
    action      jsonb NOT NULL DEFAULT '{}'::jsonb,
    meta        jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE log_entry (
    id         serial PRIMARY KEY,
    scope      log_scope NOT NULL,
    category   log_category NOT NULL,
    sub_type   text,
    year       integer NOT NULL,
    month      integer NOT NULL,
    text       text NOT NULL,
    general_id integer,
    nation_id  integer,
    user_id    integer,
    meta       jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX log_entry_scope_idx ON log_entry (scope, category, id);
CREATE INDEX log_entry_general_idx ON log_entry (general_id, category, id);
CREATE INDEX log_entry_nation_idx ON log_entry (nation_id, category, id);
CREATE INDEX log_entry_user_idx ON log_entry (user_id, category, id);

CREATE TABLE error_log (
    id         serial PRIMARY KEY,
    category   text NOT NULL,
    source     text,
    message    text NOT NULL,
    trace      text,
    context    jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX error_log_category_idx ON error_log (category, id);

-- Inheritance (cross-season; excluded from per-season truncate in P0-B) -----
CREATE TABLE inheritance_point (
    id         serial PRIMARY KEY,
    user_id    text NOT NULL,
    key        text NOT NULL,
    value      double precision NOT NULL DEFAULT 0,
    aux        jsonb NOT NULL DEFAULT '{}'::jsonb,
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (user_id, key)
);
CREATE INDEX inheritance_point_user_idx ON inheritance_point (user_id);

CREATE TABLE inheritance_log (
    id         serial PRIMARY KEY,
    user_id    text NOT NULL,
    year       integer NOT NULL,
    month      integer NOT NULL,
    text       text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX inheritance_log_user_idx ON inheritance_log (user_id, id);

CREATE TABLE inheritance_result (
    id         serial PRIMARY KEY,
    server_id  text NOT NULL,
    owner      text NOT NULL,
    general_id integer NOT NULL,
    year       integer NOT NULL,
    month      integer NOT NULL,
    value      jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX inheritance_result_server_owner_idx ON inheritance_result (server_id, owner);

CREATE TABLE inheritance_user_state (
    user_id    text PRIMARY KEY,
    meta       jsonb NOT NULL DEFAULT '{}'::jsonb,
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- Auction / betting --------------------------------------------------------
CREATE TABLE auction (
    id              serial PRIMARY KEY,
    type            auction_type NOT NULL,
    target_code     text,
    host_general_id integer NOT NULL,
    host_name       text,
    detail          jsonb NOT NULL DEFAULT '{}'::jsonb,
    status          auction_status NOT NULL DEFAULT 'OPEN',
    close_at        timestamptz NOT NULL,
    latest_event_id text NOT NULL DEFAULT '',
    latest_event_at timestamptz NOT NULL DEFAULT now(),
    finalizing_at   timestamptz,
    finished_at     timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX auction_status_close_idx ON auction (status, close_at);

CREATE TABLE auction_bid (
    id         serial PRIMARY KEY,
    auction_id integer NOT NULL REFERENCES auction(id) ON DELETE CASCADE,
    general_id integer NOT NULL,
    amount     integer NOT NULL,
    event_id   text NOT NULL,
    event_at   timestamptz NOT NULL,
    meta       jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX auction_bid_amount_idx ON auction_bid (auction_id, amount);
CREATE INDEX auction_bid_event_idx ON auction_bid (auction_id, event_at);

-- Board / vote -------------------------------------------------------------
CREATE TABLE board_post (
    id                serial PRIMARY KEY,
    nation_id         integer NOT NULL,
    is_secret         boolean NOT NULL DEFAULT false,
    author_general_id integer NOT NULL,
    author_name       text NOT NULL,
    title             text NOT NULL,
    content_html      text NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX board_post_nation_idx ON board_post (nation_id, is_secret, created_at);

CREATE TABLE board_comment (
    id                serial PRIMARY KEY,
    post_id           integer NOT NULL REFERENCES board_post(id) ON DELETE CASCADE,
    nation_id         integer NOT NULL,
    is_secret         boolean NOT NULL DEFAULT false,
    author_general_id integer NOT NULL,
    author_name       text NOT NULL,
    content_text      text NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX board_comment_post_idx ON board_comment (post_id, created_at);

CREATE TABLE vote_poll (
    id                serial PRIMARY KEY,
    title             text NOT NULL,
    body              text NOT NULL DEFAULT '',
    options           jsonb NOT NULL,
    multiple_options  integer NOT NULL DEFAULT 1,
    reveal_mode       text NOT NULL,
    opener_general_id integer NOT NULL,
    opener_name       text NOT NULL,
    start_at          timestamptz NOT NULL DEFAULT now(),
    end_at            timestamptz,
    closed_at         timestamptz,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE vote (
    id         serial PRIMARY KEY,
    vote_id    integer NOT NULL REFERENCES vote_poll(id) ON DELETE CASCADE,
    general_id integer NOT NULL,
    nation_id  integer NOT NULL,
    selection  jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (vote_id, general_id)
);
CREATE INDEX vote_poll_idx ON vote (vote_id);

CREATE TABLE vote_comment (
    id           serial PRIMARY KEY,
    vote_id      integer NOT NULL REFERENCES vote_poll(id) ON DELETE CASCADE,
    general_id   integer NOT NULL,
    nation_id    integer NOT NULL,
    general_name text NOT NULL,
    nation_name  text NOT NULL,
    text         text NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX vote_comment_idx ON vote_comment (vote_id, created_at);
