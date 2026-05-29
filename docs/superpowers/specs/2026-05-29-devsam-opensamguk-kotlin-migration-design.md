# 설계: devsam/core(2026) → opensamguk 마이그레이션 프로그램

> Kotlin/Spring(코프링) + Next.js + PostgreSQL + nginx + Docker + Redis, 메모리 중심 CQRS

- **상태**: 승인됨 (설계). 구현 계획(writing-plans) 대기.
- **작성일**: 2026-05-29
- **프로젝트명**: 삼국지 모의전투 HiDCHe (삼모/sammo/hidche) · 재작성 워킹네임 `opensamguk`
- **소스**
  - `legacy/devsam-core` — PHP 엔진. **행동·수치·로그 grand truth**. (`hwe/sammo/` 684 PHP 파일)
  - `legacy/devsam-core2026` — TypeScript 모노레포 rewrite (~85K LOC). **구조/아키텍처 직역 레퍼런스** (이미 메모리-CQRS로 분해됨).
  - `legacy/devsam-core2026/docs/` — 의도/스펙 (4,008줄).
- **레퍼런스 정책**: `legacy/`는 런타임 무의존, 마이그레이션 레퍼런스 전용. DB 이전 완료 후 의존 제거.

---

## 0.1 딥리서치 반영 (2026-05-29) — 설계 조정 (AUTHORITATIVE OVERRIDES)

소스(TS+PHP) 대조 적대적 딥리서치 결과 검증된 조정. 본문 해당 절을 **OVERRIDE**한다. 근거 리포트: `docs/superpowers/research/2026-05-29-p0b-p1-deep-research.md`.

