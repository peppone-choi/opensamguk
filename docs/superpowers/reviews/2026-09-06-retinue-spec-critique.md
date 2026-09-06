# 휘하 인물 · 부곡 수직 절편(Phase 4X-A) 스펙 교차 비평 — 통합본 (v3 판정)

- Date: 2026-09-06 (v1 비평 → v2 재판정 → v3 통합 재판정, 같은 날)
- Target: `docs/superpowers/specs/2026-09-06-retinue-buqu-vertical-slice.md` (**v3**, REVISED)
- Plan: `docs/superpowers/plans/2026-09-06-ui-redesign-implementation-plan.md` §Phase 4X-A
- Verdict: **cleared** — v2 의 fix-required 두 건(N1 flush 순서 vs UNIQUE, N2 툼스톤 시점)은 v3 §3 의 「표마다 DELETE → CREATE → UPDATE」 와 「`removeGeneral` 안 즉시 가지치기 + 정산의 실시간 주인 읽기·건너뛰기」 로 코드 대조상 닫혔다. should-fix N4·N6·N7·N8 과 Q5 도 닫혔다. 남는 것은 **should-fix 2건(P1·P2, 비차단)** 이다 — N3 의 「네 지점」 이 실제 공급 지점을 하나 빠뜨려 **인테이크 전용 flush 가 `meta.maxRetainerId` 를 0 으로 덮는다**(P1, 증상은 N3 과 같은 재기동 후 삭제-최대 id 재사용이라 차단 아님), 그리고 N5 의 「engine 4」 는 골든이 아닌 파일 수다(P2). 이 절편을 v3 대로 구현해도 실제 결함이나 프로젝트 규칙(CLAUDE.md · ADR · 월드 스코프 규약) 위반은 없다. 코드 근거는 전부 이 워크트리에서 직접 연 파일:줄이다.

---

## 0. v3 판정표 (v2 항목별)

| 항목 | 판정 | 근거(코드) |
|---|---|---|
| **N1** 8g 순서 INSERT→…→DELETE vs `UNIQUE (world_id, master_general_id, name)` | **cleared** | v3 §3: 표마다 `retainerDeleteMany → retainerCreateMany → retainerUpdate → bugokDeleteMany → bugokCreateMany → bugokUpdate`. 검산 4경우 — ① 해제 R1(부곡 B 지휘)+같은 이름 서약 R2+B 에 R2 배정: DELETE R1(DB `SET NULL (commander_retainer_id)` 로 B.commander NULL) → INSERT R2(UNIQUE 충돌 없음) → UPDATE B(commander=R2) ✓ ② 편성 B + 새 R2 배정: INSERT R2 → INSERT B ✓ ③ 해산 B + 해제 R1: DELETE R1 → DELETE B ✓ ④ 같은 틱 생성-후-삭제: `removeTroop` 규칙(`InMemoryTurnWorld.kt:482-491`) 그대로라 DB 작업 0 ✓. 부곡이 참조하는 **새** 가신은 항상 부곡 CREATE/UPDATE 앞에 INSERT 된다. general DELETE(5단계 `JdbcFlushExecutor.kt:149-152`)가 8g 앞이라 주인 사망 CASCADE 뒤 8g 에 남는 참조는 없다(N2 가지치기 전제). UPDATE 의 `requireExactlyOneAffected`(`:2331-2335`)는 값이 같아도 매치 행을 세므로 SET NULL 과 명시 NULL UPDATE 의 겹침은 무해. 단계 라벨 8g 는 비어 있다(8d `:212` → 8e `:226` → 8c `:245` → 8f `:254` → [event INSERT `:263`] → 9 `:271`; `8g` 검색 0건). |
| **N2** 툼스톤 전파를 `consumeDirtyState` 로 미룸 | **cleared** | v3 §3: `InMemoryTurnWorld.removeGeneral` 안에서 즉시 가지치기(map + dirty/created/deleted). 프로덕션의 장수 삭제 기록자는 **둘뿐**이고 둘 다 `recorder.markGeneralDeleted` → `world.removeGeneral` 이다: `InstantActionHandler.kt:46`(dieOnPrestart, 인테이크) · `ReservedTurnHandler.kt:1068`(사망 드레인). `ChangeRecorder.markGeneralDeleted :1158-1180` 의 마지막 문장이 `world.removeGeneral(generalId)` `:1179`; `removeGeneral` 자체는 `:267-280`(`generalPosition` 즉시 가지치기 `:270` 선례). `world.removeGeneral(` 의 다른 호출자는 main 전체에서 0건. 월 훅 안에서 장수를 삭제하는 경로는 없다(`MonthlyPostUpdateHook`·`markNationDeleted` — 장수는 재야로 생존, `ConquerCity.kt:32,154,604`). 따라서 L10 정산(`MonthlyPipeline.kt:126`)이 고아를 만나지 않는다. §5 「`getGeneralById(master) == null` 이면 건너뛴다」 가 방어선. 정산의 주인 읽기는 `run()` 시작 스냅샷(`MonthlyPostUpdateHook.kt:70` `val generals = world.listGenerals()`)이 아니라 실시간 — checkWander 가 같은 `run()` 안에서 장수를 이미 바꾼 선례 `:271-272`. |
| **N3** meta 키 영속 접점 | **should-fix (잔존) → P1** | v3 가 적은 네 지점 중 ①「`TurnRunService.runTick` 이 worldState 에 두 키 공급」 은 실제 공급 구조와 다르다. 기본 `worldStateUpdate` 는 **`DatabaseHooks.toFlushPayload`** 가 만든다(`DatabaseHooks.kt:633-634` `max_nation_id`/`max_general_id`; `:247` 은 `@Deprecated(level=ERROR)` overload `:172-176` 안이라 무관). `TurnRunService` 는 그 위에 다시 덮는 빌더가 **둘**이다 — `runTick :404-405` 와 `currentWorldStateUpdate :573-584`(호출자 `runIntakeCommands :250`, `runDueGeneralTurns :272`; 둘 다 프로덕션 루프 `TurnDaemonRunner.kt:229,285,293`). `JdbcFlushExecutor.kt:515` 는 키가 없으면 `?: 0` 으로 바인딩해 `meta || jsonb_build_object(..., 'maxGeneralId', :max_general_id)`(`:537-541` CAS 분기 · `:559-563` 비CAS 분기)에 **0 을 쓴다**. ②(실행기 두 분기)·③(`WorldSnapshotLoader.kt:74-90` `snapshotKeys`, `coldBootMetaKeys :690` 에 미포함이면 됨)·④(`InMemoryTurnWorld.kt:155-158` 시드, `:164-165` init 즉시 record, `:257-260` bump, `:467-469` record)는 맞다. |
| **N4** 적색 프로브 구성 | **cleared** | v3 §8: 새 world+recorder 두 벌 + `ScriptedRng`/`auctionRepo()`. `MonthlyPostUpdateHookTailWiringTest.kt:44-57` `ScriptedRng`(`nextBool` = 큐, bools 전부 false), `:496-504` `auctionRepo()`(v2 가 적은 `:66-70` 은 **오기** — 정정). 벽시계 두 곳은 모두 RNG/메타 게이트 뒤다: `triggerTournament :365-369`(`tournament` meta ≠ 0 / `tnmt_trig` / `rng.nextBool(0.4)` 셋 중 하나면 return → `Instant.now()` `:382` 도달 안 함), `registerAuction :398` 의 `Instant.now()` 는 `registerNeutralAuctions` 인자로만 가고 `rng.nextBool` 로 안 열리면 recorder 에 닿지 않는다. 비교 대상은 전부 시각 필드가 없는 data class — `LogEntryDraft`(`TurnWorldModel.kt:146-158`, timestamp 없음), `DirtyState`, `RowPatch`. |
| **N5** 골든 수 | **should-fix (경미) → P2** | logic 실측 274 json(`find … -name '*.json'` = 274, 전체 파일 275 = +`.gitignore`) ✓. 「engine 4」 는 `app/game-engine/src/test/resources` 의 파일 4개인데 **골든이 아니다**: `mockito-extensions/org.mockito.plugins.MockMaker`, `parity/php-unique-item-lottery-call-sites.txt`, `scenario/scenario_3190_test.json`, `db/migration_v2/V900__v2_sandbox_probe.sql`. `LongSimReplayGateTest` 는 리소스 골든을 읽지 않는다(파일 안 `resources`/`golden` 참조 0건). v2 비평이 「engine 4 파일」 이라 적은 것이 스펙으로 흘러들었다 — 판정자 오기. |
| **N6** `UnitCatalog.byId` id<1000 던짐 | **cleared** | `common/src/main/kotlin/opensamguk/common/constants/UnitCatalog.kt:51-59` — `setOf(id) == null` 이면 `require(id >= 1000)` `:55` → 던진다; ≥1000 미등록은 null. v3 §6 `if (crewTypeId >= 1000) UnitCatalog.byId(crewTypeId)?.name ?: "-" else "-"` 가 정확히 그 가드. (판정 의뢰문의 `app/game-api/.../UnitCatalog.kt` 경로는 오기 — 파일은 `common` 에 있다.) |
| **N7** 단계 이름·가드 | **cleared** | 8g 라벨 비어 있음(N1 행). `isNotEmpty()` 가드 선례 `JdbcFlushExecutor.kt:143,150,215`. `lastOps` 를 표 단위로 세는 게이트 `RehydrateLosslessGateIT.kt:135-139`(`executor.lastOps().count { it.table == "ng_auction" }`). |
| **N8** 결과 코드 핀 | **cleared** | `TurnDaemonCommandResult.kt:30` sealed, 직렬화기 `selectSerializer :647-706`, 집합 분기 선례 `BOARD_ACTION_TYPES :660`, `else -> throw IllegalArgumentException("unknown result type=$type")` **`:705`**. v3 §4·§8 「직렬화기 왕복 테스트로 6 코드 핀」 이 실제 실패점을 겨눈다. |
| **Q5** rehydrate 범위 | **cleared** | `RehydrateLosslessGateIT.kt:129-145` 는 `ng_auction` 투영 단언(표 열거 아님). v3 §0 Q5(해소) · §8 「손대지 않는다」 가 맞다. `WorldSnapshotLoader` 의 표 적재 선례 `loadTroops :446`, `loadAccessLogs`(`:140,142`) → `WorldSnapshot(troops=, accessLogs=)` `:161,163`. |
| **ADR-LITE-018 (판정자 소견)** | **비차단 — 사용자 확인 항목 유지** | `.ai/decisions.md:178-183` ADR-018(approved; parity 조항만 ADR-042 로 supersede): v1↔v2 「별도 DB」. 같은 파일 `:220` 의 후속 결정 (6) 「한 프로세스 = 한 월드 = 한 DB … ADR-LITE-018 의 별도 DB 결정과 정합」 이 있고, 이 사슬은 이미 `v2_city_ledger`(flush 14단계 `JdbcFlushExecutor.kt:352`)·`/api/v2/**` 를 같은 코드베이스·DB 에 둔다. v3 §10 이 「사용자 확인 항목」 으로 남긴 것은 정확하다. 스펙을 막지 않는다. |

