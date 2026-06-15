# 장기-시뮬 패러티 게이트 — 공백지→천하통일 (2026-06-15)

> 목표: PHP devsam 한 게임을 시나리오 시작부터 **천하통일(isunited=2)** 까지 돌려 턴별
> (RNG draw stream + 상태 + 로그)를 캡처하고, Kotlin이 같은 시드로 **draw-for-draw 재생**해
> byte-match를 단언하는 회귀 게이트. "완벽한 게임"의 진짜 완성 게이트 — 단일-턴 골든이 못 잡는
> 라이브 장기 결함(예: turn-loop 꼬리 미실행, 바퀴 13)을 드러낸다.

## 현 상태 (리서치 2건 종합, 2026-06-15)

**이미 있는 척추 (양측 대칭):**
- PHP `TurnExecutionHelper::executeAllCommand` (proc.php) ↔ Kotlin `MonthBoundaryDriver`(executeGeneralCommandUntil↔drain, runMonth↔postUpdateMonthly).
- 드로 레코더 대칭: PHP `tools/php-golden/RandUtilDrawRecorder.php` ↔ Kotlin `engine/golden/AiDrawRecorder.kt` ({seq,method,args,result,consumed,stateIdxBefore,bufferIdxBefore}).
- 시드 결정적: `hiddenSeed` 고정 + `LiteHashDrbg(serialize(hidden, 'monthly'/'generalCommand'/..., year,month,gid))`.
- 부팅: `infra/seed/ScenarioImporter`(JDBC) / `engine/boot/WorldSnapshotLoader`; 테스트는 `WorldSnapshot` 직접 구성 가능(InMemoryTurnWorldTest 패턴).
- 단일/부분 게이트 존재: `MonthTickReplayGateTest`(4개월 부분), `AiReplayGateTest`(174 general-turn, RNG only), `AiSelectionGateIT`(라이브 선택, world fixture 필요).

**천하통일 종료조건 (PHP grand truth):** `legacy/devsam-core/hwe/func_gamerule.php:700-763`
- `SELECT nation FROM nation WHERE level>0 LIMIT 2` → 정확히 1국이 아니면 return.
- 그 1국의 도시수 == `count(CityConst::all())` (전 도시 소유) 여야 함.
- 충족 시 `checkStatistic()` 최종기록 후 `$gameStor->isunited = 2`.
- `executeAllCommand`은 `isunited∈{2,3}`면 `$locked=true` 후 동결.

## P0 블로커 (게이트 전 선결 — 각자 1바퀴, PHP-citable, gateable)

1. **천하통일 탐지 미포팅** ⭐ Phase 1. Kotlin `checkStatistic()`/postUpdateMonthly에 국가수==1 && 전도시소유 → `world_state.isunited=2` 세팅이 **없다**. MonthBoundaryDriver는 isunited 2|3 동결만 읽음(설정 주체 부재). → 포팅 + 골든.
2. **비결정 차단원** (draw-for-draw 불가 요소):
   - `TimeUtil::now()`(wall-clock, TurnExecutionHelper.php:399) — 캡처 하네스가 고정 클럭으로 mock. Kotlin은 이미 turntime 그리드(wall-clock 격리) → PHP 측만 고정.
   - **`ORDER BY RAND()` GeneralAI.php:3324/3345 (do선양/do국가선택)** — 비시드. 다국 장기런에서 도달. 이미 Q1 격리(deterministic substitute). 양측 동일 deterministic 대체 확정 필요.
   - event `shuffle()` (RaiseNPCNation/RaiseInvader), tournament `rand()` — 발생 시 양측 시드 일치 or mock.
3. **world fixture / 부팅 경로** — 인메모리 N턴 재생용. ScenarioImporter Testcontainers 부팅 or `world-1010.json` 덤프.

## 단계 (점증 — 게이트가 턴수만큼 자란다)

- **Phase 1 (착수)**: 천하통일 탐지 포팅 — checkStatistic 국가수/전도시 체크 → isunited=2. 골든(1국 전도시→2, 2국→0). 게이트: logic/engine 단위(Docker 불요).
- **Phase 2**: PHP 풀게임 캡처 하네스 `tools/php-golden/run_long_sim.php` — TimeUtil mock + executeAllCommand 루프(까지 isunited=2 or N턴), 턴별 draw stream + 상태해시 + 로그 덤프. 결정성 차단원 중립화(ORDER BY RAND→deterministic, 양측 합의).
- **Phase 3**: Kotlin `LongSimReplayGateTest` — 시나리오 부팅 → N턴 드레인(MonthBoundaryDriver+AiTurnAdapter+MonthlyPipeline) → 턴별 draw/상태/로그 캡처 → PHP 골든과 turn-for-turn 비교, first-divergence 리포트(turn,general,seq,expected/actual,cursor).
- **Phase 4**: bounded(결정적 윈도, 예 36턴=1년) green 확보 → 차단원 하나씩 중립화하며 윈도를 천하통일까지 확장. 각 확장 = 1바퀴.

## 비결정성 전략 (핵심)

draw-for-draw는 양측이 **동일한 결정적 대체**를 써야 성립. ORDER BY RAND(선양/국가선택)은:
- 옵션 A: PHP 캡처 하네스에서 `ORDER BY no`(or 시드RNG choice)로 대체 + Kotlin 동일 대체. (권장 — 양측 코드 일치 검증 가능)
- 옵션 B: 선양/국가선택이 도달하지 않는 짧은 윈도/시나리오로 게이트 시작(Phase 4 점증). 
1010(2국)은 do국가선택(방랑군 건국) 도달성 낮음 → Phase 4 초기 윈도는 대부분 결정적일 가능성. 측정으로 확인.

## 정직한 추정
- Phase 1: 1바퀴(이번). Phase 2-3 하네스: 며칠. Phase 4 풀 천하통일: 차단원 수만큼 점증(주 단위).
- "완벽" 판정 = 이 게이트가 천하통일까지 green. 그 전엔 라이브 장기 결함이 숨을 수 있음(정직).

## 참고 (file:line)
- PHP: executeAllCommand TurnExecutionHelper.php:393-517 · 통일 func_gamerule.php:700-763 · TimeUtil::now :399 · ORDER BY RAND GeneralAI.php:3324/3345 · 시드 :280-286/461-466.
- Kotlin: MonthlyPipeline.kt:94-123 · TurnRunService.kt:145-248 · MonthBoundaryDriver(TurnDaemonLifecycle.kt) · AiTurnAdapter.kt · AiDrawRecorder.kt · ScenarioImporter.kt · WorldSnapshotLoader.kt.
- 기존 게이트: MonthTickReplayGateTest · AiReplayGateTest · AiSelectionGateIT.
</content>
