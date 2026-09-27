# 턴 루프 격리 후속 조사

## 2026-09-26 PR #980 리뷰 반영

- 최신 `origin/main`을 PR 브랜치에 병합해 은퇴 사전검사를 `RetireRules`/`RetireRulesTest`의 중립 이름으로 옮겼다. 아래의 `HwihaRetireRules` 경로와 재기반 대기 기록은 당시 상태를 설명한다.
- 새 사전 거절 사유 `RETAINER_NAME_CONFLICT`를 입력 원장에 등록해 규칙과 실패 사유 계약을 맞췄다.
- `ChangeRecorder` 체크포인트는 가변 컬렉션 채널 각각의 실제 대상 참조와 `spatialWorldId`를 캡처한다. 리플렉션 가드는 현재 필드 집합과 캡처 집합이 일치하는지 검사한다. 컬렉션 캡처 한 줄을 제거하는 적색 프로브로 가드의 실패를 확인한다.
- 러너 배선, 월드 상태 복원, 외부 ID 할당자와 `kvWriteObserver`의 원자성 경계는 여전히 후속 작업이다.

## 2026-09-26 Claude 인계 상태

- 사용자 최종 지시에 따라 이 worktree의 진행 중 변경만 커밋·푸시해 **draft PR로 보존**한다. 아래의 기능 PR 동결은 구현·ready 전환·main 병합 보류를 뜻하며, 이번 보존용 draft PR은 예외다. 새 기능·테스트·worktree·검증은 시작하지 않는다.
- 검증된 로컬 단위: `HwihaRetireRulesTest` 4/4, `TurnFailureLedgerTest` 4/4, `ChangeRecorderCheckpointTest` 3/3 (JDK 21, 각각 앞선 단독 Gradle 슬롯). 마지막 `git diff --check` 통과. 전체 엔진/PG/JDBC flush/runner 적색 프로브는 미검증이다.
- `TurnFailureLedger`는 미연결 상태다. `ChangeRecorder` 복원만으로 단위 롤백은 불완전하다. 로그 writer가 우선 소유한 `InMemoryTurnWorld.kt`의 실행 중 상태 복원, `kvWriteObserver`와 외부 효과의 커밋 경계, S4가 우선 소유한 `TurnRunService.kt`의 AI wiring 인계 뒤 runner 격리를 설계·검증해야 한다.
- 이 브랜치는 #915 직후 head `88ee3f61f`에서 시작해 최신 main보다 크게 뒤처졌다. 새 중립 이름은 지켰지만 기존 `HwihaRetireRules` 수정은 `rename-map.md`에 따라 재기반해야 하며, #957 로그 sink/#973 S4 D3 등 병렬 변경과 충돌할 수 있다. draft PR은 인계 보존용이고 현재 main 병합 가능 판정이 아니다.

