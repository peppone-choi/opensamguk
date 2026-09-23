-- 휘하 「지난 순」: 기록 한 줄의 사건 종류(event_kind)와 구체 식별자(meta.refs).
--
-- 순(phase)은 V22 가 이미 넣었다(year/month/phase). 여기서는 종류만 더한다. 식별자는 기존 meta jsonb 의
-- `refs` 키에 싣는다 — 종류마다 모양이 달라 열로 펼치지 않는다. 종류 문자열의 정본은
-- `logic/.../input/HwihaRecordKind.kt` 다. 기존 기록은 NULL 로 남는다(소급하지 않는다).
--
-- ADD COLUMN(기본값 없음, NULL 허용)은 카탈로그만 바꾼다 — 행을 다시 쓰지 않는다.
-- 인덱스는 V29 와 같은 이유로 CONCURRENTLY 다: log_entry 는 매 턴 데몬 flush 가 쓰는 표라 일반 CREATE
-- INDEX 의 배타 락이 턴 데몬을 얼린다. 그래서 이 파일은 비트랜잭션이다(V60__….sql.conf).
-- 선행 DROP 은 중단된 CONCURRENTLY 빌드가 남긴 INVALID 인덱스를 재시도가 건너뛰지 않게 한다.
-- 부분 인덱스라 휘하 기록만 담는다: 개인 기록은 (world, general, 연·월·순) 범위로, 세력 요약은
-- (world, nation, 연·월·순) 범위로 읽는다(game-api HwihaRecordReadRepository).
ALTER TABLE log_entry ADD COLUMN IF NOT EXISTS event_kind text;

DROP INDEX CONCURRENTLY IF EXISTS log_entry_hwiha_general_turn_idx;
CREATE INDEX CONCURRENTLY log_entry_hwiha_general_turn_idx
    ON log_entry (world_id, general_id, year, month, phase, id)
    WHERE event_kind IS NOT NULL;

DROP INDEX CONCURRENTLY IF EXISTS log_entry_hwiha_nation_turn_idx;
CREATE INDEX CONCURRENTLY log_entry_hwiha_nation_turn_idx
    ON log_entry (world_id, nation_id, year, month, phase, id)
    WHERE event_kind IS NOT NULL;
