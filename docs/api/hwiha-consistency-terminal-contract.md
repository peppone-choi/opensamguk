# HWIHA 입력·발령 terminal/consistency 원장

> #270 / OPENSAM-124의 **DRAFT 정적 절편**. 관측 기준 `0ce1bd1185b6730dd9152194b292959cd1752ca5`.
> 현재 사실·계약 목표·NOT_DELIVERED·UNKNOWN을 구분한다. 이 문서는 런타임 수정, 계약 승인, 제품 QA 통과를 뜻하지 않는다.

## 1. 읽는 법과 truth 경계

- catalog schema5 DRAFT 97행: GENERAL_ACTION 43, COURT_DECISION 37, STRATAGEM 13, WORK 2, PLACEMENT 1, POLICY 1.
- 입력6종과 family는 일대일이 아니다. F2는 배치/방침을 묶고 조정은 F5/F6으로 나눈다. F7은 요청이 아닌 발령 상태 전이이다.
- 현재 봉투 `CommandLifecycleResult.type`은 `reservationAccepted`, `reservationCancelled`, `queueMutation`, `executionApplied`, `executionRejected`. **`reservationRejected` 생산자는 없다.** 대문자 terminal 어휘는 계약 목표 축이며 현재 wire 값이 아니다.
- API 접수 전 HTTP/BLOCKED 거절과 durable result를 혼동하지 않는다. 접수 성공은 실행 성공이 아니며 PLANNED는 제공 가능이 아니다. UI_READY/HANDLER_READY 역시 이 원장이 제품 QA 성공을 증명하지 않는다.
- 현행은 `command_inbox` 선기록·claim과 durable `CommandResultRow`(seq1 접수, seq2 예약/조정 실행, `committedWorldVersion`), outbox relay를 갖는다. 원 구계약의 Redis-only baseline을 현행 사실로 재사용하지 않는다.
- durable truth는 primary의 scoped inbox/result/state/outbox. Redis/SSE는 cache/알림이며 durable truth를 대신하지 않는다. 현행 조회가 Redis 우선이라는 사실과 계약의 권위 목표를 구분한다.
- `consistencyClass`: 변경 요청은 `Mutation`, 결과 조회는 현 classifier의 `RYW`. `NON_STATE`는 이 원장에서 HTTP read/요청 terminal 분류가 적용되지 않는 표면(F7, sandbox)을 뜻하며 상태가 비영속이라는 뜻은 아니다. `AUTHORITATIVE`/`EVENTUAL`은 허용되는 read 분류이나 이 절편에서 새로 부여하지 않는다.

## 2. 접수 → 실행/효력 → 조회

| Family / 현재 상태 | 접수 terminal (seq1) | 실행/효력 terminal | 무효·관측 | 조회 / 목표와 차이 |
|---|---|---|---|---|
| F1 GENERAL_ACTION: 제공 40, PLANNED 3은 NOT_DELIVERED | `reservationAccepted`; 사전 거절 HTTP/BLOCKED | 자기 턴 seq2 `executionApplied`/`executionRejected`, `InputResolved`; 빈 슬롯 NoAction | 실행 무효는 code/reason + `input.rejected`, 무비용 구현 의도 | RESULT_READ의 RYW. 예약 접수는 PENDING; 접수≠실행 |
| F1 유지 작업: 취소/push/repeat | `reservationCancelled`/`queueMutation` 자체 durable terminal | 유지 작업에 후속 실행 terminal 없음 | 취소가 옛 요청의 executionRejected를 뜻하지 않음 | QUEUE_MUTATION은 RESOLVED; 개별 예약 입력과 별개 |
| F2 PLACEMENT/POLICY: 2 | `reservationAccepted`, engine 거절 `executionRejected` | **요청 terminal은 접수에서 끝남**. 다음 카드 턴 효력은 상태 전이 | 효력 거절은 상태/장수 로그 | RYW 조회는 commandKind가 pending 집합 밖이므로 RESOLVED; 효력 완료 아님 |
| F3 WORK: 2 | F2와 같음 | 다음 순 경계부터 진척, 요청 실행 terminal 없음 | 상태/장수 로그 | F2와 같음 |
| F4 STRATAGEM: 13 전부 NOT_DELIVERED | API 사전 거절 또는 engine `executionRejected`/NOT_DELIVERED | 현재 실행 가능 terminal 없음 | 잠재 큐 경로는 제공 증거 아님 | CARD_TRIGGER catalog와 결정권자 큐/발동 목표를 구분; G3 |
| F5 COURT_DECISION 대기형: 6 | `reservationAccepted`; engine 접수 거절은 `executionRejected` | 결정권자 턴 seq2 `executionApplied`/`executionRejected` + InputResolved | 미제공 큐는 제거·input.rejected. 소유권/구금 거절은 결과 code/reason; 모든 분기의 장수 기록 보장 없음 | RYW, 예약 접수 PENDING; G1 |
| F6 조정 즉시형: 3 | seq1 `executionApplied`/`executionRejected`, 장수 행동 미소모 경로 | 별도 실행 단계 없음 | 접수 전 HTTP/BLOCKED와 engine 결과 구분 | RYW, RESOLVED; catalog DECISION_TURN과 G6 |
| F7 발령 상태 (요청 아님) | PENDING | ACCEPTED / REFUSED / CANCELLED | 만료 자동 수락 `lapsed=true`; 관계/현 무효는 벌점 없이 취소 | 자동 만료는 새 requestId/result 행 없음. 사건·장수 기록으로 관측 |
| NOT_DELIVERED: 행동3 + 조정28 | 미제공 거절. 202·durable 거절 행을 일괄 보장하지 않음 | 없음 | PLANNED를 성공·실행 무효로 분류하지 않음 | G1과 분리 |
| SANDBOX | `/api/v2/commands/{commandId}(/precheck)`는 profile/property gate | live 입력 원장 아님 | sandbox 활성화/제품 제공 보장 없음 | UNKNOWN, production 의미 미확정 |
| RESULT_READ | GET `/api/command/result/{requestId}` | 새 terminal 생성 안 함 | 소유자 확인 불가·손상·키 부재는 PENDING. RESOLVED ok=false는 실패 | 현행 RYW, Redis 우선 + durable fallback. GA-075 목표 AUTHORITATIVE와 G2 |

