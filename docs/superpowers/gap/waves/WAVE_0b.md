# WAVE 0b — founding sibling cascade drain (잔여)

## 목표
라이브 데몬에서 `che_건국`/`cr_건국`/`che_무작위건국`이 실제로 국가를 세우도록 — `ReservedTurnHandler`가 이 세 형제 명령에 founding preload args(`sameMonthOrBefore` + 무작위 `candidateCityIds`/`candidateGenerals`)를 주입하고, 무작위건국 follower 이동까지 드레인을 완성해 **silent data-loss(건국 no-op)** 를 닫는다.

---

## 출처 (인벤토리 + GAP_AUDIT 섹션)
- 인벤토리: `docs/superpowers/gap/FOUNDING_SEAM_FIX.md` (§1 F2 `che_건국`/`cr_건국`/`che_무작위건국` 부분, §3 test 3, §3 test 5, §4 sibling 표, §4 implement order step (4)).
- GAP_AUDIT: `docs/superpowers/GAP_AUDIT.md` WAVE 0 — **0d** (sibling cascade drain) + **0e**(`FoundingHandlerSeamTest` 건국 UPDATE 케이스 + Docker-gated flush IT) 의 잔여분.
- PHP grand truth: `legacy/devsam-core/hwe/sammo/Command/General/che_건국.php`, `cr_건국.php`, `che_무작위건국.php`.

---

## 완료/제외 (이미 닫힌 부분 — 코드로 검증)

PR #26(머지 커밋 `ff2f12d`, 구현 커밋 `a95efdd` "fix(engine): WAVE0 founding 데몬 seam")로 **0a–0c + 0e의 거병 케이스**가 이미 깔렸다. 정밀 식별:

- **0a (F1) world API — 완료.** `InMemoryTurnWorld.kt`에 `createNation`(line 197), `createDiplomacy`(line 209), `createNationTurn`(line 222), `allocateNationId`(line 240 = `maxNationId+1`), `createdNationTurns` ledger(line 52), `consumeDirtyState`의 `nationTurnDirty` 드레인(line 357)+clear(line 335), `removeNation` 동틱 prune(line 282) 모두 존재.
- **0b (F2) 거병 crash fix — 완료.** `ReservedTurnHandler.buildFoundingArgs`(line 570-581)가 `GEOBYEONG` 분기에서 `newNationId`/`existingNationIds`/`existingNationNames`/`scenario` 주입. `CheGeobyeong.kt`의 `error(...)` 크래시 해소.
- **0c (F2) created-set drain + scenario thread — 완료.** `ReservedTurnHandler.handle` line 274-306에 nation-UPDATE diff(line 282-286), `cascadeGenerals`(line 290-294), `cascadeCities`(line 296-300), created-set FK 순서 드레인(line 304-306, nation→diplomacy→nation_turn)이 **이미 laid**. `DaemonLoopConfig.kt` line 151-152에 scenario 주입(`state.meta["scenario"]` ?? `SCENARIO_CODE` env) 완료.
- **0e 거병 게이트 — 완료.** `FoundingHandlerSeamTest.kt` 3 테스트(거병 found+drain / flush payload / secretlimit scenario) green. `GeongukTest.kt`(12 @Test) + `GeobyeongTest.kt`(13 @Test)는 **로직 레벨 draw-for-draw 골든-green** — 세 resolver 자체는 충실 포트 완료(resolver는 손대지 않는다).

**근거(핵심):** `ReservedTurnHandler.buildFoundingArgs`의 `else -> args`(line 580)가 건국/cr/무작위를 **무가공 통과**시키고, doc 주석 line 564-568이 명시 — "che_건국/cr_건국/che_무작위건국: `nationName`/`nationType`/`colorType`는 reserved arg jsonb로 이미 도착; runtime engine-scanned preload(`sameMonthOrBefore` 동월 가드, 무작위 `candidateCityIds`)는 **WAVE 0b** follow-up. 그때까지 args를 그대로 통과 → resolver early-return(무크래시·무건국). drain 블록은 이미 laid되어 WAVE 0b는 preload만 추가하면 됨." PR #26 커밋 메시지의 "WAVE0b 백로그: 건국/cr_건국/무작위건국 preload(sameMonthOrBefore/candidateCityIds)"가 같은 사실을 재확인.

**결론:** drain 블록·world API·scenario thread·resolver는 전부 완료. W0b 잔여 = **(1) 건국 형제 3종의 preload args 주입 + 무작위건국 `candidateCityIds`/`candidateGenerals`를 `GeneralActionResolveContext` ctor에 배선; (2) `FoundingHandlerSeamTest`의 건국 UPDATE 케이스 + Docker-gated flush IT.**

---

## 패러티 핵심 (PHP에서 확정한 동작)