- 기준: `origin/main` 88ee3f61f (#915 머지 직후).
- 2026-09-25 후속 fetch: `origin/main`은 #946 저장·통신 식별자 병합 `99b4556f8`까지 전진했다. 이 worktree는 기존 로컬 수정을 보존하려고 88ee3f61f에 머문다. #952 지도 보급 개명, 세계 형식 가드, 삼모 삭제는 아직 main에 없으므로 기능 PR 동결을 유지한다.
- 2026-09-26 후속 fetch: #952의 최종 트리를 main으로 옮긴 #953 병합 `940398259`를 확인했다. 지도 보급 중립 개명은 main 반영으로 취급한다. 세계 형식 가드·삼모 삭제 전 기능 PR 동결은 계속 유지한다.
- 코드 정리 draft #958의 지도 런타임 타입 개명(`WorldMapVariant`, `WorldArtifactsResolver`, `StrategicTopologyJson`, `HistoricalCityConstVariant`, `WORLD_ARCHIVE_MAP_NAME`)도 재기반 때 대응표로 적용한다. 현재 이 worktree의 수정 파일에는 해당 옛 지도 타입 참조가 없다.
- 후속 draft #960의 생성 상수 개명(`HanCityConst`·`HanGateIndex` → `BaselineCityConst`·`BaselineGateIndex`, `HanWorldV3*`·`Han780V1*` → `Archive*`)도 재기반 대응표에 포함한다. 현재 수정 파일에는 이 옛 상수 타입의 직접 참조가 없다. 저장된 `han*` 지도 릴리스 ID는 유지한다.
- 2026-09-26 정리 레인 통지: #958 런타임 지도 이름은 main에 병합됐고, #960 생성 상수·#967 보관본 로더·#970 지도 테스트 이름은 후속이다. #959 삼모 골든/캡처 삭제는 머지 가능 판정 뒤 CI 대기 중이다. 이 브랜치의 새 식별자 `ChangeRecorder.Checkpoint`와 `TurnFailureLedger`는 중립형이며 새 삼모 골든 의존을 추가하지 않았다.
- 2026-09-26 후속 정리 레인 통지: #958/#959/#960/#970은 main 머지 완료(#960 merge `8f4ba85e`). #967 보관 지도 로더명은 리뷰·CI 중, #974 삼모 대회 명령 제거는 판정 뒤 CI 중, #975 월말 데몬 제거는 draft 스택이다. 전체 코드·저장 키 중립화와 삼모 완전 은퇴는 아직 종료되지 않았다. 저장된 `han-world-v3-*` 지도 릴리스 ID는 핀 계약으로 유지한다. 이 worktree는 기존 변경 보존을 위해 옛 head에 남기고 기능 PR 동결·`rename-map.md` 추적을 계속한다. PEP C 리셋은 실행하지 않는다.
- 2026-09-26 추가 통지: #967 보관 지도 로더명과 #974 삼모 대회 명령 제거가 main에 병합됐다(#974 merge `37d57bca52`). #975 월말 토너먼트 런타임 제거는 최신 main 기반 ready 리뷰·CI 중이다. 전체 이름·저장 키 변경은 아직 끝나지 않아 1층 기능 PR 동결과 PEP C 리셋 보류를 유지한다.
- 상태: 코드 정리 동결 구간 중. 기능 PR은 코드 정리 완료 전까지 열지 않는다.
- 조사 대상: 장수 턴·인테이크 엔벨로프·월 경계 단계의 실패 범위, 메모리 상태와 저장 델타의 되돌리기, flush 유니크 실패 처리.
- 2026-09-25 후속 확인: 결정 문서 #916과 입력 원장 정리 #918이 main에 들어왔다. 현재 main은 bc855323b이며 이 조사 worktree는 원래 기준에 머문다. 코드 정리 완료 후 최신 main에 맞춰 참조 경로와 계약을 다시 확인한다.

## 확인한 현재 경계

- `TurnDaemonLifecycle.runTick`은 정렬된 장수를 `for`로 처리하며 장수별 실패 경계가 없다(`TurnDaemonLifecycle.kt:164` 이하). 실패가 명령 실행 뒤에 나면 다음 장수로 넘어가지 못한다.
- `TurnDaemonCommandDispatcher.dispatchEnvelopes`는 엔벨로프 배치를 `mapNotNull`로 실행한다(`TurnDaemonCommandDispatcher.kt:507` 이하). 잘못된 `sentAt`과 조정 `requestId` 불일치는 명시적으로 거절하지만, 다른 실행 예외는 배치를 벗어난다.
- `TurnRunService.runTick`은 인테이크, 장수 처리, 여러 순·월 경계, 단일 flush를 한 호출에 합친다(`TurnRunService.kt:297` 이하). 월 경계에는 치적 창, `MonthlyPipeline`, 보급·내정·징세·녹봉·월단평, 조정 만료가 직렬로 있다. 한 단계가 실패하면 뒤 단계와 최종 시계 반영이 중단된다.
- `InMemoryTurnWorld.consumeDirtyState`는 모든 변경 집합·로그를 비우며(`InMemoryTurnWorld.kt:964` 이하), `ChangeRecorder.clear`는 flush 성공 후 모든 채널을 비운다(`ChangeRecorder.kt:1026` 이하). 둘 모두 단위별 되돌리기 API가 없다. 엔티티 상태와 recorder 델타를 함께 되돌리지 않고 `catch`만 추가하면 실패 단위의 일부 변경이 다음 flush에 섞일 수 있다.
- `JdbcFlushExecutor.flush`는 한 JDBC 트랜잭션이다. `TurnRunService.flushWithGeneration`은 실패 payload를 회복 게이트에 보존하거나 재로드를 요구한다(`TurnRunService.kt:516` 이하). `FlushRecoveryGate.classify`는 비일시적 SQL 오류를 `RELOAD_REQUIRED`로 분류한다(`FlushRecoveryGate.kt:124` 이하). flush에서 유니크 위반을 발견한 뒤 payload에서 원인 단위를 단순 제거해 다시 flush하는 경로는 없다.

## 구현 순서와 완료 조건

1. **단위별 상태 경계부터 만든다.** 장수 턴·엔벨로프·월 경계 단계가 읽거나 바꾸는 월드 엔티티·로그·dirty 집합과 recorder 채널을 함께 체크포인트/복원한다. 기존 `WorldSnapshot`은 부팅 시드이고, 실행 중 dirty 집합·recorder를 담지 않아 그대로 쓰지 않는다. 실패 뒤 상태가 단위 실행 전과 같고, 실패 기록만 남는 테스트를 먼저 둔다.
2. **실행 예외를 단위 결과로 만든다.** 장수는 실패 사유 `EXECUTION_FAILED`와 예외 타입을 남긴 뒤 시계를 전진시키고 다음 장수를 처리한다. 엔벨로프는 해당 요청에 거절 결과를 영속화하고 ack한 뒤 다음 요청을 처리한다. 월 경계는 실패한 단계의 부분 효과를 되돌리고 실패 결과를 남기며 뒤 단계를 처리한다. 모든 경로에서 실패 로그에는 식별자·순·단계가 있어야 한다. 예외 원문과 비밀값은 사용자 결과에 넣지 않는다.
3. **반복 실패 3회 격리를 단위 키로 추적한다.** 같은 장수·요청·월 단계가 연속 세 번 실패하면 격리 기록과 경보를 남긴다. 다른 단위의 성공이 그 단위의 실패 횟수를 지우지 않는다. 장수 격리는 다음 월 경계에 재시도한다. 월드 전체는 정지하지 않는다(사용자 결정 기록).
4. **flush 유니크 위반을 분리한다.** 우선 은퇴 이관의 동명 카드 같은 이미 알려진 유니크 충돌은 쓰기 전 원장 판정에서 비용 없이 거절한다. 남은 DB 제약 위반은 안전하게 원인 단위를 특정할 수 있는 저장 경계를 도입할 때까지 회복 게이트를 우회하지 않는다. 모호한 커밋 결과를 성공으로 간주하거나 전체 payload를 임의로 재작성하지 않는다.
5. **실행 시 registry 검증을 기동 시점으로 옮긴다.** 코드 정리에서 입력 원장의 이름·스키마가 바뀌므로 정리된 registry 계약을 확인한 뒤 wiring 누락 시 부팅을 거절하고 정상 턴에는 재검증하지 않는다.

   현재 `ReservedTurnHandler.handle`은 매 장수마다 실행 문맥을 캡처한 `InputHandler` 맵을 만들고 `HwihaInputRegistry` 생성자의 원장↔핸들러 `require`를 다시 수행한다. `HwihaCourtHandler`도 엔벨로프마다 같은 검증을 한다. 문맥 캡처는 매 실행 필요하지만 *핸들러 ID 집합과 원장 대응*은 고정이므로 기동 시 한 번 검증하고 실행 중에는 `rejectionFor`와 배달 여부를 재검사하는 두 층으로 분리한다. 현재 동적 맵을 그대로 캐시하면 이전 장수·요청의 문맥이 붙잡히므로 금지한다.

## 검증 설계

- 러너 경유 적색 프로브: 손상된 장수 대기 메타, 실행 중 던지는 엔벨로프, 월 단계 예외를 각각 주입한다. 실패 단위의 저장·메모리 부분 효과가 없고, 같은 묶음의 다른 장수 `turnTime` 및 세계 `lastTurnTime`이 전진하며, 실패 결과가 콜드 재로드 뒤에도 남는지 확인한다.
- 유니크 충돌은 사전 거절 경로와 예기치 않은 DB 제약 위반 경로를 구분한다. 전자는 해당 단위만 거절하고 다음 장수를 처리해야 하며, 후자는 복구 게이트의 안전한 차단 상태를 유지해야 한다.
- 격리 경계나 실패 결과 저장을 잠시 제거해 프로브가 빨개지는지 확인한다. JDK 21에서 관련 테스트를 실행하고 XML 결과 시간을 확인한다. 이 문서는 조사만 기록하므로 아직 테스트를 실행하지 않았다.

## 재개 조건

- 코드 정리의 마지막 PR과 이름 대응표가 main에 들어온 뒤 파일·타입 이름을 다시 대조한다. #905는 별도 작업에서 잠겨 있으므로 이 브랜치에서 건드리지 않는다. 그때 위 항목을 안전한 크기의 PR로 나눈다.

## 체크포인트 범위 재확인

- `InMemoryTurnWorld`에는 실행 중 snapshot/restore가 없다. 단위 실행의 되돌리기는 엔티티 맵뿐 아니라 dirty·created·deleted 집합, 로그, 세계 시계·공간 상태, 장수 식별 세대, ID 고수위를 포함해야 한다. `consumeDirtyState()`는 변경 집합을 비우고 해결된 전투 계획을 제거하므로 체크포인트는 그 전에 잡아야 한다.
- `ChangeRecorder`도 rank·KV·외교·편지·입찰·전투·공간·상속 등 모든 누적 채널과 `pendingVoteKeys`, `inheritancePointBase`, `spatialWorldId`를 포함해야 한다. `clear()`는 성공 flush 후 전체 비움이라 실패 단위의 복원 수단이 아니다.
- 2026-09-26 파일 소유 조율: 로그 writer가 `InMemoryTurnWorld.kt`의 기록 sink를 연결하는 동안 이 파일의 체크포인트/복원 구현은 편집하지 않는다. 로그 writer sink PR이 ready/병합되거나 정확한 라인 소유가 합의된 뒤 시작한다. `DirtyState.kt`·`BootstrapConfig.kt`도 로그 writer 우선 소유다. 이 사이 `ChangeRecorder.kt` 등 독립 파일의 설계·준비만 진행한다.
- S4 무인 시즌 레인이 `Npc*Selector.kt`, `InputRegistry.kt`의 AI 정책 배선, `TurnRunService.kt`의 AI wiring을 우선 소유한다. 1층의 기동 시 registry 검증과 P0 runner 격리는 해당 S4 변경 병합 뒤 최신 main에 재기반하여 진행한다. `RecordKind.kt`는 로그 writer sink 계약 후, `ScenarioImporter.kt`는 S4 역사 190 시드 PR 후 소유를 재조율한다.
- 사용자 지시로 E8 도움말 서버·E9 튜토리얼 서버·E10 원장 단계 자동 증거 게이트는 별도 단일 레인에 이관됐다. 1층은 이 세 영역의 파일을 수정하지 않는다. `InputRegistry.kt` 순서는 S4 D3 AI 정책 배선 → E10 parser/증거 게이트 → 1층 기동 시 정적 검증으로 조율한다. 입력별 핸들러와 B1 입력은 여전히 1층 소유다.
- 외부 ID 할당자와 `kvWriteObserver`, 외교 식별 oracle은 메모리 복원 밖에 부작용이 생길 수 있다. 단위별 catch를 먼저 배선하면 부분 효과가 남는다. 식별 번호는 재사용하지 않는 방향으로 다루고, 외부 쓰기는 커밋 이후로 옮기거나 별도 보상 계약을 확인한다. 장수 identity token 카운터를 되감는 경우 ABA 위험이 있으므로 맵 복원과 카운터 재사용을 분리한다.
- 이 범위가 닫히기 전에는 실패 ledger를 runner에 연결하지 않는다. 이번 로컬 보강은 복원된 ledger의 불가능한 격리 상태와 월 번호 오버플로를 거절한다.

## 동시 작업 기록

- 사용자 지시에 따라 코드 정리와 겹치지 않는 `engine.turn`의 연속 실패 정책을 이 worktree에서 먼저 구현했다. `TurnFailureLedger`는 장수·엔벨로프·월 단계별 실패 횟수를 독립적으로 세고, 세 번째 실패에서 같은 달 실행을 막고 다음 달 재시도를 허용한다. 성공 시 해당 단위의 횟수만 지우며, 상태 스냅샷으로 재시작 시 복원할 자료를 제공한다.
- 이 상태 기계는 아직 턴 실행·영속화에 연결되지 않았다. 월드와 recorder의 원자적 되돌리기를 먼저 구현하지 않고 예외를 잡아 연결하면 부분 변경이 저장될 수 있다. 코드 정리 완료 전 기능 PR은 열지 않는다.
- 검증: 첫 JDK 21 실행은 디스크 공간 부족으로 `logic:processResources`와 `common:compileKotlin`이 중단됐다. 중지된 Gradle 데몬의 오래된 로그를 정리한 뒤 같은 대상 테스트를 재실행했다. `:app:game-engine:test --tests opensamguk.engine.turn.TurnFailureLedgerTest`는 3건 통과(실패·오류 0), `TEST-opensamguk.engine.turn.TurnFailureLedgerTest.xml` 갱신 시각은 2026-09-25 20:04:03 KST다. 빌드 시간은 병행 컴파일 영향으로 16분 46초였다. 다른 엔진 테스트나 러너 경유 검증은 아직 실행하지 않았다.
- 같은 격리 요구의 구체적인 flush 유니크 실패 경로도 막기 시작했다. 은퇴·승계 공통 사전검사에서 기존 카드 이름과 승계될 카드 이름, 바깥 주인의 카드 이름과 바뀔 이름을 대조해 DB 유니크 충돌 전에 `RETAINER_NAME_CONFLICT`로 거절하도록 로컬 수정했다. 충돌 두 형태의 프로브를 `HwihaRetireRulesTest`에 추가했다. 코드 정리의 logic 개명과 충돌하면 `rename-map.md`에 따라 옮긴다.
- 2026-09-25 23:33 KST: 조율된 단독 Gradle 슬롯에서 JDK 21 선별 테스트를 실행했다. `:logic:test --tests opensamguk.logic.input.HwihaRetireRulesTest` 3건, `:app:game-engine:test --tests opensamguk.engine.turn.TurnFailureLedgerTest` 4건 모두 통과했고 XML의 실패·오류는 0이다. 전체 실행은 3분 41초였다. 실행 뒤 슬롯을 해제했다. 은퇴 충돌의 실제 JDBC flush 통합 검증과 runner의 일반 예외 격리는 아직 남아 있다.
- 2026-09-26 로컬 후속: 은퇴자의 바깥 카드가 후계자의 휘하 카드인 상호 휘하 링크는 실행 핸들러에서만 `STATE_UNAVAILABLE`로 거절됐다. 같은 판정을 공유 `HwihaRetireRules.assess`에 추가하고 테스트 1건을 더했다. JDK 21 단독 Gradle 슬롯의 `:logic:test --tests opensamguk.logic.input.HwihaRetireRulesTest`는 4/4 통과했고 XML의 skipped·failed·errors는 0이다. 새 제품 코드 이름은 중립형으로 짓고, 기존 Hwiha 이름의 수정은 코드 정리 이후 rename-map에 맞춰 옮긴다.
- 2026-09-26 로컬 후속: `ChangeRecorder`에 단위 실행 전 누적 변경을 보존하는 `checkpoint()`/`restore()`를 추가했다. 일반 행 패치, KV, 투표, 외교, 메시지, 입찰, 전투, 공간, 상속, 연감, 아카이브, 예약 턴 등 내부 채널의 순서와 중첩 map/list/set을 복원한다. 다른 recorder의 체크포인트는 거절한다. 외부 ID 할당자는 되감지 않아 실패 후 번호에 빈칸이 생길 수 있고, `kvWriteObserver` 및 `generationSession`의 외부 효과도 되돌리지 않는다. 월드 상태 체크포인트와 외부 효과 경계가 닫힐 때까지 runner에서 사용하지 않는다. 전용 `ChangeRecorderCheckpointTest` 3건은 2026-09-26 JDK 21 단독 Gradle 슬롯에서 통과했다(skipped·failures·errors 0, 1분 40초). 검증 head는 `88ee3f61fe4a5fab37d7cc48c798e99d1ee490a8`과 로컬 미커밋 변경이다. `git diff --check`도 통과했다.