F5의 여섯 행은 `court.dispatch`, `court.reward`, `court.releaseCorps`, `court.abandonCounty`, `court.moveCapital`, `court.offerPeace`다. `CourtHandler.handleKnown`에서 dispatch/reward는 각각 전용 큐, 나머지 넷은 `CourtInput.INPUT_IDS`→`QueuedCourtAction`→`reservationAccepted`로 간다. 아래 anchors는 `CourtRules`, `CourtExpansionInput`, `DiplomacyInput` 상수 연결까지 확인한다. F6 세 행은 `inputOverrides`에 명시한다.

F7은 `DispatchExecutor`가 대상 meta의 `DispatchState`를 갱신하고 `DISPATCH_RECEIVED/ISSUED/ACCEPTED/REFUSED/CANCELLED` 사건(Court audience)과 장수 기록을 남긴다. 발행자의 요청 성공은 발령 발행 성공이지 대상자의 수락 성공이 아니다. 기한 구현값 12순·거절 충성 감소5는 관측값이며 설계 §2.4 기한 수치 미정을 이번 문서로 승인하지 않는다. 명시 응답과 자동 만료 모두 개인 12순 예약을 소비하는 경로가 아니다.

무비용·사유 기록은 계약 목표와 실제 경로의 정적 근거를 적은 것이며, 비용 미차감의 모든 입력/실패 조합을 실기 검증한 주장이 아니다. 특히 F5 소유권 변경·구금 분기에는 공통 `input.rejected` 호출이 없다. 원 계획의 포괄적 표현을 현행 보장으로 승격하지 않는다.

## 3. 알려진 간극 (이번 PR은 수정하지 않음)

| ID | 현행 | 목표 / 후속 후보 | 미정 |
|---|---|---|---|
| G1 | Court/Domestic/계책 접수 거절이 seq1 executionRejected | 접수/실행 거절 축 분리. CourtHandler/DomesticHandler + FE 폴링 별도 | 아니오 |
| G2 | 결과 classifier RYW·Redis 우선 | GA-075 AUTHORITATIVE와 정합화. classifier/controller 별도 | 아니오 |
| G3 | pending 집합에 STRATAGEM 없음 | 제공 전 수정. 현재 13행 PLANNED로 도달 불가 | 아니오 |
| G4 | 자동 만료에 요청 terminal 없음; 구현 기한12/충성감소5 | 사건/상태 전이와 요청 구분. 기한 정책 별도 기획 | 기한 수치 정책 UNKNOWN |
| G5 | 구계약 §3 Redis-only 설명과 §6.3 86/31/14는 낡은 기준선 | 전체 HWIHA endpoint/event 재추출 별도 | 전체 manifest UNKNOWN |
| G6 | F6 catalog DECISION_TURN이나 실제 즉시 적용 | PR1608 소유 해제 뒤 catalog 정합화 별도 | 아니오 |

현행 같은 정규식 `^\s+@(Get|Post|Put|Patch|Delete|Request)Mapping` 집계는 game-api **167**, gateway+engine **60**(56+4)이다. 이는 class mapping/중첩 prefix를 합성한 exact manifest가 아니며, 기존 86/31/14 gate를 변경/충족시켰다는 뜻이 아니다. 이 검사기의 변경 경로 인벤토리는 합성된 game-api non-GET **33건**만 별도로 비교한다.

## 4. 기계 판독 계약과 검사 범위

`python3 tools/ci/terminal_inventory.py --check [--root .] [--json]`

- R1: explicit `inputIds` snapshot과 (`kind`, `timing.phase`, `deliveryState`)가 모두 일치해야 한다. 새 inputId도 검토 없이 기존 family로 흡수하지 않는다. override는 정확히 하나이며 지정 family에 실제 회원이어야 한다. PLANNED는 NOT_DELIVERED에만 대응한다.
- R2: 새 kind/phase는 실패. F7/SANDBOX/RESULT_READ는 입력 회원이 없고 입력6종을 추가하지 않는다.
- R3: game-api의 모든 non-GET 매핑이 family routes 또는 사유 있는 `outOfScopeMutationRoutes`에 정확히 한번 대응한다. 공유 court 경로는 F5가 소유한다. 리터럴 경로·동일 파일의 리터럴 const·class prefix·배열·RequestMethod 목록을 읽고 지원하지 않는 변경 매핑 문법은 실패한다. Kotlin 실행/컴파일이나 모든 read/event의 완전한 분류를 주장하지 않는다.
- R4: source 경로와 literal anchor 확인. 줄번호를 사용하지 않는다. 무관한 줄 이동은 허용하되 리터럴 존재만으로 의미적 정확성까지 증명하지 않는다.
- R5: 필수 키/형식·class/status·terminal 부재 사유를 확인한다. `status`는 CURRENT/TARGET/NOT_DELIVERED/UNKNOWN이고 실행 type이 null일 때도 사유가 필수다.
- 결정적 출력, stdlib 전용, 네트워크/DB/Gradle/저장소 쓰기 없음. unittest는 임시 fixture만 사용하며 기존 `tools/ci/test_*.py` discover로 실행된다. 이 변경은 ci.yml이나 gate/보호규칙을 수정하지 않는다.

아래 원장은 관측 snapshot이다. catalog/경로 변경 시 체크 실패를 숨기거나 자동 재생성으로 덮지 말고 현재 의미·gap을 재검토한다. 전 API/event 원장, GA-079/W3 활성화, one-daemon-write·replica 규칙, 계약 DRAFT 해제(원문 reviewer 2인), 제품 QA는 이 절편으로 완료되지 않는다.

