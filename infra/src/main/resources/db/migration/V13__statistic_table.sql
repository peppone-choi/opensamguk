-- V13: statistic 테이블 — 연경계(새 달 == 1월) checkStatistic INSERT 대상.
--
-- PHP grand truth: legacy/devsam-core/hwe/sql/schema.sql:445-461 (`statistic`,
-- no/year/month/... 컬럼 순서 그대로). 2026-06-09 prod 턴동결(s1/spep)의 2층 원인 중
-- 하나 — 이 DDL이 없어서 연경계 flush가 `relation "statistic" does not exist`로
-- 영구 실패했다.
--
-- aux: PHP는 TEXT(utf8mb4_bin) + json_valid CHECK. 이 repo의 Postgres 관례
-- (V1 general.meta, V7 ng_betting.aux 등)는 jsonb이고 JdbcFlushExecutor가 PGobject
-- type="jsonb"로 바인딩하므로 jsonb를 쓴다. jsonb는 키 순서/공백을 정규화하므로
-- statistic 골든의 aux 비교는 decode-level(구조 동등)로 한다 — byte-order 계약은
-- MetaJson.encode 시점(flush 페이로드)까지다.
CREATE TABLE statistic (
    id            serial PRIMARY KEY,
    year          integer NOT NULL DEFAULT 0,
    month         integer NOT NULL DEFAULT 0,
    nation_count  integer NOT NULL DEFAULT 0,
    nation_name   text    NOT NULL DEFAULT '',
    nation_hist   text    NOT NULL DEFAULT '',
    gen_count     text    NOT NULL DEFAULT '',
    personal_hist text    NOT NULL DEFAULT '',
    special_hist  text    NOT NULL DEFAULT '',
    power_hist    text    NOT NULL DEFAULT '',
    crewtype      text    NOT NULL DEFAULT '',
    etc           text    NOT NULL DEFAULT '',
    aux           jsonb
);
