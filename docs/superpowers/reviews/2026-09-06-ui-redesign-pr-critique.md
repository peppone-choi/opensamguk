# UI 리디자인 PR #651 — V2 통합 재판정

- Date: 2026-09-07 (2차 통합)
- Target: PR #651 `work/opensamguk/ui-redesign-2026-09` → `main`, **HEAD `65578804`** (`git diff --stat origin/main...HEAD` = 573 files, +23739/−3583). 워크트리 untracked `reports/ui-redesign/phase4xb/` 는 범위 밖.
- 1차 판정(아래 「역사 기록」, HEAD `a119a57b` 기준) 뒤 커밋: `5cdb3301`(4X-B 구현 + F1/S1~S5/S9), `b338c6cf`(S6·S7·S8), `9f15df70`·`aa268eb7`(4X-C 1/3·2/3), `70eb5300`·`65578804`(문서). 4X-C 스펙 v4.1(`docs/superpowers/specs/2026-09-06-wego-field-seal-replay-vertical-slice.md`, cleared) · 4X-B 스펙 v4.1(`…/2026-09-06-operation-vertical-slice.md`, cleared) 이 대조 기준.
- Method: 파일 직접 열람 + `git log/diff/show/status` 만. gradle·docker·vitest·골든 미실행 — 실행 결과에 기대는 주장은 UNKNOWN(§V2-6). `build/`·`.env*` 미열람. 한 번에 3 파일 이하로 읽고 절마다 저장했다.
- Verdict: **cleared** — 1차 fix-required F1 과 should-fix S1~S9 는 전부 닫혔다(§V2-1, 파일:줄). 4X-C 구현은 스펙 v4.1 의 결정을 파일:줄 단위로 따르고(§V2-2), 계획 없는 `che_출병` 경로의 새 부수효과는 기본 null 훅 호출과 읽기뿐이다. 새 fix-required 없음. should-fix 8건(S10~S17) — 그중 S10(「봉인됨」 점선 조건이 서버 전역 정책 존재이지 그 장수의 autorun 창이 아니다; 스펙 T5 의 원천 `my-page.autorunLimit` 이 미배선) 과 S12(8i CREATE→UPDATE 순서가 부분 UNIQUE 와 한 payload 에서 충돌하는 재시도 경로) 는 머지 전에 닫기를 권한다. 4X-B 는 요약 수준(§V2-3).

---

## V2-1. 1차 판정 항목 닫힘 여부 (F1 · S1~S9)

| # | 판정 | 근거 (HEAD `65578804`) |
|---|---|---|
| **F1** 대표 장수 해제 400 | **닫힘** | `app/gateway-api/src/main/kotlin/opensamguk/gateway/dto/RepresentativeDto.kt:24-28` — `@field:NotNull` 제거, `val generalId: Int?` 만 남음. `app/gateway-api/src/test/kotlin/opensamguk/gateway/controller/RepresentativeControllerTest.kt:39-45` — standalone MockMvc 로 `{"generalId": null}` → 200 + `service.set(…, isNull())` verify, `{"generalId":"x"}` → 400 으로 바인딩 생존 확인. (standalone MockMvc 는 클래스패스에 JSR-303 이 있으면 `OptionalValidatorFactoryBean` 을 기본 등록하므로 `@NotNull` 을 되돌리면 빨개진다 — 실행은 하지 않았다.) 사족: `:25-26` KDoc 두 줄이 겹쳐 있다(옛 문장 + 새 문장), 정리 대상. |
| **S1** PG null 바인딩 | **닫힘(적색면 확보, 실행 UNKNOWN)** | `app/board-api/src/test/kotlin/opensamguk/boardapi/board/GatewayBoardMigrationIT.kt:89-96` — Testcontainers PG16(`:70`) + `spring.flyway.enabled=true`(`:80`) + `ddl-auto=validate`(`:79`) 에서 `searchLatest(null,null,null)`·`searchLatest(null,1L,null)`·`searchPopular(null,null,…)`·`searchLatest("FREE",null,"검색")` 4경로 실행. 바인딩 오류는 prepare 시점에 터지므로 빈 표라도 적색면이 된다. `disabledWithoutDocker=true` 라 도커 없는 환경에선 건너뛴다 — 실제 통과 여부는 이 판정에서 실행하지 않아 UNKNOWN(계획 문서 `70eb5300` 의 실측 주장에 기댄다). |
| **S2** 로그 innerHTML 잔존 | **닫힘** | `web/game/components/admin/GeneralLogPanel.tsx:2,55` · `web/game/app/game/inherit/page.tsx:21,635` 모두 `<LogText text=…/>`. 남은 `dangerouslySetInnerHTML` 7곳(`inherit/page.tsx:327` def.info, `join/page.tsx:669`, `troop/page.tsx:227` brief, `SafeHtml.tsx:60`, `BettingDetail.tsx:305`, `ChiefCommandReserve.tsx:145`, gateway `board/posts/[postId]/page.tsx:161` contentHtml)은 로그가 아니라 ADR-050 「모든 로그 표시」 범위 밖 — main 유래. |
| **S3** 지휘관 사기 펌프 | **닫힘** | `app/game-engine/src/main/kotlin/opensamguk/engine/intake/RetainerHandler.kt:152-156` — `commanderBonusApplied` 부곡 생애 1회 플래그. 영속 사슬 확인: `V56__operation.sql:71` `ALTER TABLE general_bugok ADD COLUMN … commander_bonus_applied BOOLEAN NOT NULL DEFAULT false` → `WorldSnapshotLoader.kt:246,254` → `TurnWorldModel.kt:161` → `DatabaseHooks.kt:351` → `RetainerRowMapper.kt:32` → `JdbcFlushExecutor.kt:1765-1772(INSERT),1791-1798(UPDATE)`. 테스트 `RetainerIntakeTest.kt:169`. 컬럼이 「작전」 마이그레이션(V56)에 얹혀 있는 건 이름과 어긋나지만 기능엔 무관. |
| **S4** RetinueSlot 오류 위장 | **닫힘** | `web/game/components/game/RetinueSlot.tsx:11-37` — `loading/error/ok` 3상태, 오류는 점선 버튼 + `title="휘하 정보를 불러오지 못했습니다"`, 0건은 `/game/my#retinue` 링크(`:34`). |
| **S5** 사유 없는 select disabled | **닫힘** | `RetinuePanels.tsx:102,159` `title={busy !== '' ? '처리 중' : undefined}`, `ServerStatusPanel.tsx:55` 동일. |
| **S6** NPC 가드 | **닫힘** | `RetainerHandler.kt:38-39` `if (me.npcState >= 2) … REASON_NPC`(preGate 라 6 명령 공통), `RetainerRules.kt` `REASON_NPC` 상수, `RetainerIntakeTest.kt:58-70`(npcState 2 거부 + crew 불변 + recorder 패치 0, npcState 1 허용). |
| **S7** V52 공백 | **닫힘** | `infra/src/main/resources/db/migration/V52__reserved_gap.sql` 자리표시자(`SELECT 1;`). 목록: V50…V57 연속. |
| **S8** 신고 N+1 | **닫힘** | `GatewayBoardService.kt:151-162` `targetSummariesOf` — 글·댓글 각 `findAllById` 1회, `listReports:132` 에서 페이지 단위 호출. |
| **S9** V54 주석 | **닫힘** | `V54__gateway_board_extend.sql:2` 「전략/공략·서버 이야기·창작/일지」 6종. |

1차 §3 Q4(미커밋 4X-B 코드 섞임)는 이후 커밋 `5cdb3301`·`b8f71e77` 로 PR 에 정식 포함됐다 — 이제 범위 안이다(§V2-3). Q6(`BoardActions.addArticle(kind=vote)` voteId 검증 없음)은 이번 커밋들에서 다루지 않았다 — 유지(should-fix 로 승격하지 않음, 화면 안전 확인 그대로).

## V2-2. 4X-C 구현 vs 스펙 v4.1 (핵심)

### V2-2a. logic seam · 훅 · 코덱 · 엔진 · flush — 스펙 §1·§3·§5 대조

