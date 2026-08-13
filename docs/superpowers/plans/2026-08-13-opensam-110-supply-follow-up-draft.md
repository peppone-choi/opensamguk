# OPENSAM-110 follow-up draft — 보급 shipment·forecast

- 상태: `DRAFT / NOT APPROVED`
- 입력: [타 삼국지 게임 조사](../research/2026-08-13-opensam-110-other-three-kingdoms-games.md)
- 구현·티켓·release 승인: 없음

## 사용자 가치

플레이어가 전선의 보급 부족을 결과가 난 뒤에만 알지 않고, 어느 도시에서 무엇이 출발해
언제 도착하며 어떤 경로·교란 때문에 지연되는지 사전에 판단한다.

## 제안 범위

1. `SupplyShipment`를 `planned → in_transit → delayed|interdicted → arrived|cancelled`의
   versioned state machine으로 정의한다.
2. `SupplyForecast`는 생산·가용 재고·수송 중 물량·소비율·예상 고갈 시점과 reason code를
   가진 read projection이다.
3. route cut, 포위, 기근, 약탈, 아사, 탈영은 서로 다른 원인 event/delta로 남긴다.
4. 모든 allocation/dispatch/arrival은 server-authoritative intent→resolve이며 client clock으로
   ETA를 판정하지 않는다.
5. v1 rice/battle/monthly semantics를 변경하지 않는 v2 namespace와 feature gate에만 둔다.

## 비범위

- 실제 convoy tactical renderer
- route pathfinding 알고리즘 선택
- v1 city/general rice migration
- 경제 전체 재설계, 자동 균등 분배, production activation

## 초안 AC

- Given 동일 snapshot/version/order/seed, when shipment를 resolve하면, then 같은 ordered delta와
  forecast reason을 만든다.
- shipment 생성·지연·교란·도착은 `ChangeRecorder → JdbcFlushExecutor` 한 write truth만 사용한다.
- restart 후 in-transit shipment의 위치/remaining term/owner/route/version이 lossless하게 복구된다.
- route가 끊기면 shipment 또는 소비 상태가 typed transition을 만들며 resource를 즉시 0으로
  만드는 hidden shortcut이 없다.
- 같은 local shipment ID를 가진 두 world가 read/write/flush/restart에서 섞이지 않는다.
- forecast가 stale하면 committed version/minVersion을 노출하고 성공처럼 오래된 값을 반환하지
  않는다.

## 선행 결정

- v2 world identity와 first persistent leaf의 activation 순서
- tactical convoy와 strategic shipment의 aggregate 경계
- route graph versioning, ETA 단위와 월/순/fixed-tick cadence
- hidden supply 정보와 allied sharing policy

## 예상 비용·위험

- CQRS 비용: **중간~높음** — aggregate, flush/rehydrate, route version, projection이 모두 필요하다.
- 위험: duplicate arrival, double spend, stale forecast, restart loss, cross-world collision,
  convoy battle와 strategic settlement의 이중 판정.
- foundation-first로 identity/state machine을 먼저 만들고 renderer/AI는 후속 consumer로 둔다.
