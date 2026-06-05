# WAVE 8 — tournament engine (최대 미포팅 시스템) 실행 스펙

## 목표
`legacy/devsam-core/hwe/func_tournament.php` (1393줄) 전체 — 모집/예선/추첨/본선/16강배정/베팅/16·8·4강·결승 fight + 보상정산 — 을 `:logic` 순수 로직으로 포팅하고, 라이브 데몬 tick tail에 `processTournament`를 배선하며(`processAuction` 패턴), tournament-admin FE + simulator per-side fight 패러티를 닫는다.

## 출처
- 인벤토리: `docs/superpowers/gap/LOGIC_GAP.md` §10 (Tournament — MISSING engine / PARTIAL enroll), §2 (`processTournament` not in tick tail), §6 (tournament-betting half PARTIAL)
- GAP_AUDIT 섹션: `docs/superpowers/GAP_AUDIT.md` WAVE 8 (8a~8d, lines 215-220), 부수적으로 WAVE 2c(216, `tournament_start/advance/reset` register)
- PHP grand truth: `legacy/devsam-core/hwe/func_tournament.php` (전 함수), `legacy/devsam-core/hwe/func_history.php:46-65` (fight log file store), `legacy/devsam-core/hwe/sammo/Betting.php:19,32,76,100,348` (genNextBettingID/openBetting/closeBetting/bet/giveReward), `legacy/devsam-core/src/sammo/Util.php:14,24,438,457,488,551,648` (round/setRound/randF/randRangeInt/valueFit/choiceRandomUsingWeightPair/choiceRandom), `legacy/devsam-core/hwe/b_tournament.php:22` (game_env KV 키 셋), `legacy/devsam-core/hwe/battle_simulator.php` + `legacy/devsam-core/hwe/ts/battle_simulator.ts` + `legacy/devsam-core/hwe/j_simulate_battle.php:88,249,366-457,505` (simulator per-side + processWar_NG 시드 경로)
- TS 2차 구조 오라클(참고용, PHP가 이긴다): `legacy/devsam-core2026/packages/logic/src/tournament/battle.ts`, `types.ts`, `app/game-engine/src/tournament/finalizer.ts`, `packages/common/src/tournament/autoStart.ts`, `packages/common/src/util/TournamentRNG.ts`

## 완료 / 제외 (코드로 검증, W8 스펙에서 제외)

### enroll(참가 토글) — 이미 완료, 재구현 금지
- 참가 토글(`general.tnmt` 0/1)은 이미 포팅·라이브 배선됨. 근거: `logic/.../actions/intake/TournamentEnroll.kt:24` (`clampTnmt`), `app/game-engine/.../intake/TournamentEnrollHandler.kt:25-39` (`tnmt`→general.meta, `diffGeneral` 기록), `app/game-engine/.../run/TurnDaemonCommandDispatcher.kt:65,116` (`is TournamentEnroll -> tournamentEnroll.handle`). **8a `fillLowGenAll`이 `where tnmt=1 and tournament=0`으로 이 토글을 소비한다 — enroll 자체는 W8 범위 밖.**

### read API(상태 0 렌더) — 이미 완료, 확장만
- `GET /api/tournament` read 컨트롤러 존재(상태-0 graceful). 근거: `app/game-api/.../controller/TournamentController.kt:44-67` — game_env KV(`tournament`/`tnmt_type`/`turnterm`/`tnmt_msg`) 읽어 STATE-0 기본값 반환, `groups`/`bracket` empty + 4 랭킹보드(`F4StateText.RANKING_TYPES`, `.kt:78`). **W8에서는 이 컨트롤러에 실제 bracket/groups/fight-log/betting DTO를 채워 넣는 확장만 (신규 컨트롤러 아님).**

### betting calc/giveReward 엔진 — 이미 완료, 소비만
- `BettingEngine.calcReward/giveReward` + `BettingWorldView` seam 완료(P6). 근거: `logic/.../betting/BettingEngine.kt:31,151` + `BettingWorldView` interface(`:266`). `purifyBettingKey`/`convertBettingKey`도 존재(`:238,252`). **8a `setGift`의 betting payout(`giveReward([winner])`)은 이 엔진을 그대로 소비. 단 `startBetting`(베팅 OPEN + NPC bet 주입) + `closeBetting`(closeYearMonth 스탬프)은 미포팅 — W8 범위.**

