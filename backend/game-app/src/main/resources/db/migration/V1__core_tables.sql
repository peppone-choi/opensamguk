-- app_user
CREATE TABLE app_user (
    id BIGSERIAL PRIMARY KEY,
    login_id TEXT NOT NULL UNIQUE,
    display_name TEXT NOT NULL,
    password_hash TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_login_at TIMESTAMPTZ
);

-- world_state
CREATE TABLE world_state (
    id SMALLSERIAL PRIMARY KEY,
    scenario_code TEXT NOT NULL,
    current_year SMALLINT NOT NULL,
    current_month SMALLINT NOT NULL,
    tick_seconds INTEGER NOT NULL,
    realtime_mode BOOLEAN NOT NULL DEFAULT false,
    command_point_regen_rate INTEGER NOT NULL DEFAULT 1,
    config JSONB NOT NULL DEFAULT '{}'::jsonb,
    meta JSONB NOT NULL DEFAULT '{}'::jsonb,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (jsonb_typeof(config) = 'object'),
    CHECK (jsonb_typeof(meta) = 'object')
);

-- nation
CREATE TABLE nation (
    id BIGSERIAL PRIMARY KEY,
    world_id BIGINT NOT NULL REFERENCES world_state(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    color TEXT NOT NULL,
    capital_city_id BIGINT,
    gold INTEGER NOT NULL DEFAULT 0,
    rice INTEGER NOT NULL DEFAULT 0,
    bill SMALLINT NOT NULL DEFAULT 0,
    rate SMALLINT NOT NULL DEFAULT 0,
    rate_tmp SMALLINT NOT NULL DEFAULT 0,
    secret_limit SMALLINT NOT NULL DEFAULT 3,
    chief_general_id BIGINT NOT NULL DEFAULT 0,
    scout_level SMALLINT NOT NULL DEFAULT 0,
    war_state SMALLINT NOT NULL DEFAULT 0,
    strategic_cmd_limit SMALLINT NOT NULL DEFAULT 36,
    surrender_limit SMALLINT NOT NULL DEFAULT 72,
    tech REAL NOT NULL DEFAULT 0,
    power INTEGER NOT NULL DEFAULT 0,
    level SMALLINT NOT NULL DEFAULT 0,
    type_code TEXT NOT NULL DEFAULT 'che_중립',
    spy JSONB NOT NULL DEFAULT '{}'::jsonb,
    meta JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (jsonb_typeof(spy) = 'object'),
    CHECK (jsonb_typeof(meta) = 'object')
);

-- city
CREATE TABLE city (
    id BIGSERIAL PRIMARY KEY,
    world_id BIGINT NOT NULL REFERENCES world_state(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    level SMALLINT NOT NULL,
    nation_id BIGINT NOT NULL DEFAULT 0,
    supply_state SMALLINT NOT NULL DEFAULT 1,
    front_state SMALLINT NOT NULL DEFAULT 0,
    pop INTEGER NOT NULL,
    pop_max INTEGER NOT NULL,
    agri INTEGER NOT NULL,
    agri_max INTEGER NOT NULL,
    comm INTEGER NOT NULL,
    comm_max INTEGER NOT NULL,
    secu INTEGER NOT NULL,
    secu_max INTEGER NOT NULL,
    trust INTEGER NOT NULL DEFAULT 0,
    trade INTEGER NOT NULL DEFAULT 100,
    dead SMALLINT NOT NULL DEFAULT 0,
    def INTEGER NOT NULL DEFAULT 0,
    def_max INTEGER NOT NULL DEFAULT 0,
    wall INTEGER NOT NULL DEFAULT 0,
    wall_max INTEGER NOT NULL DEFAULT 0,
    officer_set INTEGER NOT NULL DEFAULT 0,
    state SMALLINT NOT NULL DEFAULT 0,
    region SMALLINT NOT NULL DEFAULT 0,
    term SMALLINT NOT NULL DEFAULT 0,
    conflict JSONB NOT NULL DEFAULT '{}'::jsonb,
    meta JSONB NOT NULL DEFAULT '{}'::jsonb,
    CHECK (jsonb_typeof(conflict) = 'object'),
    CHECK (jsonb_typeof(meta) = 'object')
);

-- troop
CREATE TABLE troop (
    id BIGSERIAL PRIMARY KEY,
    world_id BIGINT NOT NULL REFERENCES world_state(id) ON DELETE CASCADE,
    leader_general_id BIGINT NOT NULL,
    nation_id BIGINT NOT NULL DEFAULT 0,
    name TEXT NOT NULL,
    meta JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (jsonb_typeof(meta) = 'object')
);

-- general
CREATE TABLE general (
    id BIGSERIAL PRIMARY KEY,
    world_id BIGINT NOT NULL REFERENCES world_state(id) ON DELETE CASCADE,
    user_id BIGINT,
    name TEXT NOT NULL,
    nation_id BIGINT NOT NULL DEFAULT 0,
    city_id BIGINT NOT NULL DEFAULT 0,
    troop_id BIGINT NOT NULL DEFAULT 0,
    npc_state SMALLINT NOT NULL DEFAULT 0,
    affinity SMALLINT NOT NULL DEFAULT 0,
    born_year SMALLINT NOT NULL DEFAULT 180,
    dead_year SMALLINT NOT NULL DEFAULT 300,
    picture TEXT NOT NULL,
    image_server SMALLINT NOT NULL DEFAULT 0,
    leadership SMALLINT NOT NULL DEFAULT 50,
    leadership_exp SMALLINT NOT NULL DEFAULT 0,
    strength SMALLINT NOT NULL DEFAULT 50,
    strength_exp SMALLINT NOT NULL DEFAULT 0,
    intel SMALLINT NOT NULL DEFAULT 50,
    intel_exp SMALLINT NOT NULL DEFAULT 0,
    politics SMALLINT NOT NULL DEFAULT 50,
    charm SMALLINT NOT NULL DEFAULT 50,
    injury SMALLINT NOT NULL DEFAULT 0,
    experience INTEGER NOT NULL DEFAULT 0,
    dedication INTEGER NOT NULL DEFAULT 0,
    officer_level SMALLINT NOT NULL DEFAULT 0,
    officer_city INTEGER NOT NULL DEFAULT 0,
    permission TEXT NOT NULL DEFAULT 'normal',
    gold INTEGER NOT NULL DEFAULT 1000,
    rice INTEGER NOT NULL DEFAULT 1000,
    crew INTEGER NOT NULL DEFAULT 0,
    crew_type SMALLINT NOT NULL DEFAULT 0,
    train SMALLINT NOT NULL DEFAULT 0,
    atmos SMALLINT NOT NULL DEFAULT 0,
    weapon_code TEXT NOT NULL DEFAULT 'None',
    book_code TEXT NOT NULL DEFAULT 'None',
    horse_code TEXT NOT NULL DEFAULT 'None',
    item_code TEXT NOT NULL DEFAULT 'None',
    turn_time TIMESTAMPTZ NOT NULL,
    recent_war_time TIMESTAMPTZ,
    make_limit SMALLINT NOT NULL DEFAULT 0,
    kill_turn SMALLINT,
    block_state SMALLINT NOT NULL DEFAULT 0,
    ded_level SMALLINT NOT NULL DEFAULT 0,
    exp_level SMALLINT NOT NULL DEFAULT 0,
    age SMALLINT NOT NULL DEFAULT 20,
    start_age SMALLINT NOT NULL DEFAULT 20,
    belong SMALLINT NOT NULL DEFAULT 1,
    betray SMALLINT NOT NULL DEFAULT 0,
    personal_code TEXT NOT NULL DEFAULT 'None',
    special_code TEXT NOT NULL DEFAULT 'None',
    spec_age SMALLINT NOT NULL DEFAULT 0,
    special2_code TEXT NOT NULL DEFAULT 'None',
    spec2_age SMALLINT NOT NULL DEFAULT 0,
    defence_train SMALLINT NOT NULL DEFAULT 80,
    tournament_state SMALLINT NOT NULL DEFAULT 0,
    command_points INTEGER NOT NULL DEFAULT 10,
    command_end_time TIMESTAMPTZ,
    last_turn JSONB NOT NULL DEFAULT '{}'::jsonb,
    meta JSONB NOT NULL DEFAULT '{}'::jsonb,
    penalty JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (jsonb_typeof(last_turn) = 'object'),
    CHECK (jsonb_typeof(meta) = 'object'),
    CHECK (jsonb_typeof(penalty) = 'object')
);

-- general_turn
CREATE TABLE general_turn (
    id BIGSERIAL PRIMARY KEY,
    general_id BIGINT NOT NULL REFERENCES general(id) ON DELETE CASCADE,
    turn_idx SMALLINT NOT NULL,
    action_code TEXT NOT NULL,
    arg JSONB NOT NULL DEFAULT '{}'::jsonb,
    brief TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (general_id, turn_idx),
    CHECK (jsonb_typeof(arg) = 'object')
);

-- nation_turn
CREATE TABLE nation_turn (
    id BIGSERIAL PRIMARY KEY,
    nation_id BIGINT NOT NULL REFERENCES nation(id) ON DELETE CASCADE,
    officer_level SMALLINT NOT NULL,
    turn_idx SMALLINT NOT NULL,
    action_code TEXT NOT NULL,
    arg JSONB NOT NULL DEFAULT '{}'::jsonb,
    brief TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (nation_id, officer_level, turn_idx),
    CHECK (jsonb_typeof(arg) = 'object')
);

-- diplomacy
CREATE TABLE diplomacy (
    id BIGSERIAL PRIMARY KEY,
    world_id BIGINT NOT NULL REFERENCES world_state(id) ON DELETE CASCADE,
    src_nation_id BIGINT NOT NULL,
    dest_nation_id BIGINT NOT NULL,
    state_code TEXT NOT NULL,
    term SMALLINT NOT NULL DEFAULT 0,
    is_dead BOOLEAN NOT NULL DEFAULT false,
    is_showing BOOLEAN NOT NULL DEFAULT true,
    meta JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (jsonb_typeof(meta) = 'object')
);

-- message
CREATE TABLE message (
    id BIGSERIAL PRIMARY KEY,
    world_id BIGINT NOT NULL REFERENCES world_state(id) ON DELETE CASCADE,
    mailbox_code TEXT NOT NULL,
    message_type TEXT NOT NULL,
    src_id BIGINT,
    dest_id BIGINT,
    sent_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    valid_until TIMESTAMPTZ,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    meta JSONB NOT NULL DEFAULT '{}'::jsonb,
    CHECK (jsonb_typeof(payload) = 'object'),
    CHECK (jsonb_typeof(meta) = 'object')
);

-- event
CREATE TABLE event (
    id BIGSERIAL PRIMARY KEY,
    world_id BIGINT NOT NULL REFERENCES world_state(id) ON DELETE CASCADE,
    target_code TEXT NOT NULL,
    priority SMALLINT NOT NULL DEFAULT 0,
    condition JSONB NOT NULL DEFAULT '{}'::jsonb,
    action JSONB NOT NULL DEFAULT '{}'::jsonb,
    meta JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (jsonb_typeof(condition) = 'object'),
    CHECK (jsonb_typeof(action) = 'object'),
    CHECK (jsonb_typeof(meta) = 'object')
);

-- nation_flag
CREATE TYPE nation_aux_key AS ENUM (
    'can_국기변경', 'can_국호변경', 'did_특성초토화', 'can_무작위수도이전',
    'can_대검병사용', 'can_극병사용', 'can_화시병사용', 'can_원융노병사용',
    'can_산저병사용', 'can_상병사용', 'can_음귀병사용', 'can_무희사용',
    'can_화륜차사용'
);

CREATE TABLE nation_flag (
    nation_id BIGINT NOT NULL REFERENCES nation(id) ON DELETE CASCADE,
    key nation_aux_key NOT NULL,
    value JSONB NOT NULL DEFAULT 'true'::jsonb,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (nation_id, key)
);

-- Indexes
CREATE INDEX idx_nation_world_id ON nation(world_id);
CREATE INDEX idx_city_world_id ON city(world_id);
CREATE INDEX idx_city_nation_id ON city(nation_id);
CREATE INDEX idx_general_world_id ON general(world_id);
CREATE INDEX idx_general_nation_id ON general(nation_id);
CREATE INDEX idx_general_city_id ON general(city_id);
CREATE INDEX idx_general_user_id ON general(user_id);
CREATE INDEX idx_diplomacy_world_id ON diplomacy(world_id);
CREATE INDEX idx_message_world_id ON message(world_id);
CREATE INDEX idx_message_dest_id ON message(dest_id);
CREATE INDEX idx_event_world_id ON event(world_id);