### v3 변경 문장 중 코드로 확인한 것(맞음)

| v3 주장 | 확인한 근거 |
|---|---|
| F3 `ON DELETE SET NULL (commander_retainer_id)` — PG15+ 열 지정 | PG16: `docker-compose.production.yml:7` `postgres:16-alpine`, Testcontainers 4곳 `postgres:16-alpine`. 저장소엔 열 지정 SET NULL 선례가 **없다**(`V40:4,11,33,37`·`V51:9`·`V54:16` 은 단일열 FK 의 무인자 `SET NULL`) → V55 가 첫 사용. 구문 실행은 `RetainerFlushIT`(§8) 가 검증하며 이 판정에서는 **미실행(UNKNOWN)**. |
| V32 인벤토리 테스트가 SET NULL 을 막지 않는다 | `V32WorldScopeCompletionMigrationTest.kt:505-516` 은 나열된 표의 FK 를 정규화 문자열로 **정확히** 핀할 뿐 신규 표엔 무관; `:533-536` 은 `world_state` FK 무액션(3표 예외)만; `:498-502` UNIQUE 는 `(world_id,` 선행만 요구. 스펙 §2 의 PK·UNIQUE·INDEX 전부 world 선행 ✓. |
| `TruncateContract` 두 표 등록 | 등록은 분류 계약이지 실행기가 아니다(`TruncateContract.kt:46-75`; 런타임 TRUNCATE 는 main 에서 `V48` 마이그레이션 1건뿐). `TruncateContractTest.kt:52-58` 은 **V1 baseline 표만** 분류를 강제하므로 신규 표 등록은 선택이나 스펙대로 넣는 게 일관. |
| `MonthlyPostUpdateHook` 생성자 nullable 선택 의존 + `run()` 마지막 문장 `postUpdateMonthlyTail` | `MonthlyPostUpdateHook.kt:56-66`(`auctionRepository: … = null` 등), `:218-229`. `postUpdateMonthlyTail`(`PostUpdateMonthly.kt:383-423`) 은 Q11→Q12/13→Q14→Q15→Q16→Q17 뒤 결과를 반환하므로 그 뒤의 `settle` 은 Q17 뒤 = L10 의 맨 끝. L6 조기 반환 `MonthlyPipeline.kt:112`. |
| flush 는 매 틱 무조건 | `TurnRunService.kt:415`·`:253`·`:278` `flushWithGeneration(payload)` 에 dirty 게이트 없음 → 가신 임무 변경처럼 general 패치가 없는 틱도 8g 가 flush 된다(`recorder.isDirty :260-287` 는 flush 트리거가 아니다). |
| `diffGeneral` 의 generation 게이트 안에서 정산 가능 | `ChangeRecorder.gateMutation :95-97` — 월 훅의 다른 단계가 이미 같은 generation 안에서 `diffGeneral` 을 부른다(`MonthlyPostUpdateHook.kt:271`). |
| 같은 틱 생성-후-갱신 행 | `DatabaseHooks.kt:260,669` 가 troop 의 created id 를 UPDATE 목록에서 뺀다 — 8g 도 같은 미러를 권한다(안 빼도 INSERT→UPDATE 가 1행 매치라 실패는 아니고 `lastOps` 만 하나 는다). 주석 사항. |

---

## 1. 신규 발견 (v3 에서 생긴 것만)

### P1. (should-fix) N3 의 「네 지점」 이 기본 공급 지점을 빠뜨렸다 — 인테이크 전용 flush 가 `meta.maxRetainerId/maxBugokId` 를 0 으로 덮는다

- 스펙: §3 「① `TurnRunService.runTick` 이 worldState 에 두 키를 공급(`max_general_id` 옆) ② `JdbcFlushExecutor` 의 world_state meta 병합 두 곳(정규 flush·retained flush) ③ `WorldSnapshotLoader` 허용 키 ④ `InMemoryTurnWorld` 시드·bump」.
- 코드: `worldStateUpdate` 의 원천은 `DatabaseHooks.toFlushPayload`(`DatabaseHooks.kt:576`, 키 `:633-634`)이고, `TurnRunService` 는 그 위에 덮어쓰는 빌더가 둘이다 — `runTick :396-405` 와 `currentWorldStateUpdate :573-584`. 후자는 `runIntakeCommands :250`·`runDueGeneralTurns :272` 가 쓰며, 이 둘은 `TurnDaemonRunner.kt:229,285,293` 의 **프로덕션 루프**다. `retainerPledge` 같은 인테이크 명령은 대개 `runIntakeCommands` 로 flush 된다. 실행기는 키가 없으면 `(worldState["max_general_id"] as? Number)?.toInt() ?: 0`(`JdbcFlushExecutor.kt:515`) 으로 **0 을 바인딩**해 `meta || jsonb_build_object(...)`(`:537-541`, `:559-563`) 에 쓴다. 즉 ① 을 문자 그대로 `runTick` 에만 넣으면, 서약이 들어온 바로 그 flush 가 `meta.maxRetainerId = 0` 을 쓴다. 다음 `runTick` 이 다시 바른 값을 쓰지만 그 사이 재기동이면 시드는 `maxOf(snapshot max, 0)` 이라 삭제된 최대 id 가 재사용된다 — N3 과 같은 증상, 이 절편엔 잔존 참조가 없어(commander 는 SET NULL + 메모리 가지치기) 손상은 없다 → should-fix.
- 또 ② 의 「정규 flush·retained flush」 는 실행기 구조와 다르다 — 두 SQL 은 `worldStateUpdate` 안의 **CAS 분기(`:525-546`) / 비CAS 분기(`:547-566`)** 이고, retained flush(`retryRetainedFlush :477`) 는 같은 메서드를 같은 payload 로 다시 부른다. 이름을 고쳐야 구현자가 없는 메서드를 찾지 않는다.
- 고침(스펙 §3 N3 문장 교체): ① 은 **`DatabaseHooks.toFlushPayload` 의 `worldStateUpdate` 맵(`:633-634` 옆)** 에 `max_retainer_id`/`max_bugok_id` 를 넣는 것으로 쓴다 — 그러면 `runTick`·`runIntakeCommands`·`runDueGeneralTurns` 세 경로가 전부 base 를 물려받는다(`TurnRunService` 의 두 빌더는 덮어쓰기라 손대지 않아도 되고, 손댄다면 `:404-405` 와 `:582-583` **둘 다**). ② 는 「`JdbcFlushExecutor.worldStateUpdate` 의 파라미터 바인딩 `:515` 옆 + CAS/비CAS 두 SQL 분기」 로 쓴다. ④ 에 「`init` 에서 시드 직후 즉시 `recordMax*()`(`InMemoryTurnWorld.kt:164-165` 선례) — 한 번도 할당하지 않는 세계가 0 대신 시드값을 flush 하도록」 을 더한다. 게이트: `WorldSnapshotLoaderRetainerTest` 왕복을 `runTick` 이 아니라 **`DatabaseHooks.toFlushPayload(...).worldStateUpdate` 가 두 키를 담는지** + 인테이크 경로(`currentWorldStateUpdate` 를 타는 `runIntakeCommands`) 로 flush 한 뒤 reload 해서 id 가 이어지는지로 짜라(`runTick` 만 타면 검사가 버그를 공유한다).

### P2. (should-fix, 경미) 「engine 4」 는 골든 수가 아니다

- 스펙: §0 N5 「실측: logic 골든 274 json + engine 4」, §8 「기존 골든(logic 274 json · engine 4, `LongSimReplayGateTest` 등)」, §8 마지막 「기존 골든(logic 274 · engine 4) 회귀」.
- 실측: logic 274 json 은 맞다. `app/game-engine/src/test/resources` 의 4 파일은 `MockMaker` 설정 · parity 호출지 txt · 테스트 시나리오 json · v2 샌드박스 SQL 이고 골든이 아니다. `LongSimReplayGateTest` 는 리소스 골든을 읽지 않는다. 출처는 v2 비평(N5)의 「app/game-engine/src/test/resources 4 파일」 — 판정자 표현이 스펙에서 「골든 4」 로 굳었다(지어낸 수치가 상위 지시로 살아남는 경로 그 자체).
- 고침: 「logic 골든 274 json(`logic/src/test/resources/golden`); engine 은 리소스 골든 없음 — `LongSimReplayGateTest` 등 인라인 리플레이 게이트」 로 바꿔라. 게이트 임계값이 아니므로 경미.

### 확인했으나 결함이 아닌 것(구현자 참고)