### dead 스캐폴드 wire 명령 — 정리 대상(재사용 아님)
- `common/.../wire/TurnDaemonCommand.kt:309-342,388-397` 의 `TournamentRefund`/`TournamentBettingPayout`/`TournamentReward`/`TournamentMatchResult`는 핸들러 0개·dispatcher 라우트 0개(grep 검증: `app/game-engine`에 참조 없음). **admin-mediated 옛 설계의 dead-code. W8은 이를 소비하지 않고, `processTournament`를 tick 내부에서 자율 구동한다. 정리(삭제 또는 deprecated 표기)는 T-8B-4에서 처리.**

### 기존 FE tournament-admin — 재배선만
- `web/game/app/game/tournament-admin/page.tsx:87,98,110` 이 `tournament_start/advance/reset` 미등록 코드로 POST(silent no-op, PARITY_LEDGER 추적). **8c는 이 페이지를 실제 등록 코드(또는 명시적 disabled)로 재배선 — 신규 페이지 아님.**

## ⚠️ 패러티 골든 전략 — 최우선 결정 (RNG 비결정론)

**핵심 발견(코드 검증):** `fight()`(`func_tournament.php:1004-1393`)의 모든 무작위는 **전역 `rand()` / `Util::randRangeInt`(=`mt_rand`, `Util.php:458`) / `Util::choiceRandom`(=`array_rand`, `Util.php:650`)** 이고, `fillLowGenAll`의 `choiceRandomUsingWeightPair`(`Util.php:551`)는 `randF()`=`mt_rand()/mt_getrandmax()`(`Util.php:438`)를 쓴다. `selection`/`final16set`/`startTournament`/`prev_winner` opener는 **SQL `ORDER BY rand()`**. 전 코드베이스에 `mt_srand`/`srand` **0회**(vendor 제외, grep 검증) → **PHP 토너먼트 fight·bracket·dummy fill은 시드가 없어 본질적으로 비결정론**이다. 반면 `startBetting`의 NPC bet 타겟만 **시드 있는** `RandUtil(LiteHashDRBG(simpleSerialize(hiddenSeed,'OpenBettingTournament',bettingID)))`(`func_tournament.php:401-413`) — `rng->choice(targetList)` per NPC.

**따라서 골든은 2-트랙으로 분할한다:**
- **TRACK-A (seeded, 진짜 draw-for-draw 골든 Y):** `startBetting` NPC bet 타겟 선택(`choice` per-NPC draw 순서/개수) + betting payout(`giveReward`) + `setGift` 보상수치(경험/금/inheritance_point/rank_data 증분) + 결정론 fight 산술(damage 공식·`getLog`·offset clamp·gl 계산·로그 문자열 템플릿). 이들은 입력 고정 시 출력 고정 → 캡처/포팅 모두 byte-match 가능.
- **TRACK-B (unseeded, 골든 N + RNG seam 주입):** `fight()`의 `rand()` 분기(평타 90~110%, 보너스타, 막판분노, 1합승부, 일반 critical), `fillLowGenAll`의 가중선택, `selection`/`final16set`의 `ORDER BY rand()`. **draw-for-draw 라이브-PHP 재생 불가**(시드 없음). 포팅은 `TournamentRng` seam(주입 가능한 RandUtil/시퀀스)으로 **결정론화**하되, **이 seam은 패러티 산물이 아니라 opensamguk-고유 결정론 대체**임을 quarantine 증명(아래)으로 명시한다. 게이트는 "고정 RNG 시퀀스 입력 → fight 산술/로그 출력" formula-parity(골든 N).

**Quarantine 증명(TRACK-B):** PHP가 `mt_rand`(시드 없음)를 쓰므로 같은 입력도 매 실행 다름 → 골든 캡처가 무의미. opensamguk은 (i) fight 입력별(grp/grp_no/phase) `RandUtil(LiteHashDRBG(simpleSerialize(hiddenSeed,'Tournament',year,month,tnmt_type,group,phase)))` 결정론 시드를 **신규 도입**(PHP엔 없던 결정론 — divergence 문서화), (ii) 산술/로그 템플릿은 PHP와 byte-match(이 부분은 결정론). 즉 "어떤 draw가 나오는가"는 divergence, "draw가 주어지면 무엇을 계산/출력하는가"는 패러티. backlog 기록 필수.

## foundation-first 빌드 순서 (Tier-0 공유 확장점 먼저)

