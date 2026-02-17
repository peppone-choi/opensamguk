-- tournament
CREATE TABLE tournament (
    id BIGSERIAL PRIMARY KEY,
    world_id BIGINT NOT NULL REFERENCES world_state(id) ON DELETE CASCADE,
    general_id BIGINT NOT NULL,
    round SMALLINT NOT NULL DEFAULT 0,
    bracket_position SMALLINT NOT NULL DEFAULT 0,
    opponent_id BIGINT,
    result SMALLINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- auction
CREATE TABLE auction (
    id BIGSERIAL PRIMARY KEY,
    world_id BIGINT NOT NULL REFERENCES world_state(id) ON DELETE CASCADE,
    seller_general_id BIGINT NOT NULL,
    item_code TEXT NOT NULL,
    min_price INTEGER NOT NULL DEFAULT 0,
    current_price INTEGER NOT NULL DEFAULT 0,
    buyer_general_id BIGINT,
    status TEXT NOT NULL DEFAULT 'open',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL
);

-- auction_bid
CREATE TABLE auction_bid (
    id BIGSERIAL PRIMARY KEY,
    auction_id BIGINT NOT NULL REFERENCES auction(id) ON DELETE CASCADE,
    bidder_general_id BIGINT NOT NULL,
    amount INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- betting
CREATE TABLE betting (
    id BIGSERIAL PRIMARY KEY,
    world_id BIGINT NOT NULL REFERENCES world_state(id) ON DELETE CASCADE,
    target_type TEXT NOT NULL,
    target_id BIGINT NOT NULL,
    odds JSONB NOT NULL DEFAULT '{}'::jsonb,
    status TEXT NOT NULL DEFAULT 'open',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (jsonb_typeof(odds) = 'object')
);

-- bet_entry
CREATE TABLE bet_entry (
    id BIGSERIAL PRIMARY KEY,
    betting_id BIGINT NOT NULL REFERENCES betting(id) ON DELETE CASCADE,
    general_id BIGINT NOT NULL,
    choice TEXT NOT NULL,
    amount INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- vote
CREATE TABLE vote (
    id BIGSERIAL PRIMARY KEY,
    world_id BIGINT NOT NULL REFERENCES world_state(id) ON DELETE CASCADE,
    nation_id BIGINT NOT NULL,
    title TEXT NOT NULL,
    options JSONB NOT NULL DEFAULT '{}'::jsonb,
    status TEXT NOT NULL DEFAULT 'open',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    CHECK (jsonb_typeof(options) = 'object')
);

-- vote_cast
CREATE TABLE vote_cast (
    id BIGSERIAL PRIMARY KEY,
    vote_id BIGINT NOT NULL REFERENCES vote(id) ON DELETE CASCADE,
    general_id BIGINT NOT NULL,
    option_idx SMALLINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (vote_id, general_id)
);

-- rank_data
CREATE TABLE rank_data (
    id BIGSERIAL PRIMARY KEY,
    world_id BIGINT NOT NULL REFERENCES world_state(id) ON DELETE CASCADE,
    nation_id BIGINT NOT NULL DEFAULT 0,
    category TEXT NOT NULL,
    score INTEGER NOT NULL DEFAULT 0,
    meta JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (jsonb_typeof(meta) = 'object')
);

-- board
CREATE TABLE board (
    id BIGSERIAL PRIMARY KEY,
    world_id BIGINT NOT NULL REFERENCES world_state(id) ON DELETE CASCADE,
    nation_id BIGINT,
    author_general_id BIGINT NOT NULL,
    title TEXT NOT NULL,
    content TEXT NOT NULL,
    is_secret BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- board_comment
CREATE TABLE board_comment (
    id BIGSERIAL PRIMARY KEY,
    board_id BIGINT NOT NULL REFERENCES board(id) ON DELETE CASCADE,
    author_general_id BIGINT NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- general_access_log
CREATE TABLE general_access_log (
    id BIGSERIAL PRIMARY KEY,
    general_id BIGINT NOT NULL,
    world_id BIGINT NOT NULL REFERENCES world_state(id) ON DELETE CASCADE,
    accessed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ip_address TEXT
);

-- world_history
CREATE TABLE world_history (
    id BIGSERIAL PRIMARY KEY,
    world_id BIGINT NOT NULL REFERENCES world_state(id) ON DELETE CASCADE,
    year SMALLINT NOT NULL,
    month SMALLINT NOT NULL,
    event_type TEXT NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (jsonb_typeof(payload) = 'object')
);

-- general_record
CREATE TABLE general_record (
    id BIGSERIAL PRIMARY KEY,
    general_id BIGINT NOT NULL,
    world_id BIGINT NOT NULL REFERENCES world_state(id) ON DELETE CASCADE,
    record_type TEXT NOT NULL,
    value JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (jsonb_typeof(value) = 'object')
);

-- Indexes
CREATE INDEX idx_tournament_world_id ON tournament(world_id);
CREATE INDEX idx_tournament_general_id ON tournament(general_id);

CREATE INDEX idx_auction_world_id ON auction(world_id);
CREATE INDEX idx_auction_seller_general_id ON auction(seller_general_id);
CREATE INDEX idx_auction_status ON auction(status);

CREATE INDEX idx_auction_bid_auction_id ON auction_bid(auction_id);
CREATE INDEX idx_auction_bid_bidder_general_id ON auction_bid(bidder_general_id);

CREATE INDEX idx_betting_world_id ON betting(world_id);
CREATE INDEX idx_betting_status ON betting(status);

CREATE INDEX idx_bet_entry_betting_id ON bet_entry(betting_id);
CREATE INDEX idx_bet_entry_general_id ON bet_entry(general_id);

CREATE INDEX idx_vote_world_id ON vote(world_id);
CREATE INDEX idx_vote_nation_id ON vote(nation_id);

CREATE INDEX idx_vote_cast_vote_id ON vote_cast(vote_id);
CREATE INDEX idx_vote_cast_general_id ON vote_cast(general_id);

CREATE INDEX idx_rank_data_world_id ON rank_data(world_id);
CREATE INDEX idx_rank_data_nation_id ON rank_data(nation_id);
CREATE INDEX idx_rank_data_category ON rank_data(category);

CREATE INDEX idx_board_world_id ON board(world_id);
CREATE INDEX idx_board_nation_id ON board(nation_id);
CREATE INDEX idx_board_author_general_id ON board(author_general_id);

CREATE INDEX idx_board_comment_board_id ON board_comment(board_id);

CREATE INDEX idx_general_access_log_general_id ON general_access_log(general_id);
CREATE INDEX idx_general_access_log_world_id ON general_access_log(world_id);

CREATE INDEX idx_world_history_world_id ON world_history(world_id);
CREATE INDEX idx_world_history_year_month ON world_history(world_id, year, month);

CREATE INDEX idx_general_record_general_id ON general_record(general_id);
CREATE INDEX idx_general_record_world_id ON general_record(world_id);
CREATE INDEX idx_general_record_type ON general_record(record_type);
