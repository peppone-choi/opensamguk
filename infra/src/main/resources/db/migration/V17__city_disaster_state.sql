-- PHP `city.state` (재해/사건 코드) 포팅 — RaiseDisaster가 매월 쓰는 disaster/booming stateCode(6~9 등).
--
-- opensamguk는 PHP `city.state`를 미포팅했었다(`supply_state`=PHP `supply`, `front_state`=PHP `front`만 —
-- `state`는 PHP에서 그 둘과 별개인 이벤트/재해 상태 컬럼이다). 엔진 RaiseDisaster.applyDisaster는 이미
-- in-memory city.state를 설정하지만 diffCity/flush/schema가 없어 영속되지 못했고(WorldActionContext 주석
-- "engine-only column not tracked by diffCity"), 그 결과 맵(WorldMapController가 state 자리에 front_state 대용)
-- 과 MapViewer `event<state>.gif` 렌더에 재해가 표시되지 않았다.
--
-- 기본 0(평시). RaiseDisaster는 매월 `state <= 10`을 0으로 리셋 후 당월 재해 도시만 stateCode를 쓴다(1개월 transient).
ALTER TABLE city ADD COLUMN state integer NOT NULL DEFAULT 0;
