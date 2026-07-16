-- OPENSAM-14 (W3-2): 연월 로그 조회(findGlobalHistoryByMonth / findGlobalActionByMonth)용 인덱스.
-- 이 두 쿼리는 LIMIT 없이 year/month로 거르는데 기존 인덱스 3종 어느 것도 year/month를 포함하지
-- 않아, enum 캐스트 제거(같은 티켓) 후에도 seq scan(315ms@2M행) 또는 scope_idx 전량 비트맵(531ms)만
-- 남는다. (year, month, id)면 BitmapAnd로 13.9~20.6ms — 로컬 postgres:16 2M행 실측.
--
-- CREATE INDEX CONCURRENTLY: 라이브 log_entry는 매 턴 데몬 flush가 쓰는 테이블이라 일반 CREATE
-- INDEX의 배타 락이 turn daemon(JdbcFlushExecutor)을 얼릴 수 있다(frozen-turn-daemon 증상).
-- CONCURRENTLY는 트랜잭션 안에서 실행 불가 → V29__log_entry_year_month_index.sql.conf의
-- executeInTransaction=false가 이 파일을 비트랜잭션으로 돌린다.
-- 선행 DROP: CONCURRENTLY 빌드가 중단되면 INVALID 인덱스가 남는데, IF NOT EXISTS로는 재시도가
-- 이를 건너뛰므로 드롭-후-생성 관용구로 재시도 안전성을 확보한다.
-- 잠금 요건: flyway.postgresql.transactional.lock=false(앱 yml 3종·마이그레이션 IT에 설정) 필수.
-- 기본 트랜잭션형 advisory lock은 잠금 커넥션을 idle-in-transaction으로 유지해 CONCURRENTLY가
-- 영원히 대기하는 데드락을 만든다(V29 IT에서 실증).
DROP INDEX CONCURRENTLY IF EXISTS log_entry_year_month_idx;
CREATE INDEX CONCURRENTLY log_entry_year_month_idx ON log_entry (year, month, id);
