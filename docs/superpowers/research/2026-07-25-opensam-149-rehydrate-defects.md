# OPENSAM-149 restart-rehydrate 결함 목록 (1차 실측)

- 날짜: 2026-07-25
- 기준 커밋: `836e3bdf` (origin/main)
- 성격: **조사 전용** — 코드/스키마/골든 변경 0
- 티켓: `OPENSAM-149` / GitHub `#324`
- 근거 문서: `docs/superpowers/gap/LOGIC_GAP.md` §15·§0 · `docs/superpowers/plans/2026-05-31-p6-plan.md:156`

## 0. 결론 한 줄

`RehydrateService`는 **완성돼 있으나 프로덕션 호출자가 0**이다. P6 게이트 4번이 명시한 네 항목이 전부
라이브 데몬에서 복원되지 않는다. §0(founding created-set)과 **똑같은 "로직 green, 데몬 미호출" 패턴**이다.

## 1. 축 정의 — 무엇이 "무손실"의 대상인가

`InMemoryTurnWorld`가 실제로 메모리에 들고 도는 표면은 8개다
(`app/game-engine/.../turn/InMemoryTurnWorld.kt:11-20`):

`state`(world_state) · `generals` · `cities` · `nations` · **`troops`** · `diplomacy` · `accessLogs` · `archivedNationIds`

`ng_betting`·`vote_*`·`board_*`·`statistic`·`yearbook_history`·`emperior`·`hall`은 메모리 월드에 없다 ⇒
query-only cold(S5 카탈로그)이고 rehydrate 대상이 아니다. **이 표면 밖 테이블을 "안 읽어서 결함"으로
세면 안 된다.** 아래 결함은 전부 (a) 메모리 표면이거나 (b) P6 게이트 4번이 이름을 못박은 것뿐이다.

## 2. 결함 목록

### D1 — `RehydrateService` 프로덕션 호출자 0 (헤드라인)

| 항목 | 판정 | 근거 |
|---|---|---|
| 서비스 구현 | 완비 | `app/game-engine/.../turn/RehydrateService.kt:36-72` |
| 프로덕션 호출자 | **없음** | `RehydrateService` 참조는 `RehydrateServiceTest.kt:24,44` 둘뿐 |
| 부팅 배선 | `WorldSnapshotLoader`만 | `app/game-engine/.../config/BootstrapConfig.kt:50` |

P6 게이트 4번 네 항목이 전부 이 하나에 걸려 죽는다 — **구현 결함이 아니라 배선 결함**이다:

| # | P6 게이트 4번 항목 | 구현 위치 | 판정 |
|---|---|---|---|
| 1 | `obfuscatedNamePool` KV 재읽기 (재셔플 없음) | `RehydrateService.kt:76-78` | **PASS (결정론으로 우회)** — D4 참조 |
| 2 | survivor `inheritance_*` 무손실 | `:114-116` | 부분 생존 — `WorldSnapshotLoader:82-104`·`:270-290`이 따로 읽음 |
| 3 | 활성 auction + bid 풀 | `:124`, `:132` | `FAIL:no-production-caller` |
| 3 | 활성 betting 풀 | `:143-155` | `FAIL:no-production-caller` |
| 4 | polymorphic Message `buildFromArray` 재구성 | `:164-166` | `FAIL:no-production-caller` |

관측 증상: 데몬 재기동 후 진행 중 경매·입찰·베팅·서신 풀이 메모리에서 사라진다.

### D2 — `troop` 왕복 단절 (메모리 표면 8개 중 유일한 구멍)

| 방향 | 존재 | 근거 |
|---|---|---|
| flush created | O | `JdbcFlushExecutor.kt:706` `troopCreateMany` |
| flush dirty | O | `:740` `troopUpdate` |
| flush deleted | O | `:724` `troopDeleteMany` · `:732` `troopDeleteByNation` |
| **rehydrate** | **X** | `WorldSnapshotLoader.kt:133` — `troops = emptyList()` 하드코딩 |

`WorldSnapshotLoader.kt:34-35`의 주석은 "시나리오 시작 시 부대가 없고 engine-domain troop mapper가
없다"를 근거로 든다. **이 근거는 시드 시점에만 참이다.** 한 턴이라도 부대가 생기면 DB에는 `troop` 행이
있는데 재기동 후 메모리 월드는 부대 0으로 출발한다.

판정: `FAIL:flushed-but-never-reloaded`. 재기동 후 첫 flush가 빈 부대 집합을 기준으로 diff하므로
**DB 고아 행 vs 중복 INSERT 중 어느 쪽으로 터지는지는 아직 미확인**(D5).

### D3 — `general_turn` / `nation_turn` — **결함 아님 (해소)**