1. **Tier-0 (foundation, 순차·creator-first — 모든 8a consumer가 의존):**
   - **T-0A** `tournament` 영속 store 결정 + 스키마. PHP는 `tournament` SQL 테이블(no,npc,name,leadership,strength,intel,lvl,grp,grp_no,win,draw,lose,gl,prmt,seq,h,w,b). opensamguk엔 부재(migration grep 0). → **신규 마이그레이션 `V{N}__tournament.sql`** + flush row mapper/op + world in-memory bracket 보유.
   - **T-0B** game_env KV 토너먼트 키 셋 확장점: `tournament`/`phase`/`tnmt_type`/`tnmt_auto`/`tnmt_time`/`last_tournament_betting_id`/`prev_winner`/`tnmt_msg` 읽기·쓰기 seam(KVStorage 'game_env'). 현재 read만 존재(`TournamentController`). → write seam(데몬측 KV mutator + flush KV 채널 재사용) 신설.
   - **T-0C** `TournamentRng` seam (TRACK-B 결정론화) + `TournamentLogStore`(PHP `fight{group}.txt` 파일로그 → KV/log 채널, group-keyed). `func_history.php:59-65`.
   - **T-0D** `TournamentState`/`TournamentEntry`/`TournamentMatchLog` 도메인 DTO(LinkedHashMap insertion-order; bracket grp/grp_no 순). → 8a 전 함수 + read DTO + FE가 소비.
2. **Tier-1 (consumer, T-0 위에서 병렬 — 단계함수별 disjoint 파일):** `fillLowGenAll`/`getTwo`+`qualify`/`selection`/`finallySingle`/`final16set`/`finalFight`/`fight`/`startBetting`/`closeBetting`/`setGift`/`setRefund` 포팅 + `processTournament` 상태기계 + `startTournament`.
3. **Tier-2:** 데몬 배선(`TurnRunService` tick tail + `DaemonLoopConfig`) + read DTO 확장 + FE + 게이트.

## 태스크 분해 표

PHP 출처 file:line은 `legacy/devsam-core/` 상대. 게이트 골든 Y = TRACK-A(시드 있는 진짜 draw-for-draw 캡처 필요). 골든 N = 구조/formula-parity(TRACK-B 또는 인프라/배선).

### 8a-Tier0 — foundation (순차, creator-first)

| id | 변경 파일 (disjoint) | 무엇을 (PHP 출처) | 게이트 (테스트 / 골든) | 의존성 |
|---|---|---|---|---|
| **T-8A0-1** | `infra/src/main/resources/db/migration/V{N}__tournament.sql` (신규) + `infra/.../persistence/JdbcFlushExecutor.kt`(row mapper/op 추가 구역) + `infra/.../persistence/FlushPayload.kt`(tournament delta 필드) | `tournament` 테이블 스키마(컬럼: no,npc,name,leadership,strength,intel,lvl,grp,grp_no,win,draw,lose,gl,prmt,seq,h,w,b — `func_tournament.php:500-513,651-664,742-755` insert 셋) + truncate/upsert/delete delta op. PHP `TRUNCATE TABLE tournament`(`:298`) ↔ 토너먼트 시작 시 전체 clear | `TournamentMigrationIT`(infra, Docker-gated) / 골든 N | (없음) |
| **T-8A0-2** | `app/game-engine/.../turn/InMemoryTurnWorld.kt`(tournament bracket 보유 구역) + `app/game-engine/.../turn/ChangeRecorder.kt`(tournament delta 채널) | in-memory bracket 행 보유 + created/dirty/deleted 기록(insertion-order LinkedHashMap, grp→grp_no). flush는 `consumeDirtyState`로 드레인 | `TournamentWorldStoreTest`(engine) / 골든 N | T-8A0-1 |
| **T-8A0-3** | `app/game-engine/.../tournament/TournamentKvAccess.kt` (신규) | game_env KV 8키 read/write seam(`tournament`/`phase`/`tnmt_type`/`tnmt_auto`/`tnmt_time`/`last_tournament_betting_id`/`prev_winner`/`tnmt_msg`) — `b_tournament.php:22`, `func_tournament.php:21,126-136,288-293`. write는 기존 KV flush 채널 재사용 | `TournamentKvAccessTest`(engine) / 골든 N | (없음) |
| **T-8A0-4** | `logic/.../tournament/TournamentRng.kt` (신규) | TRACK-B 결정론 seam: `RandUtil(LiteHashDRBG(simpleSerialize(hiddenSeed,'Tournament',year,month,tnmt_type,group,phase)))` 생성 팩토리 + `rand()%N`↔`nextInt(0,N)`, `randRangeInt(a,b)`↔`nextRangeInt(a,b)`, `choiceRandom`↔`choice` 매핑. **PHP 비결정론 divergence(quarantine 증명 위 참조)** | `TournamentRngTest`(logic) / 골든 N | (없음) |
| **T-8A0-5** | `logic/.../tournament/TournamentDomain.kt` (신규) | `TournamentState`(tnmt/phase/type/auto/time/lastBettingId), `TournamentEntry`(no,npc,name,leadership,strength,intel,lvl,grp,grpNo,win,draw,lose,gl,prmt,seq,h,w,b), `MatchLog`(group, lines:List<String>). LinkedHashMap insertion-order | `TournamentDomainTest`(logic) / 골든 N | (없음) |
| **T-8A0-6** | `logic/.../tournament/TournamentLogStore.kt` (신규) + `app/game-engine/.../tournament/TournamentLogChannel.kt` (신규) | `func_history.php:46-65` 파일로그 포팅 — `eraseTnmtFightLog(grp)`/`eraseTnmtFightLogAll()`(range 50)/`pushTnmtFightLog(grp,log)`/`getTnmtFightLogAll(grp)`. group-keyed append, opensamguk은 KV/log 채널 | `TournamentLogStoreTest`(logic) / 골든 N | T-8A0-5 |