| 확인 포인트 | 판정 | 근거 |
|---|---|---|
| seam 자리(§0 F1·N1, §5 (1)~(3)) | **일치** | `logic/src/main/kotlin/opensamguk/logic/war/ProcessWarNG.kt:154-170` — `addPhase()` 뒤 (1) `attacker.continueWar()` 자연 퇴각 → `retreatAttacker` + `break`(`:155-160`), (2) `def.continueWar()`(`:162`, **자리 불변** — `git diff origin/main...HEAD` 에서 이 줄은 컨텍스트) → `stop = hooks.plannedStop(attacker, def, attacker.getPhase())` **1회**(`:163`) → `fell`(`:164`, 술어 = 오늘 `siegeWin` × `!canContinue`) → `!fell && stop != null` 만 `retreatAttacker(noRice=false)`(`:165-170`), (3) 수비자 분기 그대로 + `\|\| stop != null` 로 넓힌 break(`:200`). diff 는 이 추가 + `retreatAttacker` 추출(`:250-261`)뿐. `logWritten=true`·`break` 는 호출부(T1 반영, `:157-159,167-169`). `WarBattleHooks.kt:84` 기본 `null`. |
| `stop == null` 경로 부수효과 0 | **일치** | 새로 불리는 것은 `hooks.plannedStop`(NOOP/프로덕션 = null, `WarBattleHooks.kt:84`) 과 `fell` 계산(읽기)뿐. `def.continueWar()` 호출 위치·횟수 불변. `ProductionWarBattleHooks` 는 `plannedStop` 미구현 → 기본 null(grep). |
| 훅 위임 완전성 | **일치** | `plan/PlanBattleHooks.kt:10-29` `DelegatingWarBattleHooks` 가 인터페이스 17 메서드(`WarBattleHooks.kt:27-84`) 전부 override — 계획이 있을 때 프로덕션 훅(`addTrain`·`defenderNationRice`·`citySupply`…)이 조용히 빠지지 않는다. |
| `plannedStop` 판정(§5) | **일치** | `BattlePlanRules.kt:82-89` probe(`phaseIndex >= 1`) → loss(`crewNow <= crewBefore*(100-pct)/100`, 정수) → morale(`atmosNow < value`) 표기 순. `phaseIndex` = `attacker.getPhase()` **post-addPhase** = 방금 끝난 페이즈 수(1부터) — `onPhaseLog` 의 `index = attacker.getPhase()+1`(pre-addPhase, `PlanBattleHooks.kt:56`) 과 같은 축이라 `stopAtPhase` 와 `phases[].i` 가 맞는다. `crewBefore` = `defaultProcessWar` 진입 시 `d.general.crew`(`CheChulbyeong.kt:315`) — 한 resolve 에 전투 1회라 페이즈 카운터(`WarUnitGeneral` 은 processWar 안에서 생성)·`crewBefore` 모두 전투 단위. |
| `result` 단일 규칙(P1) | **일치** | `BattlePlanRules.resultOf(conquered, retreat, lastDefenderDown)`(`:95-100`) 한 곳, `BattleReplayDraft.result()`(`:30`) 가 그것만 부른다. 플래그: `ReplayRecordingHooks.onPhaseLog` 가 `lastDefenderDown=false` 리셋(`PlanBattleHooks.kt:60`), `onDefenderDownLog` → true(`:72`), `onRetreatLog` → `retreat`(`:67`), `conquered` 는 `CheChulbyeong.kt:343` `result.conquerCity`. 검산: **병량 패퇴**(`ProcessWarNG.kt:64-78` `onSupplyRout` → `conquerCity=true` → break, `onPhaseLog` 없음) → ① `conquered` ✓(묵은 `lastDefenderDown` 은 ① 이 앞이라 무해); **`stop` 있고 `fell`**: 성이면 `:186-189` conquered, 장수면 `:197` `onDefenderDownLog` → `:200` break → ③ `defenders_down` + `plan_stop` ✓; **비공성 성 재정비**: `fell=false`(`:164`) → stop 있으면 (2) 퇴각 비용 + `retreat`, 없으면 오늘 `:193-195` `setOppose(null)`·`onDefenderDownLog` 없음 → ④ `repelled` ✓. |
| 사망자·정산 | **일치** | `deadAttacker/deadDefender` 는 `onPhaseLog` 누적(`PlanBattleHooks.kt:61-62`), `crewAfter = result.attacker.getCrew()`, `riceUsed = riceBefore − result.attacker.state.snapshot().rice`(`CheChulbyeong.kt:341-342`; `riceBefore = d.general.rice` `:315`). `lastReplayDraft` 는 `resolve` 진입 초기화 목록에 있다(`:173`, `lastBattleResult` 옆). |
| 조립·바이트 동일 구조 증거 | **일치** | `CheChulbyeong.kt:291` `bctx.sealedPlans[bctx.finalTargetCityId]`(F6 — 경유 전투도 최종 목표 키), `:319` 계획 없으면 `hooks` 원본 그대로, 있으면 `ReplayRecordingHooks(PlannedWarBattleHooks(hooks, plan, crewBefore), draft)`. `processWarFn` 주입 테스트는 우회(R12). |
| `input_hash` 내용·비결정 값 | **일치(주의 1)** | `CheChulbyeong.kt:293-312` schema/warSeed/attacker/defenders/city/env(값 12개)/plan/year/month/startYear. `generalFingerprint`(`:350-358`) 는 id·nationId·crew·crewTypeId·tech·train·atmos·rice·gold·injury·officerLevel·npcType·stats·items·**`meta` 통째**. 코덱(`BattleReplayCodec.kt:37-58`)은 Map 키 정렬·Double→`toBits()`·그 외 `toString()`. `General.meta` 는 DB JSON 유래(`LogicEntities.kt:50` 주석: explevel·*_exp·dedlevel·aux·killturn…; 엔진이 쓰는 키 grep: `last_turn`(맵)·`autorun_limit`·`makelimit`·`officer_city`·`penalty`…)라 값은 숫자·문자열·중첩 맵 — Instant 객체는 없다(로더가 JSON 을 Map 으로 넘긴다). 다만 `meta["last_turn"]` 은 **이 명령 자신의 `LastTurn`** 을 `:234` 에서 갱신한 뒤 지문에 들어가고, `killturn`·`lived_month`·각종 `_exp` 는 매 턴 바뀌므로 `input_hash` 는 사실상 「그 순 그 장수」 지문이다 — 스펙이 이미 「부분 지문, 게이트는 replay_hash」 라 했으므로(R3) 결함 아님. 주의: `"defenders"` 는 `bctx.defenderGeneralsByCity[chosenCityId]`(id 정렬 스테이징) 이지 실제 출전 순(`extractBattleOrder`)이 아니다 — 스펙 `:126` 「(출병 순)」 과 문구 불일치, 지문 값에는 영향 없음(S10 참고, 아래 S-항목). |
| 주입 경로(F2·F5·M5) | **일치** | `engine/war/BattleCommandContextBuilder.kt:40,81-85,107` — `autorunMode` 참이면 `emptyMap()`, 아니면 `world.battlePlansOf(id)` 중 `sealed && !resolved && absoluteTurn(sealedDate) <= absoluteTurn(executing)`(F5 `<=`, `OperationRules.absoluteTurn` 재사용). 호출부 `ReservedTurnHandler.kt:253-259`(`chosen.actionCode != reserved.actionCode` 때만 `autorunMode=true`) → `:386-398` build 에 `autorunMode = autorunMode` 전달 ✓. |
| 후처리(N2·R10·F7) | **일치** | `ReservedTurnHandler.kt:445` `drainBattleReplay(lastReplayDraft, general)` (`CheChulbyeong` 분기, `drainWarBattleResult` 뒤) → `:695-737`: null 이면 즉시 return(채널 0), 계획 `resolved*` UPDATE(`:699-701`), 이름은 `world.getGeneralById/getCityById` 폴백 `G$id`/`C$id`(`:702-704`), `encodePhases` → `replayHash(phasesJson, settlement)`(`:705-711`), `operation_id` = 참여 중 OPEN 작전 중 `targetCityId == plan.targetCityId` 의 최소 id(`:713-716`), `recorder.recordBattleReplayInsert(columns)`(`:735`) + 개인 기록 「리플레이 #id」(`:736`). 데몬 쓰기 규칙: `world.updateBattlePlan`·`recorder.record*`·`world.pushLog` 만 — JPA/JDBC 직접 쓰기 없음. |
| F3·N4 프룬 | **일치** | `ChangeRecorder.kt:1196-1210` `markGeneralDeleted` 가 `attacker_general_id == id` 또는 `battle_plan_id ∈ 그 장수 계획` 을 NULL 로 바꾼 뒤 `world.removeGeneral`(→ `InMemoryTurnWorld.kt:560` `pruneBattlePlansOf`: map·dirty·created·deleted 전부에서 제거). `:1275-1282` `markNationDeleted` 가 `pruneOperationsOfNation` 결과에 든 `operation_id` 를 NULL 로. |
| id 할당(F4) | **일치** | `DaemonLoopConfig.kt:272,281` `battleReplayRepository.findMaxId()` DB-seed → `{ ++nextBattleReplayId }`; `infra/read/BattleReplayRepository.kt:26-27` `WHERE world_id = ?` world-scoped. 기본값 `AtomicCounter` 는 테스트용(`ChangeRecorder.kt:90`). `battle_plan.id` 는 `InMemoryTurnWorld.kt:466-470` 고수위(`maxOf(max, keys.max, deleted.max)+1`) + `recordMaxBattlePlanId` 는 `> 0` 일 때만 meta(`:504-505`, 행 0 세계 바이트 동일). |
| 스키마(§2)·flush 8i(§3) | **일치** | `V57__battle_plan_replay.sql:5-33,37-77` — 복합 PK·`world_state` FK·`(world_id,general_id)` CASCADE·city FK·CHECK 3종·**부분 UNIQUE `WHERE resolved_year IS NULL`**(`:32`), replay 의 plan/operation/attacker 3 FK 모두 `ON DELETE SET NULL (col)`, 국가·도시 id 는 FK 없음(N4), result/plan_stop CHECK, 인덱스 3. `JdbcFlushExecutor.kt:285-291` 8i: `battlePlanDeleteMany → CreateMany → Update(requireExactlyOneAffected :1945) → battleReplayInsertMany`, 전부 `isNotEmpty()` 가드. `DatabaseHooks.kt:742-745` created 는 updated 에서 제외. 등록: `HotColdCatalog.kt:149-150`, `TruncateContract.kt:74-75`, `V32…Test.kt:660-661`. 로더 `WorldSnapshotLoader.kt:281-283` `resolved_year IS NULL` 만. |
| 부분 UNIQUE ↔ 메모리 상태 | **일치(주의 2)** | 메모리 쪽 키 판정은 `!resolved` 필터(`BattlePlanHandler.kt:53`, `myOpenPlan :42`, builder `:84`) 로 DB 부분 인덱스와 같은 술어. 소비된 계획은 메모리 map 에 `resolved=true` 로 남는다(프룬 없음; 재기동 시 로더가 버린다) — 느린 누적이지만 기능 오류는 아니다. **주의 2**: 8i 가 CREATE → UPDATE 라 「A 소비(UPDATE resolved) + 같은 (장수, 도시) 새 계획 B(INSERT)」 가 **한 payload** 에 들면 B INSERT 시점에 A 의 `resolved_year` 가 아직 NULL 이라 `battle_plan_open_uk` 위반. 오늘은 flush 가 틱마다(`TurnRunService.kt:291-301,456`: 인테이크 → 실행 → `flushWithGeneration`) 이고 인테이크가 실행 앞이라 같은 틱에 B 저장은 A 가 봉인·미소비 상태여서 `REASON_SEALED` 로 거부된다 → 쌍이 생기지 않는다. 쌍이 생기는 유일한 길은 flush 실패로 델타를 유지한 채(`:462` abort 경로) 다음 틱 인테이크가 B 를 만드는 경우 — 아래 S11. |
| HanMapCanvas | **무접촉** | `git diff --stat origin/main...HEAD -- '**/HanMapCanvas*'` 빈 출력. |