- `removeGeneral` 즉시 가지치기 × `createdRetainerIds`: 같은 틱에 만든 가신·부곡의 주인이 같은 틱에 죽으면 created 집합에서 빠져 INSERT 가 나가지 않는다(FK 대상 없음이라 반드시 빼야 하고 스펙이 그렇게 적었다). `deleted` 집합에서 빼는 것은 5단계 general DELETE 의 CASCADE 가 대신 지우기 때문이며, 만약 빼지 않아도 `troopDeleteMany` 패턴(`JdbcFlushExecutor.kt:879-885`, affected 검사 없음)이면 0행 DELETE 로 무해하다.
- `markGeneralDeleted :1158-1180` 이 주인의 pending general 패치(`generalPatches.remove`)를 지우므로 서약의 gold −500 패치도 같이 사라진다 — 삭제되는 행이라 정합.
- `deletedGeneralIds` 의 다른 기록자 없음 — `markGeneralDeleted` 를 거치지 않는 general 삭제 경로는 main 에 없다(위 N2 행). `ConquerCity`·`markNationDeleted` 는 장수를 재야로 남긴다.
- 정산 순서(부곡 → 가신)에서 loyalty 0 부장은 그 달 부곡 훈련을 준 뒤 떠난다 — 설계 선택이며 결함 아님.
- 월 경계 캐치업(한 틱에 여러 `runMonth`)은 달마다 한 번 정산 = 스펙 「같은 달 두 번 정산하지 않는다」 와 부합.
- 지어낸 수치가 게이트로 쓰인 곳: 없음(§2 상수는 S9 대로 입력값으로만, §8 에 임계값 없음).

---

## 2. UNKNOWN (판정에서 실행하지 않은 것)

- `ON DELETE SET NULL (commander_retainer_id)` 의 실제 실행 — PG16 확인만 했고 DDL 을 돌리지 않았다. `RetainerFlushIT` 가 첫 실행 증거다.
- 통일 flush(`isUnificationFlush` = `payload.emperiorInserts.isNotEmpty()` `JdbcFlushExecutor.kt:66`; 건너뛰기 선례 `:247,264`) 에서 8g 를 건너뛸지 — 스펙에 언급 없고 판정에서도 정하지 않았다(다른 월드 엔티티 채널은 건너뛰지 않으므로 기본은 「실행」 으로 읽힌다).

## 3. 읽은 파일(v3 판정 근거 경로)

`CLAUDE.md` · `docs/superpowers/specs/2026-09-06-retinue-buqu-vertical-slice.md`(v3 전문) · 이 파일의 v1·v2 본문 · `app/game-engine/src/main/kotlin/opensamguk/engine/turn/{InMemoryTurnWorld,ChangeRecorder,ReservedTurnHandler,TurnWorldModel}.kt` · `engine/run/{TurnRunService,MonthlyPostUpdateHook,TurnDaemonRunner}.kt` · `engine/flush/{DatabaseHooks,TruncateContract}.kt` · `engine/boot/WorldSnapshotLoader.kt` · `engine/intake/InstantActionHandler.kt` · `app/game-engine/src/test/kotlin/opensamguk/engine/{run/MonthlyPostUpdateHookTailWiringTest,boot/RehydrateLosslessGateIT,flush/TruncateContractTest,golden/LongSimReplayGateTest}.kt` · `logic/src/main/kotlin/opensamguk/logic/{tick/MonthlyPipeline,world/PostUpdateMonthly,war/ConquerCity}.kt` · `infra/src/main/kotlin/opensamguk/infra/persistence/JdbcFlushExecutor.kt` · `infra/src/main/resources/db/migration/` 목록 + `V40/V51/V54` SET NULL 행 · `infra/src/test/kotlin/opensamguk/infra/persistence/V32WorldScopeCompletionMigrationTest.kt` · `common/src/main/kotlin/opensamguk/common/{wire/TurnDaemonCommandResult,constants/UnitCatalog,constants/GameUnitConst}.kt` · `.ai/decisions.md` ADR-LITE-017/018 + `:220` · `docker-compose.production.yml`. 골든·리소스 수는 `find` 실측. `.env*`·gradle 미접촉.

---

<details>
<summary><b>부록 A — v2 재판정(2026-09-06, 역사 기록·원문 보존; N1·N2 는 v3 에서 cleared, N3·N5 는 P1·P2 로 승계)</b></summary>

# 휘하 인물 · 부곡 수직 절편(Phase 4X-A) 스펙 교차 비평 — 통합본 (v2 판정)

- Date: 2026-09-06 (v1 비평 → 같은 날 v2 재판정)
- Target: `docs/superpowers/specs/2026-09-06-retinue-buqu-vertical-slice.md` (**v2**, REVISED)
- Plan: `docs/superpowers/plans/2026-09-06-ui-redesign-implementation-plan.md` §Phase 4X-A (325-333행)
- Verdict: **fix-required 2건** — v1 의 F1–F6·S1–S10 은 전부 코드로 확인해 **cleared** 다. 남은 두 건은 v2 가 새로 정한 메모리·flush 규칙 안에서 생긴 신규 결함이다: 같은 틱의 「해제 → 같은 이름 재서약」 이 `UNIQUE(world_id, master_general_id, name)` 을 INSERT-먼저 순서로 때려 틱 flush 를 영구 차단하고(N1), 툼스톤 전파를 `consumeDirtyState` 로 미뤄 놓아 같은 틱에 주인이 죽은 가신·부곡이 월 정산에 고아로 보이는데 §5 가 `master` 를 무방비로 참조한다(N2). 둘 다 스펙 문장 몇 줄로 닫힌다. 코드 근거는 전부 이 워크트리에서 직접 연 파일:줄이다.

---

## 0. v2 판정표 (v1 항목별)