### sameMonthOrBefore (동월 가드) — 세 형제 공통
`che_건국.php:148-150`:
```
$initYearMonth = Util::joinYearMonth($env['init_year'], $env['init_month']);
$yearMonth     = Util::joinYearMonth($env['year'], $env['month']);
if($yearMonth <= $initYearMonth){ … 다음 턴부터 건국할 수 있습니다 … return false; }
```
→ `sameMonthOrBefore = (joinYearMonth(year, month) <= joinYearMonth(init_year, init_month))`. resolver는 `context.args["sameMonthOrBefore"] == true`만 읽는다(`CheGeonguk.kt:106`). `init_year`/`init_month` = 게임 창건 연·월(`game_env`). `Util::joinYearMonth(y,m)` = `y*12 + (m-1)` (logic의 `LastTurn`/`joinYearMonth` 헬퍼와 동일 — 포트 확인 필요, 동일 식 사용). **`init_year`/`init_month`는 현재 `world_state` 시드 meta에 없다**(`ScenarioImporter.insertWorldState` line 126-130은 `hiddenSeed`/`startYear`/`startTime`만 넣음) → 오픈 질문 참조: `startYear` + month 1 을 init 대용으로 쓸지(시나리오 창건월), 아니면 시드 meta에 `init_year`/`init_month`를 추가할지 결정 필요. 1010 라이브에서 year=190·startYear=184라면 `190*12+m > 184*12+0` 이 항상 참 → `sameMonthOrBefore=false` → 가드 통과(건국 진행). 동월/창건월 이전 케이스는 신규 게임 1턴째에만 발생.

### candidateCityIds (무작위건국) — id-ascending
`che_무작위건국.php:152`: `SELECT city FROM city WHERE level>=5 AND level<=6 AND nation=0` (DB 기본 정렬 = city id ascending). 빈 결과 → resolver early-return("건국할 수 있는 도시가 없습니다", alternative `che_해산`). `cityID = rng->choice($cities)`(line 158)는 **action-stream RNG draw** — `candidateCityIds`의 정렬이 draw 결과를 좌우하므로 **반드시 city id ascending**. 핸들러는 `world.listCities().filter { it.nationId == 0 && it.level in 5..6 }.map { it.id }.sorted()`로 구성.

### candidateGenerals (무작위건국 follower 이동)
`che_무작위건국.php:165-167`: `UPDATE general SET city=cityID WHERE nation = me` — 선택 도시로 같은 국가 장수 전원 이동. resolver(`CheMujakwiGeonguk.kt:91`)는 `context.candidateGenerals`를 순회해 각 follower의 cityId를 바꿔 `draft.cascadeGenerals`에 append. actor 자신은 `d.general`로 별도 처리(line 90)되므로 **candidateGenerals에서 actor 제외**. 핸들러는 `world.listGenerals().filter { it.nationId == actorNationId && it.id != actorId }`를 `PerTurnOverlay.toLogicGeneral`로 매핑해 `candidateGenerals` ctor param에 배선. 이미 laid된 `cascadeGenerals` 드레인 블록(line 290-294)이 이 이동을 flush로 운반한다 — **추가 드레인 코드 불필요**.

### nationName/nationType/colorType (세 형제 공통)
reserved arg jsonb로 도착 → `decodeArgs`(line 163)가 이미 `args`로 파싱. `buildFoundingArgs`는 이 세 키를 **건드리지 않고** `sameMonthOrBefore`만 추가(LinkedHashMap put). resolver `parseArgs`(`CheGeonguk.kt:72`)가 정규화. **단, 핸들러는 founding일 때 `generalName = general.name`을 ctor에 넘겨야 한다**(line 250, 이미 `isFounding` 분기로 처리됨 — 건국 형제도 `FOUNDING_COMMANDS` 집합(line 645)에 포함되어 있어 `generalName` 자동 적용. 확인 완료, 추가 작업 불필요).

---

## foundation-first 빌드 순서

이 웨이브는 **단일 파일(`ReservedTurnHandler.kt`)의 한 함수(`buildFoundingArgs`) + 한 ctor 호출(`GeneralActionResolveContext`) 확장**이 핵심이라 공유 확장점이 거의 없다. Tier-0 = preload 계산 헬퍼(가장 작은 공유 아티팩트), Tier-1 = 그 헬퍼를 consume하는 핸들러 배선, Tier-2 = 게이트.

- **Tier-0 (foundation):** `buildFoundingArgs`에 `sameMonthOrBefore` 계산 + 건국 형제 분기 추가; 무작위 전용 `candidateCityIds`/`candidateGenerals` scan 헬퍼(`ReservedTurnHandler` private). `init_year`/`init_month` 소스 결정(오픈 질문). — **T1이 consume**.
- **Tier-1 (consumer):** `handle()`의 `GeneralActionResolveContext` ctor(line 247-251)에 `candidateCityIds`/`candidateGenerals`를 무작위건국일 때만 전달. (drain 블록은 이미 laid — 변경 없음.)
- **Tier-2 (gate):** `FoundingHandlerSeamTest`에 건국 UPDATE 케이스 + 무작위건국 케이스; `JdbcFlushExecutorIT`(또는 신규 `FoundingFlushIT`) Docker-gated flush IT.

