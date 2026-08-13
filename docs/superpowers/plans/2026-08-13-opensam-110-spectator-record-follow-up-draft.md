# OPENSAM-110 follow-up draft — 관전·기록 read model

- 상태: `DRAFT / NOT APPROVED`
- 입력: [타 삼국지 게임 조사](../research/2026-08-13-opensam-110-other-three-kingdoms-games.md)
- 구현·티켓·release 승인: 없음

## 사용자 가치

게임에 참여하지 않는 관전자와 과거 기록을 보는 사용자가, 서버 권위 상태를 바꾸거나
허용되지 않은 정보를 보지 않으면서 세계의 주요 전쟁·외교·턴 진행을 이해한다.

## 제안 범위

1. `live summary`, `authorized detail`, `archive replay` 세 projection의 visibility와 retention을
   분리한다.
2. 모든 projection은 committed `worldVersion`과 event ID/cursor를 운반한다.
3. reconnect는 cursor 이후 event 또는 bounded snapshot+tail로 복구하며, 유실을 조용히
   현재 상태로 덮지 않는다.
4. Slow/Fast/Skip은 클라이언트 presentation 정책이다. daemon cadence와 simulation clock을
   바꾸지 않는다.
5. replay는 deterministic re-execution인지 materialized chronicle인지 먼저 명명하고,
   두 의미를 하나의 API로 섞지 않는다.

## 비범위

- v1 `turnCompleted` 의미 변경
- fog-of-war·첩보 규칙 자체의 신규 설계
- 공개 spectator 채팅, betting, monetization
- production activation 또는 기존 archive backfill

## 초안 AC

- Given 동일 committed version, when live summary와 authorized detail을 조회하면, then 두
  projection은 같은 world identity/version을 가리키되 visibility field set은 명시적으로 다르다.
- Given 허가 없는 관전자, when hidden general/order field를 요청하면, then field omission 또는
  typed deny가 발생하고 placeholder 값으로 존재를 누설하지 않는다.
- Given SSE reconnect cursor가 retention 안에 있으면 ordered event를 중복 없이 재개하고,
  retention 밖이면 typed reset과 snapshot version을 돌려준다.
- Given UI가 Skip을 선택해도 daemon tick, RNG draw, flush order, committed world version은 같다.
- replay 파일/record의 보존 기간, 삭제, export, schema version과 backward-read 경계를 문서화한다.

## 선행 결정

- v1/v2 surface와 world/profile authorization 경계
- archive retention 및 개인정보·비공개 외교 노출 정책
- `event replay`와 `historical record projection` 중 첫 delivery의 의미
- OPENSAM-139 minVersion barrier를 spectator read에도 적용할지 여부

## 예상 비용·위험

- CQRS 비용: **중간** — 새 read projection, cursor/retention, visibility test가 필요하다.
- 위험: reconnect 중복·누락, hidden-state leakage, hot world read amplification, replay schema drift.
- 채택 전 독립 architecture review와 two-world/same-local-ID visibility fixture가 필요하다.