### 8a-Tier1 — 단계함수 포팅 (병렬, 단계별 disjoint 파일)

| id | 변경 파일 (disjoint) | 무엇을 (PHP 출처) | 게이트 (테스트 / 골든) | 의존성 |
|---|---|---|---|---|
| **T-8A1-1** | `logic/.../tournament/StartTournament.kt` (신규) | `startTournament($type)` `:277-322`: KV 초기화(tnmt_auto=true, tnmt_time=now+unit'M', tournament=1, type, last_betting=0, phase=0), `general.tournament=0` 전체 리셋, `tournament` truncate, opener 선택(`officer_level=12 AND nation.level=7 ORDER BY rand()`→없으면 prev_winner — **SQL rand**), 개최 글로벌 히스토리 로그(`<S>◆</>{year}년 {month}월:<B><b>【대회】</b></>{openerText}<C>{typeText}</> 대회가 개최됩니다!...`) | `StartTournamentTest`(logic) / 골든 Y(로그문자열·KV초기값; opener는 N quarantine) | T-0A~T-0E |
| **T-8A1-2** | `logic/.../tournament/FillLowGen.kt` (신규) | `fillLowGenAll($type)` `:421-534`: grpCount 집계, 64명까지 `toBeFilledCnt`, scoringFunc(type별 stat^1.5), `tnmt=1 and tournament=0` 후보 가중선택(`choiceRandomUsingWeightPair`→**TRACK-B**), min-grp 배정, 잔여 더미장수('무명장수' lvl10 stat10) 채움, `general.tournament=1` 마크 + tournament INSERT. tournament=2/phase=0 | `FillLowGenTest`(logic, 고정 RNG시퀀스) / 골든 N(TRACK-B) | T-8A0-4,5 |
| **T-8A1-3** | `logic/.../tournament/TournamentBracket.kt` (신규) | `getTwo($tnmt,$phase)` `:550-581`(예선 28×2 candMap, 본선 6 candMap, phase>=28 swap), `qualify` `:583-612`(8그룹×fight + phase<55 진행 else prmt 4강 산정 `gd desc,gl desc,seq`), `finallySingle` `:688-717`(grp10-17 + prmt 2강 산정), `final16set` `:728-763`(grp/prmt 매핑 16개→grp20+ 배정, **selection/final16set의 ORDER BY rand는 TRACK-B**) | `TournamentBracketTest`(logic, 고정 RNG) / 골든 N(TRACK-B) | T-8A0-4,5, T-8A1-4 |
| **T-8A1-4** | `logic/.../tournament/TournamentFight.kt` (신규) | `fight($type,$tnmt,$phs,$group,$g1,$g2,$type0)` `:1004-1393` + `getLog($lvl1,$lvl2)` `:993-1001`: energy `Util::round(stat*getLog*10)`, 아이템 로그 4-variant(`rand()%4`, 명마/무기/서적 type별), turn루프(평타 90~110%·보너스타·막판분노 200~500%·1합 fatality·일반 critical 150~300%), `Util::setRound` damage, offset clamp(`r1>r2` 분기), gl 계산(`(gd2-gd1)/50`), win/draw/lose + rank_data g/w/l/d 증분, 다음경기 안내 로그. **fight 무작위 전부 TRACK-B(RngSeam 주입)**, 산술·로그 템플릿은 byte-match | `TournamentFightTest`(logic, 고정 RNG시퀀스 → 산술·로그 byte-match) / 골든 N(formula-parity) | T-8A0-4,5,6 |
| **T-8A1-5** | `logic/.../tournament/TournamentSelection.kt` (신규) | `selection`/`selectionAll` `:625-686`: phase별 시드1/2/3/4 배정(`prmt=1/2/>2 ORDER BY rand`→**TRACK-B**), 본선 grp10+ INSERT, 시드행 prmt=0 클리어, phase<31 진행 else tournament=4 | `TournamentSelectionTest`(logic, 고정 RNG) / 골든 N(TRACK-B) | T-8A0-4,5 |
| **T-8A1-6** | `logic/.../tournament/FinalFight.kt` (신규) | `finalFight($type,$tnmt,$phase,$type16)` `:765-808`: 16/8/4/2별 [offset,turn,next] 매핑, grp=phase+offset에서 fight(type=1=승패), 승자 다음 라운드 grp 배정 INSERT, phase>=turn → next 단계 | `FinalFightTest`(logic, 고정 RNG) / 골든 N | T-8A1-4 |
| **T-8A1-7** | `logic/.../tournament/TournamentBetting.kt` (신규) | `startBetting($type)` `:341-419`: 후보 16강자(`grp>=20 order by grp,grp_no LIMIT 16`)로 SelectItem 구성, `Betting::openBetting`(type 'tournament', selectCnt 1), betGold=`Util::valueFit(floor((3+year-startyear)*0.334)*10,10)`, **시드 있는** `RandUtil(LiteHashDRBG(...,'OpenBettingTournament',bettingID))`로 NPC(`npc>=2 AND gold>=500+betGold`)별 `rng->choice(targetList)` bet. + `closeBetting()`(`Betting.php:76` closeYearMonth 스탬프) | `TournamentBettingTest`(logic) / **골든 Y(TRACK-A: NPC choice draw-for-draw)** | T-8A0-5, BettingEngine(완료) |
| **T-8A1-8** | `logic/.../tournament/SetGift.kt` (신규) | `setGift` `:810-964` + `setRefund` `:966-990`: 16강(exp+25/gold+develcost/rank+1/ip+10), 8강(exp+50/×2/rank+1), 4강(exp+50/×3/rank+2/ip+10), 준우승(exp+100/×6/rank+2/ip+50), 우승(exp+200/×8/rank+2,p+1/ip+100), 장수열전 로그, 글로벌 【대회】 우승/준우승·상금 로그(`JosaUtil::pick '이'`), betting `giveReward([winner.no])`, tnmt_auto=false. setRefund: 미진출 develcost 환수 + `giveReward([-1])` | `SetGiftTest`(logic) / **골든 Y(TRACK-A: 보상수치·로그·rank_data·ip)** | T-8A0-5, BettingEngine |
| **T-8A1-9** | `logic/.../tournament/ProcessTournament.kt` (신규) | `processTournament()` `:16-137` 상태기계 + `calcTournamentTerm`(`:12` valueFit 5~120) + `getTournamentTermText`/`getTournamentTime`/`getTournament`(`:139-211` 표시 텍스트): tnmt_auto 가드, offset/unit/iter 계산, case 1~10 분기(fillLowGen→qualify×56→selection×32→finallySingle×6→final16set→startBetting→closeBetting→finalFight 16/8/4/2→setGift), case 6 베팅 60phase(최대 1h) 처리·early return, tnmt_time 갱신 | `ProcessTournamentTest`(logic, 고정 RNG·시계) / 골든 N(상태전이·시계산술) | T-8A1-1~8 |

