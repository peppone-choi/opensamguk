-- V16 — RTK14(삼국지14) 분기 스탯 politics(정치)/charm(매력) 컬럼 추가.
--
-- DIVERGENCE: 이 두 컬럼은 devsam/core(legacy) 패러티 대상이 아니다 — RTK14 룩업에서만 채워지는
-- 오픈삼국 독자 분기 스탯이다. PHP 골든 오라클이 없으며, leadership/strength/intel(devsam 통무지)는
-- 일절 건드리지 않는다. 리소스(rtk14_stats.local.json)가 없는 환경(CI/prod)은 DEFAULT 50으로 안착한다.
--
-- leadership/strength/intel(V1__baseline.sql)과 동일하게 INT, NOT NULL DEFAULT 50.
ALTER TABLE general ADD COLUMN politics integer NOT NULL DEFAULT 50;
ALTER TABLE general ADD COLUMN charm integer NOT NULL DEFAULT 50;
