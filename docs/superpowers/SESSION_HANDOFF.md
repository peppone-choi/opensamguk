# SESSION HANDOFF — 2026-06-05 (PHP 패러티 갭 감사 + prod 진단)

컨텍스트 클리어 전 인수인계. **다음 세션은 이 문서 + `GAP_AUDIT.md` + `gap/FOUNDING_SEAM_FIX.md`부터 읽어라.**

---

## 🔴🔴 PROD 복구 진행 (2026-06-05 cont. — monthly 4-layer seam, layer 4 미해결)

**현재 prod(`sam.peppone.dev`) game-engine clock 여전히 동결.** main=`79c0e69`. 엔진 컨테이너는 "Up"이나 turn-daemon-loop가 매 틱 실패→backoff. **거병 크래시(WAVE0)가 가리고 있던 monthly 틱 배선 결함이 layer-by-layer로 노출 중 — 엔진이 prod서 clean 월경계 틱을 한 번도 못 돌렸음.**

### 노출·수정된 레이어 (전부 main 머지+배포 완료)
1. **거병 created-set seam** (WAVE0) — PR #26. `ReservedTurnHandler` founding preload+드레인. (이 문서 §3 = 완료.)
2. **monthlyRngFactory null** — PR #28. `MonthlyPipeline`이 `@Bean @Lazy`라 Spring CGLIB 프록시(Objenesis shell, 필드 null) + `runMonth` final → 인터셉트 불가 → null-필드 shell서 실행. Fix=`open fun runMonth` + `MonthlyPipelineLazyProxyTest`.
3. **ChangeRecorder 빈 부재** — PR #29 (**Option B, 근본**). per-run 상태(handler.recorder)를 cross-config `@Bean`으로 노출한 게 2·3 공통 뿌리. Fix=`DaemonLoopConfig.turnRunService`서 `MonthlyPipeline`을 `handler.recorder`로 **인라인 생성**(nationProcessor 패턴), `EngineEventConfig`의 monthlyPipeline/monthlyPostUpdateHook `@Bean` 제거. → wiring 에러(NPE/UnsatisfiedDependency) 전부 소멸 확인됨.

### ⛔ Layer 4 (미해결 — 현재 prod 동결 원인)
```
java.lang.IllegalArgumentException: UpdateCitySupply requires UpdateCitySupplyContext
  at UpdateCitySupplyAction.run (logic/.../world/UpdateCitySupply.kt:254)
```
- `EventDispatcher.run(target, envSupplier)`가 generic `Context(env: Map)` 생성(EventDispatcher.kt:39,47) → 9개 world-event leaf가 요구하는 **타입드 컨텍스트**(`UpdateCitySupplyContext: EventActionContext` 등)를 미구현 → leaf의 `ctx as? XContext ?: throw`서 실패.
- 즉 **데몬 MONTH-이벤트 실행 경로(TurnRunService.runTick의 dispatcher 람다 → EventDispatcher → world leaf)가 prod서 한 번도 end-to-end 안 됨.** TurnRunService:152-160이 `mutableMapOf("year","month","currentEventID")`만 공급. P3는 unit-test(`UpdateCitySupplyLossTest` 등 — 각자 타입드 ctx 직접 생성)로만 닫혀 풀-와이어드 데몬 틱 미커버.
- UpdateCitySupply는 첫 leaf일 뿐 — ProcessIncome/UpdateNationLevel/RaiseDisaster 등 나머지도 같은 타입드-ctx 갭 가능성 高.

