-- V17 — inheritance_log.date 로그 시각 컬럼 (P1-043 inheritance date, W0-8 infra 공유 widen).
--
-- PHP 정본 hwe/sql/schema.sql `user_record`(:618): `date DATETIME NULL DEFAULT NULL`.
-- v_inheritPoint.php:74가 `SELECT id, server_id, year, month, date, text FROM user_record
-- WHERE log_type='inheritPoint' …`로 유산 로그 30건을 읽어 행마다 date를 노출한다.
-- opensamguk의 유산 로그 영속화 테이블은 inheritance_log(V1:291)인데 year/month만 있고
-- date가 없어 FE InheritLog.date 체인이 전체 탈락했다. NULL 허용 — 기존 행/미스탬프 행은 NULL.
ALTER TABLE inheritance_log ADD COLUMN date timestamptz;
