CREATE TABLE general_access_log (
    id                  bigserial PRIMARY KEY,
    general_id          integer NOT NULL UNIQUE,
    user_id             bigint,
    last_refresh        timestamptz,
    refresh             integer NOT NULL DEFAULT 0,
    refresh_total       integer NOT NULL DEFAULT 0,
    refresh_score       integer NOT NULL DEFAULT 0,
    refresh_score_total integer NOT NULL DEFAULT 0
);

CREATE INDEX general_access_log_user_idx ON general_access_log (user_id);
