-- V15 — inheritance KV "table" 판별자 백필.
--
-- ChangeRecorder.recordInheritancePointSet/Increase가 KvWrite 첫 인자에 물리 테이블명
-- 'game_kv'를 오기록해, 데몬발 유산포인트 쓰기가 "table"='game_kv'로 착지했다.
-- 모든 reader(InheritanceRepository / InheritPointController / BettingController)는
-- "table"='inheritance'로 조회하므로 해당 행들은 고아였다. 코드 수정(KvWrite("inheritance",…))과
-- 함께 기존 오기록 행을 정정한다. 데몬 행이 최신 상태이므로 충돌 시 데몬 값이 이긴다.

-- 1) 동일 (namespace, key)가 양쪽 판별자로 존재하면 — 데몬('game_kv') 값을 정본('inheritance')에 머지.
UPDATE game_kv i
   SET value = g.value
  FROM game_kv g
 WHERE i."table" = 'inheritance'
   AND g."table" = 'game_kv'
   AND g.namespace = i.namespace
   AND g.key = i.key
   AND g.namespace LIKE 'inheritance\_%' ESCAPE '\';

-- 2) 머지된 중복 오기록 행 제거.
DELETE FROM game_kv g
 WHERE g."table" = 'game_kv'
   AND g.namespace LIKE 'inheritance\_%' ESCAPE '\'
   AND EXISTS (
       SELECT 1 FROM game_kv i
        WHERE i."table" = 'inheritance' AND i.namespace = g.namespace AND i.key = g.key
   );

-- 3) 잔여 오기록 행을 정본 판별자로 이동.
UPDATE game_kv
   SET "table" = 'inheritance'
 WHERE "table" = 'game_kv'
   AND namespace LIKE 'inheritance\_%' ESCAPE '\';
