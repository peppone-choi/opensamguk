# 2026-09-27 #917 — 경매 엔진·인프라 쓰기 경로 제거(A2)

## 범위

A1(#991)이 사람의 경매 입력(입찰·개설 명령, 조회 API)을 지운 뒤에도 엔진에는 월별 중립 경매 등록(Q16)·만료 데몬·정산 명령·recorder/flush 쓰기·부팅 스냅숏 meta·JPA/JDBC 저장소가 남아 있었다. 이번 조각은 표(`ng_auction`·`ng_auction_bid`)만 남기고 나머지를 모두 지운다.

### 지운 것

- 엔진 `engine/auction/*`: `AuctionFinalizeHandler`·`AuctionExpiryDaemon`·`TurnDaemonCommandHandler`(구현체가 정산 핸들러 하나뿐이었다).
- 로직 `logic/auction/*`와 테스트 4개: `AuctionBase`·`AuctionDetail`·`AuctionDto`(`AuctionType`·`ResourceType`·`AuctionInfo` 등)·`AuctionResultCalculator`·`DummyGeneral`·`NeutralAuctionRegistrar`·`ObfuscatedNamePool`. `DummyGeneral`은 `logic/war/WarUnitCity.kt`·`WarUnitGeneral.kt`에서 **주석(PHP 개념 이름)**으로만 나오고 코드 참조는 0건이라 같이 지웠다.
- 인프라: `AuctionEntity`·`AuctionBidEntity`(와 enum 변환기), `AuctionRowMapper`·`AuctionBidRowMapper`(파일 전용 `boolOf` 포함), `AuctionRepository`·`AuctionBidRepository`(와 raw/월드 범위 구현), `SideReadRepositoryConfiguration`의 두 빈, `JdbcFlushExecutor`의 `auctionUpsertMany`·`auctionBidInsertMany`와 두 호출 자리(통일 flush·일반 8b), `FlushPayload.auctionUpserts/auctionBidInserts`, `AuctionUpsertRow`·`AuctionBidInsertRow`.
- recorder: `ChangeRecorder`의 경매 채널 두 개·`recordAuctionUpsert`·`recordAuctionBidInsert`·조회 함수·`auctionIdAllocator`, `DirtyState`의 `AuctionUpsert`·`AuctionBidInsert`와 (아무도 채우지 않던) 두 필드, `DatabaseHooks`의 경매 행 매핑과 `refreshActiveUniqueAuctionProjection`.
- 월별: `postUpdateMonthlyTail`의 `registerAuction`(Q16) 매개변수·호출, `MonthlyPostUpdateHook.registerAuction`·경매 저장소 매개변수·(Q16 전용이던) `turnTerm()`.
- 배선: `TurnDaemonCommandDispatcher`의 경매 저장소 매개변수·정산 핸들러·분기, `TurnRunService`의 경매 저장소 매개변수·만료 데몬·`runTick`의 만료 호출, `DaemonLoopConfig`의 두 빈 매개변수·부팅 경매 id 할당자, `WorldActionContext`의 쓰이지 않던 두 매개변수.
- 스냅숏: `WorldSnapshotLoader.loadActiveUniqueAuctionItems`와 meta `activeUniqueAuctionItems`·`activeUniqueAuctionItemsById`, `ReservedTurnHandler.occupiedUniqueCounts`의 경매 가산(함수는 유지).
- wire: `TurnDaemonCommand.AuctionFinalize`, `AuctionFinalizeOk/Fail`와 결과 디코더 분기.
- 미배선(SUPERSEDED) `RehydrateService`와 `RehydrateServiceTest`. 운영 호출자 0건(자기 KDoc이 「NOT WIRED, AND MUST NOT BE」라 적고 있고, 저장소 전체 grep에서 테스트 외 참조 0건). 배팅·select_pool 조회도 같이 사라지지만 운영 경로는 원래 쓰지 않았다.
- `HotColdCatalog`: 스냅숏 `loadActiveUniqueAuctionItems`, 부팅 할당자 `auctionRepository.findMaxId`, `MonthlyPostUpdateHook`·`AuctionExpiryDaemon`의 `findByFinishedFalse`, `AuctionFinalizeHandler` 두 호출, `RehydrateService` 직접 SQL 경계, `engine/auction` 런타임 소스 디렉터리, 이제 안 쓰는 `AccessBoundary.REHYDRATE_RECOVERY`.
- CQRS 기준선 소스셋: `CqrsBaselineMain`의 관측 정의·지표, `LocalSanitizedAggregateMaterializer`의 0 지표, `loader-input-inventory.json`·`local-sanitized-aggregate-policy.json`(두 프로필 항목 + 박힌 inventory SHA-256·정책 SHA-256 재계산), `tools/cqrs/production-shape-manifest.schema.json`, `tools/cqrs/test_production_shape_manifest.py`.
- 주석 속 경매 비유(보드·투표·외교 서신·즉시 행동·`PhpJson`·`PostgresValueEnumJdbcType` 등)를 정리했다.

### 디스패처 생성 조건

`TurnRunService`는 디스패처를 `auctionRepository != null && auctionBidRepository != null && boardPostRepository != null`일 때만 만들었다. 이제 `boardPostRepository != null` 하나다. 운영(`DaemonLoopConfig`)은 늘 셋 다 넘겼으므로 동작은 같고, HWIHA `ImmediateInput`을 포함한 모든 즉시 입력이 그대로 디스패처로 간다. 테스트 중 이 조건에 기대던 곳은 `EnlistmentFixture`(`intake` 플래그로 세 저장소를 함께 켜던 곳)와 `ProfileIconSyncLifecycleTest`뿐이며 둘 다 `boardPostRepository`를 같은 조건으로 넘기므로 결과가 같다.

## D(표 DROP)로 남기는 것과 이유

- 표 `ng_auction`·`ng_auction_bid`, PG enum `ng_auction_type`·`ng_auction_resource`, V1의 옛 `auction`·`auction_bid` 이름, 이들을 만든 Flyway 마이그레이션(V1·V7·V31·V32)과 마이그레이션 테스트(`V7MessagingEconomyMigrationTest`·`V31WorldScopeExpandMigrationTest`·`V32WorldScopeCompletionMigrationTest`).
- `TruncateContract`의 `auction`·`auction_bid`·`ng_auction`·`ng_auction_bid` 분류(그 테스트는 V1 기준선만 파싱한다).
- 이유: 표 삭제는 되돌릴 수 없는 운영 스키마 변경이라 DROP 마이그레이션·`TruncateContract`·마이그레이션 테스트를 한 PR(D)에서 따로 검토한다. 이번 조각 뒤로는 어떤 코드도 두 표를 읽거나 쓰지 않는다(아래 grep).

## Q16 제거가 다른 추첨을 밀지 않는 근거

1. 월 RNG는 달마다 새로 만든다. `DaemonLoopConfig`의 `monthlyRngFactory = { year, month -> MonthScopedRng.forMonth(hiddenSeed, year, month) }` → `MonthlyPipeline.runMonth`가 `monthlyRngFactory(oldYear, oldMonth)`로 한 번 만들고(`MonthlyPipeline.kt` L4) `postUpdateMonthly.run(monthlyRng)`에만 넘긴다(L10, 「the ONLY consumer」). 다음 달로 이어지는 상태가 없다.
2. 한 달 안의 소비 순서: `MonthlyPostUpdateHook.run`에서 Q4 `postUpdateMonthlyPower(…, monthlyRng)` → 꼬리 `postUpdateMonthlyTail(rng = monthlyRng)` 안에서 Q11 `checkWander(rng)` → Q12 `updateGeneralNumber()`(RNG 없음) → Q16 `registerAuction(rng)` → Q17 `setNationFront()`(RNG 매개변수 없음). 꼬리 뒤의 `retainerMonthly.settle(world, recorder)`·`operationMonthly.settle(world, recorder)`도 RNG를 받지 않는다(두 서비스 파일에 rng/rand 참조 0건). 즉 Q16은 이 인스턴스의 마지막 추첨이었다.
3. Q16의 다른 부수 효과는 recorder 경매 채널(→ flush)과, 그 채널이 비어 있지 않을 때 `DatabaseHooks`가 meta 두 키를 다시 쓰던 것뿐이다. 월드 행(장수·도시·국가)에는 손대지 않았다.
4. 테스트로 박았다: `PostUpdateMonthlyTailTest`는 꼬리 호출 뒤 같은 RNG의 다음 값이 기준 RNG의 두 번째 값과 같음(= Q11 말고 소비 0)을, `MonthlyPostUpdateHookTailWiringTest`는 빈 `ScriptedRng`(nextBool/nextRangeInt를 부르면 즉시 예외)로 월 정산 전체가 끝까지 도는지를 본다. 예전 테스트들이 넘기던 `bools = [false, false]`는 정확히 Q16의 두 관문 몫이었다(Q11이 bool을 하나라도 썼다면 Q16의 두 번째 관문에서 빈 큐 예외가 났을 것이다).

## 후속: world-state 해시

부팅 meta에서 `activeUniqueAuctionItems`가 빠지고(옛 로더는 행이 없어도 빈 목록을 심었다), 중립 경매가 열리던 달에 `DatabaseHooks`가 meta 두 키를 다시 심던 일도 사라진다. `WorldStateBaseline`은 `state.meta` 전체를 해시하므로 `app/game-engine/src/test/resources/invariance/world-state-sha256.txt`의 `s3-chain-48`(`PassChainInvarianceIT`, Spring·Postgres 경로)은 바뀔 것이다. 이 파일은 손대지 않았다 — Postgres로 다시 재서 갱신해야 한다. `yuzhou-36-seed-00/01`은 메모리 전용 lifecycle 경로(로더·flush·월 정산 훅 미사용)라 바뀌지 않을 것으로 본다(미실측).

## 운영 데이터 위험(미확인)

만료 데몬·정산이 사라졌으므로 이미 열려 있던 경매 행은 영원히 끝나지 않는다. A1 전에 입찰해 자원이 묶인 장수가 pep에 있다면 환급·낙찰이 일어나지 않는다. pep에 열린 경매가 있는지는 조회하지 않았다(UNKNOWN). 예정된 리셋 또는 D의 DROP 전에 확인할 일이다.

## 정적 점검 결과

- Gradle·Docker는 돌리지 않았다(다른 에이전트의 Gradle/Postgres 테스트와 겹치면 멈춘다). 컴파일·테스트는 조율자가 이어서 돌린다.
- `git grep -n -i -E 'auction' -- ':!docs' ':!*.md' ':!web'` 남은 곳(모두 의도):
  - 마이그레이션과 그 테스트: `V1__baseline.sql`·`V7__p6_messaging_economy.sql`·`V31__world_scope_expand.sql`·`V32__complete_world_scope_expand.sql`, `V7MessagingEconomyMigrationTest`·`V31WorldScopeExpandMigrationTest`·`V32WorldScopeCompletionMigrationTest` → D.
  - `TruncateContract`(5) → D.
  - 예약 서버/경로 이름 목록: 워크플로 3개(`deploy`·`promote-game-server`·`reset-game-server`), `FrontInfoController`, gateway `DeployService`·`ServerRegistry`와 그 테스트 2개, `infra/nginx/default.conf`(2), `tools/ops/game_server_recovery.py` → 지시대로 유지.
  - `LogFeedReadRepository` 주석(3) → 다른 소비자가 있는 함수라 유지.
  - 은퇴 증명 테스트: `CommandWireMapperTest`(A1), `TurnDaemonCommandResultWireTest`의 `auctionFinalize` 거절 단언, `wire_commands_malformed.json`의 `auctionFinalize` 거절 항목, `WorldSnapshotLoaderArchiveIT`·`WorldSnapshotLoaderWorldScopeIT`의 meta 키 부재 단언, `MonthlyPostUpdateHookTailWiringTest`의 Q16 은퇴 단언.
  - 은퇴 이력 주석: `RehydrateWiringTest` KDoc, `PostUpdateMonthly.kt`의 Q16 주석.
- 지운 심볼(`AuctionRepository`·`AuctionEntity`·`AuctionUpsertRow`·`AuctionFinalize`·`RehydrateService`·`ObfuscatedNamePool`·`DummyGeneral` 등 40여 개)의 코드 참조: 0건(주석만 남음).
- `HotColdCatalog` 호출·횟수: `HotColdWorldCatalogGuardTest`의 발견 규칙(`runtimeReadCalls`·`directSqlCalls`)을 파이썬으로 옮겨 HEAD에서 카탈로그와 정확 일치(참)를 먼저 확인한 뒤 작업본에 돌렸다 — 호출 키·횟수 일치, 직접 SQL 원천 = {`JdbcFlushExecutor`, `V2CityLedgerStore`} 일치, 스냅숏 로더 메서드 집합 = 카탈로그 일치.
  - `DaemonLoopConfig` 부팅 할당자: `messageRepository.findMaxId` 1→1, `auctionRepository.findMaxId` 1→0(항목 제거), `battleReplayRepository.findMaxId` 1→1.
  - `MonthlyPostUpdateHook`: `auctionRepository.findByFinishedFalse` 1→0(항목 제거).
  - `AuctionExpiryDaemon`: `auctionRepository.findByFinishedFalse` 1→0(파일·항목 제거).
  - `AuctionFinalizeHandler`: `auctionRepository.findById` 2→0, `bidRepository.findByAuctionIdOrderByAmountDesc` 1→0(파일·항목 제거).
  - 손댄 파일의 나머지 호출은 그대로: `TurnDaemonCommandDispatcher`(`repo.findByTableAndNamespaceAndKey` 1, `repo.findByTable` 2, `repo.findPollState` 1, `reader.findMessage` 1), `TurnRunService`(`inbox.*` 각 1), `WorldActionContext`(`gameKvRepository.findByTable` 3, `bettingRepository.findByBettingId` 1, `inheritanceRepository.findByTableAndNamespaceAndKey` 1), `DaemonLoopConfig`(`reservedTurnRepository.readReserved` 2, `readReservedNationTurn` 1).
  - phase-hot 후보가 경매 둘뿐이었으므로 `phase hot candidates …` 테스트의 「비어 있지 않음」 단언을 「현재 후보 집합 = 빈 집합」 핀으로 바꿨다(새 후보를 들이면 핀과 규약을 함께 고쳐야 한다).
- naming lint: retired_reference 5828→5815, `tools/ci/naming_lint_baseline.json` 갱신 후 전 항목 OK.
- `python3 -m unittest test_production_shape_manifest`: 23건 통과(패키지 jar·Docker가 필요한 3건은 환경 변수 게이트로 건너뜀). `test_run_runtime_baseline`: 24건 통과.