| 항목 | 판정 | 근거(코드) |
|---|---|---|
| **F1** `world.updateGeneral` 폐기 → `applyGeneralDirtyFree` + `diffGeneral` 짝 | **cleared** | `InMemoryTurnWorld.kt:245-250`(updateGeneral 은 world dirty set) vs `:297-301`(dirty-free apply). `ChangeRecorder.diffGeneral` `:338-383` 은 툼스톤 단락(`:342`) 뒤 `mergeRowPatch`(`:383`) 로 **병합**하므로 한 틱에 같은 장수를 여러 번 diff 해도 열이 유실되지 않는다. 짝 선례 `MonthlyPostUpdateHook.kt:271-272`. flush 는 `DatabaseHooks.toFlushPayload` `:587-590` 이 `recorder.dirtyGeneralIds()` → 월드 post-state 전체 행을 싣는다. §8 「`recorder.generalPatches()` 의 gold/crew/rice 열 단언」 은 `:322` 로 가능하다. |
| **F2** 가신·부곡을 `InMemoryTurnWorld` 세계 상태로 | **fix-required → N1·N2 로 이관** (구조는 맞음) | `troops` 미러: map `:92`, dirty `:99`, created `:104`, deleted `:112`, `createTroop :367-372`, `updateTroop :475-480`, `removeTroop :482-491`(같은 틱 생성-후-삭제 취소). `WorldSnapshot` `:20-33` 은 전부 기본값 인자라 `retainers/bugoks` 추가가 기존 생성자를 깨지 않는다. `consumeDirtyState :592-642` 는 `deletedGeneralIds` 를 `:604` 에서 읽고 `:620` 에서 지우므로 그 안에서의 툼스톤 전파는 **가능**하다 — 단 그 시점이 늦다(N2). id 는 `allocateGeneralId :453-461` 미러 가능(영속은 N3). 행 단위 UPDATE 는 general 과 같은 방식(`DatabaseHooks.kt:587-590`). 8x 단계는 5단계 general DELETE(`JdbcFlushExecutor.kt:149-152`) 뒤다. |
| **F3** 복합 FK `ON DELETE SET NULL (commander_retainer_id)` + 엔진 명시 UPDATE | **cleared** | PG16: `docker-compose.yml:25` `image: postgres:16-alpine`(v1 이 적은 `:118` 은 `depends_on` 줄이다 — 정정), Testcontainers `RehydrateLosslessGateIT.kt:48` 등 `postgres:16-alpine`. 복합 FK 선례 `V53__board_post_kind.sql:18`. 열 지정 SET NULL 은 PG15+ 구문이라 `world_id` 가 보존된다. `RetainerFlushIT` 가 그걸 단언한다(§8). 단, 이 FK 액션이 있으면 「UPDATE 가 DELETE 앞」 순서는 DB 무결성에 필요 없다 — N1 의 재정렬 근거. |
| **F4** 두 경로 `.authenticated()` | **cleared** | `GameApiSecurityConfig.kt:42-48` 나열식 `.authenticated()`, `:50` `anyRequest().permitAll()`. `/api/generals/*/retinue` 의 `*` 는 한 세그먼트 매칭(`"/api/generals/claimable"` `:44` 와 같은 접두). 401 idiom `MyController.kt:113-114`. |
| **F5** 정산을 `MonthlyPostUpdateHook.run` 맨 끝(L10 안) + 적색 프로브 | **cleared** (프로브 구성은 N4 should-fix) | `MonthlyPipeline.kt:112` `if (!preUpdateMonthly.run()) return`(L6), `:126` `postUpdateMonthly.run(monthlyRng)`(L10) — L10 안이면 L6 조기 반환과 운명을 같이한다. `MonthlyPostUpdateHook.run` 의 마지막 문장은 `postUpdateMonthlyTail(...)` `:218-229` 라 그 뒤에 `retainerMonthly?.settle(world, recorder)` 를 두는 건 건전하다. 생성자 `:56-66` 이 이미 nullable 선택 의존(`auctionRepository = null` 등)이라 `retainerMonthly: RetainerMonthlyService? = null` 이 같은 꼴. 배선 `DaemonLoopConfig.kt:397-406`. `LongSimReplayGateTest`·`ScenarioBlankUnificationIT`·`MonthlyPostUpdateHookTailWiringTest` 가 이 생성자를 직접 부르지만 기본값 인자라 컴파일 불변. 정산은 RNG 를 안 쓰므로 monthlyRng 스트림 불변. |
| **F6** ADR-LITE-017 정합, RECRUITED 만, 새 ADR 불필요 | **cleared** | `.ai/decisions.md:166-173` ADR-017(approved, supersede 없음): `origin(EXISTING\|RECRUITED)`·`hasOwnBugok`(RECRUITED 기본 false)·`role(참모\|호위\|군수관\|정찰\|사신\|NONE)`·`releasePolicy(MUTUAL\|MASTER_ONLY)`·`upkeep`(RECRUITED 만)·표 `general_retainers`·명령 `가신서약/가신해제/가신임무`. 스펙 §2 열 `origin CHECK`·`general_id CHECK((origin='EXISTING')=(general_id IS NOT NULL))`·`role` 6값·`has_own_bugok DEFAULT false`·`release_policy`·§5 upkeep 가 전부 대응. `relation`/`task`/`loyalty` 는 로드맵 `docs/design/roadmap.md:44` 「이름과 관계·충성·역할·임무」 에서 온 추가 속성이고 ADR 은 속성 추가를 금지하지 않는다. 스키마가 두 origin 을 다 담고 이 절편이 RECRUITED 만 INSERT 하는 건 범위 축소지 ADR 대체가 아니다 → 새 ADR 불필요. |
| **S1** V55 확정, V56/V57 예약 | **cleared** | 계획 `:329` 가 `V55__general_retainers_and_bugok.sql`(V56 = 4X-B, V57 = 4X-C) 로 고쳐졌다. `infra/src/main/resources/db/migration/` 에 V51·V53·V54 존재, V52 없음. |
| **S2** V32 인벤토리 등록 세부 | **cleared** | `V32WorldScopeCompletionMigrationTest.kt:67-70` 모든 물리 표를 정확히 한 번 분류(→ `postV32WorldTables :654-662` 필수), `:73-81` world_id NOT NULL·world_state FK·PK world 선행·모든 인덱스 world 선행, `:533-536` world_state FK 는 3개 표 외 **액션 없음**, `:500-502` UNIQUE 는 `(world_id,` 선행, `:95-108`+`:683-712` `serialIdentityColumns` 는 `nextval(` 기본값을 요구 → 엔진 할당 INTEGER 는 **미등록**이 맞다. 부모 general 복합 FK CASCADE 선례 `:506`. |
| **S3** 정산 빈칸 | **cleared** | §0 S3·§5 가 전액-아니면-미지급, −5 한 번, 병종 불일치 거부, train/atmos 불변을 못박았다. |
| **S4** 로그 채널·조사 | **cleared** | `LogEntryDraft(scope, category, text, generalId…)` `TurnWorldModel.kt:146-158`. `JosaUtil.pick(text, wJongsung, woJongsung="")` `common/.../josa/Josa.kt:60-72`(파일명은 `Josa.kt`, 객체는 `opensamguk.common.josa.JosaUtil`), 선례 `Presets.kt:4,547` `JosaUtil.pick(keyNick, "이")`. `"general"/"action"` 짝 선례 `MonthlyPostUpdateHookTailWiringTest.kt:100`. |
| **S5** `provisions / max(1, troops × PROVISION_PER_TROOP_MONTH)` | **cleared** | §0 S5·§6. |
| **S6** 초상 3종 | **cleared** | ADR-049 (4) `.ai/decisions.md:792-793` 「원본 히어로 / 148×210 카드 / 96 아이콘」. `web/shared/src/Portrait.tsx:5,24,33` — `card-44` 는 148×210 카드의 44×62 프리셋(「휘하 44×62」 주석 `:5`)이지 새 자산이 아니다. `web/{game,gateway}/public/portrait-default.svg` 존재(각 347B). 이름만 표시·자 없음은 RECRUITED 에 맞다. |
| **S7** 게이트 순서·throttle 범위 | **cleared** | `BoardHandler.kt:36-40`(장수 없음 → throttle) → `:66-77`(입력 null → 텍스트 → 게시물 → 권한). `AccessLogThrottle.kt:12-30` 은 거부돼도 access_log upsert(`:21`)·`game_env.refresh` KV(`:25-26`) 를 쓴다 — 스펙 §4 서술과 일치. `handleRead :111-126` 은 throttle 이 없으니 「6 명령 모두」 는 스펙의 선택이다(허용). `AccessLogThrottle` 은 `internal`(`:7`) — 같은 모듈의 `RetainerHandler` 에서 호출 가능. |
| **S8** 이름 정규화 | **cleared** | §0 S8·§4. |
| **S9** 상수 단언 금지 | **cleared** | §0 S9·§8 「상수 값 단언 없음」. |
| **S10** `RetainerActionResult` sealed 등록 | **cleared** (N8 nit) | `TurnDaemonCommandResult.kt:30` sealed, `BoardActionResult :105-110`, 직렬화기 `:647-706` 은 `type` 집합으로 분기하고 `:705` `else -> throw IllegalArgumentException("unknown result type")` — 미등록이면 런타임 예외. `toCommandResultRows` 는 `TurnRunService.kt:592-611` **private** 확장이고 `result.type/ok` + `WireJson.encodeToString` 만 쓴다(타입 무관 맞음). |
| **Q1** 정체 | 해소 | RECRUITED 만, EXISTING 다음 절편(§0). |
| **Q2** 주인 사망 | 해소 (N2 참조) | 부모 FK CASCADE + 메모리 툼스톤 — 단 전파 시점이 문제(N2). |
| **Q3** 부곡 위치 | 해소 | 이 절편 열 없음, 4X-B 가 V56 에서. |
| **Q4** `crewTypeName` | 해소 (N6 참조) | `UnitCatalog.byId(id)` `common/.../constants/UnitCatalog.kt:51-59` 존재. 단 id < 1000 이면 **던진다**(N6). |
| **Q5** rehydrate 범위 | **해소 — UNKNOWN 지워도 된다** | `RehydrateLosslessGateIT.kt:129-261` 는 표 열거가 **아니다** — 유니크 경매 projection 두 케이스(`:129`, `:200`) 뿐이다. 따라서 스펙 §0 Q5 의 대안 경로(`WorldSnapshotLoaderRetainerTest`)가 정답이고, §10 의 마지막 UNKNOWN 은 삭제 가능. 단 그 테스트는 N3 대로 flush→reload 왕복이어야 한다. |
| **Q6** 기본 초상 | 해소 | 위 S6. |
| **Q7** 잠정 표시 | 해소 | `rules.provisional = true` + UI 칩(§6·§7). |

---

## 1. 신규 fix-required

### N1. 8e 순서 INSERT → … → DELETE 와 `UNIQUE (world_id, master_general_id, name)` 이 충돌한다 — 같은 틱 「해제 → 같은 이름 재서약」 이 틱 flush 를 영구 차단한다

- 스펙: §2 `UNIQUE (world_id, master_general_id, name)`; §3 8e `retainerCreateMany → bugokCreateMany → bugokUpdate → retainerUpdate → bugokDeleteMany → retainerDeleteMany`; §4 중복 게이트는 **메모리** count 로 판단.
- 시나리오: DB 에 가신 「홍길동」(id 3) 이 있다. 한 틱에 `retainerRelease(3)` 과 `retainerPledge("홍길동")` 이 함께 도착한다(인테이크는 한 틱에 클레임한 봉투를 모두 디스패치한다 — `TurnRunService.kt:299-300`). 메모리: `removeRetainer(3)` 뒤 이름이 비므로 중복 게이트 통과 → `createRetainer(id 4, "홍길동")`. flush: 8e 가 **INSERT(4, 홍길동) 를 DELETE(3) 보다 먼저** 실행 → UNIQUE 위반 → 트랜잭션 예외 → 다음 틱도 같은 payload 를 다시 만들어 같은 자리에서 터진다. 이 저장소가 이미 같은 계열의 사고를 기록해 둔 곳: `DatabaseHooks.kt:660-663` 「…affected 0 rows 로 틱이 영구 차단된다」.
- 왜 v1 제안(INSERT → UPDATE → DELETE)이 v2 에선 필요 없나: v2 는 F3 로 `ON DELETE SET NULL (commander_retainer_id)` 를 채택했으므로 「지휘 부곡 commander NULL UPDATE 가 가신 DELETE 앞」 이라는 DB 측 순서 제약이 사라졌다. 남은 FK 제약은 「부곡 INSERT/UPDATE 가 참조하는 **새** 가신은 먼저 INSERT 돼 있어야 한다」 하나뿐이다.
- 고침(둘 중 하나, 스펙에 적어라):
  1. **재정렬(권장)**: `retainerDeleteMany → retainerCreateMany → retainerUpdate → bugokDeleteMany → bugokCreateMany → bugokUpdate`. 검산 — 해제 R1(부곡 B 지휘)+서약 R2+B 에 R2 배정: DELETE R1(DB 가 B.commander 를 NULL) → INSERT R2 → UPDATE B(commander=R2) ✓. 편성 B + 새 R2 배정: INSERT R2 → INSERT B(commander=R2) ✓. 해산 B + 해제 R1: DELETE R1 → DELETE B ✓. `requireExactlyOneAffected`(`JdbcFlushExecutor.kt:2331-2335`) 는 값이 같아도 매치된 행을 세므로 중복 UPDATE 도 통과.
  2. 또는 `UNIQUE … DEFERRABLE INITIALLY DEFERRED`(선례: `V32WorldScopeCompletionMigrationTest.kt:505-512` 의 deferred FK 들) — 단 이 경우 V32 테스트의 UNIQUE 정규화 문자열이 달라질 수 있으니 확인해라.
- 게이트: `RetainerFlushIT` 에 「DB 에 있는 이름을 같은 flush 에서 DELETE + 같은 이름 INSERT」 케이스를 넣고, `RetainerIntakeTest` §3 같은 틱 시나리오에 이 6번째 케이스를 추가해라.

