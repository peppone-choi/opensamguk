-- V14 — city.state 재해/호황 상태 컬럼 (P0-36, W0-8 infra 공유 widen).
--
-- PHP 정본 hwe/sql/schema.sql `city` 테이블(:187): `state INT(2) NOT NULL DEFAULT '0'`.
-- RaiseDisaster.php가 매월 무조건 `state<=10 → 0` 리셋 후 선택 도시에 1~9 이벤트 코드를 쓴다
-- (func_map.php:144-148이 지도 아이콘으로 직렬화). 지금까지 opensamguk 스키마에는 이 컬럼이
-- 없어 엔진 인메모리 City.state가 재기동 시 유실됐고, read 측은 front_state(전선 0~3)를 state
-- 슬롯에 오직렬화했다(P0-36 위조 표시). 이 컬럼이 flush(cityUpdate)/rehydrate(WorldSnapshotLoader)
-- 대상이 된다. V1~V13은 동결 — 이 마이그레이션은 city.state 추가만 한다.
ALTER TABLE city ADD COLUMN state integer NOT NULL DEFAULT 0;