---

## 태스크 분해 표

| id | 변경 파일(disjoint) | 무엇을 (PHP 출처 file:line) | 게이트 (테스트 클래스 + 골든 Y/N) | 의존성 |
|---|---|---|---|---|
| **T0a** | `app/game-engine/.../turn/ReservedTurnHandler.kt` (`buildFoundingArgs` 분기 + `sameMonthOrBefore` 헬퍼 추가; 무작위 `candidateCityIds`/`candidateGenerals` private scan 헬퍼) | `che_건국.php:148-150`(동월 가드), `che_무작위건국.php:152`(candidate city SELECT, id-asc), `che_무작위건국.php:165-167`(follower UPDATE WHERE nation=me, actor 제외) | `FoundingHandlerSeamTest` (N — 로직 골든은 `GeongukTest`가 이미 보유) | — |
| **T0b** | `app/game-engine/.../config/DaemonLoopConfig.kt` + `infra/.../seed/ScenarioImporter.kt` (`init_year`/`init_month`를 `world_state` meta에 추가 — 오픈 질문 (1) 결정 시) | `che_건국.php:148`(`env['init_year']`/`init_month` 소스), `ScenarioImporter.insertWorldState:126-130` | `ScenarioBootIT` (N) | — (T1과 별 family) |
| **T1** | `app/game-engine/.../turn/ReservedTurnHandler.kt` (`handle()`의 `GeneralActionResolveContext` ctor line 247-251에 `candidateCityIds`/`candidateGenerals` 조건 전달) | `CheMujakwiGeonguk.kt:79,86,91` (`candidateCityIds`/`candidateGenerals` 소비), `GeneralActionResolveContext.kt:65,91` (named params) | `FoundingHandlerSeamTest` (N) | T0a |
| **T2** | `app/game-engine/src/test/.../turn/FoundingHandlerSeamTest.kt` (테스트 추가: ① `che_건국` UPDATE 0→1 + city claim 생존 + drain, ② `cr_건국` no-unifier 동작, ③ `che_무작위건국` candidate scan + follower 이동 drain + 무도시 alternative, ④ 동월 가드 sameMonthOrBefore early-return) | FOUNDING_SEAM_FIX.md §3 test 3; `che_건국.php`/`cr_건국.php`/`che_무작위건국.php` run() | `FoundingHandlerSeamTest` (N) | T1 |
| **T3** | `infra/src/test/.../persistence/JdbcFlushExecutorIT.kt` 확장 **또는** 신규 `app/game-engine/src/test/.../turn/FoundingFlushIT.kt` (건국 UPDATE nation row + city claim + 무작위 follower city UPDATE가 Postgres에 byte-level 착지; Docker 미가용 시 skip) | FOUNDING_SEAM_FIX.md §3 test 5; `JdbcFlushExecutor.nationCreateMany:422`/`step-7 nation UPDATE` | `JdbcFlushExecutorIT` 또는 `FoundingFlushIT` (N — Docker-gated) | T1 |

**핵심 주의:** resolver(`CheGeonguk`/`CrGeonguk`/`CheMujakwiGeonguk`)·world API·drain 블록은 손대지 않는다(이미 완료). T0a/T1은 **`ReservedTurnHandler.kt` 한 파일을 co-widen**하므로 같은 family에서 순차(T0a→T1) — 두 worktree로 쪼개면 머지 충돌.

---

## 병렬화 그룹 (disjoint worktree family)

- **Family A (핸들러 배선):** T0a → T1 → T2 → T3. **순차** — T0a/T1이 `ReservedTurnHandler.kt`를 co-widen하고 T2/T3가 그 동작을 게이트하므로 creator→consumer 사슬. 단일 family·단일 worktree.
- **Family B (init_year 시드, 선택):** T0b. `DaemonLoopConfig.kt`+`ScenarioImporter.kt`는 `ReservedTurnHandler.kt`와 **disjoint** → Family A와 **병렬 가능**. 단 T0a가 `sameMonthOrBefore` 계산에 `init_year`/`init_month`를 **소비**하므로, 오픈 질문 (1)에서 "시드 meta에 추가" 결론이면 T0b가 **creator**가 되어 T0a보다 선행해야 한다(creator→consumer 순차로 격하). "startYear+1월 대용" 결론이면 T0b 불필요 → Family A만 단독.