### V2-2b. game-api 읽기 · wire · 웹 — 스펙 §4·§6·§7 대조

| 확인 포인트 | 판정 | 근거 |
|---|---|---|
| wire 3 명령 + 결과 직렬화기 | **일치** | `common/…/TurnDaemonCommandResult.kt:137` `BattlePlanActionResult`, `:657` `BATTLE_PLAN_ACTION_TYPES = {battlePlanSave, battlePlanSeal, battlePlanDelete}`, `:707-708` `selectSerializer` 분기 — 1-C(`boardRead` throw) 부류 재발 없음. `CommandWireMapper.kt:97-99,379-384` 인자 nullable 로 넘기고 엔진 ③ 게이트가 판정. |
| 인테이크 게이트 순서(§4) | **일치** | `BattlePlanHandler.kt:29-36` ①②, `:48-51` ③(`BattlePlanRules.saveInput` — stance/pct/morale/정수), `:52-54` ④ 1 도시 없음 → 2 아군 도시 → 3 봉인됨(`saveDeny :61-66`); seal `:70-74`(REASON_NO_PLAN → REASON_SEALED, `sealedAt=nowProvider()`·`sealedDate=현재 순`, 개인 기록 「<Y>{도시}</> 출병 계획을 봉인했습니다.」 `:76`); delete `:84-87` 초안만. 소비된 계획은 `!resolved` 필터로 키를 놓는다(`:42,53`) → 새 행 version 1(`:58-61`). 409 없음(인테이크 사유만). **주의 3**: `preGate` 에 S6 류 `npcState >= 2` 가드가 없다 — 아래 S11. |
| 읽기 API 인증·범위(§6) | **일치** | `GameApiSecurityConfig.kt:48` 세 경로 `.authenticated()`(`/*` 는 한 세그먼트). `BattlePlanController.kt:44-46` 401/404, `openPlansOf` = `resolved_year IS NULL`(`BattlePlanReadRepository.kt:73,92`); `:70` `scope=mine` 또는 재야(`nationId == 0`)는 본인만, 아니면 `replaysOfNation`(공격 **또는** 수비); 상세 `:79-80` 본인 ∨ 공격국 ∨ 수비국 아니면 403. `rules` 는 `BattlePlanRules` 상수만(`:115-121`, `provisional=true`, 예약 태세 3종은 `enabled=false` + `REASON_STANCE_RESERVED`). **주의 4**: `replaysOfNation` 은 공격 Top50 + 수비 Top50 합집합(`:95-97`) — 목록·헤더 수가 「최근 각 50」 임을 UI 가 말하지 않는다(S13). |
| 09 명령 봉인 화면(§7) | **일치(주의 5)** | `web/game/app/game/battle-plan/page.tsx` `?city=` 없으면 안내문. `BattlePlanPanel.tsx`: 태세 2종 활성/3종 disabled + `rules` 사유(`:148-153`), 조건 2개 플레이어 입력 + 범위는 `rules`(`:158-167`), 「합류 전 추격 금지」 disabled + 「엔진에 추격이 없습니다」(`:168-169`), 「퇴각은 부상 판정을 받습니다」(`:171`), 예상 = 기존 `simulateBattle` + 목록 첫 수비자 + 「목록 첫 수비자 1인 기준 예상」(`:82,91,175`; 없으면 disabled + 「수비 장수 없음 — 성 방어만」), 봉인 확인 「봉인 뒤에는 바꿀 수 없습니다」(`:186`), 봉인 뒤 fieldset disabled + 「봉인됨 · 읽기 전용」·칩 「봉인됨 · {순}」(`:99,144-145`), 다중 부대 점선 카드 「이 절편은 본인 부대만」(`:116-119`), 카운트다운 = `reservedCommands.turnTime`(`:49,83-84`; `parseTurnTime` 로컬 해석은 `PartialReservedCommand.tsx:144` 기존 관용과 같다). 202≠성공: `submitCommandAndAwaitResult` → `status === 'applied'` 만 성공(`:69-70`), 아니면 사유 표시. **주의 5**: 숫자 입력 `disabled={!form.lossOn}`(`:160,165`) 에 사유 없음, 초기값 `loss: 50, morale: 40`(`:41`) 은 UI 상수(체크 전엔 미전송이라 「지어낸 임계값」 은 아님) — S15. |
| 작전실 봉인 링크(S11·R14·R9) | **일치(주의 6)** | `PartialReservedCommand.tsx:183` `slot?.action === 'che_출병' && typeof slot.arg.destCityID === 'number'` 만 링크, 봉인된 미소비 계획(`/api/my-battle-plans` `sealed`) 이면 「봉인됨」 칩(`:71-78,184-191`). **주의 6**: 점선 조건 `autorunNotice` 는 `GameChrome.tsx:156` `frontInfo.global.autorunUser != null` — 이것은 `world_state.config['autorun_user']`(서버 전역 자율행동 정책, `FrontInfoController.kt:632`, `AutorunUserInfo{limitMinutes, options}`) 의 존재 여부이지 스펙 §7 `:142`·T5 의 「그 장수의 `autorunLimit` 이 현재 순 이후이거나 `npc >= 2`」 가 아니다. 원인: `/api/my-page` 의 `autorunLimit` 은 항상 null(`IdentityDto.kt:271` 「aux 컬럼 없음 … null」). 결과: 정책이 켜진 서버에선 **모든** 봉인됨 칩이 「AI 가 명령을 바꾼 턴에는 적용되지 않습니다」 점선, 꺼진 서버에선 실제 autorun 장수도 실선 — S10. |
| 10 리플레이·감찰부 열 | **일치(주의 7)** | `BattleReplayPlayer.tsx`: 對 헤더·페이즈 스크럽(‹ › n/총, 재생, 0.5×/1×/2× 간격 `1500/speed`)·페이즈 텍스트(`onPhaseLog` 수치)·조건 발동 칩(`stopAtPhase === p.i`)·정산(사상·군량·점령·작전 링크)·「같은 seed·입력이면 같은 결과를 재생합니다」 + `replayHash.slice(0,8)`·「감찰부 기록」. `BattleReplayList.tsx:24` 「기록 없음(계획 미봉인)」 점선 + title, `battle-center/page.tsx:368` 마운트. **주의 7**: 스크럽 ‹ › 는 `<button disabled>` 에 사유·점선 없음(경계라 자명하지만 ADR-049 (7) 문자 그대로는 아님) — S15. |
| 라벨 verbatim | **일치** | result 4 라벨(`BattlePlanRules.kt:33-35`)·stance 라벨·`PLAN_STOP_LABELS`·「이 절편에서는 지원하지 않습니다」(`:27`) 가 스펙 §2·§7 문구와 같다. |

### V2-2c. 테스트·게이트(§8) — 존재·형태 확인(실행은 UNKNOWN)