<!-- HWIHA-TERMINAL-LEDGER:BEGIN -->
```json
{
  "schemaVersion": 1,
  "observedBase": "0ce1bd1185b6730dd9152194b292959cd1752ca5",
  "families": [
    {
      "id": "F1",
      "inputIds": [
        "action.abdicate",
        "action.assault",
        "action.boostMorale",
        "action.commerce",
        "action.conscript",
        "action.convertProficiency",
        "action.demandSurrender",
        "action.demobilize",
        "action.deploy",
        "action.donate",
        "action.employ",
        "action.enlist",
        "action.farm",
        "action.forcedMarch",
        "action.fortify",
        "action.foundState",
        "action.gift",
        "action.move",
        "action.muster",
        "action.oath",
        "action.persuadeCaptive",
        "action.raiseVolunteers",
        "action.recuperate",
        "action.repairWall",
        "action.resign",
        "action.return",
        "action.rise",
        "action.scout",
        "action.search",
        "action.security",
        "action.selectResidents",
        "action.selfTrain",
        "action.settle",
        "action.siegeRoadFort",
        "action.tour",
        "action.tradeEquipment",
        "action.tradeGrain",
        "action.train",
        "action.transport",
        "action.travel"
      ],
      "kinds": [
        "GENERAL_ACTION"
      ],
      "phases": [
        "POLITICS",
        "MOVE",
        "FIELD",
        "SIEGE"
      ],
      "deliveryStates": [
        "HANDLER_READY",
        "UI_READY"
      ],
      "routes": [
        {
          "method": "POST",
          "path": "/api/command/{code}",
          "controller": "app/game-api/src/main/kotlin/opensamguk/gameapi/web/CommandController.kt"
        },
        {
          "method": "POST",
          "path": "/api/command/bulk",
          "controller": "app/game-api/src/main/kotlin/opensamguk/gameapi/web/CommandController.kt"
        },
        {
          "method": "POST",
          "path": "/api/command/push",
          "controller": "app/game-api/src/main/kotlin/opensamguk/gameapi/web/CommandController.kt"
        },
        {
          "method": "POST",
          "path": "/api/command/repeat",
          "controller": "app/game-api/src/main/kotlin/opensamguk/gameapi/web/CommandController.kt"
        },
        {
          "method": "DELETE",
          "path": "/api/reserved-commands",
          "controller": "app/game-api/src/main/kotlin/opensamguk/gameapi/reserve/ReservationCancelController.kt"
        }
      ],
      "admission": {
        "currentTypes": [
          "reservationAccepted",
          "reservationCancelled",
          "queueMutation"
        ],
        "target": "예약은 RESERVATION_ACCEPTED/REJECTED. 취소·push/repeat는 별도 유지 작업 terminal."
      },
      "execution": {
        "currentTypes": [
          "executionApplied",
          "executionRejected"
        ],
        "reason": "예약 입력의 실행은 resultSeq=2. 취소/queueMutation은 유지 작업 자체의 seq1로 끝나며 옛 입력에 executionRejected를 새로 만들지 않는다."
      },
      "effectTiming": "12순 예약→자기 턴 실행. 빈 슬롯은 NoAction.",
      "invalidation": "실행 조건 불일치는 executionRejected + code/reason + input.rejected. 무비용은 구현 의도/분기의 정적 근거이며 전 입력 제품 QA는 미실시.",
      "consistencyClass": "Mutation",
      "status": "CURRENT",
      "anchors": [
        {
          "path": "app/game-api/src/main/kotlin/opensamguk/gameapi/reserve/CommandReserveService.kt",
          "literal": "type = \"reservationAccepted\""
        },
        {
          "path": "app/game-api/src/main/kotlin/opensamguk/gameapi/reserve/CommandReserveService.kt",
          "literal": "commandResults.insertTerminalResult("
        },
        {
          "path": "app/game-api/src/main/kotlin/opensamguk/gameapi/reserve/CommandQueueService.kt",
          "literal": "type = \"queueMutation\""
        },
        {
          "path": "app/game-api/src/main/kotlin/opensamguk/gameapi/reserve/ReservationCancelRepository.kt",
          "literal": "CommandLifecycleResult(\"reservationCancelled\", true, \"QUEUE_MUTATION\""
        },
        {
          "path": "app/game-engine/src/main/kotlin/opensamguk/engine/turn/ReservedTurnHandler.kt",
          "literal": "inputOutcome = TurnOutcome.NoAction"
        },
        {
          "path": "app/game-engine/src/main/kotlin/opensamguk/engine/turn/ReservedTurnHandler.kt",
          "literal": "실행 시점에 조건이 맞지 않는 입력은 비용 없이 무효"
        },
        {
          "path": "app/game-engine/src/main/kotlin/opensamguk/engine/turn/ReservedTurnHandler.kt",
          "literal": "RecordKind.INPUT_REJECTED"
        },
        {
          "path": "app/game-engine/src/main/kotlin/opensamguk/engine/run/TurnRunService.kt",
          "literal": "resultSeq = EXECUTION_RESULT_SEQ"
        },
        {
          "path": "infra/src/main/kotlin/opensamguk/infra/persistence/CommandInboxRepository.kt",
          "literal": "open fun insertAccepted(command: AcceptedCommand)"
        },
        {
          "path": "infra/src/main/kotlin/opensamguk/infra/persistence/CommandInboxRepository.kt",
          "literal": "open fun claimPendingForExecution("
        },
        {
          "path": "app/game-engine/src/main/kotlin/opensamguk/engine/run/TurnRunService.kt",
          "literal": "commandOutboxRelay?.publishPending()"
        },
        {
          "path": "app/game-engine/src/main/kotlin/opensamguk/engine/run/TurnRunService.kt",
          "literal": "committedWorldVersion = committedWorldVersion"
        }
      ]
    },
    {
      "id": "F2",
      "inputIds": [
        "placement.assign",
        "policy.set"
      ],
      "kinds": [
        "PLACEMENT",
        "POLICY"
      ],
      "phases": [
        "NEXT_CARD_TURN"
      ],
      "deliveryStates": [
        "HANDLER_READY",
        "UI_READY"
      ],
      "routes": [
        {
          "method": "POST",
          "path": "/api/commands/placement/{name}",
          "controller": "app/game-api/src/main/kotlin/opensamguk/gameapi/web/DomesticController.kt"
        },
        {
          "method": "POST",
          "path": "/api/commands/policy/{name}",
          "controller": "app/game-api/src/main/kotlin/opensamguk/gameapi/web/DomesticController.kt"
        }
      ],
      "admission": {
        "currentTypes": [
          "reservationAccepted",
          "executionRejected"
        ],
        "target": "접수 성공과 효력 발생을 구분한다. 목표 축은 접수/효력."
      },
      "execution": {
        "currentTypes": null,
        "reason": "seq1 접수 결과가 이 요청의 terminal. 효력 시점에 별도 요청 execution terminal을 쓰지 않는다."
      },
      "effectTiming": "카드의 다음 턴부터 효력.",
      "invalidation": "효력 시점 거절은 상태/장수 로그이며 원 requestId의 실행 결과가 아니다.",
      "consistencyClass": "Mutation",
      "status": "CURRENT",
      "anchors": [
        {
          "path": "app/game-engine/src/main/kotlin/opensamguk/engine/campaign/DomesticHandler.kt",
          "literal": "type = \"reservationAccepted\""
        },
        {
          "path": "app/game-engine/src/main/kotlin/opensamguk/engine/campaign/DomesticHandler.kt",
          "literal": "효력 시점의 거절은 그 상태 기록과 장수 로그에 남는다."
        },
        {
          "path": "app/game-api/src/main/kotlin/opensamguk/gameapi/web/CommandController.kt",
          "literal": "result.commandKind in setOf(\"RESERVED_TURN\", \"COURT_DECISION\")"
        }
      ]
    },
    {
      "id": "F3",
      "inputIds": [
        "work.reduce",
        "work.start"
      ],
      "kinds": [
        "WORK"
      ],
      "phases": [
        "NEXT_PHASE_BOUNDARY"
      ],
      "deliveryStates": [
        "HANDLER_READY",
        "UI_READY"
      ],
      "routes": [
        {
          "method": "POST",
          "path": "/api/commands/work/{name}",
          "controller": "app/game-api/src/main/kotlin/opensamguk/gameapi/web/DomesticController.kt"
        }
      ],
      "admission": {
        "currentTypes": [
          "reservationAccepted",
          "executionRejected"
        ],
        "target": "접수와 이후 공사 진척을 구분한다."
      },
      "execution": {
        "currentTypes": null,
        "reason": "F2와 같이 seq1로 요청 완료; 순 경계 진척은 상태 전이."
      },
      "effectTiming": "다음 순 경계부터 여러 순 진척. work.reduce도 Domestic 경로.",
      "invalidation": "효력 거절의 상태/로그와 요청 결과를 분리한다.",
      "consistencyClass": "Mutation",
      "status": "CURRENT",
      "anchors": [
        {
          "path": "app/game-engine/src/main/kotlin/opensamguk/engine/campaign/DomesticHandler.kt",
          "literal": "DomesticInput.REDUCE -> \"WORK\""
        },
        {
          "path": "app/game-engine/src/main/kotlin/opensamguk/engine/campaign/DomesticHandler.kt",
          "literal": "DomesticInput.WORK -> \"WORK\""
        },
        {
          "path": "app/game-engine/src/main/kotlin/opensamguk/engine/run/TurnRunService.kt",
          "literal": "opensamguk.engine.campaign.DomesticBoundary(world, handler.recorder, handler.domesticContext).run()"
        }
      ]
    },
    {
      "id": "F4",
      "inputIds": [
        "stratagem.falseReport",
        "stratagem.fire",
        "stratagem.flood",
        "stratagem.lastStand",
        "stratagem.mobilizePeople",
        "stratagem.play",
        "stratagem.provokeRivalry",
        "stratagem.raid",
        "stratagem.raiseMilitia",
        "stratagem.reciprocity",
        "stratagem.rumor",
        "stratagem.sabotage",
        "stratagem.steal"
      ],
      "kinds": [
        "STRATAGEM"
      ],
      "phases": [
        "CARD_TRIGGER"
      ],
      "deliveryStates": [
        "PLANNED"
      ],
      "routes": [
        {
          "method": "POST",
          "path": "/api/commands/stratagem/{name}",
          "controller": "app/game-api/src/main/kotlin/opensamguk/gameapi/web/StratagemActionController.kt"
        }
      ],
      "admission": {
        "currentTypes": [
          "executionRejected"
        ],
        "target": "목표: 접수/결정권자 턴 실행/발동 시점 구분. 설치·대응은 발동 전 비공개."
      },
      "execution": {
        "currentTypes": null,
        "reason": "13행 전부 PLANNED. API 사전 거절 또는 engine NOT_DELIVERED; 접수/실행 가능을 주장하지 않는다."
      },
      "effectTiming": "catalog=CARD_TRIGGER. CourtHandler에 잠재 대기열 분기가 있으나 현행 제품에서 도달 불가.",
      "invalidation": "미제공 거절은 성공 또는 실행 시점 무효가 아니다.",
      "consistencyClass": "Mutation",
      "status": "NOT_DELIVERED",
      "anchors": [
        {
          "path": "app/game-api/src/main/kotlin/opensamguk/gameapi/reserve/CourtAdmission.kt",
          "literal": "throw AdmissionDenied(\"NOT_DELIVERED\", \"아직 제공되지 않는 입력입니다.\")"
        },
        {
          "path": "logic/src/main/kotlin/opensamguk/logic/input/InputRegistry.kt",
          "literal": "InputRejection.NOT_DELIVERED"
        },
        {
          "path": "app/game-engine/src/main/kotlin/opensamguk/engine/campaign/CourtHandler.kt",
          "literal": "in StratagemInput.INPUT_IDS -> {"
        }
      ]
    },
    {
      "id": "F5",
      "inputIds": [
        "court.abandonCounty",
        "court.dispatch",
        "court.moveCapital",
        "court.offerPeace",
        "court.releaseCorps",
        "court.reward"
      ],
      "kinds": [
        "COURT_DECISION"
      ],
      "phases": [
        "DECISION_TURN"
      ],
      "deliveryStates": [
        "HANDLER_READY",
        "UI_READY"
      ],
      "routes": [
        {
          "method": "POST",
          "path": "/api/commands/court/{name}",
          "controller": "app/game-api/src/main/kotlin/opensamguk/gameapi/web/CourtController.kt"
        }
      ],
      "admission": {
        "currentTypes": [
          "reservationAccepted",
          "executionRejected"
        ],
        "target": "RESERVATION_ACCEPTED/REJECTED → EXECUTION_APPLIED/REJECTED."
      },
      "execution": {
        "currentTypes": [
          "executionApplied",
          "executionRejected"
        ],
        "reason": "takeExecutions→toCourtExecutionRows로 같은 flush에 resultSeq=2 기록."
      },
      "effectTiming": "결정권자 onIssuerTurn; 개인 행동을 대신하지 않는다.",
      "invalidation": "미제공 대기열은 제거 + input.rejected 기록. 소유권 변경/구금은 executionRejected이지만 모든 분기에 장수 input.rejected 기록은 없다. 무비용 목표를 전 분기 제품 검증 완료로 해석하지 않는다.",
      "consistencyClass": "Mutation",
      "status": "CURRENT",
      "anchors": [
        {
          "path": "app/game-engine/src/main/kotlin/opensamguk/engine/campaign/CourtHandler.kt",
          "literal": "\"court.dispatch\" -> {"
        },
        {
          "path": "app/game-engine/src/main/kotlin/opensamguk/engine/campaign/CourtHandler.kt",
          "literal": "RewardInput.INPUT_ID -> {"
        },
        {
          "path": "logic/src/main/kotlin/opensamguk/logic/input/RewardInput.kt",
          "literal": "const val INPUT_ID = \"court.reward\""
        },
        {
          "path": "app/game-engine/src/main/kotlin/opensamguk/engine/campaign/CourtHandler.kt",
          "literal": "in CourtInput.INPUT_IDS -> {"
        },
        {
          "path": "logic/src/main/kotlin/opensamguk/logic/input/CourtRules.kt",
          "literal": "val INPUT_IDS = CourtExpansionInput.INPUT_IDS + CourtResourceInput.INPUT_IDS +"
        },
        {
          "path": "logic/src/main/kotlin/opensamguk/logic/input/CourtExpansionInput.kt",
          "literal": "const val RELEASE_CORPS = \"court.releaseCorps\""
        },
        {
          "path": "logic/src/main/kotlin/opensamguk/logic/input/CourtExpansionInput.kt",
          "literal": "const val ABANDON_COUNTY = \"court.abandonCounty\""
        },
        {
          "path": "logic/src/main/kotlin/opensamguk/logic/input/CourtExpansionInput.kt",
          "literal": "const val MOVE_CAPITAL = \"court.moveCapital\""
        },
        {
          "path": "logic/src/main/kotlin/opensamguk/logic/input/DiplomacyInput.kt",
          "literal": "const val OFFER_PEACE = \"court.offerPeace\""
        },
        {
          "path": "logic/src/main/kotlin/opensamguk/logic/input/CourtRules.kt",
          "literal": "DiplomacyInput.INPUT_IDS + INSTITUTION"
        },
        {
          "path": "app/game-engine/src/main/kotlin/opensamguk/engine/campaign/CourtHandler.kt",
          "literal": "fun onIssuerTurn(generalId: Int)"
        },
        {
          "path": "app/game-engine/src/main/kotlin/opensamguk/engine/campaign/CourtHandler.kt",
          "literal": "fun takeExecutions(): List<CourtExecution>"
        },
        {
          "path": "app/game-engine/src/main/kotlin/opensamguk/engine/run/TurnRunService.kt",
          "literal": "handler.courtHandler.takeExecutions().toCourtExecutionRows(committedWorldVersion)"
        }
      ]
    },
    {
      "id": "F6",
      "inputIds": [
        "court.dispatchReply",
        "court.politicalConsent",
        "court.releaseCaptive"
      ],
      "kinds": [
        "COURT_DECISION"
      ],
      "phases": [
        "DECISION_TURN"
      ],
      "deliveryStates": [
        "HANDLER_READY",
        "UI_READY"
      ],
      "routes": [],
      "admission": {
        "currentTypes": [
          "executionApplied",
          "executionRejected"
        ],
        "target": "즉시 APPLIED/REJECTED. catalog DECISION_TURN과 실제 즉시 경로의 G6 간극."
      },
      "execution": {
        "currentTypes": null,
        "reason": "immediate intake seq1에서 적용/거절하므로 별도 seq2 없음. 경로는 F5와 공유하며 중복 계수하지 않는다."
      },
      "effectTiming": "court.dispatchReply / court.releaseCaptive / court.politicalConsent는 접수 즉시 실행.",
      "invalidation": "API 접수 전 거절은 HTTP/BLOCKED. engine 거절은 seq1 executionRejected.",
      "consistencyClass": "Mutation",
      "status": "CURRENT",
      "anchors": [
        {
          "path": "app/game-engine/src/main/kotlin/opensamguk/engine/campaign/CourtHandler.kt",
          "literal": "\"court.dispatchReply\" -> {"
        },
        {
          "path": "app/game-engine/src/main/kotlin/opensamguk/engine/campaign/CourtHandler.kt",
          "literal": "when (val applied = executor.reply(request))"
        },
        {
          "path": "logic/src/main/kotlin/opensamguk/logic/input/CaptiveState.kt",
          "literal": "const val INPUT_ID = \"court.releaseCaptive\""
        },
        {
          "path": "logic/src/main/kotlin/opensamguk/logic/input/PoliticalConsent.kt",
          "literal": "const val COURT_INPUT_ID = \"court.politicalConsent\""
        },
        {
          "path": "app/game-engine/src/main/kotlin/opensamguk/engine/campaign/CourtHandler.kt",
          "literal": "updateMeta(captive, captive.meta - CaptiveState.META_KEY)"
        },
        {
          "path": "app/game-engine/src/main/kotlin/opensamguk/engine/campaign/CourtHandler.kt",
          "literal": "actor.meta + (PoliticalConsent.META_KEY to consent.toMetaValue())"
        }
      ]
    },
    {
      "id": "F7",
      "inputIds": [],
      "kinds": [],
      "phases": [],
      "deliveryStates": [],
      "routes": [],
      "admission": {
        "currentTypes": [],
        "target": "발령 응답 상태와 request terminal은 별개."
      },
      "execution": {
        "currentTypes": null,
        "reason": "자동 만료는 새 requestId가 없어 command result 행을 만들지 않는다. F6 명시 응답은 자신의 요청 terminal을 갖는다."
      },
      "effectTiming": "PENDING→ACCEPTED/REFUSED. 기한 자동 수락(lapsed=true); 관계/현 무효는 CANCELLED·벌점 없음.",
      "invalidation": "응답은 장수 행동 미소모. 구현 responsePhases=12/refusalLoyaltyLoss=5는 관측값이며 설계 기한 수치 미정을 확정하지 않는다.",
      "consistencyClass": "NON_STATE",
      "status": "CURRENT",
      "anchors": [
        {
          "path": "logic/src/main/kotlin/opensamguk/logic/input/DispatchState.kt",
          "literal": "enum class DispatchStatus { PENDING, ACCEPTED, REFUSED, CANCELLED }"
        },
        {
          "path": "logic/src/main/kotlin/opensamguk/logic/input/DispatchState.kt",
          "literal": "val responsePhases: Int = 12"
        },
        {
          "path": "logic/src/main/kotlin/opensamguk/logic/input/DispatchState.kt",
          "literal": "val refusalLoyaltyLoss: Int = 5"
        },
        {
          "path": "app/game-engine/src/main/kotlin/opensamguk/engine/campaign/DispatchExecutor.kt",
          "literal": "fun expireDue(): List<DispatchExecution>"
        },
        {
          "path": "app/game-engine/src/main/kotlin/opensamguk/engine/campaign/DispatchExecutor.kt",
          "literal": "val lapsed = automatic || (accept && !request.accept)"
        },
        {
          "path": "app/game-engine/src/main/kotlin/opensamguk/engine/campaign/DispatchExecutor.kt",
          "literal": "old.copy(status = DispatchStatus.CANCELLED)"
        },
        {
          "path": "app/game-engine/src/main/kotlin/opensamguk/engine/campaign/DispatchExecutor.kt",
          "literal": "EventKind.DISPATCH_CANCELLED"
        },
        {
          "path": "app/game-engine/src/main/kotlin/opensamguk/engine/campaign/CourtHandler.kt",
          "literal": "fun expireDue() { executor.expireDue() }"
        },
        {
          "path": "app/game-engine/src/main/kotlin/opensamguk/engine/campaign/DispatchExecutor.kt",
          "literal": "EventKind.DISPATCH_RECEIVED"
        },
        {
          "path": "app/game-engine/src/main/kotlin/opensamguk/engine/campaign/DispatchExecutor.kt",
          "literal": "EventKind.DISPATCH_ISSUED"
        },
        {
          "path": "app/game-engine/src/main/kotlin/opensamguk/engine/campaign/DispatchExecutor.kt",
          "literal": "EventKind.DISPATCH_ACCEPTED"
        },
        {
          "path": "app/game-engine/src/main/kotlin/opensamguk/engine/campaign/DispatchExecutor.kt",
          "literal": "EventKind.DISPATCH_REFUSED"
        },
        {
          "path": "app/game-engine/src/main/kotlin/opensamguk/engine/campaign/DispatchExecutor.kt",
          "literal": "audience = AudienceTarget.Court(dispatch.nationId, recipients)"
        }
      ]
    },
    {
      "id": "NOT_DELIVERED",
      "inputIds": [
        "action.dissolve",
        "action.independence",
        "action.retire",
        "court.amendVassal",
        "court.appoint",
        "court.appointSubordinate",
        "court.breakNonAggression",
        "court.confiscate",
        "court.declareWar",
        "court.diplomacy",
        "court.dismiss",
        "court.dismissSubordinate",
        "court.edictPropose",
        "court.edictRegister",
        "court.edictReply",
        "court.edictReview",
        "court.edictSeal",
        "court.endVassal",
        "court.foundVassal",
        "court.identityAdopt",
        "court.institution",
        "court.nonAggression",
        "court.offerReply",
        "court.officeClaim",
        "court.officeNominate",
        "court.officeNominationReply",
        "court.officeNominationReview",
        "court.officeRecognize",
        "court.proclaimEmperor",
        "court.settlementPolicy",
        "court.unitTraditionAdopt"
      ],
      "kinds": [
        "GENERAL_ACTION",
        "COURT_DECISION"
      ],
      "phases": [
        "POLITICS",
        "DECISION_TURN"
      ],
      "deliveryStates": [
        "PLANNED"
      ],
      "routes": [],
      "admission": {
        "currentTypes": [
          "executionRejected"
        ],
        "target": "PLANNED는 제공 약속/성공이 아님."
      },
      "execution": {
        "currentTypes": null,
        "reason": "미제공 입력 31행. API 사전 거절 또는 engine NOT_DELIVERED."
      },
      "effectTiming": "실제 실행·효력 없음.",
      "invalidation": "미제공 거절과 실행 시점 무효는 구분한다.",
      "consistencyClass": "Mutation",
      "status": "NOT_DELIVERED",
      "anchors": [
        {
          "path": "logic/src/main/kotlin/opensamguk/logic/input/InputRegistry.kt",
          "literal": "return if (entry.deliveryState.hasHandler) null else InputRejection.NOT_DELIVERED"
        }
      ]
    },
    {
      "id": "SANDBOX",
      "inputIds": [],
      "kinds": [],
      "phases": [],
      "deliveryStates": [],
      "routes": [
        {
          "method": "POST",
          "path": "/api/v2/commands/{commandId}/precheck",
          "controller": "app/game-api/src/main/kotlin/opensamguk/gameapi/command/CanonicalCommandController.kt"
        },
        {
          "method": "POST",
          "path": "/api/v2/commands/{commandId}",
          "controller": "app/game-api/src/main/kotlin/opensamguk/gameapi/command/CanonicalCommandController.kt"
        }
      ],
      "admission": {
        "currentTypes": [],
        "target": "live API 적용 범위 아님."
      },
      "execution": {
        "currentTypes": null,
        "reason": "sandbox profile/property가 모두 필요하다."
      },
      "effectTiming": "production 입력 terminal 원장 제외.",
      "invalidation": "live 사용 가능/승인으로 해석 금지.",
      "consistencyClass": "NON_STATE",
      "status": "UNKNOWN",
      "anchors": [
        {
          "path": "app/game-api/src/main/kotlin/opensamguk/gameapi/command/CanonicalCommandController.kt",
          "literal": "@Profile(SandboxGate.PROFILE)"
        },
        {
          "path": "app/game-api/src/main/kotlin/opensamguk/gameapi/command/CanonicalCommandController.kt",
          "literal": "@ConditionalOnProperty(name = [SandboxGate.PROPERTY], havingValue = \"true\", matchIfMissing = false)"
        }
      ]
    },
    {
      "id": "RESULT_READ",
      "inputIds": [],
      "kinds": [],
      "phases": [],
      "deliveryStates": [],
      "routes": [
        {
          "method": "GET",
          "path": "/api/command/result/{requestId}",
          "controller": "app/game-api/src/main/kotlin/opensamguk/gameapi/web/CommandController.kt"
        }
      ],
      "admission": {
        "currentTypes": [],
        "target": "계약 GA-075 목표 AUTHORITATIVE (G2)."
      },
      "execution": {
        "currentTypes": null,
        "reason": "조회는 mutation terminal을 생성하지 않는다."
      },
      "effectTiming": "Redis 우선, 부재/대기형이면 durable fallback. 소유자만 조회.",
      "invalidation": "키 부재·손상·소유권 확인 실패는 PENDING; RESOLVED ok=false는 성공 아님.",
      "consistencyClass": "RYW",
      "status": "CURRENT",
      "anchors": [
        {
          "path": "app/game-api/src/main/kotlin/opensamguk/gameapi/web/CommandController.kt",
          "literal": "if (!ownsRequest(userId, requestId)) return pending(requestId)"
        },
        {
          "path": "app/game-api/src/main/kotlin/opensamguk/gameapi/web/CommandController.kt",
          "literal": "val durableResult = if (redisResult == null || isPendingReservation(redisResult.result))"
        },
        {
          "path": "app/game-api/src/main/kotlin/opensamguk/gameapi/consistency/ReadConsistencyClassifier.kt",
          "literal": "ReadConsistencyClass.READ_YOUR_WRITES"
        }
      ]
    }
  ],
  "inputOverrides": [
    {
      "inputId": "court.dispatchReply",
      "familyId": "F6"
    },
    {
      "inputId": "court.releaseCaptive",
      "familyId": "F6"
    },
    {
      "inputId": "court.politicalConsent",
      "familyId": "F6"
    }
  ],
  "outOfScopeMutationRoutes": [
    {
      "method": "POST",
      "path": "/api/battles/{worldId}/{battleId}/join-ticket",
      "controller": "app/game-api/src/main/kotlin/opensamguk/gameapi/battle/realtime/BattleJoinTicketController.kt",
      "reason": "전투 join ticket: catalog 밖 변경 경로, 후속 원장."
    },
    {
      "method": "POST",
      "path": "/api/v2/city-transport/route",
      "controller": "app/game-api/src/main/kotlin/opensamguk/gameapi/city/CityTransportController.kt",
      "reason": "도시 수송: catalog 밖 HWIHA 변경 경로, 후속 원장."
    },
    {
      "method": "POST",
      "path": "/api/v2/city-transport",
      "controller": "app/game-api/src/main/kotlin/opensamguk/gameapi/city/CityTransportController.kt",
      "reason": "도시 수송: catalog 밖 HWIHA 변경 경로, 후속 원장."
    },
    {
      "method": "POST",
      "path": "/api/v2/garrison-recruit",
      "controller": "app/game-api/src/main/kotlin/opensamguk/gameapi/city/GarrisonRecruitController.kt",
      "reason": "수비대 징집: catalog 밖 HWIHA 변경 경로, 후속 원장."
    },
    {
      "method": "POST",
      "path": "/api/admin/server-status",
      "controller": "app/game-api/src/main/kotlin/opensamguk/gameapi/controller/AdminWriteController.kt",
      "reason": "관리자 변경: 이번 입력6종 절편 제외, 후속 원장."
    },
    {
      "method": "POST",
      "path": "/api/admin/general-moderation",
      "controller": "app/game-api/src/main/kotlin/opensamguk/gameapi/controller/AdminWriteController.kt",
      "reason": "관리자 변경: 이번 입력6종 절편 제외, 후속 원장."
    },
    {
      "method": "PATCH",
      "path": "/api/admin/game-settings",
      "controller": "app/game-api/src/main/kotlin/opensamguk/gameapi/controller/AdminWriteController.kt",
      "reason": "관리자 변경: 이번 입력6종 절편 제외, 후속 원장."
    },
    {
      "method": "POST",
      "path": "/api/messages/{id}/accept",
      "controller": "app/game-api/src/main/kotlin/opensamguk/gameapi/controller/DiplomaticMessageController.kt",
      "reason": "외교 메시지 응답: catalog 밖 경로, 후속 원장."
    },
    {
      "method": "POST",
      "path": "/api/messages/{id}/decline",
      "controller": "app/game-api/src/main/kotlin/opensamguk/gameapi/controller/DiplomaticMessageController.kt",
      "reason": "외교 메시지 응답: catalog 밖 경로, 후속 원장."
    },
    {
      "method": "POST",
      "path": "/api/council/articles",
      "controller": "app/game-api/src/main/kotlin/opensamguk/gameapi/council/CouncilController.kt",
      "reason": "국가 의회: 이번 입력6종 절편 제외, 후속 원장."
    },
    {
      "method": "POST",
      "path": "/api/council/articles/{articleId}/comments",
      "controller": "app/game-api/src/main/kotlin/opensamguk/gameapi/council/CouncilController.kt",
      "reason": "국가 의회: 이번 입력6종 절편 제외, 후속 원장."
    },
    {
      "method": "POST",
      "path": "/api/council/articles/{articleId}/read",
      "controller": "app/game-api/src/main/kotlin/opensamguk/gameapi/council/CouncilController.kt",
      "reason": "국가 의회: 이번 입력6종 절편 제외, 후속 원장."
    },
    {
      "method": "PUT",
      "path": "/api/council/participants/{targetGeneralId}",
      "controller": "app/game-api/src/main/kotlin/opensamguk/gameapi/council/CouncilController.kt",
      "reason": "국가 의회: 이번 입력6종 절편 제외, 후속 원장."
    },
    {
      "method": "DELETE",
      "path": "/api/council/participants/{targetGeneralId}",
      "controller": "app/game-api/src/main/kotlin/opensamguk/gameapi/council/CouncilController.kt",
      "reason": "국가 의회: 이번 입력6종 절편 제외, 후속 원장."
    },
    {
      "method": "POST",
      "path": "/api/generals/creation",
      "controller": "app/game-api/src/main/kotlin/opensamguk/gameapi/creation/GeneralCreationController.kt",
      "reason": "장수 생성: 이번 입력6종 절편 제외, 후속 원장."
    },
    {
      "method": "POST",
      "path": "/api/command/nation/bulk",
      "controller": "app/game-api/src/main/kotlin/opensamguk/gameapi/web/CommandController.kt",
      "reason": "nation ring: GA-079/W3 동결 기준선; 활성화/기존 gate 변경 없음."
    },
    {
      "method": "POST",
      "path": "/api/command/nation/push",
      "controller": "app/game-api/src/main/kotlin/opensamguk/gameapi/web/CommandController.kt",
      "reason": "nation ring: GA-079/W3 동결 기준선; 활성화/기존 gate 변경 없음."
    },
    {
      "method": "POST",
      "path": "/api/command/nation/repeat",
      "controller": "app/game-api/src/main/kotlin/opensamguk/gameapi/web/CommandController.kt",
      "reason": "nation ring: GA-079/W3 동결 기준선; 활성화/기존 gate 변경 없음."
    },
    {
      "method": "POST",
      "path": "/api/join",
      "controller": "app/game-api/src/main/kotlin/opensamguk/gameapi/web/JoinController.kt",
      "reason": "월드 가입: 이번 입력6종 절편 제외, 후속 원장."
    },
    {
      "method": "POST",
      "path": "/api/command/presence",
      "controller": "app/game-api/src/main/kotlin/opensamguk/gameapi/web/PresenceController.kt",
      "reason": "presence: catalog 밖 변경 경로, 후속 원장."
    },
    {
      "method": "POST",
      "path": "/api/internal/profile-icon-sync",
      "controller": "app/game-api/src/main/kotlin/opensamguk/gameapi/web/ProfileIconSyncController.kt",
      "reason": "profile icon 동기화: 이번 입력6종 절편 제외, 후속 원장."
    }
  ],
  "gaps": [
    {
      "id": "G1",
      "current": "Court/Domestic/계책 engine 접수 거절은 seq1 executionRejected. API 접수 전 거절은 HTTP/BLOCKED이며 durable 거절 행과 다르다.",
      "target": "접수 거절과 실행 시점 무효 축을 분리; reservationRejected 생산자는 현행 없음.",
      "followup": "CourtHandler/DomesticHandler + 결과 어휘·FE 폴링 별도 구현 후보.",
      "unknown": false
    },
    {
      "id": "G2",
      "current": "결과 조회 classifier=RYW, Redis 우선 + durable fallback.",
      "target": "구계약 GA-075 AUTHORITATIVE와의 정합성 결정/구현.",
      "followup": "ReadConsistencyClassifier/CommandController 담당 별도 검토.",
      "unknown": false
    },
    {
      "id": "G3",
      "current": "isPendingReservation은 RESERVED_TURN/COURT_DECISION만 포함. 계책 예약은 STRATAGEM이면 RESOLVED. 단 전13행 PLANNED로 현재 도달 불가.",
      "target": "계책 제공 전에 접수 대기와 실행 terminal 조회를 분리.",
      "followup": "CommandController + FE 폴링; 계책 제공 전 별도 구현.",
      "unknown": false
    },
    {
      "id": "G4",
      "current": "발령 자동 만료는 request terminal 없음; 사건/장수 기록. 구현 기한 12순·충성거절비용5.",
      "target": "설계 §2.4의 기한 수치 미정 유지. 상태 전이와 request terminal 구분.",
      "followup": "기획 정책 확인은 별도; 이번 작업에서 정책값 확정/변경 없음.",
      "unknown": true
    },
    {
      "id": "G5",
      "current": "§3 Redis-only baseline과 §6.3 gate 86/31/14는 과거 기록. 정규식 현행 game-api167/non-game60은 exact manifest 아님.",
      "target": "HWIHA 전체 endpoint/event manifest 별도 재추출.",
      "followup": "후속 전체 원장; 기존 manifest/gate·GA-079/W3 불변.",
      "unknown": true
    },
    {
      "id": "G6",
      "current": "F6 3행 catalog=DECISION_TURN, 실제 CourtHandler는 즉시 적용. F5 6행은 대기 분기 확인.",
      "target": "catalog 시점과 실제 terminal 의미 정합화.",
      "followup": "PR1608 catalog 소유 해제 후 별도 변경; 이번 PR runtime/catalog 수정 없음.",
      "unknown": false
    }
  ]
}
```
<!-- HWIHA-TERMINAL-LEDGER:END -->