### 8b — tick tail 배선

| id | 변경 파일 (disjoint) | 무엇을 (PHP 출처) | 게이트 (테스트 / 골든) | 의존성 |
|---|---|---|---|---|
| **T-8B-1** | `app/game-engine/.../tournament/TournamentDaemon.kt` (신규) | `ProcessTournament`를 world/recorder/KV/log seam에 thread하는 데몬 어댑터(`AuctionExpiryDaemon` 패턴, `AuctionExpiryDaemon.kt:42-72`). TRACK-A betting은 시드 RandUtil, TRACK-B fight는 `TournamentRng` seam. 모든 변경은 동일 `ChangeRecorder`로 flush | `TournamentDaemonTest`(engine) / 골든 N | T-8A1-9, T-8A0-* |
| **T-8B-2** | `app/game-engine/.../run/TurnRunService.kt` (`runTick` step 1b 구역만) | `auctionExpiryDaemon?.checkExpiredAuctions(...)`(`:137`) **다음 줄**에 `tournamentDaemon?.processTournament(world, recorder, runTime)` 추가 — general drain 후, flush 전(PHP `executeAllCommand`가 general drain 후 `processTournament`→`processAuction` 순서, `TurnExecutionHelper.php:393-518`). ctor에 `tournamentDaemon` 옵셔널 주입 | `TurnRunServiceTournamentTickTest`(engine) / 골든 N(호출순서) | T-8B-1 |
| **T-8B-3** | `app/game-engine/.../config/DaemonLoopConfig.kt` (`TurnRunService` 빌드 구역만) | `TurnRunService` 생성 시 `tournamentDaemon` 주입(auction repo 패턴, `TurnRunService.kt:99-103`) | `DaemonTournamentWiringTest`(engine) / 골든 N | T-8B-1, T-8B-2 |
| **T-8B-4** | `common/.../wire/TurnDaemonCommand.kt`(dead 스캐폴드 구역만) | dead `TournamentRefund`/`TournamentBettingPayout`/`TournamentReward`/`TournamentMatchResult`(`:309-342,388-397`) deprecated 표기 또는 제거(소비자 0개 검증됨) — 자율 `processTournament`로 대체됨 명시 | 컴파일 green / 골든 N | (없음) |