| 스펙 §8 항목 | 파일 | 판정 |
|---|---|---|
| `ProcessWarPlanHookTest` 적색 프로브 | `logic/src/test/kotlin/opensamguk/logic/war/ProcessWarPlanHookTest.kt:90-105`(no plan), `:109-122`(probe: phase 1개·`retreat:G2:false`·tryWound 2 draw·`result:G1,G2`·draft stop/atPhase/result), `:126-141`(loss pct: 두 번 실행 같은 phases json·replay_hash, assault 무조건 = no plan `:135`, 조건 미충족 = 돌격과 동일 `:141`), `:146-157`(M1 비공성 성: `retreat:C10:false`·draw 1·result retreat) | **형태 일치(주의 8)**. `:90` 「no plan」 프로브는 **같은 새 코드**를 맨 `RecordingHooks` 와 `DelegatingWarBattleHooks` 래퍼로 두 번 돌려 비교한다 — 래퍼 투명성은 핀하지만 「리팩터 전 = 후」(바이트 동일) 는 이 테스트로 빨개지지 않는다(양쪽에 같은 추가 draw 가 들어가도 같다). 바이트 동일의 실제 핀은 (a) diff 가 최소라는 구조 증거, (b) 무접촉 `ProcessWarNGOrderTest`(draw 스트림·tryWound 횟수 리터럴 `:147-162`), (c) 골든 274 JSON 회귀(테스트·리소스 무접촉, `git diff --stat` 빈 출력). 스펙 S14 의 「`ProcessWarNGOrderTest` 확장(`onBattleResultLog` 포함)」 은 **하지 않았다**(diff 0) — S14. |
| `BattlePlanRulesTest` | 존재 여부·행 수는 열지 않았다(logic 테스트 목록 미열람) — UNKNOWN. 계획 문서 `70eb5300` 「rules 5」 주장에 기댄다. |
| `BattlePlanIntakeTest` | `app/game-engine/src/test/…/intake/BattlePlanIntakeTest.kt:61-96`(게이트 순서·version+1·봉인 잠금·초안만 삭제·타인 계획 NO_PLAN·payload 채널·`max_battle_plan_id`·개인 기록·replay 0), `:100-119`(F5 같은 순 봉인 적용·미래 순 제외·M5 빈 맵·소비 뒤 재저장 version 1), `:123-143`(F3 장수 사망 → id NULL + 계획 프룬, N4 국가 소멸 → operation_id NULL) | **일치** |
| `ReservedTurnHandler` 후처리 | `…/turn/ReservedTurnBattlePlanTest.kt:40-46`(계획 없음 = 행 0·계획 UPDATE 0·로그 0), `:50-68`(행 열 전부·JSON 키 정렬 바이트·해시 길이·소비·개인 기록 「리플레이 <Y>#1</>」), `:72-78`(점령 = conquered·`plan_stop` NULL·수비국 cascade 뒤 스냅샷 유지), `:82-88`(두 번 = 같은 phases/replay/input 해시) | **일치** |
| `BattlePlanReplayFlushIT`(PG16) | `infra/src/test/…/BattlePlanReplayFlushIT.kt:87-116` 부분 UNIQUE 적색면(`:99-101` 미소비 둘 거부 `assertThrows`)·F3(`:107-109` CASCADE + SET NULL + 이름 유지)·N4(`:111-116` operation_id NULL 로 COMMIT, `attacker_nation_id` 스냅샷) | **일치(주의 2 재확인)**: 「소비 뒤 같은 키 재생성」 은 UPDATE(`:93`) 와 CREATE(`:98`) 를 **다른 flush** 로 나눠 통과시킨다 — 한 payload 에 함께 들어가는 경우는 검증되지 않았고 8i 순서(CREATE → UPDATE)상 실패한다(S12). |
| game-api | `BattlePlanReadControllerTest.kt:63,83`(401/200 규칙, nation scope 공격·수비, 3국 403, 본인 200) — 스펙 이름 `BattleReplayReadControllerTest` 와 다르지만 내용 일치 | **일치** |
| vitest | `web/game/__tests__/battle-plan-panel.test.tsx`(3: 태세 사유·인자 null·봉인 잠금/예상 사유), `battle-replay-player.test.tsx`(2: 헤더·스크럽·조건 칩·해시, 목록 점선), `reserved-command-seal-link.test.tsx`(2: che_출병+숫자만·봉인됨·autorun 점선·href 없으면 0) | **일치** |
| e2e | 스펙 §8 `:155` 대로 UNKNOWN(로컬 턴 시각) — 계획 문서 주장 미확인 | UNKNOWN |

## V2-3. 4X-B(작전) 구현 vs 스펙 v4.1 — 요약

- 스펙 `docs/superpowers/specs/2026-09-06-operation-vertical-slice.md` v4.1(cleared, R1~R7 반영). 구현 커밋 `5cdb3301`(+ `d92a7f26` SMALLINT→Short 수정, `b8f71e77` 관리자 개입 0 시뮬).
- 확인한 대응: 선언 가능 3종 `DECLARABLE_KINDS = {capture_city, relieve, cut_supply}`(`logic/…/operation/OperationRules.kt:34,108`); 기한 산술 `ServerClock.advance`(`logic/…/tick/ServerClock.kt:82`)·`OperationRules.absoluteTurn`(`:77`, 4X-C 가 재사용); `V56__operation.sql:30-32,55-57` 국가 CASCADE·선언자 SET NULL·unit bugok SET NULL, `:63-67` `board_post.operation_id` FK **DEFERRABLE INITIALLY DEFERRED**(8d 가 8h 앞); `InMemoryTurnWorld.pruneOperationsOfNation`(`:446`) 이 `ChangeRecorder.markNationDeleted`(`:1266`) 한 곳에서 불리고, 국가 소멸 4경로 전부 그 함수를 탄다(`ReservedTurnHandler.kt:467` 해산, `:789` 점령 멸망, `RulerSuccessionHandler.kt:138`, `MonthlyPostUpdateHook.kt:279`); 관리자 개입 0 시뮬 `OperationAdminZeroSimTest.kt:40` 「선언 → 참여 → 예약 출병 실경로 점령 → 월 정산 달성 — 직접 world 쓰기 없음」 + `OperationMonthlyNoopGateTest`.
- 1차 판정 §3 Q4 가 지적한 「미커밋 4X-B 코드 섞임」 은 `5cdb3301` 로 PR 에 정식 포함됐다. `commander_bonus_applied` 컬럼(S3)이 V56 에 얹힌 것 외에 스펙과 어긋나는 점을 이 요약 수준에서는 찾지 못했다. 4X-B 의 코드 대조 깊이는 4X-C 보다 얕다(파일:줄 위주 표본 확인) — 전면 대조는 하지 않았다.

## V2-4. 새 결함 (fix-required) — **없음**

4X-C 구현은 스펙 v4.1 의 결정(F1~F7·N1~N4·M1~M6·P1·R1~R14)을 파일:줄 단위로 따르고, 계획 없는 경로의 부수효과는 `hooks.plannedStop`(기본 null)·`fell` 읽기뿐이다. 1차 F1·S1~S9 는 전부 닫혔다.

## V2-5. should-fix (S10~S17)

### S10. 「봉인됨」 점선의 조건이 스펙과 다르다 — 서버 전역 정책 존재 ≠ 그 장수의 autorun 창

- `web/game/components/game/GameChrome.tsx:156` `autorunNotice={frontInfo.global.autorunUser != null}` → `PartialReservedCommand.tsx:186-188` 점선 + 「AI 가 명령을 바꾼 턴에는 적용되지 않습니다」. `autorunUser` = `world_state.config['autorun_user']`(`FrontInfoController.kt:632`, `AutorunUserInfo{limitMinutes, options}`) — 서버가 사용자 자율행동을 허용하는지이지, 이 장수가 autorun 대상인지가 아니다. 스펙 §7 `:142`(T5) 는 「`/api/my-page` 의 `autorunLimit` 이 현재 순 이후이거나 `npc >= 2`」.
- 원인은 스펙 쪽 원천 부재: `IdentityDto.kt:271,280` my-page `autorunLimit` 은 「aux 컬럼 없음 … null」 로 항상 null. 
- 재현: 자율행동 정책이 켜진 서버에서 활성 플레이어가 봉인 → 작전실 슬롯 칩이 점선 + 「AI 가 명령을 바꾼 턴에는 적용되지 않습니다」 (실제로는 적용된다). 꺼진 서버에서 `npc >= 2` 장수의 칩은 실선.
- 고치는 법(둘 중 하나): (a) my-page 에 엔진 `isAutorunEligible`(`ReservedTurnHandler.kt:1747-1751`) 과 같은 술어 결과(`autorunEligible: Boolean`)를 실어 그것으로 점선; (b) 원천을 못 실으면 문구를 정직하게 「이 서버는 자율행동이 켜져 있습니다 — AI 가 명령을 바꾼 턴에는 적용되지 않습니다」 로 바꾸고 스펙 T5 행을 실측 원천으로 고쳐라. vitest `reserved-command-seal-link.test.tsx:24` 는 prop 만 보므로 어느 쪽이든 그대로다.

### S11. `BattlePlanHandler.preGate` 에 NPC 가드가 없다(S6 와 같은 부류)

- `app/game-engine/src/main/kotlin/opensamguk/engine/intake/BattlePlanHandler.kt:29-36` 은 ①②만. F2 전환기 `?generalId=` 신뢰(`InstantActionController.kt:47-48,90`, `GameApiSecurityConfig.kt:56` `anyRequest().permitAll()`) 아래에서 누구나 NPC(또는 타인) 장수에 `probe` 계획을 봉인할 수 있고, 그 장수의 예약 `che_출병` 이 AI 선택과 같은 코드면(`autorunMode=false`, `ReservedTurnHandler.kt:255-259`) 첫 페이즈 뒤 퇴각한다(부상 판정 포함). NPC 결정성 원칙(스펙 §밖 「NPC 는 선언·참여하지 않는다」) 과 어긋난다.
- S6 처럼 `if (me.npcState >= 2) return … REASON_NPC` 한 줄 + `BattlePlanIntakeTest` 1건. (NPC 예약 슬롯이 실제로 AI 선택과 일치하는 빈도는 UNKNOWN — 가드는 그 빈도와 무관하게 싸다.)

### S12. 8i 순서(CREATE → UPDATE) 와 부분 UNIQUE 의 한 payload 충돌 경로

- `JdbcFlushExecutor.kt:288-290`: 계획 DELETE → CREATE → UPDATE. 「A 소비(UPDATE `resolved_year`) + 같은 (장수, 도시) 새 계획 B(INSERT)」 가 한 payload 에 들면 B INSERT 시점에 A 가 아직 `resolved_year IS NULL` → `battle_plan_open_uk`(`V57:32`) 위반 → flush 실패 → 델타 유지(`TurnRunService.kt:453-462`) → 다음 재시도도 같은 쌍 → 영구 실패.
- 오늘은 틱마다 flush(인테이크 → 실행 → flush) 라 정상 경로에선 쌍이 생기지 않는다(같은 틱의 B 저장은 A 가 봉인·미소비라 `REASON_SEALED`). 쌍이 생기는 길: **어떤 이유로든 flush 가 한 번 실패해 델타가 남은 채** 다음 틱 인테이크가 B 를 만들면(메모리의 A 는 이미 `resolved=true` 라 허용) 그때부터 영구 실패. `BattlePlanReplayFlushIT.kt:93,98` 은 두 flush 로 나눠 통과시켰다.
- 고치는 법: 8i 를 DELETE → **UPDATE → CREATE** 로(같은 틱 생성분은 `DatabaseHooks.kt:743` 이 updated 에서 제외하므로 안전) 또는 부분 인덱스를 `DEFERRABLE` 로 못 박을 수 없으니(인덱스는 불가) 순서 교체가 맞다 + IT 에 「한 payload 에 UPDATE(resolved) + 같은 키 CREATE」 행 1건.

### S13. `replaysOfNation` 50+50 절단이 UI 에 드러나지 않는다

- `BattlePlanReadRepository.kt:95-97` 공격 Top50 ∪ 수비 Top50 → 최대 100, 어느 쪽이 50 을 넘으면 그 쪽 오래된 것부터 빠진다(합집합이라 전역 최신 100 도 아니다). `BattleReplayList.tsx:18` 헤더 `sub={rows.length}` 는 그 수를 총계처럼 보인다. 목록 아래에 「공격·수비 각 최근 50건」 한 줄, 또는 단일 쿼리 `(attacker_nation_id = :n OR defender_nation_id = :n) ORDER BY id DESC LIMIT 100`.

### S14. 「적색 프로브」 라벨과 실제 적색면이 다르다 + 스펙 S14 확장 미이행

- `ProcessWarPlanHookTest.kt:90` 「no plan … (red probe)」 는 새 코드 두 실행(맨 훅 vs 위임 래퍼)의 동일성이다 — 리팩터로 계획 없는 경로에 draw/훅 호출이 추가돼도 양쪽에 똑같이 들어가 **빨개지지 않는다**(「검사가 버그를 공유한다」). 바이트 동일의 진짜 핀은 무접촉 `ProcessWarNGOrderTest`(draw 스트림 리터럴) + 골든 274 JSON 이다. 테스트 이름·주석을 「래퍼 투명성」 으로 고치고, 스펙 §8 `:149`·S14 `:39` 가 요구한 **`ProcessWarNGOrderTest` 의 훅 호출 순서 확장(`onBattleResultLog`·`onRetreatLog` 포함, 리터럴 기대열)** 을 실제로 더해라(diff 0 이었다). 계획 문서의 「적색 프로브 4건」 주장도 같이 고쳐라.

### S15. 비활성 컨트롤 사유·UI 상수 잔여

- `BattlePlanPanel.tsx:160,165` 숫자 입력 `disabled={!form.lossOn}` 에 `title` 없음(체크박스가 사유 역할이지만 S5 와 같은 규칙), `:41` 초기값 `loss: 50, morale: 40` 은 UI 상수 — `rules` 에 `retreatLossPctDefault`/`retreatMoraleDefault` 를 싣거나 빈 값으로 시작. `BattleReplayPlayer.tsx:53,55,56` 스크럽 ‹ ›·재생 `disabled` 에 사유 없음(경계값이라 자명 — `title="첫 페이즈"`/「마지막 페이즈」/「페이즈 없음」 한 단어면 된다).

### S16. 문서·정의 미세 불일치

- 스펙 §5 `:126` `input_hash` 의 「`defenders:[…](출병 순)`」 — 구현은 `bctx.defenderGeneralsByCity[chosenCityId]`(id 오름차순 스테이징, `BattleCommandContextBuilder.kt:75`) 이지 `extractBattleOrder` 결과가 아니다(`CheChulbyeong.kt:297`). 지문 값엔 무관하나 문구를 「스테이징 순(id)」 으로.
- `plan_stop` 과 `conquered` 의 공존: `stop != null && fell && def === city` 는 `:186-189` 에서 점령으로 끝나지만 `draft.stop` 은 이미 기록돼 `plan_stop='probe'`·`result='conquered'` 행이 가능하다(리플레이 화면은 P1 에 「조건 발동」 칩 + 정산 「점령」). 스펙 §2 `:83` 「계획 조건이 멈춘 경우」 정의를 「조건이 참이었던 페이즈(결말과 무관)」 로 적거나, `conquered` 면 NULL 로 정규화 — 어느 쪽이든 문장 한 줄 + `ReservedTurnBattlePlanTest` 행 1개.
- `RepresentativeDto.kt:25-26` KDoc 두 줄 중복.

### S17. 소비된 계획이 메모리에 남는다

- `drainBattleReplay` 는 `updateBattlePlan(resolved…)` 만 하고(`ReservedTurnHandler.kt:699-701`) 프룬하지 않는다 — 재기동까지 `battlePlans` map 에 누적(로더는 `resolved_year IS NULL` 만). 소비자는 전부 `!resolved` 필터라 기능 영향은 없다. flush 뒤(UPDATE 가 나간 뒤) 제거하는 훅이 없으므로 「다음 틱 시작 시 resolved 행 제거(dirty 아님)」 정도로 — 또는 의도라면 스펙 §3 에 「소비 행은 세계 상태에 남고 재기동 시 버려진다」 를 적어라.

## V2-6. 질문 · UNKNOWN

- **U1** 모든 테스트·IT·vitest·골든의 실행 결과 — 이 판정에서 실행하지 않았다. 계획 문서 `70eb5300` 의 실측 주장(「wire 2·rules 5·ProcessWarPlanHook 4·intake 3·reserved-turn 4·FlushIT 1·V32 10」) 은 XML mtime 으로 확인하지 않았다(JDK 25 함정).
- **U2** `BattlePlanRulesTest`(스펙 §8 result 표 4+1행·`plannedStop` 표·코덱 결정성) 파일을 열지 않았다 — 존재·행 수 UNKNOWN.
- **U3** NPC 장수의 예약 슬롯이 AI 선택과 같은 `che_출병` 이 되는 빈도(S11 의 실효 위험) — UNKNOWN.
- **U4** 프로덕션 `world_state.config['autorun_user']` 설정 여부(S10 의 노출 범위) — 확인하지 않았다.
- **U5** `.env*`·`build/` 미열람, gradle·docker·vitest·골든 미실행.

## V2-7. 읽은 파일(2차)

문서: `docs/superpowers/specs/2026-09-06-wego-field-seal-replay-vertical-slice.md`(v4.1 전문), `docs/superpowers/reviews/2026-09-06-wego-seal-replay-spec-critique.md`(V4 절 `:1-60`), `docs/superpowers/specs/2026-09-06-operation-vertical-slice.md`(`:1-8`, §0 표 행), 이 파일의 1차 절.

logic: `war/ProcessWarNG.kt`(전문 + origin/main diff), `war/WarBattleHooks.kt`(diff·fun 목록), `war/ProductionWarBattleHooks.kt`(override 목록), `war/plan/BattlePlanRules.kt`, `plan/PlanBattleHooks.kt`, `plan/BattleReplayDraft.kt`, `plan/BattleReplayCodec.kt`, `actions/war/CheChulbyeong.kt`(`:160-180,236-370`), `domain/LogicEntities.kt`(`:50,91,112`), `operation/OperationRules.kt`(grep), `tick/ServerClock.kt`(grep), `memory/HotColdCatalog.kt`(grep), 테스트 `war/ProcessWarPlanHookTest.kt`(이름·단언), `war/ProcessWarNGOrderTest.kt`(단언 grep, diff 0)

engine: `intake/BattlePlanHandler.kt`, `intake/RetainerHandler.kt`(`:15-60,135-175`), `war/BattleCommandContextBuilder.kt`, `turn/ReservedTurnHandler.kt`(`:244-266,380-400,436-452,688-745`, grep), `turn/ChangeRecorder.kt`(`:86-92,836-842,1175-1215,1262-1285`), `turn/InMemoryTurnWorld.kt`(battlePlans grep·`:905-925`), `turn/PerTurnOverlay.kt`(meta grep), `flush/DatabaseHooks.kt`(grep), `flush/TruncateContract.kt`(grep), `boot/WorldSnapshotLoader.kt`(`loadBattlePlans`), `config/DaemonLoopConfig.kt`(`:270-281`), `run/TurnRunService.kt`(`:440-460`, grep), `run/MonthlyPostUpdateHook.kt`·`turn/RulerSuccessionHandler.kt`(호출부 grep), 테스트 `intake/BattlePlanIntakeTest.kt`·`turn/ReservedTurnBattlePlanTest.kt`(이름·단언), `intake/RetainerIntakeTest.kt`(`:58-70,169`), `operation/OperationAdminZeroSimTest.kt`(이름)

infra: `db/migration/V52__reserved_gap.sql`, `V54__gateway_board_extend.sql`(`:1-4`), `V56__operation.sql`(`:30-32,55-67,71`), `V57__battle_plan_replay.sql`(전문), `persistence/JdbcFlushExecutor.kt`(8i grep·`:1765-1798`), `persistence/RetainerRowMapper.kt`(grep), `read/BattleReplayRepository.kt`(`:9-27`), 테스트 `persistence/BattlePlanReplayFlushIT.kt`(`:86-116`), `V32WorldScopeCompletionMigrationTest.kt`(grep)

common: `wire/TurnDaemonCommandResult.kt`(grep `:137,657,707`)

game-api: `controller/BattlePlanController.kt`, `read/BattlePlanReadRepository.kt`, `security/GameApiSecurityConfig.kt`(`:42-56`), `reserve/CommandWireMapper.kt`(`:97-99,375-386`), `controller/InstantActionController.kt`(grep), `controller/FrontInfoController.kt`(`:632,670` grep), `dto/IdentityDto.kt`(`:85,140-144,271,280`), `dto/F4Dto.kt`(`:546`), 테스트 `controller/BattlePlanReadControllerTest.kt`(이름)

gateway-api: `dto/RepresentativeDto.kt`(`:15-28`), 테스트 `controller/RepresentativeControllerTest.kt`(전문)

board-api: `board/GatewayBoardService.kt`(`:106-174` grep), 테스트 `board/GatewayBoardMigrationIT.kt`(전문)

web/game: `app/game/battle-plan/page.tsx`, `components/game/BattlePlanPanel.tsx`, `BattleReplayPlayer.tsx`, `BattleReplayList.tsx`, `PartialReservedCommand.tsx`(`:36-48,66-84,178-198`), `GameChrome.tsx`(`:156` grep), `RetinueSlot.tsx`, `RetinuePanels.tsx`(`:98-106,155-163`), `components/admin/ServerStatusPanel.tsx`(`:55`), `GeneralLogPanel.tsx`(grep), `app/game/inherit/page.tsx`(grep), `app/game/battle-center/page.tsx`(`:362-374`), `lib/api.ts`·`lib/types.ts`·`types/game.ts`(autorun grep), 테스트 `__tests__/battle-plan-panel.test.tsx`·`battle-replay-player.test.tsx`·`reserved-command-seal-link.test.tsx`(it 이름)

`git`: `log --oneline origin/main..HEAD`, `diff --stat origin/main...HEAD`, `show --stat 5cdb3301 b338c6cf`, `diff origin/main...HEAD -- ProcessWarNG.kt WarBattleHooks.kt`, `diff --stat -- '**/HanMapCanvas*'`(빈 출력), 골든·`ProcessWarNGOrderTest` diff(빈 출력). `.env*`·`build/` 는 열지 않았다. 빌드·테스트는 실행하지 않았다.

---

# 역사 기록 — 1차 판정 (HEAD `a119a57b` 기준, 바이트 그대로)

# UI 리디자인 PR #651 교차 비평 (2차) — 코드·마이그레이션

- Date: 2026-09-06
- Target: PR #651 `work/opensamguk/ui-redesign-2026-09` → `main`, **HEAD `a119a57b`** (비평 중 브랜치가 두 번 움직였다: `04551374` → `25b8232f` → `a119a57b`. 아래 file:line 은 전부 `a119a57b` 기준이며, `25b8232f` 로 닫힌 항목은 §1-C 에 따로 적었다.)
- Base: `origin/main` (`git diff --stat origin/main...HEAD` = 512 files, +17580/−3549). `docs/design/**` 와 캡처 PNG 는 범위 밖.
- Plan: `docs/superpowers/plans/2026-09-06-ui-redesign-implementation-plan.md`
- Method: 워크트리에서 파일을 직접 열어 대조했다. gradle/vitest 는 돌리지 않았다(제약) — 실행 결과에 기대는 주장은 UNKNOWN 으로 남긴다. 워크트리에 커밋되지 않은 Phase 4X-B 작업(`git status`: `TruncateContract.kt`·`TurnDaemonCommand*.kt`·`BoardActions.kt`·`V32…Test.kt` 수정 + `logic/operation/**` 신규)이 있어 **커밋된 HEAD 만** 판정 대상으로 삼았다(§3 Q4).
- Verdict: **fix-required 1건** — 대표 장수 「해제」 가 API 검증에 막혀 UI 에서 불가능하다(§1 F1). should-fix 9건, 그중 S1(board-api 네이티브 쿼리의 Postgres null 바인딩)은 PLAUSIBLE 이지만 커뮤니티 목록 전체가 걸린 항목이라 **PG 적색 프로브 없이 머지하지 말 것**을 권고한다.

---

## 0. 범위 · 확인한 것 (통과)

| 축 | 확인 | 근거 |
|---|---|---|
| 인증 등록 | `/api/my-retinue`·`/api/generals/*/retinue` 가 authenticated 목록에 있다. 컨트롤러는 401(익명)·404(내 장수 없음)·403(타국/재야 대상)·200(본인·같은 국가, 둘 다 nationId≠0) — 적국 장수의 사병·군량 누출 없음. `findById` 는 process-world 스코프. | `GameApiSecurityConfig.kt:44`, `RetinueController.kt:35-51`, `GeneralReadRepository.kt:348`, `RetinueReadControllerTest.kt:66-99` (3) |
| gateway-api 보안 | `GET /notices` 만 permitAll(메서드 한정), `/admin/**` ADMIN, `/auth/account/representative` 는 `anyRequest().authenticated()`. 소유 검증은 `general.user_id = 계정 id` 한 축. | `SecurityConfig.kt:48,52-53`, `RepresentativeService.kt:37,43-44`, `OwnedGeneralReader.kt:21-29` |
| board-api 보안 | 신고 POST 는 authenticated, `/board/admin/reports` 는 GET permitAll 이지만 서비스가 비관리자 403. | `BoardSecurityConfig.kt:33-35`, `GatewayBoardService.kt:120,135` |
| XSS / LogText | `LogText` 는 세그먼트 배열 → span 렌더, innerHTML 없음. `style=color` 는 `#hex` 정규식 통과값만. 게임 8곳 + 게이트웨이 `ServerLog` 가 소비. | `LogText.tsx:22-34`, `logTokens.ts:27-28,37-38`, `logText.test.tsx` |
| HanMapCanvas | `web/shared/src/HanMapCanvas.tsx` 와 두 테스트 모두 diff 0. | `git diff --stat origin/main...HEAD -- '**/HanMapCanvas*'` = 빈 출력 |
| 초상 파일 · 브랜드 | 커밋된 래스터는 `reports/ui-redesign/**/*.png` 캡처뿐. 기본 초상은 자작 `portrait-default.svg`. 워드마크는 `Brand.tsx` 의 `/logo-wordmark.png` 하나. RTK14 는 CDN URL 계약만. | `git diff --name-status` 필터, `portraitResolver.ts:16,44-48`, `Brand.tsx:20` |
| 데몬 쓰기 규칙 | 가신·부곡은 `InMemoryTurnWorld.create/update/remove*` + 주인 장수는 `applyGeneralDirtyFree`+`recorder.diffGeneral` 짝. 월 정산도 같은 경로. `DatabaseHooks.toFlushPayload` → `JdbcFlushExecutor` 8g. 엔진에 JPA 쓰기 없음. | `RetainerHandler.kt:42-46`, `RetainerMonthlyService.kt:66-70`, `DatabaseHooks.kt:698-704`, `JdbcFlushExecutor.kt:264-273` |
| 세계 범위 규약 | V53 `board_post_read`·V55 `general_retainers`/`general_bugok`: PK `(world_id,id)`, `world_state` FK, 부모 `(world_id,id)` 복합 FK, 인덱스 world_id 선행. V32 인벤토리에 세 표 + 전역 표(V51 `gateway_notice`, V54 `gateway_board_report`) 등록. | `V53:10-21`, `V55:6-62`, `V32WorldScopeCompletionMigrationTest.kt:654-657,676-678` |
| flush 순서 · FK | 8g 는 5단계 general DELETE(CASCADE) 뒤. 표마다 DELETE→CREATE→UPDATE 라 「해제→같은 이름 서약」 UNIQUE 만족, 새 가신 INSERT 가 부곡 commander FK 앞. created 는 updated 에서 제외. `removeGeneral` 이 가신·부곡을 map+dirty/created/deleted 전부에서 즉시 가지치기 → `requireExactlyOneAffected` 가 CASCADE 로 사라진 행을 만나지 않는다. `RetainerFlushIT` 가 PG16 에서 순서·`SET NULL (col)`·CASCADE·meta 키를 실측. | `JdbcFlushExecutor.kt:149,264-273`, `DatabaseHooks.kt:598-602,698-704`, `InMemoryTurnWorld.kt` `pruneRetinueOf`·`removeGeneral`, `RetainerFlushIT.kt:82-135` |
| PG 문법 | `ON DELETE SET NULL (commander_retainer_id)` 는 PG15+ — 로컬·프로덕션·IT 전부 `postgres:16-alpine`. | `docker-compose.yml:25`, `docker-compose.production.yml:7`, `RetainerFlushIT`/`*IT.kt` 컨테이너 태그 |
| 기존 세계 바이트 동일 | `maxRetainerId/maxBugokId` 는 값이 있을 때만 meta 에 병합(행 0 세계 meta 불변). `RetainerMonthlyNoopGateTest` 가 행 0 동일·행 1 상이 적색 프로브. `board_post` INSERT 는 `kind='general'`, `vote_id=NULL` 기본. | `InMemoryTurnWorld.kt` `recordMaxRetainerId`, `DatabaseHooks.kt:653-657`, `JdbcFlushExecutor.kt:527-537`, `RetainerMonthlyNoopGateTest.kt:105-130`, `JdbcFlushExecutor.kt:1793-1795` |
| 202 ≠ 성공 | 가신 6 명령·`boardRead` 모두 `submitCommandAndAwaitResult` 로 RESOLVED 까지 기다린 뒤 분기·재조회. 서버 상태 패널은 「접수 ≠ 반영」 을 명시(엔드포인트가 requestId 없는 `publishImmediate` 라 폴링 불가 — 기존 계약). | `RetinuePanels.tsx:40-47`, `board/page.tsx:284-289`, `ServerStatusPanel.tsx:35,48`, `AdminWriteController.kt:48-50` |
| 게이팅 불변 | `gateAllows` 가 main 의 `MainControlBar` 판정과 문자 그대로 같고, `MainControlBar` 가 같은 함수를 import 한다. 20 버튼·14 잎 1회 배치, 사유 문자열, 로딩 중 사유 미날조 테스트. | `dept-menu-config.ts:53-66` vs `origin/main:MainControlBar.tsx:24-33`, `MainControlBar.tsx:12,96`, `dept-menu-config.test.ts` |
| DTO ↔ TS 계약 | `RetinueResponse`·`RetinueRulesDto` ↔ `types/game.ts` 필드 1:1. `BoardResponse` 확장(`kind/vote/readers/participants/chiefCount/myPermission`) 은 TS 에서 optional. gateway `board.ts` ↔ `GatewayBoardPostResponse` 신규 4필드 optional. | `RetinueDto.kt`, `game.ts:1329-1388`, `F4Dto.kt:640-735`, `board.ts:32-36` |
| 수치 날조 없음 | 도시 화면 `수비○`·`守` 는 원천 미배선이라 `-` 마스킹. 가신 상한·비용은 응답 `rules` 로만, 「잠정」 칩. 세력 현황은 `/api/rankings/kingdoms`. | `city/page.tsx:209-211,286-288`, `RetinuePanels.tsx:58-63,80`, `NationSummary.tsx:8,40-41` |
| 시크릿 | compose/.env.example 은 `INTERNAL_SERVICE_TOKEN` 키만 추가(빈 기본값). `.env*` 미열람. | `.env.example:52-53`, `docker-compose.yml:98-99,202-205` |
| 동결 테스트 | `WaterControlPersistenceIT` 는 cold boot 전에 최신 스키마까지 올리는 `migrateLatest()` 만 추가 — 로더가 V55 표를 읽어서 필요한 변경이지 기대값 약화가 아니다. | diff `WaterControlPersistenceIT.kt` |

---

## 1. fix-required

### F1. 대표 장수 「해제」 가 API 검증에 막힌다 (confirmed)

- UI 는 「없음」 을 고르면 `generalId: null` 을 보낸다: `RepresentativeSection.tsx:31` (`draft === '' ? null : Number(draft)`) → `representative.ts:39-44` (`body: JSON.stringify({ generalId })`) → Next 라우트가 null 을 그대로 전달 `app/api/account/representative/route.ts:36-41`.
- gateway-api 는 `@Valid @RequestBody SetRepresentativeRequest` (`RepresentativeController.kt:27-30`) 인데 필드가 `@field:NotNull val generalId: Int?` 다 (`RepresentativeDto.kt:24-28`, 주석은 「null 이면 대표 장수 해제」). Bean Validation 이 null 을 거부 → `MethodArgumentNotValidException` → 400 (`GlobalExceptionHandler.kt:91-92`). 서비스의 해제 분기(`RepresentativeService.kt:38-41`)에는 도달하지 못한다.
- 테스트가 잡지 못한 이유: `RepresentativeServiceTest.kt:41` 은 서비스를 직접 호출(검증 레이어 없음), `account-representative.test.tsx:9-14` 는 fetch 를 mock. 계획 §Phase 4 C-2(`:305`)의 「GET/POST /auth/account/representative」 는 해제 경로를 한 번도 실제 컨트롤러로 통과시키지 않았다.
- 고치는 법: `@field:NotNull` 제거(타입은 `Int?` 유지, 본문 부재와 명시 null 을 같은 「해제」 로) + MockMvc 로 `{"generalId":null}` → 200 · `current.generalId == null` 을 단언하는 테스트 1건.

### 1-C. 비평 중 닫힌 항목 (기록)

- **`boardRead` 결과 직렬화기 throw** — `04551374` 에서 confirmed: `BOARD_ACTION_TYPES = setOf("boardArticle","boardComment")` 에 `boardRead` 가 없어 `BoardHandler.handleRead` 가 돌려주는 `BoardActionResult("boardRead", …)` 가 `selectSerializer` 의 `else -> throw IllegalArgumentException("unknown result type=…")` 로 떨어졌고, 이 함수는 **encode 경로**(`concreteSerializer` → `TurnRunService.toCommandResultRows`)에서도 불리므로 기밀실을 여는 순간 그 배치의 결과 기록이 통째로 죽는다. `25b8232f` 가 집합에 등록하고 `BoardIntakeWireTest.kt:48-52` 왕복 회귀를 더했다. HEAD `a119a57b` 에서 `TurnDaemonCommandResult.kt:634` 확인. 닫힘.

---

## 2. should-fix

### S1. board-api 네이티브 `(:x IS NULL OR …)` 의 Postgres null 바인딩 — **PLAUSIBLE, PG 실측 요구**

- `GatewayBoardRepositories.kt:19-21,27-29` (`searchLatest`), `:49-50,57-58` (`searchPopular`): `:category`·`:author`·`:q` 를 `IS NULL` 과 비교식 양쪽에 쓴다. 기본 목록(`GET /board/posts`)은 셋 다 null 로 들어간다(`GatewayBoardService.kt:52-56`).
- Spring Boot 3.4.1(`gradle/libs.versions.toml:3`) = Hibernate 6.6. Hibernate 6 는 타입을 모르는 null 네이티브 파라미터를 PostgreSQL 에서 `bytea`/unspecified 로 보내 `operator does not exist: character varying = bytea` 또는 `could not determine data type of parameter $n` 이 나는 사례가 널리 보고돼 있다. 이 저장소에는 같은 패턴을 PG 에서 돌린 선례가 없다(`IS NULL OR` 네이티브 사용처는 이 파일뿐).
- 확인된 사실: board-api 테스트는 **H2 만** 쓴다 — `application-test.yml:3` `MODE=PostgreSQL`, `:9` `ddl-auto: create-drop`, `:13` `flyway.enabled: false`. 즉 (a) V54 마이그레이션 자체가 테스트에서 한 번도 실행되지 않고, (b) `ILIKE`/`NULLS LAST`/`||`/null 바인딩의 PG 동작이 검증되지 않았다. 계획 `:305` 와 커밋 `969371b5` 본문의 「native 쿼리 — H2/Postgres 공통」 은 H2 결과를 PG 증거로 쓴 주장이다(「검사가 버그를 공유한다」). 유일한 커뮤니티 캡처 `reports/ui-redesign/phase4c/13-community-desktop.png` 는 「게시글을 불러오는 중…」 + 옛 3탭(공지·자유·건의) 상태라 증거가 아니다.
- 요청: gateway-api 의 `NoticePostgresIT` 선례대로 Testcontainers PG16 IT 를 하나 두고 `GET /board/posts`(필터 0)·`?sort=popular`·`?q=` 를 통과시켜라(V54 도 Flyway 로 실행). 실패하면 `CAST(:q AS text)` 계열로 바꾸거나 JPQL/Specification 으로 내려라.

### S2. ADR-LITE-050 위반 잔존 + 계획의 허위 주장

- 계획 `:293` 「`dangerouslySetInnerHTML` 로그 렌더 0」 · ADR-050 「두 앱의 모든 로그 표시는 … `LogText` 로만」 — 남아 있는 로그 innerHTML: `web/game/components/admin/GeneralLogPanel.tsx:54` (관리 허브 장수 로그 4종, 주석 `:16` 은 이미 바뀐 world-log/history 를 선례로 인용), `web/game/app/game/inherit/page.tsx:635` (`log.text`). 둘 다 main 에 있던 코드가 옮겨지거나 남은 것이지만 ADR 이 「모든 로그」 라 못 박았다. `LogText` 로 바꾸고 계획 문장을 고쳐라.

### S3. 부곡 지휘관 사기 +6 이 재배정으로 무한 반복된다

- `RetainerHandler.kt:150-153`: `newlyAssigned = retainerId != null && b.commanderRetainerId != retainerId` → 해제(null)→재배정, 또는 부장 A→B→A 마다 +6(상한 100). 스펙 `§4 :131` 「새로 배정할 때만」 을 문자 그대로 구현했지만 결과는 공짜 사기 펌프다. `RetainerIntakeTest.kt:135-146` 은 같은 부장 재지정(무변화)만 본다. 부곡당 1회 플래그 또는 「정산 한 번 지나기 전엔 재부여 없음」 중 하나를 스펙에 적고 테스트로 고정해라(§3 Q2).

### S4. `RetinueSlot` 이 오류를 「휘하 없음」 으로 위장한다

- `RetinueSlot.tsx:16-28`: `api.myRetinue` 실패(`catch → setCounts(null)`)와 진짜 0건이 같은 「휘하 없음 / 서약하면 여기 나옵니다」 로 그려진다 — 「서버 정보 없음」 을 따로 둔 `dept-menu-config.ts:179` 원칙과 어긋난다. 또 0건일 때 disabled 버튼이라 서약 화면(`/game/my#retinue`)으로 가는 길이 없다. 오류 상태 분리 + 빈 상태는 링크로.

### S5. 사유 없는 `<select disabled>`

- `RetinuePanels.tsx:101,157` (`disabled={busy !== ''}`), `ServerStatusPanel.tsx:55` (`disabled={busy}`). ADR-049 (7) 「비활성은 점선 + 사유」 는 `Button` 타입에서만 강제된다(`Button.tsx:8-9`). `title`/`aria-describedby` 로 「처리 중」 을 붙여라. (`RepresentativeSection.tsx:51` 은 옵션 텍스트가 사유 역할을 해 제외.)

### S6. 가신·부곡 인테이크에 NPC 가드가 없다

- `RetainerHandler.kt:21` 은 「NPC 는 서약하지 않는다(인테이크만)」 라 적었지만 `preGate`(`:32-39`)는 `npcState` 를 보지 않는다. 인테이크 소유 가드는 principal 이 있을 때만 작동하고 F2 전환기엔 `?generalId=` 를 그대로 믿는다(`InstantActionController.kt:47-48,89-92`, `GameApiSecurityConfig.kt:52` anyRequest permitAll). 전환기 구멍은 기존 것이지만, 이 절편은 NPC 의 crew/rice 를 부곡으로 옮길 수 있게 하므로 엔진 쪽에서 `npcState >= 2 → deny` 한 줄과 테스트 1건을 두는 게 싸고 결정적이다.

### S7. V52 번호 공백

- `infra/src/main/resources/db/migration/` 에 V51 → V53 (V52 없음; `git log --all -- 'V52*'` 0건). Flyway 기본 `outOfOrder=false` 라 이 브랜치가 배포된 뒤 다른 브랜치가 V52 를 들고 오면 적용이 거부된다. 머지 전에 V53~V55 를 당기거나 V52 를 예약 파일로 못 박아라(§3 Q3).

### S8. 신고 목록 N+1

- `GatewayBoardService.kt:150-153` `reportResponse` 가 행마다 `postRepository.findById`/`commentRepository.findById` — `listReports` 는 size ≤ 100. 같은 파일의 `authorsOf`/`commentCountsOf` 처럼 한 번에 끌어와라.

### S9. V54 주석 오기

- `V54__gateway_board_extend.sql:2` 「분류 6종(공지·자유·건의·전략·공략·서버 이야기·창작·일지)」 — 라벨 8개, 값 6개. `전략·공략` / `창작·일지` 로 고쳐라.

---

## 3. 질문 · UNKNOWN

- **Q1 (S1)** `searchLatest(null, null, null, …)` 이 PG16 + Hibernate 6.6 에서 실제로 무엇을 반환하는가 — 이 워크트리에서는 실행하지 않았다. UNKNOWN. 적색 프로브(PG IT) 결과로 닫아라.
- **Q2 (S3)** 지휘관 사기 보너스의 의도는 「부곡당 1회」 인가 「배정 이벤트당 1회」 인가. 스펙 `:131` 만으로는 펌프를 막지 못한다.
- **Q3 (S7)** V52 를 비운 이유가 있는가(다른 브랜치 예약?). 없으면 번호를 당기는 게 안전하다.
- **Q4** 워크트리에 커밋되지 않은 Phase 4X-B 코드(`TurnDaemonCommand.kt` +49, `TurnDaemonCommandResult.kt` +14 `OperationActionResult`, `TruncateContract.kt`, `BoardActions.kt`, `logic/operation/**`, `reports/ui-redesign/phase4xa/`)가 있다. 이 PR 범위가 아니면 PR 브랜치에 섞이지 않게 해라 — 비평 중 HEAD 가 두 번 움직였다.
- **Q5** 계획이 적은 테스트 수(「board-api 전체 57 녹색」·「game vitest 667」·「gateway 240」)는 실행하지 않아 UNKNOWN. XML mtime 으로 확인해라(JDK 25 함정).
- **Q6** `BoardActions.addArticle(kind=vote)` 는 `voteId` 존재·국가 일치를 검사하지 않는다(`BoardActions.kt:56-57`). 읽기 쪽 `voteSummary` 가 `votePolls.findById` null 이면 `vote=null` 로 그리므로 화면은 안전하지만, 타국 설문 id 를 붙인 글이 저장될 수 있다. 의도인가.

---

## 4. 읽은 파일

마이그레이션: `infra/src/main/resources/db/migration/V51__gateway_notice.sql`, `V53__board_post_kind.sql`, `V54__gateway_board_extend.sql`, `V55__general_retainers_and_bugok.sql` (+ `V1`/`V32` 의 `board_post` PK 확인)

common/logic: `common/…/wire/TurnDaemonCommand.kt`, `TurnDaemonCommandResult.kt`(HEAD 와 `04551374` 두 판), `common/src/test/…/RetainerIntakeWireTest.kt`, `BoardIntakeWireTest.kt`(HEAD), `logic/…/retainer/RetainerRules.kt`, `logic/…/actions/intake/BoardActions.kt`, `logic/…/memory/HotColdCatalog.kt`, `logic/src/test/…/RetainerRulesTest.kt`

infra: `infra/…/persistence/JdbcFlushExecutor.kt`(diff), `RetainerRowMapper.kt`, `infra/…/read/OwnedGeneralReader.kt`, `GatewayNoticeRepository.kt`, `infra/…/entity/GatewayNoticeEntity.kt`, `UserEntity.kt`(diff), `infra/src/test/…/RetainerFlushIT.kt`, `BoardFlushIT.kt`(diff), `V32WorldScopeCompletionMigrationTest.kt`(diff)

game-engine: `boot/WorldSnapshotLoader.kt`(diff), `config/DaemonLoopConfig.kt`(diff), `flush/DatabaseHooks.kt`(diff), `flush/TruncateContract.kt`(diff), `intake/RetainerHandler.kt`, `intake/BoardHandler.kt`(diff), `retainer/RetainerMonthlyService.kt`, `run/MonthlyPostUpdateHook.kt`(diff), `run/TurnDaemonCommandDispatcher.kt`(diff), `run/TurnRunService.kt`(diff + `:590-660`), `turn/ChangeRecorder.kt`(diff), `turn/DirtyState.kt`(diff), `turn/InMemoryTurnWorld.kt`(diff), `turn/TurnWorldModel.kt`(diff), 테스트 `RetainerIntakeTest.kt`, `RetainerMonthlyNoopGateTest.kt`, `BoardIntakeSliceCTest.kt`(diff), `WaterControlPersistenceIT.kt`(diff)

game-api: `controller/RetinueController.kt`, `controller/BoardController.kt`(diff), `controller/AdminWriteController.kt`(`:38-51`), `controller/InstantActionController.kt`(가드 부분), `dto/RetinueDto.kt`, `dto/F4Dto.kt`(diff), `read/RetainerReadRepository.kt`, `read/BoardReadRepository.kt`(diff), `read/GeneralReadRepository.kt`(스코프 부분), `owner/GeneralResolver.kt`(필드), `reserve/CommandWireMapper.kt`(diff), `security/GameApiSecurityConfig.kt`, 테스트 `RetinueReadControllerTest.kt`, `F4ReadControllersTest.kt`(diff), `CommandWireMapperTest.kt`(grep)

gateway-api: `controller/NoticeController.kt`, `controller/RepresentativeController.kt`, `service/NoticeService.kt`, `service/RepresentativeService.kt`, `dto/NoticeDto.kt`, `dto/RepresentativeDto.kt`, `security/SecurityConfig.kt`, `config/InfraBeanConfig.kt`(diff), `web/GlobalExceptionHandler.kt`, 테스트 `NoticeControllerTest.kt`, `NoticePostgresIT.kt`, `NoticeServiceTest.kt`(이름), `RepresentativeServiceTest.kt`

board-api: `board/GatewayBoardContracts.kt`, `GatewayBoardController.kt`, `GatewayBoardEntities.kt`, `GatewayBoardRepositories.kt`, `GatewayBoardService.kt`(전부 diff), `security/BoardSecurityConfig.kt`, `build.gradle.kts`(테스트 의존), `src/test/resources/application-test.yml`, 테스트 `GatewayBoardFeedAndReportTest.kt`

web/shared: `LogText.tsx`, `logTokens.ts`, `Portrait.tsx`, `portraitResolver.ts`, `Button.tsx`, `Brand.tsx`, `HanMapCanvas.tsx`(diff 0 확인)

web/game: `lib/dept-menu-config.ts`, `lib/control-bar-config.ts`, `lib/api.ts`(diff), `lib/commandSubmit.ts`, `lib/auth-context.tsx`(diff), `types/game.ts`(diff), `hooks/useShellFrontInfo.ts`, `components/game/MainControlBar.tsx`(+ main 판), `components/game/RetinuePanels.tsx`, `components/game/RetinueSlot.tsx`, `components/game/MapViewer.tsx`(`:351-352`), `components/admin/ServerStatusPanel.tsx`, `components/admin/GeneralLogPanel.tsx`, `components/DeptNav.tsx`(권한 부분), `app/game/board/page.tsx`(`:260-310`, kind 게이팅), `app/game/city/page.tsx`(`:203-291`), `app/game/my/page.tsx`(마운트), `app/game/inherit/page.tsx`(`:635`), 테스트 `dept-menu-config.test.ts`, `retinue-panels.test.tsx`(이름), `board-council.test.tsx`(`:85-110`)

web/gateway: `lib/board.ts`(diff), `lib/representative.ts`, `lib/lobbyEntry.ts`, `lib/auth-context.tsx`(diff), `app/api/notices/route.ts`, `app/api/server-nations/[id]/route.ts`, `app/api/account/representative/route.ts`, `app/api/board/[...path]/route.ts`(diff), `components/NationSummary.tsx`, `components/account/RepresentativeSection.tsx`, `components/admin/AdminOverview.tsx`(원천), 테스트 `account-representative.test.tsx`

문서·설정: `CLAUDE.md`, `.ai/decisions.md`(diff, ADR-049/050), `docs/superpowers/plans/2026-09-06-ui-redesign-implementation-plan.md`(게이트·테스트 주장), `docs/superpowers/specs/2026-09-06-retinue-buqu-vertical-slice.md`(사기·정산 절), `docs/superpowers/reviews/2026-09-06-retinue-spec-critique.md`(형식), `docs/superpowers/reviews/2026-09-06-ui-phase-6-closeout.md`, `docker-compose.yml`(diff), `docker-compose.production.yml`(PG 태그), `.env.example`(diff), `gradle/libs.versions.toml`, `reports/ui-redesign/phase4c/13-community-desktop.png`

`.env*` 는 열지 않았다. 빌드·테스트는 실행하지 않았다.