### 다음 작업 계획 (Phase 4.5 — blind 배포-per-layer 중단)
1. **데몬 boot+tick IT 신규** (이 전체 outage를 통과시킨 갭): `GameEngineApplicationTests`는 `opensamguk.daemon.enabled=false`라 `@Lazy turnRunService` 영원히 미해석 → 틱 미실행. Testcontainers PG + Redis mock(`@MockBean RedisCommandStream/RealtimePublisher`) + 시드 월드 + 월경계 `runTick` → **남은 monthly 체인 일괄 노출**.
2. **world-event-leaf 타입드 컨텍스트 배선**: 통과하는 로직 테스트가 `UpdateCitySupplyContext`를 어떻게 만드는지(logic/test/world/*, `EventDispatcherTest`, `WorldActions`/`EventActionFactory`) 보고, 데몬 dispatcher(또는 MonthlyPostUpdateHook 경로)가 world-backed 타입드 ctx를 공급하도록 미러. 9 leaf 전부.
3. 로컬 IT green → **한 번만** 배포(자동).

### 표준 지시 (이 세션)
- **머지+배포 자동, 동의 불요**(메모리 `feedback_auto_merge_deploy`). CI green 선결, gate red면 금지, 배포 후 검증(clock 전진+에러 소멸+nginx 502) 필수.
- 컨텍스트 50% 초과 시 클리어하고 이 문서 기준으로 이어가기(클리어 무손실 위해 이 섹션 최신 유지).
- prod 복구 후 다음: **맵 프리뷰 "준비중" 제거**(`MapViewer.tsx:252` placeholder — `/api/map/preview` empty/실패 시 렌더 → 시드 맵 실제 렌더 배선) → **레거시 갭 전수 닫기**(`GAP_AUDIT.md` WAVE 1-9).
- 미래 마일스톤 기록됨: `MILESTONES.md` M-config(post-parity 상수 외부화), PR #27(머지 보류).

---

---

## 0. 🔴 가장 급한 것 — prod 다운

**sam.peppone.dev (EC2 3.37.232.176) game-engine는 현재 STOP 상태(내가 정지시킴). 턴 0진행.**

- SSH: `ssh -i ~/.ssh/id_ed25519 ubuntu@3.37.232.176` (opens.pem 아님, id_ed25519가 인증키).
- box 디렉토리: `~/opensamguk`, compose=`docker-compose.production.yml`, **컨테이너 `opensamguk-db`** (Postgres, user/db=`samguk`, `docker exec -i opensamguk-db psql -U samguk -d samguk`).
- **크래시 원인**: AI훅이 중립 NPC에 `che_거병`을 매 틱 동적 선택 → `ReservedTurnHandler`가 `newNationId` preload + created-set drain 미배선 → `CheGeobyeong.kt:71` IllegalStateException 매 틱. **DB로 복구 불가 — 코드 fix(WAVE 0) 배포 후에만 엔진 재기동 가능.**
- 엔진 재기동 명령(주의 — fix 배포 전엔 다시 크래시): `docker start opensamguk-game-engine`.

### prod 추가 이슈 (검증으로 발견)
- **어드민 0명**: `users` 테이블 비어있음. AdminSeeder는 `ADMIN_USERNAME`+`ADMIN_PASSWORD` 둘 다 있어야 시드 → box .env에 없어 스킵. 로그인 불가. (의도된 계정=peppone, 미시드.) box compose gateway-api에 ADMIN_* 미전달 → AdminSeeder 경로 쓰려면 compose+env 추가, 또는 BCrypt 직접 INSERT.
- **설정 드리프트**: box compose ≠ repo compose. box는 멀티서버 변형(service `db`, `OPENSAMGUK_DB_NAME/USER=samguk`), repo는 `postgres`/`sammo`. 배포가 compose 동기화 안 함.
- **시드 옛 24도시**: prod world는 2일 전 24도시 시드(general 678/city 24/nation 2). 신규 94도시 풀맵 시드는 `world_state` 비었을 때만(멱등) → prod 미반영. 사용자 **94도시 재시드 승인**(파괴적, DROP SCHEMA 또는 world_state 비우고 엔진 재기동). 단 box 멀티서버/공유 인프라 가능성 → 재시드 전 영향범위 재확인 권장.
- nginx 502는 **force-recreate로 해소됨**(stale upstream DNS — 앱 재기동 시 IP 변경, nginx 미재생성이 원인. 앞으로도 앱 재기동 후 `docker compose up -d --force-recreate --no-deps nginx` 필요).

---

## 1. 이번 세션 완료분

- **맵 PR 머지+배포**: #19(f-map-fullseed) + #21→재생성 #25(f-map-icons) **main 머지 + EC2 배포 성공**. main tip=`4a5c862`.
  - 근본 fix: #19이 시드를 94도시로 확장했으나 `ScenarioBootIT.kt`가 24 하드코딩 → jvm CI fail. `24→94` 수정(line 84/90)으로 green.
  - 부수: 로컬 브랜치 정리됨 (`f4-c3-chief`=9d20c6d 복구, mapfix 워크트리 `f-map-icons`=origin 동기). #21은 base 삭제로 자동 close → #25로 재생성됨.
- **PHP 4면 전수 갭 감사 완료** → `docs/superpowers/GAP_AUDIT.md` + `gap/` 7개 문서 + `PARITY_LEDGER.md`(명령 93종 원장).

---

## 2. 갭 감사 결과 (마스터 = `docs/superpowers/GAP_AUDIT.md`)

차원별 (partial / missing):
| 차원 | partial | missing |
|---|---|---|
| API 엔드포인트 | 37 | **54** |
| 로직 시스템 | 7 | 3 |
| FE 구조 | 27 | 7 |
| FE 출력(read) | 12 | **153 필드** |
| FE 출력(action) | 8 | 0 |
| read-DTO | 11 | 1 |
| founding seam(prod) | 1 | 4 |
| 명령 포팅(PARITY_LEDGER) | 20 | 31 |

**헤드라인**: 로직 코어는 게이트 닫힘(~2195 테스트)이나 **prod 다운**(founding seam). 그 아래 golden-green이지만 prod-broken 데몬 seam 4개(계승·외교만료·checkStatistic·건국캐스케이드), 무음 no-op 인테이크 3개(버튼이 거짓 성공), read 표면 ~30% 필드패러티(스켈레톤 DTO, 권한티어/게이지바 없음), mutation 표면 대부분 read-only(chief-center 100% read-only, ~46명령 미도달).

### 클로저 10웨이브 (순서)
- **WAVE 0 — prod 복구**: founding 데몬 seam (아래 §3). ← **여기부터**
- WAVE 1 — 데몬 seam 정합: 계승(nextRuler/deleteNation), 외교만료 tick 배선, checkStatistic 실구현, 잔여 아이템 효과 훅.
- WAVE 2 — 무음 no-op 인테이크: auction `auction_bid→auctionBid`, betting `bet→placeBet`(+bettingType), inherit `BuyHiddenBuff`/`BuyRandomUnique` 등록, tournament-admin 코드.
- WAVE 3 — read-DTO 토대(FE출력 언블락): FrontInfo 보강, GeneralList P1/P2 권한티어, 게이지 now/max, ChiefCenter DTO + 예약명령 READ 엔드포인트, GetConst/Auction/Betting/Message/Map DTO.
- WAVE 4 — read 페이지 출력 패러티: 무력 mojibake fix, 보강필드+게이지바+권한티어 렌더, 로그/기록+연감+전투센터.
- WAVE 5 — mutation 표면: chief-center 예약편집기, 21 FE_MISSING ring 명령 노출, finance/npc/inherit 세터, 외교 편지 송신/롤백/파기.
- WAVE 6 — 도메인 REST + 신규플레이어 플로우: Message 송신, Vote write, Auction Open*, General Join + BuildNationCandidate + 입국/장수풀/빙의, 명령큐 관리, NPC 선택풀.
- WAVE 7 — 명령 포팅 롱테일(24 PORT_MISSING via /parity-wave): 계략 family, military/personal, event_*연구 8 + cr_인구이동, 등용수락.
- WAVE 8 — 토너먼트 엔진: func_tournament processTournament/bracket/betting 포팅 + tick tail 배선 + tournament-admin FE + simulator.
- WAVE 9 — 공개 read 엔드포인트 + 잔여 뷰 + admin 패러티.

---

## 3. WAVE 0 — founding 데몬 seam fix (착수했고 미완, 정밀 스펙 있음)

**정밀 스펙 = `docs/superpowers/gap/FOUNDING_SEAM_FIX.md`** (그대로 따르면 됨). 요약:

- 버그 A (거병 = INSERT-created-set → **크래시**): `ReservedTurnHandler`가 거병 resolve 전 `newNationId`/`existingNationIds`/`existingNationNames`/`scenario` preload 미주입 + resolve 후 `draft.createdNations/createdDiplomacy/createdNationTurns` 미드레인.
- 버그 B (건국/cr_건국/무작위건국 = UPDATE-nation+cascade → **silent 손실**, 크래시 아님): `draft.nation` 미diff + `cascadeGenerals/cascadeCities` 미드레인 + preload(sameMonthOrBefore/candidateCityIds) 미주입.
- **이미 OK(재작업 금지)**: InMemoryTurnWorld가 `createdNationIds/createdDiplomacyKeys` 선언+드레인함(populate 메서드만 없음). DirtyState.nationTurnDirty 존재. DatabaseHooks가 createdNations/Diplomacy/NationTurns 배선. JdbcFlushExecutor에 nationCreateMany/diplomacyCreateMany/nationTurnCreateMany SQL 있음. **→ fix는 업스트림(world에 populate + preload args)만.**
- `nation.id`는 integer PK(serial 아님) → placeholder id가 곧 권위적 id, **reconciliation 불요**. `allocateNationId() = (nations.keys.max ?: 0)+1`.

### 변경 파일 (스펙 §1)
- **F1 `InMemoryTurnWorld.kt`**: `createNation`/`createDiplomacy`/`createNationTurn`(+ `createdNationTurns` 리스트 채널) + `allocateNationId()` 추가. `consumeDirtyState()`에 `nationTurnDirty = createdNationTurns.toList()` + clear. `removeNation`에 `createdNationTurns.removeAll{it.nationId==id}` prune. (import `opensamguk.logic.domain.NationTurn` 필요. 패턴=기존 `createTroop`/`createGeneral` line 92-99.)
- **F2 `ReservedTurnHandler.kt`** (load-bearing, resolve 영역 line 216-263):
  - ctor에 `scenario: Int = 0` 추가(startYear 뒤).
  - resolve 전(line ~230) `resolveArgs = args + foundingPreload(거병만 newNationId 등)` 만들어 `GeneralActionResolveContext(... args = resolveArgs)`. **HandledTurn.args는 원본 args 유지**(파러티 오라클 오염 금지).
  - resolve 후 드레인 블록 추가(기존 cascadeDiplomacy 루프 옆): nation diff(`draft.nation`!==`nation`일 때, 비-founding엔 no-op) + cascadeGenerals(applyGeneralPatch) + cascadeCities(applyCityPatch) + created-set(**순서 load-bearing: nation→diplomacy→nation_turn**).
  - companion에 `toEngineNation(logic Nation)→engine Nation`, `toEngineDiplomacy(logic Diplomacy)→TurnDiplomacy`, `applyNationPatch(engine Nation, logic Nation)`(=ProcessNationCommand.applyLogicToNation 복제) 추가. engine Nation 필드=id,name,color,capitalCityId,chiefGeneralId,gold,rice,power,level,typeCode,meta. **tech 필드 없음**(라운드트립서 logic 기본값=0.0, 거병 tech 0.0이라 무해 — 선존재 특성).
- **F4 `DaemonLoopConfig.kt`** (line ~141): `val scenario = (state.meta["scenario"] as? Number)?.toInt() ?: System.getenv("SCENARIO_CODE")?.removePrefix("scenario_")?.toIntOrNull() ?: 0` 후 `ReservedTurnHandler(..., scenario = scenario, ...)`.
- **게이트 테스트**: `FoundingHandlerSeamTest` 신규 — world에 nation {1,2} + 중립 general(nation 0) 예약 거병 → `handler.handle()` no-throw + `world.consumeDirtyState().createdNations` id=3 + createdDiplomacy 4 + nationTurnDirty 24. 패턴=`AiHookTest.kt`(WorldSnapshot(baseState, generals, cities, nations) + handler.handle). 기존 `FoundingGoldenTest`(logic) green 유지 확인.

### WAVE 0b 백로그 (이번에 안 함 — 날조 금지)
- 건국/cr_건국/무작위건국 preload `sameMonthOrBefore` 동월 가드 math + 무작위 `candidateCityIds`(nation=0 && level∈{5,6}, id오름차순) — PHP `che_건국.php:148`/`che_무작위건국.php:98` 충실 포팅 후 추가. 드레인 블록은 WAVE 0에서 이미 깔림 → preload만 추가하면 영속화됨.

### 빌드/검증
- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-engine:test :logic:test 2>&1 | tail -40` (출력 tail로 검증, exit code 불신). Testcontainers IT는 Docker 필요(없으면 skip).
- green이면 commit → push → PR(base main) → CI green → 머지 = **main 자동배포**. 배포 후 엔진 재기동(`docker start opensamguk-game-engine`) + 턴진행 검증.

---

## 4. git/브랜치 상태

- main = `4a5c862` (맵 PR 머지 완료, 배포됨).
- 작업 브랜치 `wave0-founding-daemon-seam` (main 기준, **코드 변경 없음 — 읽기만 함**). 이 문서/docs는 이 브랜치에 커밋.
- `f4-c3-chief` = 9d20c6d (세션시작 상태, 이미 #24로 main 머지됨 — stale).
- mapfix 워크트리(`~/opensamguk-mapfix`) f-map-icons=origin(15f9bb6), conductor `bogota` 워크트리는 무관.
- **docs를 main에 푸시하지 말 것** — main push = 자동배포 = 크래시 엔진 재기동 트리거.

---

## 5. 재개 순서 (다음 세션)
1. 이 문서 + `GAP_AUDIT.md` + `gap/FOUNDING_SEAM_FIX.md` 읽기.
2. `wave0-founding-daemon-seam` 브랜치서 WAVE 0 구현(§3) → 테스트 → PR → 머지/배포.
3. 배포 후 prod 엔진 재기동 + 어드민 시드 + (승인된) 94도시 재시드 + 턴진행 검증.
4. WAVE 1→9 순차(다수는 /parity-wave·워크플로 팬아웃 가능).
