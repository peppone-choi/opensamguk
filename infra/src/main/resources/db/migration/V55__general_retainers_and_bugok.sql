-- Phase 4X-A 가신(휘하 인물)·부곡 (ADR-LITE-017 가신 1트랙 · ADR-LITE-049 07 아트보드,
-- specs/2026-09-06-retinue-buqu-vertical-slice v3). 두 표 모두 V32 세계 범위 규약을 따른다:
-- world_id 선행 복합 PK · world_state FK(무액션) · 부모 (world_id, id) 복합 FK · 모든 인덱스 world_id 선행.
-- id 는 identity 가 아니라 엔진 할당(general.id 와 같은 방식, world_state.meta.maxRetainerId/maxBugokId 고수위).

CREATE TABLE IF NOT EXISTS general_retainers (
    world_id            INTEGER      NOT NULL,
    id                  INTEGER      NOT NULL,
    master_general_id   INTEGER      NOT NULL,
    origin              VARCHAR(16)  NOT NULL,
    general_id          INTEGER      NULL,
    name                VARCHAR(24)  NOT NULL,
    relation            VARCHAR(16)  NOT NULL,
    role                VARCHAR(16)  NOT NULL DEFAULT 'NONE',
    has_own_bugok       BOOLEAN      NOT NULL DEFAULT false,
    release_policy      VARCHAR(16)  NOT NULL,
    loyalty             INTEGER      NOT NULL DEFAULT 50,
    task                VARCHAR(16)  NOT NULL DEFAULT 'none',
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT general_retainers_pkey PRIMARY KEY (world_id, id),
    CONSTRAINT general_retainers_world_id_fkey FOREIGN KEY (world_id) REFERENCES world_state(id),
    CONSTRAINT general_retainers_master_fkey FOREIGN KEY (world_id, master_general_id) REFERENCES general(world_id, id) ON DELETE CASCADE,
    CONSTRAINT general_retainers_general_fkey FOREIGN KEY (world_id, general_id) REFERENCES general(world_id, id) ON DELETE CASCADE,
    CONSTRAINT general_retainers_origin_ck CHECK (origin IN ('EXISTING', 'RECRUITED')),
    CONSTRAINT general_retainers_origin_general_ck CHECK ((origin = 'EXISTING') = (general_id IS NOT NULL)),
    CONSTRAINT general_retainers_relation_ck CHECK (relation IN ('staff', 'lieutenant', 'guest')),
    CONSTRAINT general_retainers_role_ck CHECK (role IN ('STAFF', 'GUARD', 'QUARTERMASTER', 'SCOUT', 'ENVOY', 'NONE')),
    CONSTRAINT general_retainers_release_ck CHECK (release_policy IN ('MUTUAL', 'MASTER_ONLY')),
    CONSTRAINT general_retainers_loyalty_ck CHECK (loyalty BETWEEN 0 AND 100),
    CONSTRAINT general_retainers_task_ck CHECK (task IN ('none', 'domestic', 'scout', 'train')),
    CONSTRAINT general_retainers_master_name_uk UNIQUE (world_id, master_general_id, name)
);
CREATE INDEX IF NOT EXISTS general_retainers_master_idx ON general_retainers (world_id, master_general_id);

CREATE TABLE IF NOT EXISTS general_bugok (
    world_id                INTEGER      NOT NULL,
    id                      INTEGER      NOT NULL,
    master_general_id       INTEGER      NOT NULL,
    name                    VARCHAR(24)  NOT NULL,
    troops                  INTEGER      NOT NULL,
    crew_type_id            INTEGER      NOT NULL,
    training                INTEGER      NOT NULL,
    morale                  INTEGER      NOT NULL,
    fatigue                 INTEGER      NOT NULL DEFAULT 0,
    provisions              INTEGER      NOT NULL DEFAULT 0,
    commander_retainer_id   INTEGER      NULL,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT general_bugok_pkey PRIMARY KEY (world_id, id),
    CONSTRAINT general_bugok_world_id_fkey FOREIGN KEY (world_id) REFERENCES world_state(id),
    CONSTRAINT general_bugok_master_fkey FOREIGN KEY (world_id, master_general_id) REFERENCES general(world_id, id) ON DELETE CASCADE,
    -- PG15+ 열 지정 SET NULL: 부장 삭제 시 commander 만 NULL, world_id(PK) 보존 (spec F3).
    CONSTRAINT general_bugok_commander_fkey FOREIGN KEY (world_id, commander_retainer_id) REFERENCES general_retainers(world_id, id) ON DELETE SET NULL (commander_retainer_id),
    CONSTRAINT general_bugok_troops_ck CHECK (troops > 0),
    CONSTRAINT general_bugok_training_ck CHECK (training BETWEEN 0 AND 100),
    CONSTRAINT general_bugok_morale_ck CHECK (morale BETWEEN 0 AND 100),
    CONSTRAINT general_bugok_fatigue_ck CHECK (fatigue BETWEEN 0 AND 100),
    CONSTRAINT general_bugok_provisions_ck CHECK (provisions >= 0)
);
CREATE INDEX IF NOT EXISTS general_bugok_master_idx ON general_bugok (world_id, master_general_id);
CREATE INDEX IF NOT EXISTS general_bugok_commander_idx ON general_bugok (world_id, commander_retainer_id);