### 8c — tournament-admin FE + 명령 등록 (WAVE 2c 마감)

| id | 변경 파일 (disjoint) | 무엇을 (PHP 출처) | 게이트 (테스트 / 골든) | 의존성 |
|---|---|---|---|---|
| **T-8C-1** | `common/.../wire/TurnDaemonCommand.kt`(신규 명령 구역) + `app/game-engine/.../intake/TournamentAdminHandler.kt` (신규) + `app/game-engine/.../run/TurnDaemonCommandDispatcher.kt`(라우트 구역) | `TournamentStart(type)`/`TournamentReset` 인테이크 명령 + 핸들러(start=`startTournament`, reset=`setRefund`+KV clear). **advance는 자동(`tnmt_auto`)이라 수동 advance 미지원 — FE에서 제거 또는 disabled**. dispatcher 라우트 추가(`:105-133` 패턴) | `TournamentAdminHandlerTest`(engine) / 골든 N | T-8A1-1, T-8A1-8 |
| **T-8C-2** | `app/game-api/.../controller/TournamentController.kt`(DTO 채우기 구역) + `app/game-api/.../dto/TournamentResponse.kt` | `groups`/`bracket`/fight-log/betting candidates를 실제 bracket/log store에서 read해 채움(현재 empty 반환, `.kt:62-64`). `printFighting`(`func_tournament.php:224-275`) group 표시 매핑 + `getTnmtFightLogAll` | `TournamentReadDtoTest`(game-api) / 골든 N | T-8A0-2,6, T-8B-* |
| **T-8C-3** | `web/game/app/game/tournament-admin/page.tsx` + `web/game/app/game/tournament/page.tsx` + `web/game/app/lib/api.ts`(command 코드) | `tournament_start`(type 선택) → 등록된 인테이크 코드, `tournament_advance` 버튼 제거(자동 진행 안내), `tournament_reset` → 등록 코드. bracket/fight-log/betting 후보 렌더(read DTO 소비). silent no-op 종식(WAVE 2c) | (FE 수동 QA / `/qa`) / 골든 N | T-8C-1, T-8C-2 |

### 8d — simulator per-side fight 패러티

| id | 변경 파일 (disjoint) | 무엇을 (PHP 출처) | 게이트 (테스트 / 골든) | 의존성 |
|---|---|---|---|---|
| **T-8D-1** | `logic/.../simulator/SimulatorInput.kt` (신규) | per-side combat 입력 export-object DTO: attacker/defender general(no,npc,name,officer_level,explevel,leadership/strength/intel,horse/weapon/book/item,injury,rice,personal,special2,crew,crewtype,atmos,train,dex1~5,defence_train) + city(level,def,wall) + nation(level,type,tech,capital) + year/month/startYear + **seed**. `battle_simulator.ts:44-97`(GeneralInfo), `j_simulate_battle.php:88,274-324` | `SimulatorInputTest`(logic) / 골든 N | (없음) |
| **T-8D-2** | `logic/.../simulator/SimulateBattle.kt` (신규) | `simulateBattle()` `j_simulate_battle.php:366-457`: WarUnitGeneral×2 + WarUnitCity 구성, `processWar_NG($warSeed, attacker, getNextDefender, city)` 호출(`:457`). **시드 있는 `RandUtil(LiteHashDRBG($warSeed))`(`:383`) — 완전 결정론, fight()와 무관**. 1회(로그) / 1000회(요약) 모드 | `SimulateBattleGoldenTest`(logic) / **골든 Y(시드 고정 draw-for-draw 전투로그)** | ProcessWarNG(완료, `logic/.../war/ProcessWarNG.kt:35`) |
| **T-8D-3** | `app/game-api/.../controller/SimulatorController.kt` (신규) | `j_simulate_battle.php`/`j_export_simulator_object.php` 대응 POST 엔드포인트(seed+per-side input→battle log/summary). game-api read+pure-sim(쓰기 없음 → one-daemon-write-rule 비위반) | `SimulatorControllerIT`(game-api) / 골든 N | T-8D-1, T-8D-2 |
| **T-8D-4** | `web/game/app/game/simulator/page.tsx` | per-side 입력 폼(crew/crewtype/train/atmos/city/items/specialties) + seed + 반복횟수 + export/import + battle-log/summary 렌더. `battle_simulator.php:62-120`, `battle_simulator.ts:378,711,751` | (FE 수동 QA / `/qa`) / 골든 N | T-8D-3 |