1. **CQRS 불변식 재정의 (§4)**: `precheck(api,DB)`와 `full(daemon,memory)`는 **동일 제약 라이브러리**를 쓰되 **freshness가 다른 스냅샷**을 평가한다 (precheck=제출시 마지막 flush DB, full=실행시 다중 뮤테이션 반영된 memory). 동일 로직·다른 데이터 → allow-후-deny는 **불가피하고 설계된 CQRS 동작**. 실행시 deny→휴식 fallback은 **에러가 아니라 의도된 결과**. 검증: 제출↔실행 사이 월드를 변형시켜 fallback이 깔끔히 발동하는 fixture 추가.
2. **정복 conflict map 포팅 정책 OVERRIDE (§10·Risk#8)**: 전역 "TS 구조 직역" 규칙을 이 경로만 **뒤집는다**. conflict 순서 = **PHP `arsort`(UNSTABLE) + JSON 키 삽입순서**가 권위 (TS `war/aftermath.ts`는 stable sort라 동점에서 PHP와 이미 발산). Kotlin은 PHP arsort 시맨틱을 재현하는 comparator+ordered-map 구현, 골든은 **PHP에서** 동점 fixture 생성.
3. **신규 Locked 결정 — 데몬 쓰기 EntityManager 미경유**: game-engine 데몬은 쓰기에 **JPA EntityManager를 절대 사용 안 함**. JPA = game-api read/precheck 전용. 데몬 쓰기는 오직 change-recorder → databaseHooks JDBC batch. (안 그러면 JPA dirty-checking과 change-recorder = 경쟁하는 dirty-truth 2개.) P0 아키텍처 테스트로 강제.
4. **P5 게이트 재범위 (§11 P5)**: GeneralAI가 `che_불가침제의`/`che_선전포고` 등 **P6 외교 resolver**를 emit → P5 풀게임 replay turn-for-turn 패리티는 P5에서 **종결 불가**. 조정: P5 게이트 = P0–P5 명령 AI 선택만, **diplomacy 명령 결과 패리티는 P6로 명시 연기** (또는 외교 accept-path resolver를 P5로 당김).
5. **P1 알고리즘 (§12)**: `che_상업투자`·`che_농지개간` **둘 다 full PHP 상업투자 알고리즘**(trust/getIntel injury·교차스탯·clamp/critical/front-debuff) 포팅. TS 레퍼런스는 농지개간을 단순 +100 cityDevelopment로, 상업투자에서 getIntel을 누락 → **TS 미러 금지**. §12에 공유 base 명시.
6. **패리티 하네스 경계 (§10·§6)**: DB 덤프는 **턴/월 경계에서만** (mid-checkpoint 부분 flush 절대 관측 금지 — wall-clock budget 만료가 PHP에 없는 중간 DB 상태 생성). replay 부분진행은 **processed-count로 게이트**(wall-clock 아님), 또는 패리티-replay run은 budgetMs를 매우 크게. §6에 catch-up/데몬 밀도 한계 분석 추가 (catchUpCap=1, 단일스레드, t3.large당 다중 프로파일 headroom).
7. **23 미싱 명령 재우선순위 (§11 P4/P8)**: `건국`/`방랑`/`수도이전`/`무작위건국`은 전부 **arsort/수도 tie-break** 사용 → P8 백로그 연기 말고 **P4 conflict 작업과 병행** 포팅 (최고위험 패리티 표면을 마지막이 아닌 P4에서 검증).

P0-B/P1 plan 작성 전 PHP 확인 필요 항목은 §14에 통합.

---

## 1. 목표 & 비목표

### 목표
- core2026가 정의한 **메모리 중심 CQRS** 게임 엔진을 Kotlin/Spring + Next.js 스택으로 **전체 포팅**.
- 게임 로직을 순수 모듈(`logic`)로 분리, API/엔진/게이트웨이를 서비스 단위로 구성.
- server+scenario 프로파일별 빌드·배포 흐름.
- **패리티**: 명령/전투/경제/AI의 수치 결과와 한글 로그 텍스트가 PHP grand truth와 일치(byte/draw-for-draw).

### 비목표
- 신규 게임플레이 기능 추가(현 단계). 마이그레이션 = 동작 보존.
- LLM/외부 API 의존(완전 LLM-free 유지, 외부 API 0).
- 디컴파일 산출물 사용. PHP/TS 소스 + docs만.

---

## 2. 확정 결정 (Locked)

| 항목 | 결정 | 근거 |
|------|------|------|
| 백엔드 | Kotlin + Spring Boot | 사용자 지정 |
| 프론트 | Next.js (App Router, client-driven 렌더) | 사용자 지정. docs의 "client owns data shaping" 의도 |
| DB | PostgreSQL + Flyway | core2026 스키마 직계 |
| 캐시/전송 | Redis (Streams + pub/sub + 세션) | docs 전송 설계 |
| 프록시/배포 | nginx + Docker Compose, AWS EC2 t3.large | 외부 API 0, ~$80/월 |
| **포팅 소스 우선순위** | TS 구조 직역 + PHP 수치·로그 tiebreaker + docs 스펙 | TS는 타겟과 동일 CQRS 구조. PHP가 정확성 grand truth |
| **영속화** | Spring Data JPA + Flyway, **단 flush 핫패스는 JDBC batch** | JPA dirty-checking이 bulk flush에 부적합 |
| **API** | REST(OpenAPI) + SSE | docs 의도, Next.js 친화, 단순 |
| **불변 규율** | 게임 영향 난수 = 재현 가능, **draw 순서까지 bit-identical** | 패리티 검증의 기반 |

---

## 3. 소스 인벤토리 (포팅 표면)

**PHP grand truth (`hwe/sammo/`, 684 파일):**
- 명령 **93개** = General 55 + Nation 38 (깊은 상속 체인, 실제 로직은 ~10 base 클래스에 집중)
- Constraint 검증자 **~73개**
- Event 엔진: Action 핸들러 29 + Condition 6
- 전투: WarUnitTrigger **36** + WarUnit core 4 (~1,132줄)
- 아이템 **161** (책 26 / 무기 26 / 말 26 + 효과아이템 ~30)
- 특기/특성: 전투특기 21 + 내정특기 30 + 성격 12 + 국가타입 15
- Enums 16, DTO 14
- **GeneralAI 4,293줄** (단일 monolith — 최장 long pole)

**TS 레퍼런스 (~85K LOC):** `packages/logic` 41.8K줄(384 파일, framework/DB-free 룰엔진) · `app/game-engine` 19.4K줄(데몬: InMemoryTurnWorld + dirty-set + databaseHooks + reservedTurnHandler ~950줄 + generalAi ~3.8K줄) · `app/game-api` 16.3K줄(tRPC read/precheck + battleSim) · `app/gateway-api` 5.3K줄 · `packages/infra` 829줄 · `packages/common` 2.4K줄(RNG/JosaUtil/sealed types).

> ⚠️ **소스 불완전**: TS 포팅은 PHP 93명령 중 **23개 누락** (`che_선양/모반시도/무작위건국`, `cr_건국`, 모든 `event_*연구`). PHP를 grand truth로, 미싱은 백로그 명시(패리티 통과로 간주 금지).

---

## 4. 시스템 토폴로지

프로파일(server+scenario, 예 `che:scenario_2`)당 게임 데몬 1개. nginx 뒤 Docker Compose.

```
[Next.js gateway]   [Next.js game]              ← 프론트 (App Router, client-driven, SSE 구독)
        │ REST/SSE        │ REST/SSE
        ▼                 ▼
   [gateway-api]      [game-api]                 ← 코프링
   인증(Kakao+로컬)    read + precheck(DB-backed)
   프로파일 오케      뮤테이션 인테이크 + SSE fan-out
        │                 │  Redis Streams(뮤테이션/제어)  +  pub/sub(라이브)
        │                 ▼
        │           [game-engine 데몬]            ← 코프링. in-memory authoritative world
        │           턴 처리 → dirty-set → bulk flush
        ▼                 ▼
   [PostgreSQL]  ◄──flush/read──►  [Redis] (streams/pubsub/세션)
```

**CQRS 경계:**
- **Write**: 데몬 in-memory world = source of truth. 턴 처리 후 dirty delta만 Postgres bulk flush.
- **Read**: game-api가 Postgres(+read projection) 직접 read. precheck도 DB-backed StateView.
- **핵심 불변식**: `precheck(api, DB) 판정 == full 실행(데몬, memory) 판정` → **제약 라이브러리 단 하나** 공유 (절대 이중 구현 금지).

**스키마 분리:** Gateway = 공유 스키마(`public`, 로그인/프로파일). Game = 프로파일별 스키마(리셋 격리).

---

## 5. Gradle 멀티모듈 + 디렉토리 레이아웃

```
opensamguk/                       ← Gradle root (Kotlin DSL, 멀티모듈)
├─ common/        RNG(LiteHashDRBG/RandUtil/serializeSeed), Clock, JosaUtil,
│                 로그 토큰 모델(<C>/<Y>/<G>, formatLogText), 와이어 sealed types
├─ infra/         JPA 엔티티/리포지토리, Flyway 마이그레이션, Redis 커넥터, JDBC batch flush
├─ logic/         순수 게임 로직 (DI/인터페이스, 무상태, DB/framework-free)
│                 constraints · triggers/iAction · commands · war · economy · diplomacy
│                 · ai · scenario · inheritance · auction · logging · domain entities
├─ app/
│  ├─ game-engine/   턴 데몬: InMemoryTurnWorld, 턴 루프, flush hook, Redis 소비, AI
│  ├─ game-api/      read + precheck + 뮤테이션 인테이크 + SSE
│  └─ gateway-api/   인증(Kakao+로컬), 프로파일 상태머신, gameToken
├─ web/
│  ├─ gateway/    Next.js (App Router)
│  └─ game/       Next.js (App Router, client-driven)
├─ tools/         parity 하네스(compare-command-logs PHP↔Kotlin), 시드/맵 생성
├─ docker/        Compose, 서비스 Dockerfile
├─ nginx/         리버스 프록시 설정
├─ docs/          설계/스펙 (본 문서 등)
└─ legacy/        devsam-core(PHP) + devsam-core2026(TS)  ← 레퍼런스 전용, 런타임 무의존
```

`common → infra → logic → app/*`, `logic`은 `infra` 무의존(순수). 데몬/API는 `logic`을 공유.

---

## 6. 메모리 중심 CQRS

### 턴 데몬 루프
단일스레드. 상태: `Idle → Running → Flushing → Idle` (+ `Paused`(admin) / `Stopping`(shutdown)).

```
while (!stopping) {
  signal = waitForNextSignal(nextTurnTime, wakeSignal)   // 제어/스케줄
  if (paused || running) continue
  drainApiRequestsUntil(nextTurnTime)                    // Redis Streams 뮤테이션 → in-memory 적용
  if (now < nextTurnTime && signal != run) continue
  running = true
  try { runUntil(now, budget); flushChanges(); publishTurnEvents() }
  finally { running = false }
}
```

- 틱 도달 시 큐가 남아도 즉시 턴 처리 시작. 처리 중 들어온 요청은 큐잉 후 처리.
- **catch-up 월간 루프**: 각 월 경계까지 due한 general+nation 명령 실행 → Pre/Month 이벤트 → pre/postUpdateMonthly 경제 → 날짜 advance.

### 실행 순서 (패리티 load-bearing)
per-general: `preprocess(che_부상경감/병력군량소모 등 암묵) → blocked → nation cmd(officer≥5) → general cmd → 큐 pull → turntime advance`, 반복은 `(turntime, no)` 오름차순. 월간: `PRE_MONTH → preUpdateMonthly → turnDate → (month==1 ? checkStatistic) → MONTH → postUpdateMonthly`.

### Run budget + 체크포인트 (PHP `max_execution_time` 대체)
`budgetMs` / `maxGenerals` / `catchUpCap`. 한도 도달 시 체크포인트 영속화(`world_state.turntime`, 옵션 cursor=마지막 general id, year/month) → 다음 run이 안전하게 재개. **부팅**: `world_state.turntime`에서 now까지 catch-up. **종료**: 신규 트리거 차단 → 현재 run 완료 → flush → clean exit.

### Immer 대체 (구조적 주의 — P1 격리)
TS `resolveGeneralAction`은 `produceWithPatches`로 patch/dirty 도출. Kotlin엔 등가물 없음 → **명시적 immutable-copy + change-recorder**로 dirty/patch 출력을 동일 재현. 어느 뮤테이션이 어떤 엔티티를 dirty/created/deleted로 표시하는지, `consumeDirtyState` drain 순서, `databaseHooks` write 순서가 byte-comparable flush와 크래시복구를 좌우.

### Dual-StateView 제약 계약
```
Constraint { name; requires(ctx): RequirementKey[]; test(ctx, view): ConstraintResult }
ConstraintResult = allow | deny{reason,code} | unknown{missing}   // unknown은 precheck 모드만
```
- 데몬: `InMemoryStateView`(full 스냅샷). API: `DbStateView`/`ProjectedStateView`(요구 필드만 DB/projection).
- `requires()`로 필요 데이터 선언 → StateView가 런타임별로 충족. 제약 로직은 순수·결정적. **단일 Kotlin 라이브러리**.

---

## 7. 영속화 & flush 전략

- **엔티티/read/precheck = JPA**: General/Nation/City/Troop/Diplomacy/Message/Event + turn 예약 테이블. JSONB(`aux/penalty/last_turn/conflict/spy/meta`) → jsonb 컬럼. **lazy/delete-on-null 쓰기 시맨틱 재현** (set null = 키 삭제) → flush payload가 골든 fixture와 byte-comparable.
- **flush 핫패스 = JDBC batch upsert**: dirty delta만. 영속성 컨텍스트 우회. `databaseHooks` write 순서 = `worldState → created → deleted → updates → rankData → logEntry`, **1 트랜잭션**.
- **FK**: 레거시는 DB FK 無(PHP 코드 강제). 타겟은 in-memory 불변식(데몬) + 선택적 DB FK. 결정: jsonb/satellite는 FK 없이, 핵심 관계만 FK.
- **flush 제외 보존 키** (per-season truncate에서 **반드시 제외**, 안 그러면 시즌 통화 소실):
  - `storage` / `inheritance_result` (계승 포인트 — 시즌 리셋 생존)
  - 난독화 경매 풀(host name pool, 1회 셔플 후 영속)
  - 오픈 경매·베팅, 외교 협정
  - → P0 스켈레톤에 flush-exclusion + restart-rehydrate 계약 박제, P6 검증.

### Postgres 핵심 테이블 (확정초안)
`app_user`, `world_state`(scenario_code/year/month/tick/config/meta), `nation`(+nation_turn/nation_env, `nation_flag` enum 테이블=NationAuxKey), `city`(+conflict jsonb), `troop`, `general`(+general_turn 예약, last_turn/aux/penalty jsonb), `diplomacy`(+ng_diplomacy 로그), `message`, `event`, `storage`/`inheritance_result`(KV), `rank_data`, `log_entry`. meta 정책: 레거시 `aux` → `meta`, hot key는 컬럼/`*_flag` 테이블로 승격.

---

## 8. Redis 전송 contract

- **Streams** (durable, replay):
  - `sammo:{profile}:turn-daemon:commands` — api→데몬 (뮤테이션·제어). `requestId` 멱등, consumer group, 실패 시 retry 후 dead-letter.
  - `sammo:{profile}:turn-daemon:events` — 데몬→api (상태/결과/에러).
- **pub/sub** (transient): `sammo:{profile}:realtime:events` — turnCompleted 등 라이브 신호 → api SSE fan-out.
- **rate limit**: 사용자당 미처리 뮤테이션 최대 30, 초과 reject.
- **와이어 타입**: Kotlin sealed class. `DaemonCommand`(run/pause/resume/getStatus), `DaemonEvent`(status/runStarted/runCompleted/runFailed), `RealtimeEvent`. 스트림 키·형태를 TS contract와 동일 유지.
- **세션**: gateway가 Redis에 1차 세션 소유. profile-scoped 토큰. API는 Redis 검증, 재인증 불필요. HTTP-only secure 쿠키, logout 시 revoke.

---

## 9. 데이터 모델 요지

5개 per-turn 변동 루트 엔티티 + KV + 풀:
- **General (XL/high)**: ~70 스칼라 컬럼 + jsonb(last_turn/aux/penalty). **action 스택 9소스 머지**(국가타입·관직·내정특기·전투특기·성격·병종·계승·시나리오·아이템 순서) + `getStatValue` 레이어드(부상 배율 → 교차스탯 +1/4 → maxLevel clamp → onCalcStat) **캐시**. 이 두 가지가 최고 패리티 리스크 — 어긋나면 전투·경제 수치 전파.
- **Nation (L/high)**: nation + nation_turn(officer_level별) + nation_env(KV). `aux`=NationAuxKey(유닛 언락/기능 플래그) → `nation_flag` enum 테이블. **level 0-9 확장은 의도적 divergence** (legacy 7단계) — 리뷰어가 패리티 위반으로 취급 금지.
- **City (L/high)**: pop/agri/comm/secu/def/wall/supply/front + `conflict`(공성 기여 맵, arsort tie-break으로 정복 승자 결정 — 정확 일치 필수). **레벨 컨벤션 quirk**: lv=4 "이"=이민족 전용, 한족 군 치소=lv=5 "소". 정적 const(CityConstBase/InitialDetail)=시드 데이터.
- **Troop (S/med)**: 3컬럼. 복잡도는 관계/행동(general.troop 포인터 + aux 발령 마커).
- **Diplomacy (M/high)**: directional-pair state + term countdown(턴마다 감소). ng_diplomacy = 유저 노출 외교 prose(정확 wording). Message 시스템과 결합.
- **World/game_env (L/high)**: `storage(namespace='game_env')` 단일 KV blob. 시간/턴 상태.
- **Pools**: select_pool(TTL lease) + create-commit 바인딩.

---

## 10. 패리티 규율 (P0 게이트 — 최우선)

룰 코드 한 줄 쓰기 전에 확립:
1. **RNG 골든**: Kotlin `LiteHashDRBG`(SHA-512 DRBG, LE getBigUint64, rejection sampling, 54-bit nextFloat1) + `RandUtil`(nextInt/nextBool/shuffle/choice/choiceUsingWeight) + `serializeSeed`(`str(len,val)` | `int(n)`). TS로 고정시드 N draw 덤프 → byte/draw-for-draw diff.
2. **시드 문자열 합성**: 컨텍스트별 seed-string(필드 순서·타입·구분자)이 byte-identical. 예 정복: `hiddenSeed+ConquerCity+year+month+attackerNationID+attackerID+cityID`. GeneralAI: `hiddenSeed+GeneralAI+year+month+generalID`.
3. **로그 토큰 골든**: JosaUtil(한글 조사), `<C>/<Y>/<G>` 컬러태그, `formatLogText` 프리픽스(PLAIN/YEAR_MONTH/NOTICE…). 골든 텍스트 fixture.
4. **compare-command-logs 하네스 재겨냥**: 기존 PHP↔TS 비교기 → PHP↔Kotlin. 명령마다 byte-match 게이트. matched-count 대시보드.
5. **PHP 부동소수/반올림**: `Util::round`, `<=>` spaceship, 정수나눗셈, `(train*atmos)^1.5`, `(def+wall*9)/500+200`, `dex*0.001`, `betting^2`, 수입 int-division. 공식별 골든값 + 명시적 RoundingMode. **arsort tie-break + JSON 키 삽입순서**(분쟁도시 승자) → 결정적 ordered-map.

---

## 11. 페이즈 로드맵 (크리티컬패스 P0→P8)

> 각 페이즈 = 자체 spec → plan → impl 사이클. 명령은 **base 클래스 먼저**, 명령별 로그 골든 게이트.

### P0 — 기반: 코프링 메모리-CQRS 모노레포 골격 + RNG/로그/상수 커널 (L)
- Gradle 멀티모듈(common/infra/logic + game-engine/game-api/gateway-api), Next.js 2앱, Docker Compose(postgres/redis/nginx/daemon/api), GitHub Actions CI(Testcontainers: Postgres+Redis).
- Flyway baseline (prisma 유래 ~11 마이그레이션) — 컬럼/jsonb 레이아웃 byte-faithful.
- Kotlin LiteHashDRBG + RandUtil + serializeSeed (bit-identical), JosaUtil + 로그토큰 모델, 상수 직역(GameConst/GameUnitConst/GameUnitDetail/CityConstBase) = 부팅 시 immutable config.
- 공유 Redis 와이어 contract(sealed), 빈 InMemoryTurnWorld + dirty/created/deleted 세트 + consumeDirtyState + databaseHooks flush **stub** + Redis Streams 소비 + SSE relay.
- **flush-exclusion + rehydrate 계약 박제** (룰 없음 — 패리티 testable + CQRS 루프 runnable로 만드는 단계).
- **게이트**: RNG draw 골든 byte-diff, JosaUtil/로그토큰 골든, 스키마 round-trip, Compose 부팅 + no-op 명령 왕복 ACK.

### P1 — 수직슬라이스: 내정명령 1개 end-to-end (XL) ← P0
§12 참조. **게이트**: `che_농지개간` 로그 byte-match PHP골든, precheck==full, flush row+jsonb byte-comparable, api→Redis→데몬→flush→turnCompleted SSE 왕복.

### P2 — 내정/인사/모병 명령 + 제약 라이브러리 완성 (XL) ← P1
- ~36 비전투 명령(내정 개발, 모병/훈련/이동, 인사, 건국/거병, 자원교역) + 비외교 nation 내부명령(발령/포상/감축/증축/천도/국호변경) Kotlin resolver, 명령별 골든.
- 완전 제약 검증자 ~73 (실패 reason string 패리티). General `getStatValue` 캐시 + 9소스 스택(내정), `*_exp` checkStatChange 레벨업/다운 로그.
- 내정특기 ~30 + 성격 12 + 국가타입 15(내정측) + 선언적 아이템 스탯(책/무기/말). onCalcDomestic/Strategic/NationalIncome 누산 hook(PHP 반올림 순서).
- rank_data/general_record satellite write-set, LastTurn term-stack, next_execute/turn_last_{officer_level} 쿨다운 KV(세이브 호환).
- **게이트**: 명령별 로그 byte-match, matched-count 0 mismatch 상승, precheck==full, flush byte-comparable.

### P3 — 월간 경제 틱 + 도시/보급 + 국가레벨 + 달력 (L) ← P2
- 순차(비병렬) 월간 파이프라인(per-entity 반복 순서 보존). ProcessIncome/SemiAnnual/WarIncome(int-division/반올림 faithful), onCalcNationalIncome, prev_income 히스토리.
- UpdateCitySupply 결정적 BFS(레거시 이웃 순서, 10%/5% loss, trust<30 중립화, officer reset cascade), front 재계산. UpdateNationLevel(**0-9 확장**) + 유니크 lottery. RandomizeCityTradeRate(per-city RNG 순서).
- Global turn loop + per-general 실행 정확 순서, killturn/block/retire/rebirth = tombstone/삭제 flush delta. 동적 event 엔진(load 전략, priority DESC/id ASC, Condition/Action sealed DSL, StaticEventHandler). ServerTool.changeServerTerm = admin 명령.
- **게이트**: N개월 replay 수치+히스토리 string byte/number-match, 실행순서 로그-시퀀스 diff, 0-9 확장 기준 검증, tombstone flush(중복적용 無).

### P4 — 트리거/iAction 전투 기반 + 전투엔진 + 전투아이템/특기 (XL) ← P3
- TriggerCaller(오름차순 priority + uniqueID dedup + raiseType 공존, 안정 순서). onCalcStat → 상대 onCalcOpposeStat **교차순서**. ~15+ battle-param 스탯키 enum.
- processWar_NG 결정적 위상머신(정확 RNG draw 순서: damage/wound/trigger/crit/avoid/magic, HP-ratio clamp, killed/dead, byte-exact 위상 로그). WarUnitGeneral 전투력 합성 + finishBattle 반올림. WarUnitCity(HP=def*10, (def+wall*9)/500+200, year-scaled).
- city.conflict 결정적 ordered-map(arsort tie-break, 선타/막타 x1.05, 분쟁 로그). ConquerCity 전체 side-effect(distinct conquest seed, collapse/survive, 수도 이전 tie-break, 전부 flush delta로 기록 — inline DB write 금지). 전투아이템 + 전투특기 트리거 주입. battleSim preview worker(엔진 재사용). 전투발생 명령(출병/급습/거병).
- **게이트**: 전투+정복 골든 replay byte/draw-for-draw(PHP+TS), 분쟁도시 승자 일치, finishBattle byte-comparable, 정복 side-effect draw-for-draw.

### P5 — NPC AI 두뇌(GeneralAI) + autorun 정책 + 명령유효성 브릿지 (XL) ← P4
- GeneralAI 시드(bit-exact) + per-turn GameSnapshot read-only facade + ID-sorted 후보 → weighted RNG. 파생상태(diplomacy d평화/선포/직전/전쟁, genType 80%-hybrid RNG, city/general 버킷) per-turn 캐시.
- 불변 PolicyView = 4레이어 머지(user > nation_env > global > live). chooseNationTurn/chooseGeneralTurn 디스패처(정확 control flow + early-return + fallback chain).
- **hasFullConditionMet** = context-source-agnostic 단일 조건 평가기, 데몬(memory) + api(DB) precheck 공유.
- **게이트**: 고정시드 풀게임 replay NPC 선택+다운스트림 로그 turn-for-turn 일치, AI 선택 명령이 실행과 동일 predicate 통과, 장기 시뮬 timeline 무발산.

### P6 — 외교 + 메시징 + 경매/베팅 + 계승 + 잔여 뮤테이션 (L) ← P4, P5
- 외교 directional-pair 상태머신 + term + ng_diplomacy prose. Message mailbox 라우팅 + polymorphic buildFromArray. DiplomaticMessage accept(NoRNG) → che_* nation 명령. event_*연구 troop-research 언락.
- 경매 base + 자원/유니크(난독화 풀 1회 셔플 후 영속, flush/재시작 생존) + close/rollback + reverse-auction sort. Betting + nation-betting(DESTROY_NATION event 등록).
- 계승 매니저: 정확 derived-key 수학(combat*5, sabotage*20, dex*0.001, betting^2, unifier +250/+2000), isunited 양측 게이팅, rebirthStoreCoeff. **storage/inheritance_result = per-season truncate 제외**. spend API(api precheck + 데몬 mutate) + TriggerInheritBuff.
- worldCommandHandler(부대/발령/kick/setNationMeta/patchGeneral/auctionBid/Finalize/tournament/voteReward) — flush hook 재사용. General Pools(select_pool TTL + create 바인딩).
- **게이트**: 외교/경매/계승 수치 byte/number-match, 재시작 rehydrate 무손실(난독화 풀/오픈 경매/협정/계승 KV), CQRS double-spend 안전.

### P7 — read API 표면 + Next.js 프론트 + SSE 리플랫폼 (XL) ← P2, P6
> P3·P4·P5와 **병행 가능**(읽기측은 전투/AI 완성 불필요).
- game-api read 엔드포인트 = Spring REST DTO(general/nation/city/map/messagebox/trends/records), **공개/인증/관리 노출 매트릭스 서버강제**(공개 장수목록=name/NPC/nation/base stat만, 나머지 cache-gated 10분).
- scenario-fixed MapViewer 레이아웃(프로파일 캐시). Next.js gateway+game(route/auth 매트릭스 재구성), gateway 1회용 gameToken → access-token 교환.
- Realtime: 데몬 turnCompleted publish → api SSE fan-out → Next.js 메인화면 라이브(sync 토글). 명령제출 = Constraint precheck 후 Redis 뮤테이션 publish.
- **게이트**: 노출 매트릭스 자동검증(비인증 게이트필드 차단, cache TTL), precheck==데몬, SSE 라이브 렌더, API 검증 논리적 동등.

### P8 — parity 하네스 완성 + 게이트웨이 오케 + 프로덕션 배포 (L) ← P5, P7
- compare-command-logs full 커버(PHP 93명령), 미싱 23개(che_선양/모반시도/무작위건국/cr_건국/event_*연구) 포팅 또는 명시 백로그. matched-count 대시보드.
- Docker/Spring e2e 오케 테스트(api+데몬 별 프로세스, Redis Streams 제어+뮤테이션, pub/sub, 뮤테이션 왕복). gateway-api 재설계: planProfileReconcile 상태머신 + Docker/Spring 라이프사이클(게임당 데몬 1프로세스) + auth + scenarioCatalog.
- Spring build-info 버전 주입(git shell-out 대체). ServerEnv → Spring 프로파일(`TURN_PROFILE_NAME=profile:scenario`). AWS EC2 t3.large + nginx + Compose, CI/CD(Testcontainers + 풀게임 replay 게이트), LLM-free/외부 API 0 확인.
- **게이트**: compare-command-logs 0 mismatch(미싱 명시 백로그), Docker e2e 프로세스경계 증명, 풀게임 replay timeline 일치, EC2 라이브 SSE + 외부 API 0.

### 의존 그래프
`P0→P1→P2→P3→P4→P5→P6→P7→P8`. 분기: **P7 ← {P2, P6}** (P3/P4/P5와 병행). P6 ← {P4, P5}.

---

## 12. P1 수직 슬라이스 (CQRS 루프 증명)

**명령**: `che_농지개간` (상업투자 base 공유 / 계승·전투·AI·외교 무관, 가장 결정적).

**흐름**:
1. Next.js → game-api precheck: DB row(General/City/Nation/WorldState) → logic 엔티티, precheck-mode MemoryStateView + ConstraintContext(mode=precheck), `buildMinConstraints`(ReqGeneralGold/OccupiedCity/SuppliedCity) 평가 → available.
2. 예약: game-api가 Redis 뮤테이션 스트림에 envelope(requestId/sentAt/command) publish + general_turn 예약 row write.
3. 데몬 XREAD BLOCK + cursor 소비, commandRegistry 정규화/검증, 턴 파이프라인 enqueue.
4. TurnDaemonLifecycle 다음 run 시각 resolve, due general drain. reservedTurnHandler가 copy-on-write per-turn overlay 위 full WorldStateView 구성, **full 제약 평가(precheck와 일치 필수)**, 시드 RNG(serializeSeed) + action context. deny → 휴식 fallback.
5. resolveGeneralAction: onCalcDomestic 비용(getActionList), 성공/크리 RNG draw, immutable draft에 General+City 변경, change-recorder가 patch+dirty 도출, ActionLogger 컬러태그 한글 로그.
6. InMemoryTurnWorld patch 적용 + general/city dirty 마킹. consumeDirtyState drain → databaseHooks bulk flush(worldState→created→deleted→updates→rankData→logEntry, 1 트랜잭션).
7. 데몬 turnCompleted RealtimeEvent publish + commandResult. game-api eventHub(Redis SUBSCRIBE) → SSE 프레임 → Next.js 메인화면 재렌더.
8. **패리티 게이트**: compare-command-logs(PHP↔Kotlin) 액션로그 byte-diff vs PHP골든, 통합테스트가 flush된 General/City row+jsonb를 골든 DB 덤프와 byte-comparable 단언.

**증명**: ① dual-StateView 제약커널 단일 공유 + precheck==full(CQRS 핵심 불변식) ② Redis Streams 뮤테이션 + 제어루프 + SSE pub/sub가 api↔데몬 프로세스 경계 동작 ③ action/dirty 엔진 + InMemoryTurnWorld dirty-set + databaseHooks bulk flush가 byte-comparable Postgres row+jsonb 생성 ④ RNG+로그 byte-parity 규율 day-one 가동. 이후 전 페이즈는 이 레일 위 breadth/depth.

---

## 13. Top Risks & 완화

1. **RNG byte-parity = 단일 gating 리스크.** 1 draw라도 어긋나면 모든 명령/전투/정복/lottery/AI desync, 골든 검증 불가. → P0에서 standalone draw-sequence 골든 먼저, 룰 코드 전 게이트.
2. **dual-StateView CQRS 계약 drift.** full(데몬)·precheck(api)·AI StateView가 has/get + buildMinConstraints vs buildConstraints에서 불일치 → UI 거짓말, NPC가 실패할 명령 큐잉. → 단일 Kotlin 제약 라이브러리(이중 구현 금지), P1 lockstep 포팅 + precheck-vs-exec fixture.
3. **한글 로그 byte-parity** (~93 명령 + 36 전투트리거 + 이벤트, JosaUtil/컬러태그/프리픽스/'수행중'/block/flush 내 순서). 로그 순서가 실행 순서 의존 → 실행 drift = 패리티 깨짐. → P0/P1에서 로그토큰 모델 + 하네스 선행, 명령마다 게이트.
4. **Immer produceWithPatches Kotlin 부재** = 주 구조적 hazard. → 명시적 copy+change-recorder로 dirty/patch 동일 재현, P1 격리. 틀리면 flush(dirty/created/deleted 마킹, drain/write 순서) silently 발산 → 크래시복구·byte-flush 깨짐.
5. **규모 long-pole.** logic ~34K LOC(~70 action + ~161 item + war/trigger), 전투(P4)/GeneralAI(P5 4.3K줄)에서 발산 전역 전파. → vertical-slice-first, 명령별 골든 게이트, base 먼저, 전투/AI는 검증된 substrate 뒤로 + 풀 replay fixture.
6. **시즌리셋/재시작 상태손실.** 계승 KV(storage/inheritance_result) 시즌 생존, 난독화 풀/오픈 경매/협정은 flush+rehydrate 생존 필요. per-season truncate가 storage 제외 안 하면 통화 소실. → P0 flush-exclusion + rehydrate 계약 박제, P6 검증.
7. **소스 불완전.** TS는 PHP 93중 23 누락 + open question(preUpdate/postUpdate/turnDate side effect, 경매 스케줄, per-map CityConst delta). → PHP grand truth 우선, 미싱은 백로그(패리티 통과 금지), open question은 해당 페이즈 lock 전 PHP로 해소.
8. **PHP float/rounding/순서 시맨틱.** Util::round, 정수나눗셈, fractional power, arsort tie-break, JSON 키 삽입순서(분쟁도시 승자 — Kotlin stable sort 다름). → 공식별 골든값 + 핀된 RoundingMode + 결정적 ordered-map.

---

## 14. 미해결 질문 (페이즈 lock 전 PHP로 해소)

- preUpdateMonthly / postUpdateMonthly / turnDate의 정확한 side-effect 집합·순서 (P3).
- 경매 open/close 스케줄 타이밍 (P6).
- per-map CityConst delta (시나리오별 도시 상수 차이) (P3/P0 시드).
- TS 누락 23 명령의 PHP 정확 동작 (P2/P8).
- General action 스택 9소스의 정확 머지·캐시 무효화 규칙 (P1/P2).

---

## 15. 후속

각 페이즈는 독립 `spec → plan(writing-plans) → impl(TDD)` 사이클. P0 spec부터 시작. 패리티 하네스가 모든 페이즈의 게이트. 학습은 누적 기록(compound).
