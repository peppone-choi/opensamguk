-- Phase 4X-B 작전 (specs/2026-09-06-operation-vertical-slice v4.1, ADR-LITE-032 operationId 키). V32 세계 범위 규약:
-- world_id 선행 복합 PK · world_state FK(무액션) · 부모 (world_id, id) 복합 FK · 모든 인덱스 world_id 선행. id 는 엔진 할당.
-- V55(general_bugok) 뒤에만 적용된다(operation_unit.bugok_id FK).

CREATE TABLE IF NOT EXISTS operation (
    world_id                INTEGER      NOT NULL,
    id                      INTEGER      NOT NULL,
    nation_id               INTEGER      NOT NULL,
    kind                    VARCHAR(16)  NOT NULL,
    target_city_id          INTEGER      NOT NULL,
    title                   VARCHAR(40)  NOT NULL,
    fallback_text           VARCHAR(200) NULL,
    declared_by_general_id  INTEGER      NULL,
    declared_year           SMALLINT     NOT NULL,
    declared_month          SMALLINT     NOT NULL,
    declared_phase          SMALLINT     NOT NULL,
    deadline_year           SMALLINT     NOT NULL,
    deadline_month          SMALLINT     NOT NULL,
    deadline_phase          SMALLINT     NOT NULL DEFAULT 1,
    status                  VARCHAR(16)  NOT NULL,
    m_departed              BOOLEAN      NOT NULL DEFAULT false,
    m_arrived               BOOLEAN      NOT NULL DEFAULT false,
    m_supplied              BOOLEAN      NOT NULL DEFAULT false,
    m_objective             BOOLEAN      NOT NULL DEFAULT false,
    closed_reason           VARCHAR(16)  NULL,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT operation_pkey PRIMARY KEY (world_id, id),
    CONSTRAINT operation_world_id_fkey FOREIGN KEY (world_id) REFERENCES world_state(id),
    CONSTRAINT operation_nation_fkey FOREIGN KEY (world_id, nation_id) REFERENCES nation(world_id, id) ON DELETE CASCADE,
    CONSTRAINT operation_target_city_fkey FOREIGN KEY (world_id, target_city_id) REFERENCES city(world_id, id),
    CONSTRAINT operation_declared_by_fkey FOREIGN KEY (world_id, declared_by_general_id) REFERENCES general(world_id, id) ON DELETE SET NULL (declared_by_general_id),
    CONSTRAINT operation_kind_ck CHECK (kind IN ('capture_city', 'relieve', 'cut_supply', 'secure_route', 'pass_through', 'blockade')),
    CONSTRAINT operation_status_ck CHECK (status IN ('declared', 'active', 'achieved', 'failed', 'closed')),
    CONSTRAINT operation_deadline_phase_ck CHECK (deadline_phase = 1),
    CONSTRAINT operation_closed_reason_ck CHECK (closed_reason IS NULL OR closed_reason IN ('achieved', 'deadline', 'command', 'nation_gone', 'target_gone'))
);
CREATE INDEX IF NOT EXISTS operation_nation_idx ON operation (world_id, nation_id);
CREATE INDEX IF NOT EXISTS operation_status_idx ON operation (world_id, status);

CREATE TABLE IF NOT EXISTS operation_unit (
    world_id        INTEGER      NOT NULL,
    id              INTEGER      NOT NULL,
    operation_id    INTEGER      NOT NULL,
    general_id      INTEGER      NOT NULL,
    bugok_id        INTEGER      NULL,
    role            VARCHAR(16)  NOT NULL,
    joined_city_id  INTEGER      NOT NULL,
    joined_year     SMALLINT     NOT NULL,
    joined_month    SMALLINT     NOT NULL,
    joined_phase    SMALLINT     NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT operation_unit_pkey PRIMARY KEY (world_id, id),
    CONSTRAINT operation_unit_world_id_fkey FOREIGN KEY (world_id) REFERENCES world_state(id),
    CONSTRAINT operation_unit_operation_fkey FOREIGN KEY (world_id, operation_id) REFERENCES operation(world_id, id) ON DELETE CASCADE,
    CONSTRAINT operation_unit_general_fkey FOREIGN KEY (world_id, general_id) REFERENCES general(world_id, id) ON DELETE CASCADE,
    CONSTRAINT operation_unit_bugok_fkey FOREIGN KEY (world_id, bugok_id) REFERENCES general_bugok(world_id, id) ON DELETE SET NULL (bugok_id),
    CONSTRAINT operation_unit_role_ck CHECK (role IN ('main', 'flank', 'scout', 'convoy', 'reserve')),
    CONSTRAINT operation_unit_once_uk UNIQUE (world_id, operation_id, general_id)
);
CREATE INDEX IF NOT EXISTS operation_unit_general_idx ON operation_unit (world_id, general_id);

-- 회의실 글 ↔ 작전 연결. board 8d INSERT 가 작전 채널(8h)보다 앞이라 트랜잭션 끝에서 검사한다(DEFERRABLE, spec N1).
ALTER TABLE board_post ADD COLUMN IF NOT EXISTS operation_id INTEGER NULL;
ALTER TABLE board_post DROP CONSTRAINT IF EXISTS board_post_operation_fkey;
ALTER TABLE board_post ADD CONSTRAINT board_post_operation_fkey
    FOREIGN KEY (world_id, operation_id) REFERENCES operation(world_id, id) ON DELETE SET NULL (operation_id) DEFERRABLE INITIALLY DEFERRED;
CREATE INDEX IF NOT EXISTS board_post_operation_idx ON board_post (world_id, operation_id);

-- 4X-A 보정(PR 비평 S3): 부장 배정 사기 +6 은 부곡 생애에 한 번 — 해제→재배정 반복으로 사기를 채우는 경로를 막는다.
ALTER TABLE general_bugok ADD COLUMN IF NOT EXISTS commander_bonus_applied BOOLEAN NOT NULL DEFAULT false;