## 병렬화 그룹 (disjoint worktree family — 같은 파일 co-widen 금지)

- **Group-A (Tier-0 foundation, 순차 creator-first):** T-8A0-1 → T-8A0-2(world/recorder co-widen, ChangeRecorder는 W1 등 다른 웨이브와 충돌 주의 → W8 단독 windowing) → T-8A0-3,4,5,6(이후 병렬 가능). **여기 끝나기 전 Tier-1 시작 금지.**
- **Group-B (8a-Tier1 단계함수, Group-A 후 병렬):** {StartTournament}, {FillLowGen}, {TournamentFight→TournamentBracket·FinalFight}, {TournamentSelection}, {TournamentBetting}, {SetGift} — 각 신규 파일 disjoint. **TournamentFight는 Bracket/FinalFight의 선행(같은 fight 호출)** → Fight 먼저, 그 다음 Bracket·FinalFight 병렬. ProcessTournament(T-8A1-9)는 전 Tier-1 consumer → 마지막 순차.
- **Group-C (8b 배선):** T-8B-1 → T-8B-2·T-8B-3(TurnRunService·DaemonLoopConfig는 다른 웨이브가 동시 수정 가능 → W8 단독 윈도우). T-8B-4(wire dead-code)는 독립 병렬.
- **Group-D (8c FE+intake):** T-8C-1(wire+handler+dispatcher) → T-8C-2(read DTO) → T-8C-3(FE). Group-C와 `TurnDaemonCommand.kt`·`TurnDaemonCommandDispatcher.kt` co-widen → **T-8B-4와 T-8C-1은 같은 파일(`TurnDaemonCommand.kt`) 동시수정 금지, 순차(creator T-8C-1 → T-8B-4 또는 반대 단일 worktree)**.
- **Group-E (8d simulator, 완전 독립 — Group-A~D와 disjoint):** T-8D-1 → T-8D-2 → T-8D-3 → T-8D-4 순차. processWar_NG 재사용이라 토너먼트 fight 엔진과 무관 → **8a~8c와 병렬 가능**.

**disjoint family 수 = 5** (A foundation / B 8a-stages / C 8b-wire / D 8c-fe / E 8d-simulator). 단 A→B, C↔D는 `TurnDaemonCommand*.kt` 공유로 일부 순차 게이팅.

## 패러티 주의점

