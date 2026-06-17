-- V16 — yearbook_history.global_history / global_action 연감 글로벌 로그 컬럼 (P0-20, W0-8).
--
-- PHP 정본 hwe/sql/schema.sql `ng_history`(:465): server_id/year/month + JSON 4컬럼
-- map / global_history / global_action / nations. LogHistory(func_history.php:436-448)가
-- getCurrentHistory 스냅샷(중원 정세 = global_history, 장수 동향 = global_action 포함)을
-- 월별 INSERT한다. V1 yearbook_history에는 map/nations만 있어 두 글로벌 로그를 실을 자리가
-- 없었다(연감 2섹션 영구 공백의 스키마 측 원인). 기존 행 호환을 위해 NOT NULL DEFAULT '[]'
-- (PHP 컬럼은 NULL 허용이지만 LogHistory는 항상 JSON 배열을 쓴다 — 빈 달은 빈 배열).
ALTER TABLE yearbook_history ADD COLUMN global_history jsonb NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE yearbook_history ADD COLUMN global_action  jsonb NOT NULL DEFAULT '[]'::jsonb;