flush에는 있고(`generalTurnPullMany:2199` · `nationTurnPullMany:2235`) `WorldSnapshotLoader`의 12개
read 대상에는 없지만, **예약 턴은 엔진 메모리 표면이 아니다**:

- `ReservedTurnHandler.handle(generalId, reserved: ReservedTurn, ...)`(`:221`)는 `ReservedTurn`을
  **인자로 받는다** — 핸들러가 턴 저장소를 소유하지 않는다.
- 읽기 주체는 game-api(`read/GeneralTurnReadRepository.kt`, `read/NationTurnReadRepository.kt`)와
  S4 durable ring(`command_inbox`, `NationTurnRingIT`)이다.

⇒ DB 상주 + 요청/틱 시 read. 부팅 시 복원할 메모리 상태가 없으므로 rehydrate 대상 아님.

### D4 — `obfuscatedNamePool` 시드 인자 불일치 (D1 수정 경로 위의 지뢰)

라이브 경로는 KV를 **아예 읽지 않고** 매 호출마다 시드에서 재생성한다:

| 경로 | 호출 | 시드 인자 |
|---|---|---|
| 라이브 (경매 개설) | `AuctionOpenHandler.kt:427` | `buildPool(serializeSeed(hiddenSeed, ObfuscatedNamePool.KV_KEY))` |
| 라이브 (입찰) | `AuctionBidHandler.kt:408` | 동일 |
| rehydrate (미배선) | `RehydrateService.kt:82` | **`buildPool(hiddenSeed)`** ← 인자 다름 |

`buildPool`은 `RandUtil(LiteHashDrbg(seed)).shuffle(pool)`(`ObfuscatedNamePool.kt:42-51`)로 **시드에
대해 순수·결정론적**이다. 따라서 재기동해도 라이브 경로는 같은 풀을 재생성한다 ⇒ P6 항목 1은
**결정론으로 이미 restart-safe**(KV 영속은 PHP 패리티 경로이고 코틀린 라이브는 우회).

**단, D1을 고치려고 `RehydrateService`를 배선하는 순간 이 인자 차이가 터진다** — rehydrate가 만든 풀과
핸들러가 만드는 풀이 서로 다른 이름 집합이 된다. D1 수정 시 **시드 인자를 먼저 일치**시켜야 한다.

### Q1 — §0 의존 격리 (티켓 범위 4항)

`draft.createdNations` / `createdDiplomacy` / `createdNationTurns`는 `ReservedTurnHandler.handle()`이
애초에 읽지 않는다(`LOGIC_GAP.md` §0, `ReservedTurnHandler.kt:145-269`). 왕복 이전에 끊기므로
**round-trip 게이트로 측정 불가**. 티켓 4항대로 §0이 닫힐 때까지 증거 첨부 격리한다. 날조·우회 금지.

## 3. 기존 테스트 커버리지 — 왕복 테스트가 하나도 없다

채널별 flush IT는 많다(`infra/src/test/.../persistence/`): `AuctionFlushIT` · `BettingFlushIT` ·
`BettingUpsertFlushIT` · `BoardFlushIT` · `CityStateFlushIT` · `DiplomacyUpdateFlushIT` ·
`GameKvFlushIT` · `GeneralCreateFlushIT` · `MessageFlushIT` · `ProfileIconFlushIT` ·
`DeleteFlushNoDoubleApplyIT` · `CommandResultOutboxFlushIT` · `NationTurnRingIT` …

**전부 단방향이다** — `FlushPayload` → SQL → DB 행 단정에서 끝난다. `flush → rehydrate → 비교`를
왕복으로 도는 테스트는 엔진·인프라 어디에도 없다. U-4("betting/auction 채널만 측정됐다")의 실체는
"다른 채널이 안 측정됐다"가 아니라 **왕복 축 자체가 측정된 적 없다**는 것이다.

## 4. 남은 확인 대상 (D5)

1. `troop` 재기동 후 첫 flush의 실제 파손 형태 — 고아 행인가 PK 충돌인가 중복인가.
   `InMemoryTurnWorld.createTroop(:265)`은 id를 **인자로 받으므로** 할당 주체가 따로 있다. 그 할당
   지점이 메모리 max(id) 기준이면 재기동 후 기존 DB 행과 PK 충돌한다 — 미확인.

## 4. 게이트 설계 방향 (착수 전, 승인 대상)

티켓 3항의 round-trip 게이트 = `턴 N → flush → 엔진 재기동(rehydrate) → 턴 N+1`이
**무재기동 실행과 draw-for-draw + 한국어 로그 바이트 동일**임을 증명.
D1이 배선 결함이라 **게이트를 먼저 세우면 D1·D2가 자동으로 red로 잡힌다** — 테스트 우선이 맞다.