- **RNG (최중대):** 위 "패러티 골든 전략" 2-트랙 분할 필수. TRACK-A(startBetting NPC choice / setGift 보상 / simulator processWar_NG)는 시드 있는 `RandUtil(LiteHashDRBG)` → 진짜 draw-for-draw 골든. TRACK-B(fight/bracket/fill의 `mt_rand`·`ORDER BY rand`)는 PHP 비결정론 → `TournamentRng` 결정론 seam으로 divergence(quarantine 증명+backlog 등록), 산술·로그만 byte-match. **PHP `rand()%N` → `nextInt(0,N)` 매핑 정확성 검증**(`rand()%21+90`=90~110 → `nextRangeInt`/`nextInt` 경계). `Util::choiceRandomUsingWeightPair`는 `randF()*sum` 누적선택 — `RandUtil.choiceUsingWeightPair`(`common/.../RandUtil.kt:53`)와 매핑.
- **Rounding:** `Util::round`/`Util::setRound`=half-away → `PhpRound.phpRound` 사용, NEVER Math.round/kotlin round. `getLog`는 `log(1+diff,10)/10` 부동소수 — PHP `log` base-10. energy/damage `Util::round`, offset `Util::round`, gl `Util::round((gd2-gd1)/50)`. fight 더미 stat·`(leadership+strength+intel)*7/15`(total) 정수나눗셈 주의. betGold `floor(...)`=truncate. (TS `battle.ts:17` `Math.round`는 오라클 divergence — 무시, PHP `Util::round` 따른다.)
- **로그 byte-parity:** 아이템 4-variant 로그(`rand()%4` 분기마다 Josa `이`/`을` 다름), `<S>●</> <Y>{name}</> <C>({energy})</> vs ...`, `合 : <C>003</>(-005) vs ...`(`StringUtil::padStringAlignRight` zero-pad 3/2자리), 분노/critical/fatality `<M>{skill}</>`, 승리/무승부/재대결, 다음경기 `--------------- 다음경기 ---------------<br><S>☞</>...`, setGift 글로벌 【대회】 우승/준우승, 개최 로그. skillMap/crticialSkillMap/fatalitySkillMap type별 배열(`:1136-1153`) 정확 복제. **로그 순서 = 실행 순서**.
- **insertion-order:** bracket 행은 grp→grp_no INSERT 순서 보존(LinkedHashMap), 결과 정렬은 `gd desc, gl desc, seq`(prmt 산정) — `seq`는 INSERT 순(stable). PHP 8 stable sort — secondary comparator 추가 금지.
- **flush-delta:** 토너먼트 행/KV/rank_data/general(gold·exp)·inheritance_point 변경 전부 `ChangeRecorder` created/dirty 기록 → JdbcFlushExecutor 배치(one-daemon-write-rule). 인라인 DB write 금지. PHP `$db->update('general',...sqleval('experience+25'))`=증분 → recorder 증분 패치.
- **호출 순서(tick):** general drain → `processTournament` → `processAuction`(PHP `TurnExecutionHelper.php:393-518`). opensamguk `TurnRunService.runTick`은 현재 auction이 step 1b(`:137`)에 있음 — **PHP는 둘 다 general drain 후**이므로 tournament를 auction과 같은 위치(general drain 후, flush 전)에 두되 **순서는 tournament→auction**. 단 auctionExpiryDaemon은 현재 commandDispatch 직후라 위치 재검토 필요(open question).

## 오픈 질문

1. **TRACK-B 결정론 시드 키 합의:** `TournamentRng` 시드를 `simpleSerialize(hiddenSeed,'Tournament',year,month,tnmt_type,group,phase)`로 제안했으나, fight 내부 phase루프의 per-draw 일관성(평타/보너스/분노/critical이 한 RandUtil에서 연속 draw인지, 아니면 draw별 재시드인지) 확정 필요. PHP는 한 `mt_rand` 스트림(전역)이므로 **fight 1회당 RandUtil 1개를 만들고 연속 draw**하는 것이 가장 PHP-스트림에 가깝다 — 결정 필요.
2. **tournament 영속 store: SQL 테이블 vs KV?** PHP는 SQL `tournament` 테이블. opensamguk은 (a) 신규 마이그레이션 테이블(T-8A0-1 제안) 또는 (b) game_env KV에 bracket 직렬화 중 택1. 토너먼트가 일시적·전체 truncate라 KV가 더 단순할 수 있음 — 인프라 결정 필요.
3. **fight 로그 저장소:** PHP는 파일(`logs/{serverID}/fight{group}.txt`). opensamguk은 (a) KV 채널, (b) 신규 log 테이블, (c) Redis 중 택1. read DTO(`TournamentController`)가 어떻게 group별 로그를 읽을지와 연동.
4. **simulator 1000회 요약 모드:** `repeat_cnt=1000`은 seed 없이 1000번 반복(통계). 각 반복마다 seed를 어떻게 변주하는지(`battle_simulator.ts:751` seed 입력 1개) — PHP `j_simulate_battle.php`가 반복마다 seed를 증분하는지 고정인지 확인 필요. 1회 모드(로그)만 골든 Y, 1000회는 통계 → 골든 N 가능.
5. **`tnmt_auto` 자동진행과 수동 admin:** PHP `processTournament`은 `tnmt_auto`면 시계기반 자동 진행, 아니면 무시(`:26`). FE tournament-admin의 `advance`는 수동 단계진행 의미였으나 PHP엔 수동 advance가 없음(시작/환수만). → advance 버튼 제거 확정(T-8C-1) 또는 디버그용 강제 진행 명령 신설 여부 결정.
6. **auctionExpiryDaemon 위치:** 현재 `TurnRunService.kt:137`에서 commandDispatch 직후(general drain 전). PHP는 general drain 후. tournament 배선 시 auction 위치도 PHP 정합으로 옮길지(별도 회귀 위험) 또는 tournament만 올바른 위치에 둘지 — 범위 결정 필요.
7. **`prev_winner` KV 키:** `startTournament` opener fallback(`:302`) + `setGift` 우승자 기록처가 `prev_winner`를 set하는지 확인 필요(현 캡처엔 미관측). 미사용이면 빈 fallback.
