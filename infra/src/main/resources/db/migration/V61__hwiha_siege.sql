-- HWIHA 縣城 포위 상태(재설계 spec §5.1 6단계·§5.2 2단계, march-tempo-targets-v1.json siegeResolution·armyEncirclement).
-- V32 세계 범위 규약: world_id 선행 복합 PK · world_state FK(무액션) · 부모 (world_id, id) 복합 FK · 인덱스 world_id 선행.
-- 縣 하나에 포위 행 하나(PK = 縣治 城 id). 끝난 포위(lifted·fallen)도 조회·기록용으로 남기고, 같은 縣의 새 포위는 행을 덮어쓴다.
-- 쓰기는 엔진 ChangeRecorder → JdbcFlushExecutor 만 한다.

CREATE TABLE IF NOT EXISTS hwiha_siege (
    world_id                  INTEGER      NOT NULL,
    county_id                 INTEGER      NOT NULL,
    status                    VARCHAR(16)  NOT NULL,
    besieger_general_id       INTEGER      NOT NULL,
    besieger_owner_general_id INTEGER      NOT NULL,
    besieger_order_id         VARCHAR(128) NOT NULL,
    besieger_nation_id        INTEGER      NOT NULL,
    defender_nation_id        INTEGER      NOT NULL,
    approach_province_id      VARCHAR(128) NOT NULL,
    started_year              SMALLINT     NOT NULL,
    started_month             SMALLINT     NOT NULL,
    started_phase             SMALLINT     NOT NULL,
    settled_year              SMALLINT     NULL,
    settled_month             SMALLINT     NULL,
    settled_phase             SMALLINT     NULL,
    turns                     INTEGER      NOT NULL DEFAULT 0,
    morale                    INTEGER      NOT NULL,
    garrison                  INTEGER      NOT NULL,
    end_reason                VARCHAR(32)  NULL,
    timeline                  JSONB        NOT NULL DEFAULT '[]'::jsonb,
    created_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT hwiha_siege_pkey PRIMARY KEY (world_id, county_id),
    CONSTRAINT hwiha_siege_world_id_fkey FOREIGN KEY (world_id) REFERENCES world_state(id),
    CONSTRAINT hwiha_siege_county_fkey FOREIGN KEY (world_id, county_id) REFERENCES city(world_id, id) ON DELETE CASCADE,
    CONSTRAINT hwiha_siege_besieger_fkey FOREIGN KEY (world_id, besieger_general_id) REFERENCES general(world_id, id) ON DELETE CASCADE,
    CONSTRAINT hwiha_siege_status_ck CHECK (status IN ('ACTIVE', 'LIFTED', 'FALLEN')),
    CONSTRAINT hwiha_siege_end_ck CHECK ((status = 'ACTIVE') = (end_reason IS NULL)),
    CONSTRAINT hwiha_siege_phase_ck CHECK (started_month BETWEEN 1 AND 12 AND started_phase BETWEEN 1 AND 3),
    CONSTRAINT hwiha_siege_settled_ck CHECK (
        (settled_year IS NULL AND settled_month IS NULL AND settled_phase IS NULL)
        OR (settled_year IS NOT NULL AND settled_month BETWEEN 1 AND 12 AND settled_phase BETWEEN 1 AND 3)),
    CONSTRAINT hwiha_siege_turns_ck CHECK (turns >= 0),
    CONSTRAINT hwiha_siege_morale_ck CHECK (morale BETWEEN 0 AND 10000),
    CONSTRAINT hwiha_siege_garrison_ck CHECK (garrison >= 0),
    CONSTRAINT hwiha_siege_nations_ck CHECK (besieger_nation_id > 0 AND defender_nation_id >= 0 AND besieger_nation_id <> defender_nation_id),
    CONSTRAINT hwiha_siege_timeline_ck CHECK (jsonb_typeof(timeline) = 'array')
);
CREATE INDEX IF NOT EXISTS hwiha_siege_besieger_idx ON hwiha_siege (world_id, besieger_general_id);
CREATE INDEX IF NOT EXISTS hwiha_siege_status_idx ON hwiha_siege (world_id, status);
