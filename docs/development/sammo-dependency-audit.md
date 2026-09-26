# 휘하 런타임의 삼모 의존 감사

기준: `origin/main` 88ee3f61f (2026-09-25, #913·#914·#915 머지 포함). 분류 근거는 파일:줄과 실제 호출 경로를 함께 기록한다. 삼모 전용 코드는 휘하가 사용하는 동작을 이전한 뒤에만 제거한다.

착수 재측정(57c0e13)과 0단계 머지 뒤 재측정(88ee3f61f)은 같다: `Hwiha*` 파일 444개(common 2, logic 197, infra 15, engine 125, api 74, web 31), `data/**/hwiha-*.json` 18개다. 제품 main 소스의 `/v2/` 경로 또는 `V2*` 파일은 45개(에셋 제외)다.

| 항목 | 휘하 사용 여부와 근거 | 처리 |
|---|---|---|
| 월 경계 파이프라인 | `app/game-engine/src/main/kotlin/opensamguk/engine/run/TurnRunService.kt:330-381`에서 휘하 분기 밖의 `pipeline.runMonth`가 실행된다. `logic/src/main/kotlin/opensamguk/logic/tick/MonthlyPipeline.kt:102-126`은 PRE_MONTH·MONTH 사건과 월말 hook을 호출한다. | 사건별 효과를 분리해 휘하가 쓰는 비재정 처리를 보존한다. 분리 전 파이프라인을 끄지 않는다. |
| `MonthlyPostUpdateHook` | `app/game-engine/src/main/kotlin/opensamguk/engine/config/DaemonLoopConfig.kt:454-465`가 월말 hook을 배선한다. `app/game-engine/src/main/kotlin/opensamguk/engine/run/MonthlyPostUpdateHook.kt:205-231`은 `checkEmperior`를 tail에 전달한다. | 실제 휘하 효과를 추적한 뒤 공유 동작과 삼모 전용 동작을 분류한다. 전 城 통일 판정은 제거 대상이나, 인접한 휘하 의존 처리는 별도 이전한다. |
| 월 전처리 hook | `app/game-engine/src/main/kotlin/opensamguk/engine/config/DaemonLoopConfig.kt:420-423`은 `MonthlyPreUpdateHook`을 배선한다. `app/game-engine/src/main/kotlin/opensamguk/engine/run/MonthlyPreUpdateHook.kt:27-36,50-53,79-120`은 프로필 조건 없이 연감, 장수·국가 meta, 도시 상태, `game_env.develcost`를 갱신한다. | 휘하에서도 호출된다. 연감·도시 상태와 삼모 명령 한도·첩보·개발비 등 효과를 항목별로 분류한 뒤 필요한 동작만 이전한다. |
| 기본 동적 사건 | `infra/src/main/kotlin/opensamguk/infra/seed/ScenarioImporter.kt:1055-1078`은 `ignoreDefaultEvents`가 false면 기본 사건을 DB에 넣고, `infra/src/main/kotlin/opensamguk/infra/seed/ScenarioJson.kt:80`의 누락 기본값도 false다. 휘하 `infra/src/main/resources/scenario/scenario_990002.json:1-5`에는 이 플래그가 없으므로 기본 사건을 받는다. `app/game-engine/src/main/kotlin/opensamguk/engine/config/EngineEventConfig.kt:39-66`도 DB 행이 없을 때 기본 사건을 fallback으로 넣는다. `logic/src/main/kotlin/opensamguk/logic/event/EventStore.kt:157-190`의 PRE_MONTH·MONTH 행에는 재정과 비재정 액션이 혼합되어 있다. | 휘하에서 도는 실제 사건이다. 행 전체를 지우거나 `ignoreDefaultEvents=true`로 바꾸지 않고 각 액션의 효과를 먼저 분류한다. |
| 기존 재정과 함께 실행되는 비재정 효과 | `app/game-engine/src/main/kotlin/opensamguk/engine/world/WorldActionContext.kt:384-389`은 휘하의 기존 세입만 차단한다. 같은 파일 `:518-534`는 `ProcessWarIncome`의 국가 금 가산만 빼고 도시 인구·사상자 갱신을 적용하며, `:570-615`는 `ProcessSemiAnnual`의 도시 성장·신뢰·인구를 적용한 뒤 유지비만 뺀다. | 도시 성장·인구·사상자 처리의 제품 의미를 보존해 도메인 코드로 이전한다. 기존 국가·개인 재정은 제거한다. 이 경계의 같은 입력 전후 상태 비교가 필요하다. |
| `checkEmperior` | `app/game-engine/src/main/kotlin/opensamguk/engine/run/MonthlyPostUpdateHook.kt:205-231`이 무조건 `postUpdateMonthlyTail`에 넘기고, `logic/src/main/kotlin/opensamguk/logic/world/PostUpdateMonthly.kt:392-408`이 호출한다. | 전 城 통일 판정은 삼모 전용으로 제거한다. 월말 tail의 다른 효과가 휘하에서 필요한지는 먼저 분리해 확인한다. |
| 월말 tail의 삼모 기능 | `logic/src/main/kotlin/opensamguk/logic/world/PostUpdateMonthly.kt:392-419`은 방랑 군주 처리, 장수 수 갱신, `checkEmperior`, 토너먼트, 경매, 국가 전선 갱신을 차례로 호출한다. `app/game-engine/src/main/kotlin/opensamguk/engine/run/MonthlyPostUpdateHook.kt:359-452`가 실제 구현을 제공하고 프로필 분기는 없다. | 토너먼트·경매·삼모 통일은 삭제 후보. 장수 수·전선·방랑 처리는 휘하의 현재 소비자를 확인하고 필요한 부분만 중립 코드로 이전한다. RNG 순서가 바뀌는 경우 결정론 해시 변경 이유를 기록한다. |
| 기존 명령 레지스트리의 데몬 배선 | `app/game-engine/src/main/kotlin/opensamguk/engine/config/DaemonLoopConfig.kt:232-247`는 `CommandRegistry`를 만들고 `AiTurnAdapter`에 넘긴다. 같은 파일 `:356-369`는 `ReservedTurnHandler`에, `:399-406`은 `ProcessNationCommand`에 전달한다. | 생성 자체는 휘하에서도 일어난다. 각 소비자의 휘하 실제 호출을 분류하고, 필요한 공유 기능은 제품 원장·도메인 코드로 옮긴 뒤 `CommandRegistry`를 삭제한다. |
| API 예약·사전 판정 | #913 뒤 `app/game-api/src/main/kotlin/opensamguk/gameapi/reserve/CommandReserveService.kt:196-208`은 휘하 입력을 원장의 `rejectionFor`로 먼저 분류한다. 같은 서비스는 여전히 `CommandRegistry`를 보유한다(`:80`). `app/game-api/src/main/kotlin/opensamguk/gameapi/precheck/CommandPrecheckService.kt:56-74`는 기존 레지스트리로 명령을 판정한다. | 휘하 입구의 원장 분류·사유 계약은 유지한다. 기존 레지스트리 사용이 휘하 호출 경로에 남는지를 추적하고 삼모 전용 사전 판정은 제거한다. |
| 장수 턴의 삼모 AI·사령턴 | `app/game-engine/src/main/kotlin/opensamguk/engine/turn/TurnDaemonLifecycle.kt:182-221`은 휘하 입력을 처리한 뒤 `continue` 한다. 아래 `:222-260`의 `beginGeneralTurn`·`nationProcessor.process`·기존 장수 AI를 건너뛴다. `ReservedTurnHandler.kt:299-445`도 휘하 처리 뒤 반환하며, 뒤의 `CommandRegistry` 해석은 실행하지 않는다. | `AiTurnAdapter`와 `ProcessNationCommand`의 휘하 장수 턴 의존은 배선 수준이다. 삼모 전용 명령 처리로 분류하되, `GameConst`의 순 길이·장수 한도처럼 공유 상수는 소비자별로 이전한다. |
| API 삼모 명령 카탈로그·사전검사 | `app/game-api/src/main/kotlin/opensamguk/gameapi/web/CommandController.kt:111-133`은 휘하에서 미등록 명령을 원장으로 거절하고, 휘하 예약 가능 행동은 전용 admission으로 보낸다. `:154-164`의 `CommandPrecheckService` 호출은 삼모 분기다. `AvailableCommandsController.kt:76-83,111-119`는 휘하에서 404로 반환하고, `CommandPrecheckService.kt:56-96`은 `CommandRegistry`를 읽는다. | 삼모 제품 API를 제거할 때 기존 사전검사와 알파 명령 목록을 삭제한다. 휘하 admission의 원장 분류와 실패 사유는 유지한다. |
| 휘하 입력 원장 | `app/game-engine/src/main/kotlin/opensamguk/engine/hwiha/HwihaCourtHandler.kt:92-103`이 원장을 실제 호출한다. `logic/src/main/kotlin/opensamguk/logic/input/HwihaInputRegistry.kt:74-79,95-111`의 옛 `legacyCommands`·`retiredLegacyCommands`·역참조 인덱스는 삼모 대응이었다. 입력 구문·프로필·배달 사유는 휘하 진입 계약이다. | 이 PR에서 삼모 대응 필드·색인·70개 검사를 제거하고 원장 73행을 유지한다. 직접 행동 42행의 `displayName`을 필수로 두고 예약 기록에서 읽는다. `HwihaLegacyStratagemCorrespondence`의 삼모 역참조는 제거한다. 계책 12종 ID는 코드에 명시하고 원장 STRATAGEM 행과 전수 대조해 새 행이 실행 규칙에 자동 편입되지 않게 한다. |
| 공유 세계와 쓰기 경로 | `app/game-engine/src/main/kotlin/opensamguk/engine/turn/InMemoryTurnWorld.kt:105`, `app/game-engine/src/main/kotlin/opensamguk/engine/turn/ChangeRecorder.kt:72`, `infra/src/main/kotlin/opensamguk/infra/persistence/JdbcFlushExecutor.kt:47-57`, `app/game-engine/src/main/kotlin/opensamguk/engine/boot/WorldSnapshotLoader.kt:67`가 공유 기반이다. `app/game-engine/src/main/kotlin/opensamguk/engine/run/TurnRunService.kt:516-519`가 flush를 호출한다. | 휘하에서도 쓰는 기반이므로 유지한다. 이름·패키지가 삼모 계보와 섞여 있으면 동작 변경 없이 중립화한다. |
| 군주 투영 | `logic/src/main/kotlin/opensamguk/logic/input/HwihaDomesticRules.kt:221`, `HwihaPoliticalRules.kt:125`, `HwihaEnlistmentPrecheck.kt:50`은 모두 `officerLevel == 12`를 휘하 주공 판정에 사용한다. | 공유 엔티티의 군주 투영을 유지한다. 삼모 명령 삭제와 함께 숫자 의미를 지우지 않는다. |

## 다음 조사

1. `MonthlyPreUpdateHook`·`MonthlyPostUpdateHook` 내부의 각 액션과 `EventStore` 기본 행을 실제 휘하 효과별로 분해한다. `checkWander`, 외교 기간 정산, 통계, 도시 성장·사상자·재정, 가신·작전 월 정산을 구분한다.
2. `AiTurnAdapter`·`ProcessNationCommand`는 휘하 장수 턴의 `continue` 아래에 있어 건너뛰지만, 즉시 명령 디스패처 등 다른 진입점이 있는지 따로 확인한다. #913~#915의 기존 변경은 이 감사의 기준에 포함했다.
3. 삭제 후보(`logic/actions`, 삼모 AI·경매·베팅·토너먼트·상속, 엔진 동일 기능, 알파 카탈로그, 웹·시나리오·DB)에 대해 import 그래프와 호출·테스트를 교차 확인한다. 현재 표는 삭제 허가 목록이 아니다.

## 2026-09-26 재감사: #956·#958 이후 삭제 순서

기준은 #958 지도 이름 변경 브랜치 `a324cc360`이다. 위 표의 2026-09-25 파일명·줄 번호는 당시 기록이며, 삭제 판단에는 아래 현재 경계를 쓴다. 이 감사 브랜치는 #958을 부모로 쌓고, #958 병합 뒤 main에 재기반한다.

| 묶음 | 현재 사용 근거 | 판정·순서 |
| --- | --- | --- |
| PHP 캡처 도구 `tools/php-golden/`(57파일) | `.github/workflows`와 제품 소스·실행 스크립트에서 이 디렉터리를 실행하는 참조가 없다. 테스트의 `logic/src/test/kotlin/opensamguk/logic/golden/MonthTickReplayGateTest.kt:21-32`, `app/game-engine/src/test/kotlin/opensamguk/engine/golden/LongSimReplayGateTest.kt:318-327` 등은 캡처 출처를 설명한다. | 삼모 비교 도구 전용이다. 독립 1차 삭제 슬라이스로 제거한다. 캡처 자료를 사용하던 테스트·리소스는 아래 의존을 끊은 뒤 별도 삭제한다. |
| Kotlin 삼모 골든 테스트·리소스 | `logic/src/test/kotlin/opensamguk/logic/golden/` 52파일, `app/game-engine/src/test/kotlin/opensamguk/engine/golden/` 8파일, `logic/src/test/resources/golden/` 270파일이다. 바깥의 `MakeGeneralInheritanceTest.kt:4`는 `JoinDrawRecorder`, `OneRngPerGeneralTurnTest.kt:5`는 `AiDrawRecorder`를 import한다. `ConquerCityCollapseTest.kt:330-334`는 `golden/p4`를 읽는다. `app/game-engine/build.gradle.kts:78-87`은 로직 테스트 리소스를 엔진에 공유한다. | 테스트 디렉터리 전체를 바로 지우면 컴파일/fixture가 깨진다. 세 외부 소비 테스트를 현재 제품 의미에 맞게 이전하거나 삼모 전용 구간을 제거하고, 공유 classpath 배선을 정리한 다음 골든 자료를 삭제한다. |
| 삼모 명령/AI·경매·베팅·상속 | `TurnDaemonLifecycle.kt:182-260`의 현행 입력 뒤 `continue`는 장수·사령턴의 레거시 경로를 건너뛴다. 그러나 `WorldActionContext.kt:41-68`, `MonthlyPostUpdateHook.kt:20-24`, `CommandReserveService.kt:19` 등은 기존 타입을 여전히 import한다. | 레거시 턴 실행부는 삭제 후보지만 import만 보고 패키지를 일괄 삭제할 수 없다. 월 사건의 도시 성장·인구·사상자 같은 현재 효과는 위 표대로 분리한 후 제거한다. |
| `logic.actions.intake.SecretPermission` 등 | `PersonnelHandler.kt:12`, `OperationHandler.kt:14`, `RankReadService.kt:38`이 import한다. 해당 핸들러/API의 현행 도달성을 확인해야 한다. | 공유 보안 판정 가능성이 있어 이번 도구 삭제 슬라이스에서 유지한다. 삼모 전용 endpoint를 걷을 때 소비자별로 분류한다. |
| 기록·flush 경계 | writer 담당이 `JdbcFlushExecutor.kt`, `InMemoryTurnWorld.kt` 기록 구간, `DatabaseHooks`, `RecordKind`/`HwihaRecords`, `DirtyState.kt`, `BootstrapConfig.kt`를 편집한다. | 이 감사/삭제 브랜치에서는 직접 편집하지 않는다. 삼모 호출 제거 지점만 줄 단위로 전달하고 writer PR 또는 그 병합 후 후속 PR에서 처리한다. |

독립 도구 삭제 뒤 검사: 저장소 전체에서 `tools/php-golden/`의 **실행** 참조 0건, `python3 tools/ci/naming_lint.py --counts` 실측 기준 반영, `git diff --check`. 남은 설명·과거 보고서의 경로 언급은 기록으로 남기고, 제품 안내·CI의 실행 경로는 0건이어야 한다.

### writer 담당에게 넘긴 삭제 후보(직접 편집 금지)

- `app/game-engine/src/main/kotlin/opensamguk/engine/turn/DirtyState.kt:68-85,251-262`: 경매·입찰·베팅·상속 dirty 채널 타입과 필드. 현행 제품의 가신·부곡 dirty 채널(`:204`)과 섞지 않는다.
- `app/game-engine/src/main/kotlin/opensamguk/engine/flush/DatabaseHooks.kt:661-662,778-780,811-815,837-864`: 옛 auction/betting/inheritance payload와 active auction 투영. 현재 기록 writer 변경과 병합해 처리한다.
- `infra/src/main/kotlin/opensamguk/infra/persistence/JdbcFlushExecutor.kt:79-94,195-201`: 옛 경매·상속 flush 분기와 하위 SQL 함수. Flyway의 공유/삼모 전용 표 판정 뒤 제거한다.
- `app/game-engine/src/main/kotlin/opensamguk/engine/turn/InMemoryTurnWorld.kt:269-277`: SAMMO 위치 규칙 분기. 현행 세계 형식 가드가 도달을 거절하므로 제품 위치 규칙만 남기는 후보이나 writer의 기록 경계 변경과 겹치므로 뒤로 미룬다.
- `logic/src/main/kotlin/opensamguk/logic/input/RecordKind.kt`, `app/game-engine/src/main/kotlin/opensamguk/engine/campaign/Records.kt`는 현재 경매·베팅·상속·troop 키워드 실행 코드가 없었다. `BootstrapConfig.kt:15`는 관련 주석만 있다. writer가 독립적으로 다룬다.