### N2. 툼스톤 전파를 `consumeDirtyState` 로 미루면 같은 틱 안에서 고아 가신·부곡이 정산에 보인다 — §5 는 `master` 를 무방비로 참조한다

- 스펙: §3 「툼스톤 전파(`consumeDirtyState` 안)」, §5 「`shortPay = master.gold < pay`」「`upkeepPaid = master.gold ≥ … && master.rice ≥ …`」 — `master` 가 없을 때의 경로가 없다. §3 은 「메모리 불변식이 행 존재를 보장」 이라고까지 적어 구현자가 `requireNotNull` 을 고르게 유도한다.
- 코드: 한 틱은 ① 인테이크 디스패치(`TurnRunService.kt:299-300`) → ② 월 드라이버(`:302-305`, 드레인 사이에 `runMonth` → L10 `MonthlyPipeline.kt:126`) → ③ flush(`:383`, `buildFlushPayload :587-590` 안에서 `consumeDirtyState`). 장수 삭제는 ①(`InstantActionHandler.kt:46` dieOnPrestart → `recorder.markGeneralDeleted`)과 ②의 드레인(`ReservedTurnHandler.kt:1068` 사망 경로)에서 일어나고, `markGeneralDeleted` 는 곧장 `world.removeGeneral` 을 부른다(`ChangeRecorder.kt:1179` → `InMemoryTurnWorld.kt:267-280`). 그 순간 `generals` 맵에서 주인은 사라지지만(`:269`) 가신·부곡 맵은 ③까지 그대로다 → L10 정산이 `listBugoks()` 를 id 순으로 돌다가 `getGeneralById(masterId) == null` 을 만난다. `requireNotNull`/`!!` 이면 월 훅 예외 = 틱 전체 실패; `?: continue` 면 살지만 스펙에 없다. 이 저장소의 선례는 **삭제 시점 즉시 프룬**이다 — `removeNation :493-514` 가 diplomacy 를 `:501-509` 에서 바로 걷어내고, `removeGeneral` 자신도 `generalPosition` 을 `:270` 에서 바로 걷어낸다.
- 고침: (a) 전파를 `InMemoryTurnWorld.removeGeneral` 안으로 옮겨라 — 주인(또는 EXISTING 의 `general_id`)이 그 장수인 가신·부곡을 map 과 created/dirty/deleted 집합에서 즉시 제거(`removeNation` 의 diplomacy 프룬 미러). `consumeDirtyState` 의 필터는 남겨도 되지만 주 경로가 아니다. (b) §5 에 「주인이 메모리에 없는 행은 건너뛴다(방어)」 한 줄. (c) 정산은 `run()` 첫머리의 `generals` 스냅샷(`MonthlyPostUpdateHook.kt:69-70`) 이 아니라 `world.getGeneralById` 를 **실시간**으로 읽어라 — 같은 `run()` 안에서 checkWander 가 장수 gold 를 이미 바꿨을 수 있다(`:271-272`). (d) `RetainerIntakeTest` 「주인 사망 → pending 없음」 을 「주인 사망 직후 `listRetainers()/listBugoks()` 에 그 주인 행이 없다」 로 강화해라(flush 만 보는 검사는 이 버그를 공유한다).

---

## 2. 신규 should-fix

- **N3. `world_state.meta` 할당자 영속은 「meta 키 미러」 로 끝나지 않는다 — 접점이 넷이다.** `recordMaxGeneralId`(`InMemoryTurnWorld.kt:467-469`) 는 메모리 `state.meta` 에만 쓴다. DB 로 가는 건 `TurnRunService.kt:405-406` 이 `worldState["max_general_id"]` 로 뽑아 넘기고, `JdbcFlushExecutor.worldStateUpdate` 가 `meta || jsonb_build_object('lastTurnTime', 'maxNationId', 'maxGeneralId')` **세 키만** 병합하기 때문이다(`:537-541` CAS 분기, `:559-563` 비CAS 분기 — 둘 다 고쳐야 한다). 부팅은 `WorldSnapshotLoader.kt:72` 가 `world_state.meta` 전체를 시작점으로 삼되 `snapshotKeys :74-86`(`maxNationId`/`maxGeneralId` 는 `:85-86`) 만 game_env 위로 재적용한다 → 새 키도 여기 넣어야 game_env 동명 키에 안 덮인다. 스펙 §3 「갱신·영속은 … 그대로 미러(meta 키)」 를 이 네 접점으로 풀어 써라. 누락 시 증상은 재기동 뒤 `max(id)` 로만 시드돼 삭제된 최대 id 가 재사용되는 것(이 절편엔 잔존 참조가 없어 손상은 없다 — 그래서 should-fix). `WorldSnapshotLoaderRetainerTest` 는 **flush → 재적재 왕복**으로 짜라(메모리만 보면 검사가 버그를 공유한다).
- **N4. 적색 프로브의 구성.** §8 「같은 fixture 세계에서 두 번 돌린다」 를 문자 그대로 하면 틀린다 — `run()` 은 세계를 변형하고 `consumeDirtyState` 는 단발이다. 「같은 스냅샷에서 **새** `InMemoryTurnWorld`+`ChangeRecorder` 를 둘 만들어 각각 한 번」 으로 써라. 또 Q15/Q16 은 벽시계를 읽는다(`MonthlyPostUpdateHook.kt:382`, `:398` `Instant.now()`; `registerNeutralAuctions` 는 `rng.nextBool(1/5)` 로 경매를 연다 `NeutralAuctionRegistrar.kt:29,50`) → 열리면 두 실행의 recorder 채널이 시각으로 갈린다. `MonthlyPostUpdateHookTailWiringTest.kt:44-58` 의 `ScriptedRng`(bools 전부 false)와 `:66-70` 의 `auctionRepo()` 픽스처를 그대로 쓰고 그 사실을 스펙에 적어라. 비교 대상은 `DirtyState`(data class, `DirtyState.kt:175`)·`recorder.generalPatches()`(`RowPatch` data class, `ChangeRecorder.kt:51`)·`LogEntryDraft`(data class) 라 deep-equal 은 성립한다.
- **N5. 「기존 골든 208 파일」 은 출처가 없다.** `logic/src/test/resources/golden` 실측 = json 274 + `.gitignore` 1, `app/game-engine/src/test/resources` 4 파일. 이 저장소·docs 어디에도 208 이 없다. 실측 수치로 바꾸거나 「골든 디렉터리 전체」 로 써라(임계값이 아니라 증거 문장이므로 should-fix — 다만 지어낸 수치가 「상위 지시」 로 살아남는 경로다).
- **N6. `UnitCatalog.byId(crewTypeId)` 는 id < 1000 이면 던진다.** `UnitCatalog.kt:51-59` → `GameUnitConst.kt:372-374` `require(id >= 1000)`. `general.crew_type_id` 의 DB 기본값은 0 이다(`V1__*.sql:83`). 스펙 §0 Q4 가 선례로 든 `CityDetailController.kt:231` 은 정확히 그래서 `g.crewTypeId >= 1000` 가드를 두고 있다. §6 의 식을 `if (crewTypeId >= 1000) UnitCatalog.byId(crewTypeId)?.name ?: "-" else "-"` 꼴로 쓰거나 `general_bugok.crew_type_id` 에 CHECK 를 두어라(편성 게이트는 crew ≥ 100 을 요구하므로 실제로 0 이 들어올 확률은 낮지만 500 은 500 이다).
- **N7. 단계 이름·가드 명시.** 실행기엔 이미 「8e. 투표 채널」 이 있다(`JdbcFlushExecutor.kt:226`; 순서는 8d `:212` → 8e `:226` → 8c `:245` → 8f `:254`). 새 단계는 **8g**(8f 뒤) 로 부르고, 8d 처럼 리스트마다 `isNotEmpty()` 가드를 둔다고 적어라 — `lastOps` 를 표 단위로 세는 게이트(`RehydrateLosslessGateIT.kt:138-142`, `FullRehydrateFlushAssertions.kt`) 가 행 0 세계에서 op 0 을 봐야 한다.
- **N8. `toCommandResultRows` 검증 방법.** private(`TurnRunService.kt:592`)이라 직접 테스트할 수 없다. 실제 실패 지점은 직렬화기 `:705` 의 `else -> throw` 이므로, 6 코드 × ok/fail 을 `TurnDaemonCommandResultSerializer` 왕복으로 핀하는 테스트로 바꿔 적어라.

---

## 3. v2 주장 중 코드로 확인한 것(맞음)

| 스펙 주장 | 확인한 근거 |
|---|---|
| `DirtyState`/`FlushPayload` 에 6 필드 추가가 기존 테스트를 안 깨뜨린다 | `DirtyState.kt:175-241` 뒤쪽 필드는 전부 기본값(신규도 **뒤에 기본값으로** 붙여야 한다 — 앞 14개는 기본값이 없다). `FlushPayload` `JdbcFlushExecutor.kt:2544-2632` 는 `worldId`/`worldStateUpdate` 외 전부 기본값. 생성자를 직접 부르는 테스트 10 파일(`FlushPayloadConvergenceTest` 등) 은 named-arg 라 불변. |
| 프로덕션 payload 빌더는 하나다 | `TurnRunService.buildFlushPayload :587-590` → `DatabaseHooks.toFlushPayload(world, recorder, dirty)` `:576`. `:167-180` 의 옛 overload 는 `@Deprecated(level = ERROR)`. |
| 8d 패턴: 핸들러 columns 에 `world_id` 없음, 실행기가 주입 | `JdbcFlushExecutor.kt:1634-1660` (`.addValue("world_id", worldId.value)` `:1638`), 채널 `ChangeRecorder.kt:817-829`, 매핑 `DatabaseHooks.kt:703-705`. |
| 열 LWW 채널의 실체(v2 가 피한 것) | `recordVotePollUpdate :627-629` putAll, flush `:1803-1825` `check(affected == 1)` `:1821`. |
| `TruncateContract` 등록 | `TruncateContract.kt:46-75` (`board_post_read` `:69`). |
| 와이어 allowlist·매핑 위치 | `CommandWireMapper.kt:83`(allowlist), `:332`(매핑). |
| `LogEntryDraft` 필드 | `TurnWorldModel.kt:146-158` (`scope, category, text, generalId?, nationId?, …`). |
| `TurnGeneral.crew/rice/gold/crewTypeId/train/atmos` | `TurnWorldModel.kt:65-70`. |
| v1↔v2 별도 DB(ADR-LITE-018 `:182`) 와의 충돌 여부 | 비저촉으로 본다: 같은 체인에 이미 `v2_city_ledger`(OPENSAM-150 R1, `FlushPayload :2625`, step 14) 와 `/api/v2/**`(`GameApiSecurityConfig.kt:46-48`) 가 있고, ADR-042 가 패러티 축을 해제했으며, ADR-049 승인 계획(`:325-333`) 이 4X-A 를 이 코드베이스에 둔다. 판정자 소견이며 별도 ADR 이 필요하면 사용자 결정 사항. |
| OPENSAM-48 을 이 절편으로 닫는 것 | 백로그 `README.md:94` 「V2-2 부곡 foundation」 — 스키마·flush·명령이 foundation 이므로 정합. OPENSAM-61(`:96`) 은 스펙대로 코멘트만. |

