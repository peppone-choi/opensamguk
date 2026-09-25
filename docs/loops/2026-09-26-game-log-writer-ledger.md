# 게임 사건 작성 경로 대조표

기준: ADR-LITE-069, 사건 모델 PR #955의 `EventKind`, 2026-09-26 `origin/main`의 생산자. 새 쓰기 변경은 이 브랜치에서 준비하되 세계 형식 가드 PR #917이 소유한 공유 런타임 파일은 main 병합 뒤 다룬다.

## 단일 저장 경로

현재 `InMemoryTurnWorld.pushLog`가 `LogEntryDraft`를 메모리 목록에 놓고 `consumeDirtyState()`에서 비운다. `DatabaseHooks`의 두 `toFlushPayload` 경로가 각각 `dirty.logs`를 `LogRow`로 바꾸고, `JdbcFlushExecutor.logEntryCreateMany`가 같은 flush 트랜잭션에서 `log_entry`에 넣는다. 새 사건도 **동일한** `ChangeRecorder → DirtyState → DatabaseHooks → FlushPayload → JdbcFlushExecutor` 경로를 써야 한다. 엔진 생산자가 JDBC를 직접 호출하지 않는다.

`game_event`의 `(world_id,occurred_year,occurred_month,occurred_phase,occurred_ordinal)`은 그 순에 발생한 **모든** 작성자의 순서를 공유한다. `pushEvent` 목록의 인덱스만 쓰면 같은 순에서 flush가 여러 번 일어날 때 다시 0부터 시작한다. 다음 단계는 부팅 시 기존 최대 ordinal을 로드하고 메모리 카운터를 이어 쓰거나, flush 경계가 순마다 단 한 번임을 테스트로 증명해야 한다. `eventKey`는 월드·순·생산자·안정 원인 ID·해당 원인의 결과 순번으로 만들고, 문장이나 현재 표시 이름을 해시에 넣지 않는다. 재실행 시 동일 키와 동일 ordinal을 얻는 테스트가 필요하다.

## 현재 kind가 있는 휘하 생산자

| 생산자 | 새 사건 | 필요한 구조화 값과 수신 범위 |
| --- | --- | --- |
| `AssignmentMarchTurn`, `TravelHandler`, `PersonalEncounter`의 이동 결과 | `march.assignment` / `march.direct` | 장수·도착 城 ID; SELF. 이동 전술 경로는 공개하지 않는다. |
| `CorpsMarchTurn`, `DeployHandler`, `MusterHandler` | `march.corps` / `deploy.started` / `military.musterOrdered` | 안정 군단·도착 城 ID; SELF 또는 현재 권한을 검증한 RETINUE/NATION. |
| `CorpsEncounterRecorder`, `EncounterResolver`, `PersonalEncounter` | `encounter.pending` / `encounter.disbanded` / `encounter.personal` | 자기 참가자·장소 ID와 허용된 replay ID만; 참가자 쪽 비공개. |
| `DispatchExecutor` | `court.dispatch*` 다섯 결과 | 같은 요청 ID, 발신/수신 ID, 사건 당시 허용 수신자 snapshot; 발령 본문·목적을 천하에 공개하지 않는다. |
| `EnlistmentExecutor`, `PeopleHandler` | `enlist.joined`, `enlist.retainerJoined`, `people.searched/joined/resisted` | 출사 세력 ID·관계 대상 ID; SELF/RETINUE. 거절 대상은 당사자 범위만. |
| `FieldHandler`, `CityMilitaryHandler`, `DirectActionHandler`, `TransferHandler`, `PersonalHandler`, `PoliticalHandler`, `StratagemActionExecutor`, `CourtActionExecutor`, `RetireHandler` | `field.applied` / `personal.applied` | 행동 종류를 서술 text 대신 별도 안정 결과 코드로 분리할 필요. 현재 범용 kind의 임의 문장을 그대로 유실시키지 않도록 결과별 kind/fact 감사. SELF. |
| `CourtHandler`, `TravelTurn`, `UnitResupply`, `PersonalEncounter` 실패 | `input.rejected` | 안정 거절 코드가 현재 `reason.message` 평문을 대신해야 한다. SELF, 비밀 원인 투영 금지. |
| `MonthlyCountyIncome` | `income.monthly` | 세력 내부 `COUNTIES/MONEY/GRAIN/IRON/TIMBER/HORSES` facts. NATION만, 세계·연감에는 없음. |
| `MonthlyAssessment` | `yuedan.assessed`, `retinue.departureJudged`, `retinue.departed`, `yuedan.announced` | 개인 점수 전/후/변화와 이탈자 ID는 SELF. 공개 발표는 안전한 별도 WORLD 사건. |
| `RenownEventRecorder` | `renown.event`, 공개 `county.ownerChanged` | 전공 변화는 SELF, 縣 소유권은 이전/새 세력과 城 ID로 한 건만 PUBLIC. 현 `county.captured` + `county.lost` 두 국가 행을 복사하지 않는다. |
| `SiegeService` | `roadFort.siege` 및 결산 결과 추가 필요 | 참가/관할자의 전장 사건, 공개 영토 변화는 별도 승인된 투영만. |

