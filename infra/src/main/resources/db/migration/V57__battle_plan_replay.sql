-- Phase 4X-C 출병 계획 봉인(공격자)·결정론 해결·리플레이 (specs/2026-09-06-wego-field-seal-replay-vertical-slice v4.1 §2).
-- V32 세계 범위 규약: world_id 선행 복합 PK · world_state FK(무액션) · 부모 (world_id, id) 복합 FK · 모든 인덱스 world_id 선행.
-- id 는 엔진 할당(battle_plan = 세계 상태 고수위, battle_replay = recorder 선할당 DB-seed). V56(operation) 뒤에만 적용된다.

CREATE TABLE IF NOT EXISTS battle_plan (
    world_id              INTEGER      NOT NULL,
    id                    INTEGER      NOT NULL,
    general_id            INTEGER      NOT NULL,
    target_city_id        INTEGER      NOT NULL,
    stance                VARCHAR(16)  NOT NULL,
    retreat_loss_pct      SMALLINT     NULL,
    retreat_morale_below  SMALLINT     NULL,
    sealed_at             TIMESTAMPTZ  NULL,
    sealed_year           SMALLINT     NULL,
    sealed_month          SMALLINT     NULL,
    sealed_phase          SMALLINT     NULL,
    resolved_year         SMALLINT     NULL,
    resolved_month        SMALLINT     NULL,
    resolved_phase        SMALLINT     NULL,
    version               INTEGER      NOT NULL DEFAULT 1,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT battle_plan_pkey PRIMARY KEY (world_id, id),
    CONSTRAINT battle_plan_world_id_fkey FOREIGN KEY (world_id) REFERENCES world_state(id),
    CONSTRAINT battle_plan_general_fkey FOREIGN KEY (world_id, general_id) REFERENCES general(world_id, id) ON DELETE CASCADE,
    CONSTRAINT battle_plan_target_city_fkey FOREIGN KEY (world_id, target_city_id) REFERENCES city(world_id, id),
    CONSTRAINT battle_plan_stance_ck CHECK (stance IN ('assault', 'probe')),
    CONSTRAINT battle_plan_loss_pct_ck CHECK (retreat_loss_pct IS NULL OR retreat_loss_pct BETWEEN 10 AND 90),
    CONSTRAINT battle_plan_morale_ck CHECK (retreat_morale_below IS NULL OR retreat_morale_below BETWEEN 0 AND 100)
);
-- 미소비 계획은 장수 × 목표 도시 하나(F7·R7) — 부분 UNIQUE 인덱스(표 제약 `UNIQUE … WHERE` 는 PG 문법이 아니다).
CREATE UNIQUE INDEX IF NOT EXISTS battle_plan_open_uk ON battle_plan (world_id, general_id, target_city_id) WHERE resolved_year IS NULL;
CREATE INDEX IF NOT EXISTS battle_plan_general_idx ON battle_plan (world_id, general_id);

-- 계획이 봉인된 전투만 기록한다(INSERT 전용). 국가·도시 id 열은 FK 없는 스냅샷(N4) — 같은 틱 국가 소멸에서 터지지 않는다.
-- 해시 열은 VARCHAR(고정 길이 hex): game-api Hibernate `ddl-auto: validate` 가 CHAR(bpchar) 를 String 매핑과 다른 타입으로 거부한다.
CREATE TABLE IF NOT EXISTS battle_replay (
    world_id                    INTEGER      NOT NULL,
    id                          INTEGER      NOT NULL,
    battle_plan_id              INTEGER      NULL,
    operation_id                INTEGER      NULL,
    attacker_general_id         INTEGER      NULL,
    attacker_name               VARCHAR(50)  NOT NULL,
    attacker_nation_id          INTEGER      NOT NULL,
    defender_city_id            INTEGER      NOT NULL,
    defender_city_name          VARCHAR(50)  NOT NULL,
    defender_nation_id          INTEGER      NOT NULL,
    year                        SMALLINT     NOT NULL,
    month                       SMALLINT     NOT NULL,
    phase                       SMALLINT     NOT NULL,
    war_seed                    VARCHAR(32)  NOT NULL,
    input_hash                  VARCHAR(64)  NOT NULL,
    replay_hash                 VARCHAR(64)  NOT NULL,
    schema_version              SMALLINT     NOT NULL DEFAULT 1,
    battle_phases_json          TEXT         NOT NULL,
    attacker_crew_before        INTEGER      NOT NULL,
    attacker_crew_after         INTEGER      NOT NULL,
    attacker_dead               INTEGER      NOT NULL,
    defender_dead               INTEGER      NOT NULL,
    rice_used                   INTEGER      NOT NULL,
    result                      VARCHAR(16)  NOT NULL,
    plan_stop                   VARCHAR(24)  NULL,
    plan_stance                 VARCHAR(16)  NULL,
    plan_retreat_loss_pct       SMALLINT     NULL,
    plan_retreat_morale_below   SMALLINT     NULL,
    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT battle_replay_pkey PRIMARY KEY (world_id, id),
    CONSTRAINT battle_replay_world_id_fkey FOREIGN KEY (world_id) REFERENCES world_state(id),
    CONSTRAINT battle_replay_plan_fkey FOREIGN KEY (world_id, battle_plan_id) REFERENCES battle_plan(world_id, id) ON DELETE SET NULL (battle_plan_id),
    CONSTRAINT battle_replay_operation_fkey FOREIGN KEY (world_id, operation_id) REFERENCES operation(world_id, id) ON DELETE SET NULL (operation_id),
    CONSTRAINT battle_replay_attacker_fkey FOREIGN KEY (world_id, attacker_general_id) REFERENCES general(world_id, id) ON DELETE SET NULL (attacker_general_id),
    CONSTRAINT battle_replay_result_ck CHECK (result IN ('retreat', 'repelled', 'defenders_down', 'conquered')),
    CONSTRAINT battle_replay_plan_stop_ck CHECK (plan_stop IS NULL OR plan_stop IN ('probe', 'loss_pct', 'morale'))
);
CREATE INDEX IF NOT EXISTS battle_replay_attacker_nation_idx ON battle_replay (world_id, attacker_nation_id, id);
CREATE INDEX IF NOT EXISTS battle_replay_defender_nation_idx ON battle_replay (world_id, defender_nation_id, id);
CREATE INDEX IF NOT EXISTS battle_replay_attacker_idx ON battle_replay (world_id, attacker_general_id);