---

## 4. 읽은 파일(근거 경로)

`app/game-engine/src/main/kotlin/opensamguk/engine/turn/{InMemoryTurnWorld,ChangeRecorder,DirtyState,TurnWorldModel,ReservedTurnHandler}.kt` · `engine/flush/{DatabaseHooks,TruncateContract}.kt` · `engine/run/{MonthlyPostUpdateHook,TurnRunService}.kt` · `engine/config/DaemonLoopConfig.kt` · `engine/boot/WorldSnapshotLoader.kt` · `engine/intake/{BoardHandler,AccessLogThrottle,InstantActionHandler}.kt` · `app/game-engine/src/test/kotlin/opensamguk/engine/{boot/RehydrateLosslessGateIT,run/MonthlyPostUpdateHookTailWiringTest,flush/FlushPayloadConvergenceTest}.kt` · `logic/src/main/kotlin/opensamguk/logic/{tick/MonthlyPipeline,world/PostUpdateMonthly,auction/NeutralAuctionRegistrar,constraints/Presets}.kt` · `infra/src/main/kotlin/opensamguk/infra/persistence/JdbcFlushExecutor.kt` · `infra/src/main/resources/db/migration/{V1,V53}__*.sql` + 디렉터리 목록 · `infra/src/test/kotlin/opensamguk/infra/persistence/V32WorldScopeCompletionMigrationTest.kt` · `app/game-api/src/main/kotlin/opensamguk/gameapi/{security/GameApiSecurityConfig,controller/MyController,controller/GeneralLogController,web/CityDetailController,reserve/CommandWireMapper}.kt` · `common/src/main/kotlin/opensamguk/common/{wire/TurnDaemonCommandResult,josa/Josa,constants/UnitCatalog,constants/GameUnitConst}.kt` · `web/shared/src/Portrait.tsx` · `web/{game,gateway}/public/portrait-default.svg`(존재 확인) · `docs/design/roadmap.md:38-50` · `docs/superpowers/plans/2026-09-06-ui-redesign-implementation-plan.md:129,318-335` · `docs/superpowers/plans/2026-07-17-v2-ticket-backlog/README.md:88-100` · `.ai/decisions.md` ADR-LITE-017/018/049 · `CLAUDE.md` · `docker-compose.yml`. 골든 수는 `find` 실측.

</details>

<details>
<summary><b>부록 B — v1 비평(2026-09-06, 역사 기록·원문 보존)</b></summary>

# 휘하 인물 · 부곡 수직 절편(Phase 4X-A) 스펙 교차 비평

- Date: 2026-09-06
- Target: `docs/superpowers/specs/2026-09-06-retinue-buqu-vertical-slice.md` (DRAFT)
- Plan: `docs/superpowers/plans/2026-09-06-ui-redesign-implementation-plan.md` §Phase 4X-A (320-332행)
- Verdict: **fix-required 6건** — 이대로 구현하면 crew/rice/gold 보존이 DB에 도달하지 않고(F1), 같은 틱의 명령·정산이 서로를 덮어쓰거나 flush 를 터뜨리며(F2), 부장 해임이 FK 위반으로 실패하고(F3), 읽기 API 가 공개되며(F4), 「골든 불변」이 바뀐 경로를 타지 않는 게이트로 주장되고(F5), 승인된 ADR-LITE-017 과 모순된다(F6). 코드 근거는 전부 이 워크트리에서 직접 읽은 파일:줄이다.

---

## 0. 스펙 주장 중 코드로 확인한 것(맞음)

| 스펙 주장 | 확인한 근거 |
|---|---|
| 인테이크 명령 → 엔진 핸들러 → `ChangeRecorder` 채널 → `JdbcFlushExecutor` (board 패턴 미러) | `BoardHandler.kt:28-60` → `ChangeRecorder.kt:816-829` → `DatabaseHooks.kt:703-705` → `JdbcFlushExecutor.kt:212-224` (8d), `1627-1700` (`world_id` 는 executor 가 `worldId.value` 로 주입, 핸들러 columns 에 없음: `1638`) |
| 결과는 `BoardActionResult` 와 같은 꼴 `type/ok/generalId/reason` | `common/.../TurnDaemonCommandResult.kt:105-110` |
| 와이어: `TurnDaemonCommand` + `CommandWireMapper` + 디스패처 | `CommandWireMapper.kt:81-83`(allowlist) · `332-335`(매핑) · `TurnDaemonCommandDispatcher.kt:415-417` |
| `TruncateContract` 에 두 표 등록 | `TruncateContract.kt:46-75` (`board_post_read` 선례 `:69`) |
| V32 인벤토리 테스트에 두 표 등록 | 테스트는 V31 까지 → **최신까지** migrate 하므로(`V32WorldScopeCompletionMigrationTest.kt:371-388`) 새 표는 `postV32WorldTables`(`:654-662`) 에 넣어야 하고, world_id NOT NULL·world_state FK·PK world 선행·모든 인덱스 world 선행을 검사한다(`:71-79`) |
| `general(world_id,id)` 복합 FK 대상 존재 | `V32__*.sql:190-192` `PRIMARY KEY (world_id, id)` |
| 월 경계는 상순(phase 1) 1회 | `TurnRunService.kt:358` `runMonthWhen = phase == 1`, 드라이버가 경계마다 1회 |
| 인테이크 202 ≠ 성공, `CommandModal` pinnedCommand + extraArgs 는 회의실과 같은 경로 | `CommandModal.tsx:28,61,73,501-520` (`submitCommandAndAwaitResult` 의 terminal status 로 분기) · `web/game/app/game/board/page.tsx` 가 같은 프롭 사용 · `/game/my/page.tsx` 존재 |
| 07 아트보드 문구 「사기 +6」「내정 명령 효율 +8%」「부곡은 장수 개인의 사병입니다…」 | `docs/design/ui-redesign-2026-09/src/General.body.html` (텍스트 추출) |
| `TurnGeneral` 에 crew/rice/gold/crewTypeId/train/atmos 가 있고 diff 대상이다 | `TurnWorldModel.kt:65-70` · `ChangeRecorder.diffGeneral` `:345`(gold) `:350`(rice) `:356-359`(crew/train/atmos/crewTypeId) |
| 「접속 제한입니다.」 게이트 | `AccessLogThrottle.kt:12-28` (board 핸들러와 동일 호출) |
| 로드맵 「휘하 인물과 부곡」 4원칙(부장은 지휘관이 될 수 있으나 부곡이 아니다, 국가군과 별도) | `docs/design/roadmap.md:39-46` — 스펙 §1.1·§2 가 이를 따른다 |
| PostgreSQL 16 | `docker-compose.yml:118` `postgres:16-alpine`, Testcontainers 4곳 동일 |

---

## 1. fix-required

### F1. `world.updateGeneral` 은 데몬 쓰기 경로가 아니다 — crew/rice/gold 변경이 DB에 도달하지 않는다

- 스펙: §3 「gold −500(메모리 general)」「general.crew −troops, general.rice −rice(메모리)」, §4.2 「주인 장수 gold 에서(메모리 `world.updateGeneral`) 차감」.
- 코드: `InMemoryTurnWorld.updateGeneral` 은 `dirtyGeneralIds` 에 넣을 뿐이고(`InMemoryTurnWorld.kt:245-250`), 바로 아래 문서가 **「the world's own dirty set is never the daemon write path」** 라고 못박는다(`:288-294`). `ChangeRecorder` 헤더도 「resolver NEVER calls the world's updateGeneral … RowPatches are the ONLY thing that marks a row dirty」(`ChangeRecorder.kt:60-64`). 실제 인테이크 핸들러는 전부 `world.applyGeneralDirtyFree(next)` + `recorder.diffGeneral(pre, next)` 짝이다 — `TroopHandler.kt:141-148`, gold 차감·지급은 `VoteHandler.kt:207-221`.
- 결과: 스펙대로 짜면 부곡 행은 INSERT 되는데 `general.crew/rice/gold` 의 감소는 `FlushPayload.updatedGenerals` 에 들어가지 않는다 → 재기동·rehydrate 후 병력이 국가군과 부곡에 **이중으로** 존재한다. 스펙 §1.1 「합은 보존된다」 가 메모리에서만 성립한다. CLAUDE.md 「daemon writes go only through ChangeRecorder → JdbcFlushExecutor」 위반.
- 고침: §3·§4 의 「메모리 general」「`world.updateGeneral`」 을 모두 「`pre = toLogicGeneral(me)`; `next = me.copy(...)`; `world.applyGeneralDirtyFree(next)`; `recorder.diffGeneral(pre, toLogicGeneral(next))`」 로 바꾸고, §7 `RetinueIntakeTest` 의 「메모리 general 변화」 를 「`recorder.generalPatches()` 에 gold/crew/rice 열이 기록된다」 로 바꿔라(메모리만 보는 검사는 F1 을 통과시킨다 — 「검사가 버그를 공유한다」).