## kind 없는 휘하 평문 생산자

| 생산자 | 잠정 새 종류/판정 | 감사할 항목 |
| --- | --- | --- |
| `MonthlySalary` | 휘하 급료 결산 | 주인/이탈 인물별 수신, 급료·부족액은 비공개 facts. `retinue.departed`와 중복 여부. |
| `DomesticTurn`, `DomesticBoundary`, `PlacementMarch` | 현장/부임 성공·실패 | 기존 `field.applied`/`march.assignment`에 대응 가능한지와 각 거절 코드. |
| `ScoutHandler` | 정찰 결과 | #343 시야 계약, 적 병력·첩보 정보를 PUBLIC·타 세력에 0건. |
| `RewardExecutor` | 포상 수여 결과 | COURT 공문 수신자와 SELF 당사자 투영 분리. |
| `EncounterResolver.log`, `SiegeService.log` | 전투/공성/조우 결산 | 결산 kind·replay 권한·양측 안전 투영, `roadFort.siege` 시작과 중복 여부. |
| `CapitalAfterCapture` | 수도 이전/점령 후 조정 | 지도에 나타나는 공개 사실과 내부 후속 조치 분리. `county.ownerChanged`와 이중 발표 금지. |

## 공유·레거시 생산자와 파일 소유

`ActionLogger`의 개인/국가/세계 text 버퍼, `ProcessNationCommand`, `ReservedTurnHandler`, `WorldActionContext`, `MonthlyPostUpdateHook`, `RulerSuccessionHandler`, `MakeGeneralHandler`·입장/즉시 행동, `OperationMonthlyService`, `BattlefieldTurnHandler`, 논리 이벤트·시나리오, 경매/토너먼트/베팅, `AiTurnAdapter`가 나머지 주요 작성 경계다. 설계 원장의 부록 A에 호출부 전수 목록이 있다. 각 경로가 새 세계에서도 실행되는지 코드 정리 결과와 입력 원장으로 먼저 판단한다. 살아 있는 월 경계·전투·가입 결과를 단순 삭제하지 않는다.

첫 writer PR의 소유 파일은 새 `EventRecorder`/row mapper·테스트와 휘하 전용 생산자다. `TurnWorldModel.kt`, `InMemoryTurnWorld.kt`, `DirtyState.kt`, `ChangeRecorder.kt`, `DatabaseHooks.kt`, `JdbcFlushExecutor.kt`, `FlushPayload` 및 월 경계 생산자는 #917 세계 가드 병합 뒤 최신 main에서 수정한다. 공개 연감/행정 오버레이와 이름 핀은 별도 구현과 계약을 맞춘다.

## 쓰기 관문

1. 새 사건용 생산자 파일에서 `text=`, 색 태그, `<1>` 날짜, 임의 문장 합성을 금하는 검사와 일부러 한 줄을 주입해 실패하는 음성 시험.
2. 월드·타국·비로그인 red fixture에서 수입·정찰·병력·발령이 공개 0건. 공개 점령 한 건은 세계 행 하나.
3. 같은 턴 재실행·중단/재개에서 eventKey·ordinal 중복 0건, 같은 순 내 순서 동일.
4. 가입·실패·성공·지연·월말·전투·이탈의 활성 입력 결과가 kind에 전수 대응. 런타임 writer 검색 결과가 0건이 되기 전 기존 경로를 제거하지 않는다.
