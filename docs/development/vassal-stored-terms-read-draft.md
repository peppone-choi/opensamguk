# 봉신 저장 조건 HTTP 조회

`GET /api/court/vassals?generalId=<actor>`는 저장된 계약 조건을 먼저 제공한다. K8과 합의한 `PARTIAL` 응답이며 활성 계약·설립 선택지·원군 요청의 producer는 아직 제공하지 않는다. 구현은 `VassalController → VassalHttpQuery → VassalStoredTermsQuery → VassalStoredTermsReader`다.

## 인증과 저장 원천

검증된 JWT 계정으로 `GeneralResolver`가 해석한 살아 있는 본인 장수만 actor가 된다. 쿼리의 userId나 ADMIN 역할로 actor 소유 확인을 대체하지 않는다. 인증 부재는 HTTP401 `AUTH_REQUIRED`, 본인 장수가 아니면 HTTP403 `FORBIDDEN`이다. 성공·이 두 오류 모두 `Cache-Control: no-store`다.

처리 월드, actor의 현재 세력과 정확한 `game_kv(game_env, game_env, vassalContracts)`를 REPEATABLE_READ 읽기 트랜잭션에서 조회한다. 다른 namespace·월드 meta로 부재를 덮지 않는다. 같은 세력으로 저장된 계약만 반환하며 종료 기록도 보존한다. 읽기는 writer·접수·활동 권한을 만들지 않는다.

## 응답 계약

| 필드 | 원천과 의미 |
| --- | --- |
| `status` | 저장 조건 READY이면 `PARTIAL`, 그 밖은 `UNAVAILABLE` |
| `now` | 실제 world_state 연월순. 미확인 시 명시 null |
| `contractsStatus` | `READY / NOT_SEEDED / UNAVAILABLE`. 정상 codec1 빈 상태만 READY 빈 목록. 미생산·손상·범위 불명은 빈 계약 확정이 아님 |
| `contracts[]` | 원본 ID·봉토·상납률·원군 의무·자율권·외교권·충성·Long 시점·계약별 영수증. 자율권은 이름순, 영수증은 연월순 |
| `vassalName` | 같은 현재 월드·세력의 장수 행에서 확인한 이름. 빈 이름·미확인은 명시 null |
| `isHuman / isHumanStatus` | 같은 장수 행의 계정 ID와 현재 NPC 상태가 일치하면 true/false 및 READY. 누락·모순·형식 불명은 null 및 UNAVAILABLE. 이름으로 추정하지 않음 |
| `calendarStatus` | `UNAVAILABLE`. legacy Long의 epoch가 미확인이라 `signedAt / expiresAt / endedAt`은 null. `signedTurn / expiresTurn / endedTurn` 원본은 보존 |
| `monthlyTribute` | 실제 now의 연월에 해당하는 영수증만 읽음. 아래 상태 구분 참조 |
| `reinforcementResponse` | 요청 원천 미제공으로 UNAVAILABLE, requestId/dueTurn/dueAt은 null |
| `activityStatus` | UNAVAILABLE. 저장 READY를 활성·명령권으로 승격하지 않음 |
| `foundingOptionsStatus / foundingOptions` | UNAVAILABLE 및 빈 목록. 소비자는 선택지를 대기/숨김 처리하며 후보 없음 확정으로 표시하지 않음 |

`monthlyTribute.status`는 5자원 미납 중 하나라도 양수면 UNPAID, 미납0·청구0이면 ZERO_DUE, 미납0·청구 양수면 PAID다. 당월 영수증이 없으면 NO_RECEIPT 및 receipt=null이다. ZERO_DUE는 계약의 무의무 판정이 아니다. 실제 정산 producer와 의무 판단이 없으므로 obligationStatus는 항상 UNAVAILABLE이다. codec의 중복 월·음수·보존식 위반·합산 overflow는 전체 조건 UNAVAILABLE로 반환한다.

## 검증 범위

`app/game-api/src/test/resources/court/vassal/`의 11개 고정 응답을 실제 Spring/JWT/controller/query/reader 경로에서 비교한다. 인증·소유·월드/세력 범위와 손상 영수증도 검증한다. 저장소는 대역이며 실제 JDBC snapshot·flush·재기동·운영 검증을 대신하지 않는다. 실행 결과는 메타 작업 보고서와 정상 CI에서 별도로 기록한다.