### F2. DB 원천 행을 같은 틱 안에서 명령(1단계)과 월 정산(2단계)이 따로 읽고 쓴다 — 유실·flush 실패

- 스펙: 휘하·부곡 행은 메모리에 두지 않고 DB 원천(§2, §4.1 「infra `BuquRepository` 로 전부 읽기」), 명령은 INSERT/UPDATE/DELETE 채널만 기록(§3).
- 코드: 한 틱은 ① 인테이크 디스패치(`TurnRunService.kt:299-300`) → ② 월 경계 드라이버(`:302-364`) → ③ **단일** flush(`:384-414`) 순서다. ②가 DB 를 읽는 시점에 ①의 채널 기록은 아직 flush 되지 않았다. 기존 DB-only UPDATE 채널은 **열 단위 last-write-wins 절대값**이다(`ChangeRecorder.kt:620-629` `recordVotePollUpdate`), flush 는 `check(affected == 1)` 로 행 부재를 예외로 만든다(`JdbcFlushExecutor.kt:1804-1824`). general 삭제(5단계, `:149`)는 8x 채널보다 먼저 실행된다.
- 깨지는 경우(전부 같은 뿌리):
  1. `buquAssignCommander`(morale 50→56 UPDATE) 뒤 같은 틱 월 정산이 DB morale 50 을 읽어 −5 → 45 UPDATE. 같은 열 LWW → **+6 유실**.
  2. `retinueDismiss`(DELETE) 뒤 같은 틱 정산이 DB 에서 그 사람을 읽어 loyalty UPDATE → 삭제된 행 UPDATE → `affected == 1` 위반 → **틱 전체 flush 실패**(FLUSH_RETRY 진입).
  3. 주인 장수 사망(`deletedGeneralIds`) → 5단계 general DELETE 가 CASCADE 로 buqu 를 지운 뒤 8x 에서 pending buqu UPDATE → 동일.
  4. `MAX_PERSONS/MAX_BUQU` 상한을 DB count 로 재면 같은 틱 두 번 등용이 둘 다 통과(4 → 6).
  5. `buqu.id` 가 identity 라 flush 전엔 id 가 없다 → 「편성 → 지휘관 배정」 을 같은 틱에 못 한다(「부곡이 없습니다.」). 결과(`BoardActionResult` 꼴)에도 id 를 실을 수 없다.
- 고침(하나로 해결): 휘하·부곡을 `InMemoryTurnWorld` 의 세계 상태로 올려라(`troops`·`accessLogs` 처럼 부팅/rehydrate 시 적재). 명령·정산은 메모리를 읽고, 리코더 채널은 **행 단위 병합 + 툼스톤 우선**(DELETE 기록된 id 는 pending UPDATE 에서 제외 — `diffGeneral` 의 `deletedGeneralIds` 단락 `ChangeRecorder.kt:342` 와 같은 규칙, 주인 장수 툼스톤도 전파), flush 순서는 INSERT → UPDATE → DELETE 로 고정하고 스펙에 적어라. id 는 `diplomacyLetterIdAllocator` 처럼 DB-seed `max(id)+1` 선할당(`ChangeRecorder.kt:82-88`) 후 명시 INSERT(`GENERATED BY DEFAULT` 라 허용). 최소한 이 규칙들이 스펙에 없으면 구현자가 매번 다르게 짠다.

### F3. 복합 FK `ON DELETE SET NULL` 은 `world_id` 까지 NULL 로 만든다 — 부장 해임이 실패한다

- 스펙: §2 `buqu.commander_person_id (FK retinue_person(world_id,id) SET NULL)`, §3 `retinueDismiss` 「지휘 중이던 부곡은 commander NULL — FK」.
- 코드: 세계 범위 규약대로 부모 FK 는 `(world_id, commander_person_id) REFERENCES retinue_person(world_id, id)` 복합이어야 한다(V53 `board_post_read_world_post_fkey` 선례 `V53__board_post_kind.sql:18`). PostgreSQL 의 `ON DELETE SET NULL` 은 **참조 열 전부**를 NULL 로 만든다 → `world_id`(PK 구성, NOT NULL) 위반 → 지휘 중인 부장을 DELETE 하는 순간 예외 → flush 실패. PG 16 이므로(`docker-compose.yml:118`) PG15+ 의 열 지정 `ON DELETE SET NULL (commander_person_id)` 가 가능하다.
- 고침: DDL 을 `ON DELETE SET NULL (commander_person_id)` 로 명시하고, `RetinueFlushIT` 에 「지휘 중인 부장 DELETE 후 buqu.world_id 보존·commander NULL」 케이스를 넣어라. 또는 FK 액션을 두지 말고 엔진이 DELETE 전에 commander UPDATE 를 명시 기록(F2 채택 시 메모리에서 자연히 됨). 둘 중 하나를 스펙이 골라야 한다.

### F4. 읽기 API 권한 — `GameApiSecurityConfig` 에 등록하지 않으면 두 경로가 공개다

- 스펙: §5 「권한: 본인 또는 같은 국가. 아니면 403」.
- 코드: `GameApiSecurityConfig.kt:41-49` 는 나열된 경로만 `.authenticated()` 이고 나머지는 `.anyRequest().permitAll()`. `/api/my-retinue`·`/api/generals/{id}/retinue` 는 목록에 없다 → 익명 요청이 컨트롤러까지 온다. 기존 idiom 은 `@AuthenticationPrincipal userId: Long?` null → 401(`MyController.kt:113-116`). 스펙엔 401 도, 익명 케이스도, 재야(nationId 0) 케이스도 없다. `/api/generals/{id}/retinue` 가 공개되면 적국 장수의 개인 사병·군량이 그대로 새는 정보 누출이다.
- 고침: 두 경로를 `GameApiSecurityConfig` 의 `.authenticated()` 목록에 추가(스펙 변경 항목으로 명시), 계약을 「principal 없음 401 / 본인 또는 같은 국가(nationId ≠ 0) 200 / 그 외 403 / 재야는 본인만」 으로 쓰고, `RetinueReadControllerTest` 에 익명 401 과 재야-타인 403 을 넣어라.

### F5. 「골든 불변」 이 바뀐 경로를 타지 않는 게이트로 주장된다 + 정산 배치가 `runMonth` 조기 반환을 못 본다

- 스펙: §1.3·§7 「기존 engine/logic 골든 전부 녹색(행 0 경로)」 를 불변의 증거로 삼고, §4 「`TurnRunService` 의 월 경계 드라이버가 `runMonth` 뒤에 `settle(world, recorder, repo)` 를 부른다」.
- 코드: (a) 유일한 엔진 리플레이 골든 `LongSimReplayGateTest` 는 `lifecycle.runTick` 을 직접 돌린다(`LongSimReplayGateTest.kt:1606`) — `TurnRunService.runTick` 의 월 드라이버(`:302-364`)를 타지 않는다. `TurnRunServiceIT` 는 `pipeline` 을 배선하지 않는다(일치 0). 즉 settle 훅이 들어갈 자리를 핀하는 골든이 **없다**. 「전부 녹색」 은 F1·F2 를 포함해 아무것도 증명하지 않는다(메모리: exit 0 은 게이트의 증거가 아니다). (b) `MonthlyPipeline.runMonth` 는 L6 에서 `preUpdateMonthly.run()` 이 false 면 달력을 올리지 않고 조용히 return 한다(`MonthlyPipeline.kt:107`) — 반환값이 없어 호출자는 모른다 → 「runMonth 뒤에 settle」 은 중단된 달에도 정산한다. (c) `TurnRunService` 의 read repo 는 전부 nullable(`:118` `boardPostRepository: BoardPostRepository? = null`) — `BuquRepository` 가 null 인 테스트/부팅 경로에서 settle 이 뭘 하는지 스펙에 없다.
- 고침: settle 을 드라이버 람다가 아니라 파이프라인 안(MONTH 이벤트 leaf 또는 `PostUpdateMonthly` 마지막 단계)에 두어 조기 반환과 같은 운명을 타게 하고, 골든 증거는 **적색 프로브**로 바꿔라: pipeline 을 배선한 `TurnRunService` 테스트에서 행 0 세계의 `FlushPayload`(updatedGenerals·logEntries·`lastOps`) 가 훅 유무와 바이트 동일함을 단언하고, 같은 테스트의 행 1 변형이 빨개지는 것을 함께 보여라. repo null → 정산 생략을 명문화.

### F6. 승인된 ADR-LITE-017 과 모순된다 — OPENSAM-61 을 이 모델로 닫을 수 없다

