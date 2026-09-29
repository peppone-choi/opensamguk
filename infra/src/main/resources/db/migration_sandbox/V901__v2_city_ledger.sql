-- V2-FORWARD-ONLY: rollback is a new compensating V900+ migration.
-- OPENSAM-150 (R1) — v2 도시 원장. round3-proposal-city-guanxi.md §2.1.
-- v1 `city` 테이블/`City` 데이터 클래스는 건드리지 않는다 — 별도 표·별도 타입.
-- 훈련·사기·3개월 병량 유지비는 오픈 후(§2.1).
--
-- world_id 타입: 설계안 §2.1 스케치는 bigint이나 실제 참조 대상 `world_state.id`는 serial(integer)이고
-- (`db/migration/V1__baseline.sql:11`) `WorldId.value`도 Int다 (`common/.../world/WorldId.kt:17`).
-- 0A-c 규약(`db/migration_v2/README.md` §4)과 V900 probe도 integer이므로 integer로 맞춘다.
CREATE TABLE v2_city_ledger (
    world_id integer NOT NULL REFERENCES world_state(id),
    city_id  integer NOT NULL,
    gold     bigint  NOT NULL DEFAULT 0,
    rice     bigint  NOT NULL DEFAULT 0,
    garrison integer NOT NULL DEFAULT 0,
    PRIMARY KEY (world_id, city_id)
);