**병렬 family 수 = 1** (init_year를 startYear 대용으로 처리하면 Family A 단독; 시드 meta 추가 결론이면 T0b가 T0a의 creator라 사슬에 합류해 여전히 1 family). 같은 파일 co-widen 금지 규칙상 핸들러 작업은 분할 불가.

---

## 패러티 주의점

- **RNG draw-for-draw:** `sameMonthOrBefore`/`candidateGenerals`는 순수 DB·env 쿼리 대용 — action rng를 **소비하지 않는다**. 단 `che_무작위건국`의 `rng.choice(candidateCityIds)`(`CheMujakwiGeonguk.kt:86`)는 **유일한 action-stream draw** → `candidateCityIds`는 PHP `SELECT … nation=0 level 5,6`의 DB 기본 정렬과 동일하게 **city id ascending** 이어야 draw가 동기화된다. `.sorted()` 누락/역순 = 자본 desync.
- **Rounding:** 해당 없음(exp/ded +1000은 resolver가 pipeline fold로 이미 처리, 골든-green).
- **로그 byte-parity:** 건국 성공 로그(`<D><b>{name}</b></>{을} 건국하였습니다.` + global `<Y>{actor}</>{이} <G><b>{city}</b></>에 국가를 건설하였습니다.`)와 alternative-block 로그(`다음 턴부터 건국할 수 있습니다.` / `건국할 수 있는 도시가 없습니다.`)는 resolver가 이미 byte-match. 핸들러는 `resolveCtx.logs()`/`globalActionLogs()` 드레인(line 309-315)으로 운반 — **추가 로그 코드 불필요**. plainLogs(레벨 변동 side-bucket) 라우팅은 WAVE 4 범위(out-of-scope).
- **flush-delta:** nation UPDATE는 `recorder.diffNation`+`applyNationDirtyFree`(line 284-285, 이미 laid), city claim은 `diffCity`+`applyCityDirtyFree`(line 256/260), follower 이동은 `cascadeGenerals` 드레인(line 290-294). created-set은 **거병 전용**(건국 형제는 INSERT 없음, nation은 UPDATE) → 건국 형제는 `createdNations` empty가 정상. ChangeRecorder 단일 dirty 소스 유지 — one-daemon-write-rule 비위반(JPA 미사용).
- **insertion-order:** `buildFoundingArgs`는 `LinkedHashMap(args).apply { put(...) }` 패턴(line 573)으로 reserved arg key 순서 보존; aux(`can_국기변경`/`can_무작위수도이전`)는 resolver의 `withFoundingNationAux`가 set 순서 보존(이미 완료).

---

## 오픈 질문

1. **`init_year`/`init_month` 소스(T0b 존부를 결정).** 현재 `world_state` 시드 meta(`ScenarioImporter.insertWorldState:126-130`)에 없음. 선택지 (a) `startYear`+창건월(시나리오 1턴=`current_month` 1)을 init 대용으로 핸들러에서 계산(추가 시드 불필요, Family B 제거) vs (b) 시드 meta에 `init_year`/`init_month` 키 추가(T0b가 T0a의 creator). 1010 라이브(year 190 > startYear 184)에서는 어느 쪽이든 `sameMonthOrBefore=false`라 결과 동일하나, **신규 게임 1턴째 동월 건국 차단**의 패러티는 (b)라야 정확. 권장: (a)로 시작(prod 동작 동일) + (b)를 백로그 등록.
2. **`Util::joinYearMonth(y,m)` 포트 식 확정.** logic에 `joinYearMonth` 헬퍼가 이미 있는지(LastTurn 인접) 확인 후 재사용; 없으면 `y*12 + (m-1)`로 핸들러 private 헬퍼 추가. resolver는 boolean만 받으므로 식 위치는 핸들러.
3. **`relYear` 제약 누락(선존재, W0b 범위 밖이나 founding 인접).** `ReservedTurnHandler.handle`이 `ConstraintContext`(line 189)에 `args`를 넘기지 않아 `beOpeningPart`의 `relYear`가 항상 0(default)으로 평가됨 → `relYear+1 < openingPartYear` 가드가 1010 초반엔 우연히 통과하나 충실하지 않음. founding 형제 4종 모두 `BeOpeningPart(relYear+1)`을 min/full에 둠 → 정확성 위해 `ctx.args = mapOf("relYear" to year-startYear)` 배선 필요. **별도 백로그(WAVE 1 daemon-seam correctness)로 분리 권장** — W0b는 resolve-path preload만.
4. **flush IT 위치.** 기존 `JdbcFlushExecutorIT`(infra) 확장 vs 신규 `FoundingFlushIT`(engine). 건국은 UPDATE-path라 infra의 row-mapper IT로 충분할 수 있음 — Docker-gated 스킵 규약(`api.version=1.44`, Ryuk disabled) 준수만 확인.