- 스펙: §2 `retinue_person`(자유 입력 이름, role `staff/lieutenant/guest`, task `none/domestic/scout/train`), §3 `retinueRecruit/Dismiss/AssignTask`, 티켓 「OPENSAM-61(#203) 가신」 닫기.
- 기록: `.ai/decisions.md:166-173` ADR-LITE-017(approved 2026-07-25, 이후 supersede 기록 없음)은 가신을 `origin(EXISTING|RECRUITED)`(기존 장수 풀에서도 온다), `hasOwnBugok`, `role(참모|호위|군수관|정찰|사신|NONE)`, `releasePolicy`, `upkeep(RECRUITED 만)`, 표 `general_retainers`, 명령 `가신서약/가신해제/가신임무` 로 정의했다. 백로그도 OPENSAM-61 을 「가신 (ADR-LITE-017로 1트랙 병합)」 으로 적는다(`2026-07-17-v2-ticket-backlog:96`). 07 아트보드 07 도 휘하를 **기존 장수**로 그린다 — 「문약·막료 / 문겸·부장 / 중강·부장 / 중덕·문객」 은 荀彧·樂進·許褚·程昱의 자(字)이고 「허저를 지휘관으로 배정하면 사기 +6」(`General.body.html`). 스펙은 RECRUITED 자유 이름만 허용하고 EXISTING 을 아예 뺐다.
- 고침: 둘 중 하나. (i) ADR-LITE-017 을 명시적으로 supersede 하는 새 ADR(사용자 승인) 을 이 스펙과 함께 발행하고 그 안에 「EXISTING 은 다음 절편」 을 적는다. (ii) 도메인을 ADR-017 에 맞춘다 — `origin` 열, EXISTING 은 `general_id` 참조(이름 자유 입력 아님), role enum 은 ADR 값, 명령명은 `가신서약/가신해제/가신임무`. 어느 쪽이든 하지 않으면 OPENSAM-61 은 닫지 말고 코멘트만 남겨라.

---

## 2. should-fix

- **S1. 마이그레이션 번호·계획 불일치.** 계획 328행은 `V52__retinue_and_buqu.sql` 에 `assigned_buqu_id`·`formation`·`commander_retinue_id` 를 적었고 스펙은 V55 에 다른 열(`commander_person_id`, formation 없음). 이 브랜치엔 V51·V53·V54 가 있고 V52 는 비었으며 main 은 V50 까지다. Flyway 기본 `outOfOrder=false` 라 V53/54 적용 후 V52 를 넣으면 거부된다 → V55 가 맞다. 계획 328행을 스펙에 맞춰 고치고, 4X-B/4X-C 가 V55 를 선점하지 않도록 번호를 계획 표에 못박아라(`flyway-v45-merge-collision-hotfix` 브랜치가 그 사고의 흔적이다).
- **S2. V32 인벤토리 등록 세부.** `world_id → world_state(id)` FK 는 delete action 이 없어야 한다(`V32WorldScopeCompletionMigrationTest.kt:533-534`, CASCADE 허용 표 3개 고정). identity 열은 `column_default` 가 NULL 이라 `serialIdentityColumns`(`:684-712`) 에 넣으면 실패한다(`board_post_read` 가 빠져 있는 이유). 스펙에 「world_state FK 무액션, 부모 general FK 만 CASCADE, serialIdentityColumns 미등록」 을 적어라.
- **S3. 정산 규칙의 빈칸.** (a) gold 부족 시 급여를 일부라도 깎는지, 0 으로 만드는지, 안 깎는지 미정. (b) 군량 부족과 급여 부족이 동시에 나면 −5 한 번인지 −10 인지 미정(§4.2 「provisions < 0 이거나 gold 부족이면 morale −5」 는 「이거나」 라 한 번으로 읽히지만 표엔 「급여·군량 부족 시」). (c) `buquDisband` 때 장수 `crewTypeId` 가 편성 시와 다르면 병종이 섞인다 — 거부(「병종이 다릅니다」)인지 병합인지, train/atmos 는 가중 평균인지 미정. 순수 함수 표(§7)에 이 세 행을 넣어라.
- **S4. 개인 기록 로그의 정확한 채널.** 「장수 개인 기록」 은 `world.pushLog(LogEntryDraft(scope = "general", category = "action", generalId = 주인))` 이어야 `/api/general-log`(scope=GENERAL, category 필터 `GeneralLogController.kt:28,91-93`) 에 뜬다. 스펙에 scope/category 를 적고, 「{이름}이(가)」 조사 처리 방식(기존 josa 헬퍼 유무)을 확인해라.
- **S5. `provisionMonths = provisions / max(1, troops)`** 는 `PROVISION_PER_TROOP_MONTH = 1` 을 하드코딩한 식이다. §1.4 「한 곳」 원칙대로 `provisions / max(1, troops × PROVISION_PER_TROOP_MONTH)` 로 써라.
- **S6. 초상 3종 규칙.** ADR-LITE-049 (4) 는 초상을 「원본 히어로 / 148×210 카드 / 96 아이콘」 3종만 허용한다. 「카드 44×62 = Portrait card-44」 가 148×210 카드 자산을 축소 렌더한 것이면 그렇게 적고, 새 자산이면 위반이다. 또 아트보드 행은 「이름」 이 아니라 **자(字)** 를 보여준다(S/F6 참조) — §6 「이름·자(없음)」 은 아트보드와 반대다.
- **S7. 게이트 순서의 명시.** board 핸들러는 「장수 없음 → 접속 제한 → 입력 → 권한」 순서를 PHP 줄 번호까지 적어 고정한다(`BoardHandler.kt:72-73`). 스펙 §3 표는 거부 문자열만 나열하고 순서가 없다. 6 명령 각각의 게이트 순서를 적고 `RetinueRulesTest` 가 순서를 핀하게 해라(문자열이 같아도 순서가 다르면 유저에게 다른 사유가 보인다). 또 `AccessLogThrottle` 은 거부돼도 access_log·`game_env.refresh` KV 를 쓴다(`AccessLogThrottle.kt:21-26`) — 어느 명령에 적용할지 6종 모두 표기(지금은 등용에만 있다).
- **S8. `retinueRecruit` 이름 정규화.** 「공백 제거 2~12자」 — trim 인지 내부 공백 제거인지, NFC 정규화, 같은 장수 안 중복 허용 여부를 정해라. varchar(24) 는 12자 한글에 충분하다.
- **S9. `RetinueRulesTest` 의 「상수」 검사.** `MAX_PERSONS == 5` 를 단언하는 테스트는 동어반복이다. 상수는 순수 함수 표의 입력으로만 쓰고, 상수 자체를 단언하지 마라(값을 바꿀 때 테스트도 같이 바뀌는 「지어낸 수치가 스펙이 된다」 경로).
- **S10. 결과 타입.** 「`BoardActionResult` 와 같은 꼴」 이 재사용인지 신규 `RetinueActionResult` 인지 정해라. 신규면 `TurnDaemonCommandResult` sealed 등록·직렬화·`toCommandResultRows` 영향을 적어야 한다. 편성 결과엔 새 `buquId` 를 실어야 UI 가 바로 배정할 수 있다(F2 의 id 선할당 전제).

---

## 3. 질문 / UNKNOWN

- **Q1. 휘하 인물의 정체.** 아트보드(기존 장수의 자)와 ADR-017(EXISTING|RECRUITED)은 기존 장수를 포함하고, 스펙은 자유 이름 신규 인물만이다. 사용자가 원하는 쪽이 어느 것인지 UNKNOWN — F6 의 (i)/(ii) 선택이 여기에 달렸다.
- **Q2. 주인 장수 사망·삭제 시.** CASCADE 로 부곡·휘하가 사라진다(병력 소멸). 국가군 crew 도 같이 사라지므로 일관되지만 의도인지, 아니면 부곡이 국가군으로 환원되는지 UNKNOWN.
- **Q3. 부곡의 위치.** 스펙 buqu 에 city 가 없다. 계획 §Phase 4X 서두는 「작전이 부곡을 부대로 쓴다」 고 한다 — 4X-B 가 부곡 위치를 전제하면 V56 이 또 필요하다. 4X-B 스펙과 열 합의가 필요한지 UNKNOWN.
- **Q4. `crewTypeName` 원천.** `GameConst` 의 어느 표에서 읽는지 확인하지 않았다(UNKNOWN).
- **Q5. rehydrate 범위.** F2 대로 메모리에 올리면 재기동 시 적재가 필요하다. `RehydrateLosslessGateIT` 류 bounded gate 에 두 표를 넣어야 하는지(CLAUDE.md 133행: all-channel lossless 는 격리 운영 범위) UNKNOWN.
- **Q6. 기본 초상 자산.** §6 「이름 이니셜 타일이 아니라 기본 초상」 — 그 자산이 `image-manifest.md` 에 있는지 확인하지 않았다(UNKNOWN).
- **Q7. 잠정 상수의 규모.** `ceil(troops/100)×10` 이면 1,000명 부곡이 월 100금이다. 스펙이 「실측 기준선이 아니다, 게이트 임계값으로 쓰지 않는다」 고 밝혔으므로 판단하지 않는다 — 다만 플레이테스트 전엔 UI 도움말이 「잠정」 임을 표시해야 한다.

---

## 4. 읽은 파일(근거 경로)

`app/game-engine/src/main/kotlin/opensamguk/engine/intake/{BoardHandler,TroopHandler,VoteHandler,AccessLogThrottle}.kt` · `engine/turn/{ChangeRecorder,InMemoryTurnWorld,TurnWorldModel}.kt` · `engine/flush/{DatabaseHooks,TruncateContract}.kt` · `engine/run/{TurnRunService,MonthlyPreUpdateHook,TurnDaemonCommandDispatcher}.kt` · `logic/src/main/kotlin/opensamguk/logic/tick/MonthlyPipeline.kt` · `infra/src/main/kotlin/opensamguk/infra/persistence/JdbcFlushExecutor.kt` · `infra/src/main/kotlin/opensamguk/infra/read/BoardPostRepository.kt` · `infra/src/main/resources/db/migration/{V32,V53}__*.sql` + 디렉터리 목록 · `infra/src/test/kotlin/opensamguk/infra/persistence/V32WorldScopeCompletionMigrationTest.kt` · `app/game-engine/src/test/kotlin/opensamguk/engine/golden/LongSimReplayGateTest.kt` · `app/game-api/src/main/kotlin/opensamguk/gameapi/{security/GameApiSecurityConfig,controller/MyController,controller/GeneralLogController,reserve/CommandWireMapper}.kt` · `common/src/main/kotlin/opensamguk/common/wire/{TurnDaemonCommand,TurnDaemonCommandResult}.kt` · `web/game/components/CommandModal.tsx` · `web/game/app/game/{board,my}/page.tsx` · `docs/design/ui-redesign-2026-09/src/General.body.html` · `docs/design/roadmap.md:39-46` · `.ai/decisions.md` ADR-LITE-017/049 · `CLAUDE.md` · `docker-compose.yml`.

</details>
