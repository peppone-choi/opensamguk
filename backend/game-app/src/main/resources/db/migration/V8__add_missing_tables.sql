-- V8: Add missing tables for Emperor, HallOfFame, GameHistory, OldGeneral, OldNation, YearbookHistory

CREATE TABLE emperor (
    id          BIGSERIAL PRIMARY KEY,
    server_id   VARCHAR(255) NOT NULL,
    phase       VARCHAR(255) NOT NULL,
    nation_count VARCHAR(255) NOT NULL,
    nation_name VARCHAR(255) NOT NULL,
    nation_hist VARCHAR(255) NOT NULL,
    gen_count   VARCHAR(255) NOT NULL,
    personal_hist VARCHAR(255) NOT NULL,
    special_hist VARCHAR(255) NOT NULL,
    name        VARCHAR(255) NOT NULL,
    type        VARCHAR(255) NOT NULL,
    color       VARCHAR(255) NOT NULL,
    year        SMALLINT NOT NULL,
    month       SMALLINT NOT NULL,
    power       INT NOT NULL,
    gennum      INT NOT NULL,
    citynum     INT NOT NULL,
    pop         VARCHAR(255) NOT NULL,
    poprate     VARCHAR(255) NOT NULL,
    gold        INT NOT NULL,
    rice        INT NOT NULL,
    l12name     VARCHAR(255) NOT NULL,
    l12pic      VARCHAR(255) NOT NULL,
    l11name     VARCHAR(255) NOT NULL,
    l11pic      VARCHAR(255) NOT NULL,
    l10name     VARCHAR(255) NOT NULL,
    l10pic      VARCHAR(255) NOT NULL,
    l9name      VARCHAR(255) NOT NULL,
    l9pic       VARCHAR(255) NOT NULL,
    l8name      VARCHAR(255) NOT NULL,
    l8pic       VARCHAR(255) NOT NULL,
    l7name      VARCHAR(255) NOT NULL,
    l7pic       VARCHAR(255) NOT NULL,
    l6name      VARCHAR(255) NOT NULL,
    l6pic       VARCHAR(255) NOT NULL,
    l5name      VARCHAR(255) NOT NULL,
    l5pic       VARCHAR(255) NOT NULL,
    tiger       VARCHAR(255) NOT NULL,
    eagle       VARCHAR(255) NOT NULL,
    gen         VARCHAR(255) NOT NULL,
    history     JSONB NOT NULL DEFAULT '[]',
    aux         JSONB NOT NULL DEFAULT '{}'
);

CREATE TABLE hall_of_fame (
    id          BIGSERIAL PRIMARY KEY,
    server_id   VARCHAR(255) NOT NULL,
    season      INT NOT NULL DEFAULT 1,
    scenario    INT NOT NULL DEFAULT 0,
    general_no  BIGINT NOT NULL,
    type        VARCHAR(255) NOT NULL,
    value       DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    owner       VARCHAR(255),
    aux         JSONB NOT NULL DEFAULT '{}'
);

CREATE TABLE game_history (
    server_id     VARCHAR(255) PRIMARY KEY,
    winner_nation BIGINT,
    date          TIMESTAMPTZ,
    meta          JSONB NOT NULL DEFAULT '{}'
);

CREATE TABLE old_general (
    id              BIGSERIAL PRIMARY KEY,
    server_id       VARCHAR(255) NOT NULL,
    general_no      BIGINT NOT NULL,
    owner           VARCHAR(255),
    name            VARCHAR(255) NOT NULL,
    last_yearmonth  INT NOT NULL,
    turn_time       TIMESTAMPTZ,
    data            JSONB NOT NULL DEFAULT '{}'
);

CREATE TABLE old_nation (
    id        BIGSERIAL PRIMARY KEY,
    server_id VARCHAR(255) NOT NULL,
    nation    BIGINT NOT NULL,
    data      JSONB NOT NULL DEFAULT '{}',
    date      TIMESTAMPTZ
);

CREATE TABLE yearbook_history (
    id         BIGSERIAL PRIMARY KEY,
    world_id   BIGINT NOT NULL,
    year       SMALLINT NOT NULL,
    month      SMALLINT NOT NULL,
    map        JSONB NOT NULL DEFAULT '{}',
    nations    JSONB NOT NULL DEFAULT '[]',
    hash       VARCHAR(255) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (world_id, year, month)
);
