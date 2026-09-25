# 삼모 삭제 후보 import 감사

기준: `origin/main` `88ee3f61f` (2026-09-25). 이 표는 **현재 트리에서의 삭제 허가**를 판정한다. `삭제 가능`만 즉시 파일 제거가 가능하고, `이동 후 삭제`는 표에 적은 호출·효과·테스트를 이전한 뒤 다시 컴파일해야 한다. 현 기준에서 무조건 삭제 가능한 묶음은 **0개**다. 제품 코드는 이 감사에서 바꾸지 않았다.

결정 근거: [ADR-LITE-065·066, PR #916](https://github.com/peppone-choi/opensamguk/pull/916) (승인 결정, 문서 PR은 아직 미머지)과 [휘하 런타임 의존 감사, PR #918](https://github.com/peppone-choi/opensamguk/pull/918) (아직 삭제 허가 목록이 아님). #918의 문서는 수정하지 않았다. 아래 `파일:줄`은 모두 위 `origin/main` 기준이다. 테스트·설정 문자열도 정적 참조로 취급했다.

## 판정표

| 삭제 후보 (경로 범위) | import·호출·테스트·문자열 근거와 휘하 사용 | 삭제 전 이전할 동작 / 조건 | 판정 |
| --- | --- | --- | --- |
| `logic/.../actions/CommandRegistry.kt`, `GeneralActionDefinition.kt`, `GeneralActionResolveContext.kt`, `CommandFormSpec.kt` 및 `Che*` 명령군 (`actions/` 116파일 전체 삭제안) | `CommandRegistry.kt:132-217`은 코드 문자열로 `Che*` 구현을 선택한다. 휘하 장수 턴은 `TurnDaemonLifecycle.kt:189-221`에서 `continue`, `ReservedTurnHandler.kt:299-335`에서 전용 처리한다. 그러나 `MonthlyPostUpdateHook.kt:251-281`은 휘하 분기 없이 `GeneralActionDraft`·`GeneralActionResolveContext`·`CheHaesan`을 직접 호출한다. `DaemonLoopConfig.kt:232-247`, `CommandReserveService.kt:80,196-208`, `ChiefCenterController.kt:184-197`도 레지스트리를 생성·호출한다. `logic/src/test/kotlin/opensamguk/logic/actions/`의 78개 파일과 `MonthlyPostUpdateHookTailWiringTest.kt:60-135`가 참조한다. | 개인/국가 명령 진입점과 API 표를 닫고, Q11 방랑 군주 자동 해산의 국가·장수·도시 갱신, 로그, `runOccupyCityEvent`, RNG를 중립 월말 규칙으로 이전한다. `actions/` 일괄 삭제는 금지한다. | 이동 후 삭제 |
| `actions/military/UnitSetTable.kt`, `actions/vote/VoteLottery.kt` 등 `Che*` 외의 공용 계산 | `GetConstController.kt:17,73-78,206`은 `UnitSetTable`을 읽고, `VoteHandler.kt:11,296-303`은 `VoteLotteryInputs`를 만들며 `TurnDaemonCommandDispatcher.kt:274`가 투표를 배선한다. 휘하 월드에서 해당 API·즉시 투표의 사용 여부는 아직 프로필별로 닫히지 않았다. | 공용 카탈로그/투표 수학의 소비자를 먼저 분리한다. 제품 규칙이 확인되기 전에는 제거하지 않는다. | UNKNOWN |
| `logic/.../ai/**`와 `app/game-engine/.../turn/AiTurnAdapter.kt` | `DaemonLoopConfig.kt:238-247,369,484-488`에서 AI를 배선한다. 휘하 장수 턴의 `continue`는 `TurnDaemonLifecycle.kt:189-221`에 있어 `beginGeneralTurn` 아래 블록을 건너뛴다. `AiTurnAdapter.kt:478,611`은 `GeneralAiFactory`를 호출하며 AI/명령 테스트가 남는다. | 생성·훅·NPC 정책의 다른 호출자, 즉시 디스패처와 구분된 경로를 제거하고 휘하 NPC 입력 선택(`TurnDaemonLifecycle.kt:199-205`)은 보존한다. | 이동 후 삭제 |
| `logic/.../auction/**`, `app/game-engine/.../auction/**`, 경매 API·저장 경로 | `MonthlyPostUpdateHook.kt:396-414`가 Q16 중립 경매를 열고 `TurnRunService.kt:437-438`가 틱마다 만료 경매를 처리한다. `TurnDaemonCommandDispatcher.kt:197-201,390-391,453-455`는 입찰·마감·개설을 프로필 가드 없이 디스패치한다. `AuctionController.kt:51,77-150`은 조회 경로다. `MonthlyPostUpdateHookTailWiringTest.kt:401-412`가 Q16 삽입을 검증한다. | 월말 Q16 등록, 틱 만료, 즉시 명령, API, `ng_auction*` 및 유산포인트 쓰기를 함께 닫는다. Q16 제거에 따른 RNG 순서와 해시를 검증한다. | 이동 후 삭제 |
| `logic/.../betting/**`, `logic/.../event/{BettingActions,OpenNationBetting,FinishNationBetting}.kt`, 엔진·API 베팅 | `WorldActions.kt:34`가 이름 기반 이벤트 액션을 등록한다. `EngineEventConfig.kt:61,90`은 기본 이벤트/팩토리를 로드하고 `WorldActionContext.kt:857-926`이 국가 베팅 후속 이벤트를 만든다. `TurnDaemonCommandDispatcher.kt:205-221,392`의 즉시 베팅과 `BettingController.kt:46,54-147`의 조회가 남는다. 기본 사건은 `EventStore.kt:157-190`에 있으며 #918은 휘하도 기본 사건을 받는다고 확인했다. | 이벤트 행과 등록 이름 `OpenNationBetting`·`FinishNationBetting`, 즉시 입력, API, 저장 KV를 원자적으로 정리한다. 기본 이벤트의 비재정 효과는 따로 보존한다. | 이동 후 삭제 |
| `logic/.../tournament/**`, `app/game-engine/.../tournament/**`, 토너먼트 API | `PostUpdateMonthly.kt:410-415`는 Q15 뒤 Q16을 호출한다. `MonthlyPostUpdateHook.kt:376-393`의 Q15는 `rng.nextBool(0.4)`를 소비하고 별도 `MonthScopedRng`로 패턴을 섞는다. `TurnRunService.kt:437`은 틱마다 `TournamentDaemon`을 호출하고 `TurnDaemonCommandDispatcher.kt:402-404`는 신청·시작·초기화를 디스패치한다. `TournamentController.kt:31,63-132`, `MonthlyPostUpdateHookTailWiringTest.kt:304-386`도 참조한다. | Q15 시작, 틱 진행, 베팅 보상, API/즉시 명령, `tournament*` KV를 함께 제거한다. 월 RNG의 Q15 소비가 없어지는 효과를 명시해 재기준화한다. | 이동 후 삭제 |
| `logic/.../inheritance/**`, `actions/instant/inherit/**`, 엔진 유산 처리 | `WorldActionContext.kt:68,1606`은 `mergeTotalInheritancePoint`를 호출한다. `TurnDaemonCommandDispatcher.kt:249-255,405-414`가 유산 구매·초기화·즉시 액션을 처리하며 `InheritPointController.kt:40,67`은 조회한다. `WorldSnapshotLoader.kt:124,582`, `ChangeRecorder.kt:1085-1126`은 저장·flush 형식을 참조한다. 경매·베팅도 `previous` KV를 읽고 쓴다(`AuctionBidHandler.kt:225-231`, `PlaceBetHandler.kt:123-149`). | 유산 점수와 액션을 은퇴하더라도 월말/이벤트 병합, 공유 KV/flush 및 휘하가 실제 쓰는 보상인지 분리한다. 저장 형식 제거는 별도 스키마 결정이 필요하다. | 이동 후 삭제 |
| `data/commands/public-alpha-command-catalog.json`, `PublicCommandCatalogIndex.kt`, `CommandCatalogRowFactory.kt`, 생성·검증 도구 | `app/game-api/build.gradle.kts:17-19`가 JSON을 리소스로 복사하고 `PublicCommandCatalogIndex.kt:30-35`가 `classLoader.getResource` 문자열로 읽는다. `CommandCatalogRowFactory.kt:36`과 `AvailableCommandsController.kt:76-83,103`이 사용하며 이 일반 명령 목록은 휘하에서 404다. 하지만 `ChiefCenterController.kt:184-197`은 같은 factory를 프로필 가드 없이 호출한다. `PublicCommandCatalogIndexTest.kt:12-29`, `tools/commands/tests/test_public_alpha_command_catalog.py:196,255,366`도 핀이다. JSON `commands` 124개와 휘하 원장 `inputs` 73개의 `canonicalId`/`inputId` 교집합은 0개(두 JSON 직접 파싱). | 사령부 표의 휘하 노출을 먼저 확인·교체하고, 레거시 API/리소스 복사/도구/테스트를 같이 제거한다. 휘하 `data/commands/hwiha-input-catalog.json`은 별도 원장으로 유지한다. | 이동 후 삭제 |
| 월 경계 공유 파이프라인, `PostUpdateMonthly.kt` 전체, `WorldActionContext.kt` 전체 | `MonthlyPipeline.kt:95-126`은 PRE_MONTH/MONTH를 실행한다. `WorldActionContext.kt:517-534,569-615`는 휘하에서도 도시 인구·사상자·성장을 적용한다. `MonthlyPostUpdateHook.kt:222-238,417-433`은 Q11·장수 수·전선·가신/작전 정산을 호출하고 `ConquerCity.kt:439`도 전선을 쓴다. | 사건별 재정만 분리한다. 도시 효과, 장수 수, 전선, 가신·작전 정산과 공유 단일 쓰기 경로는 보존한다. | 유지 |
| `CheckEmperior.kt` 전 城 통일 판정 | `MonthlyPostUpdateHook.kt:205-231`이 무조건 전달하고 `PostUpdateMonthly.kt:407-408`이 Q14로 호출한다. `PostUpdateMonthlyTailTest.kt:101-137`은 Q14 순서를 핀한다. Q14 자체는 월 RNG를 소비하지 않는다. | Q14 호출/테스트만 분리한다. 인접 Q11·Q15·Q16·Q17을 함께 지우면 안 된다. | 이동 후 삭제 |
| 웹·시나리오·DB의 경매/베팅/토너먼트/유산 표면 전체 | API 경로가 현재 코드에 있고, `FrontInfoController.kt:637-713`은 `tournament` 상태로 UI 플래그를 계산한다. #918은 시나리오 기본 이벤트 삽입을 확인했다. 웹·시나리오·DB 전체의 문자열과 호환/운영 소비자를 이번 후보별 컴파일만으로 증명할 수 없다. | 코드 제거와 별도 계약/마이그레이션 감사가 필요하다. 옛 Flyway 파일은 ADR-LITE-066에 따라 수정하지 않는다. | UNKNOWN |

## 월말 tail RNG 경계

`PostUpdateMonthly.kt:397-419`의 공유 `monthlyRng` 소비 순서는 Q4(앞 단계) → 연도 조건부 Q11 방랑 처리 → Q15 토너먼트 → Q16 경매다. Q14 통일 판정, Q12 장수 수, Q17 전선은 여기서 RNG를 받지 않는다. `MonthlyPostUpdateHook.kt:376-414`의 Q15는 활성 상태·`tnmt_trig` 조건을 통과하면 `nextBool(0.4)` 한 번을, Q16의 `NeutralAuctionRegistrar.kt:30-57`은 매수·매도 게이트 두 번과 성공 시 추가 범위 추첨을 소비한다. Q11은 방랑 군주 수와 `CheHaesan.resolve` 경로에 따라 소비량이 달라진다. Q15·Q16을 제거하면 뒤 소비자만 아니라 이후 재현 해시도 달라질 수 있다. `PostUpdateMonthlyTailTest.kt:61-97`과 `MonthlyPostUpdateHookTailWiringTest.kt:304-412`의 과거 삼모 순서 기대값을 휘하 기준 결정론 테스트로 교체해야 한다. 실제 변경 전후 해시 비교는 제품 코드를 바꾸지 않아 실행하지 않았다.

| 후보 | 현재 tail RNG 영향 / 제거 시 예상 변화 |
| --- | --- |
| `actions/`·`CheHaesan` | Q11에서 같은 `monthlyRng`을 전달한다(`MonthlyPostUpdateHook.kt:268-281`). 방랑 군주가 있을 때 제거 방식에 따라 소비량이 바뀔 수 있어 이관 전 수치 확정 불가. |
| AI·즉시 명령·베팅·상속·알파 카탈로그 | tail의 Q11/Q15/Q16 인자로 직접 전달되지 않는다(`MonthlyPostUpdateHook.kt:222-231`). 다만 베팅은 Q15 토너먼트의 후속 처리에 연결돼 결과 해시 영향을 별도 검증해야 한다. |
| 토너먼트 | Q15의 월 RNG 0~1회 게이트가 사라진다. Q16의 시작 커서와 후속 월 RNG 재현이 달라진다. 별도 패턴 shuffle RNG는 월 RNG 소비가 아니다. |
| 경매 | Q16의 두 게이트 및 성공 시 추가 추첨이 사라진다. tail 이후 월 RNG 재현이 달라진다. |
| `CheckEmperior` | Q14 자체는 0회 소비하므로 직접 커서 변화는 없다. `isunited` 변경의 후속 상태 효과는 별도 비교한다. |
| 공유 월 경계·웹/시나리오/DB | 전체 파이프라인을 지우면 Q4·Q11·Q15·Q16 호출 자체가 없어지므로 영향 범위를 이 감사만으로 특정할 수 없다. 공유 경로는 유지한다. |

## 확인 방법과 한계

- `git grep -n`으로 main 소스·테스트의 import, 호출, 코드/이벤트 이름, `getResource`·Gradle 리소스 복사·KV 문자열을 찾았다. Kotlin의 정적 import가 없어도 이벤트 이름, 명령 문자열, Spring 라우트, JSON 리소스는 삭제 계약이다.
- JDK 21 Gradle 컴파일러로 `:logic:compileKotlin :app:game-engine:compileKotlin :app:game-api:compileKotlin --rerun-tasks`를 실행했다. 이는 **기준 트리의 컴파일 확인**이다. 후보를 실제 삭제한 뒤의 컴파일 통과나 런타임 경로 제거 증명은 아니다.
- 휘하 프로필에서 즉시 명령 API 전부가 도달 가능한지, 웹/외부 시나리오/운영 저장 소비자의 전수 목록은 미확인이다. 따라서 프로필 가드가 없는 경로는 안전하다고 추정하지 않았다.
