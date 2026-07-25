# round-3 설계안 개정 5차 — 처리 기록

> 일시: 2026-07-25 · 입력: `REVIEW-round3-r4.md`(9/10 `fix-required`, N = 문항 7, **4바퀴 연속 동일 실패형**) · 대상: `round3-proposal-city-guanxi.md` 제자리 수정 (1320 → 1394줄, 새 파일 0[본 기록 제외], 미커밋, 코드 무수정, `docs/wiki/raw/**` 무수정)
> 상태: **재채점 대기** (동일 시험지 + 독립 reviewer, 5번째)

## 한 문단 요약

MAJOR-A는 **채점자가 준 두 선택지 중 어느 것도 답이 아니었다.** 게이트 메커니즘이 아니라 **아키텍처 테스트에서 역추적**하자 `WorldSnapshotLoader.kt`가 v1 가드 테스트에 의해 물리적으로 봉인돼 있어 v2 SELECT를 **넣을 방법 자체가 없다**는 사실이 나왔다. 같은 역추적이 `JdbcFlushExecutor`가 **v1 DataSource에 묶여 있어 v2 step이 방어선 1을 위반**한다는 것도 드러냈다. 그 결과 T2 표는 12행 → **10행**이 됐고, 바뀐 것은 2행이 아니라 **8행**(5행 삭제·3행 신설)이다. MAJOR-B는 `ddl-auto: validate` 때문에 채점자가 제시한 (a)·(c)가 **v1 부팅을 깨는 길**임을 확인해 **(b) `JdbcTemplate` 고정**으로 닫았다. UNKNOWN-5(v2 DataSource 배선)는 **답을 썼다.** MINOR-C는 이유 정정을 넘어 **행 자체를 삭제**했다. 오픈 경로는 **20 불변**.

---

## 1. 항목별 처리

| 항목 | 처리 | 위치 (`path:line`) |
|---|---|---|
| **MAJOR-A** `BootstrapConfig.kt` 누락 | **(a)도 (b)도 아닌 제3안.** v2 원장 읽기를 `WorldSnapshotLoader`/`InMemoryTurnWorld`/`BootstrapConfig` 경로에서 **완전히 뺐다.** 근거: `app/game-engine/src/test/kotlin/opensamguk/engine/boot/HotColdWorldCatalogGuardTest.kt`가 로더의 `private fun load[A-Z]…` 이름 집합을 `HotColdCatalog.snapshotMethodNames`와 `assertEquals`하고, 로더 안의 모든 `jdbc.query`/`queryForObject`가 카탈로그 등재 메서드 안에 있을 것을 요구한다. 기대값은 `logic/.../memory/HotColdCatalog.kt:135-144` = **T1 하드**. ⇒ T2 6·7행 삭제, `BootstrapConfig.kt` **미추가**. 게이트는 `engine.v2` 신규 `@Configuration`의 `@ConditionalOnProperty`가 되어 **0A-b "bean 등록 게이트"의 정의와 처음으로 일치**한다 | 제안서 `:972-976`(4차 결론 철회) · `:999-1006`(가드 테스트 근거) · `:1050-1053`(게이트 역추적) |
| **MAJOR-B** R6 "T2 편집 0" 논거 반증 | **(b) v2 read를 `JdbcTemplate`으로 못 박음.** (a)·(c)를 기각한 근거는 "추가 T2 행"이 아니라 **v1 부팅 파괴** — `app/game-api/src/main/resources/application.yml:8-10`이 `spring.jpa.hibernate.ddl-auto: validate`이므로 `@EntityScan`에 v2 엔티티가 들어오면 Hibernate가 **v1 DB를 상대로 v2 표를 검증**하고 실패한다. 실측 인구조사도 채점자 주장대로였다 — `read/` 31파일 중 **23개**가 Spring Data, game-api main 전체에서 `JdbcTemplate` 사용 파일은 **4개** | 제안서 `:997-1010` |
| **MINOR-C** `TruncateContract` 비활성 이유 | **이유 정정을 넘어 T2 행 자체를 삭제했다.** 리포 전수 grep에서 **프로덕션 소비자 0**(참조는 `TruncateContractTest.kt`와 주석뿐). 게다가 `every baseline CREATE TABLE is classified` 테스트는 `baseline − classified = ∅`만 검사하고 역방향을 보지 않으므로 v2 항목을 더할 **필요도 이유도 없다** | 제안서 T2 삭제행 표 3행 |
| **UNKNOWN-5** v2 DataSource 배선 | **답을 썼다.** 서비스마다 신규 `@Configuration` 1개가 자체 Hikari 풀을 만들되 **`DataSource` 빈을 노출하지 않고 래퍼 타입만** 내보낸다 — 선례 `ReadBarrierDataSourceConfig.kt:23`(`@Configuration`)·`:10-11`(래퍼 `ReadBarrierJdbcTemplate`)·`:43-45`(`HikariDataSource` → 래퍼). `DataSource` 빈을 `@Primary` 없이 하나 더 노출하면 Boot 자동설정이 흔들려 v1이 깨지기 때문이다. 접속 정보는 `@Value` 기본값으로 읽어 **`application.yml` 무수정** | 제안서 `:1010`(R6 절 말미) |
| **채점자 UNKNOWN-6** "탈출 경로가 문서에 없다" | 두 건 모두 **탈출 경로를 문서에 썼다** — R1은 `engine.v2` 조건부 빈, R6는 `JdbcTemplate` 고정 | 위 두 항목 |
| **절차 재실행 요구** | 확장점이 아니라 **메커니즘 5종에서 역추적**한 결과를 §7.1-2 "개정 5차" 절에 전량 실었다(아래 §3) | 제안서 `:989-1073` |

---

## 2. 채점자 반박 — **없다. 4차 채점의 세 지적은 전부 옳았다**

MAJOR-A·MAJOR-B·MINOR-C 모두 파일을 열어 확인한 결과 지적이 정확했다. 다만 **MAJOR-A는 지적이 옳았으나 처방이 옳지 않았다** — 채점자가 준 (a)/(b) 어느 쪽도 성립하지 않으며 그 이유는 채점자가 보지 않은 세 번째 파일(`HotColdWorldCatalogGuardTest.kt`)에 있다. 이것은 반박이 아니라 **지적을 더 깊이 밀어붙인 결과**이므로 반박 항목에 넣지 않는다.

*(4차 채점이 "저자가 옳다"고 인정한 `UpdateNationLevel.kt:145-146` 건은 종결됐다. 이번 라운드에서 새로 다투는 것은 없다.)*

---

## 3. 메커니즘 역추적 결과 표 (요구된 산출물)

### 3-0. 선례 파일셋 복제 — P6 betting 채널은 실제로 몇 파일인가

`grep -rli "betting" --include="*.kt"`를 **main 소스셋**에 돌리면 **63개 파일**(app 30 / logic 15 / infra 6 / common 3 …). 개정 3·4차가 "P6 betting 선례"로 인용한 4개는 그 부분집합이었다. 그 목록에 있으면서 이 설계안이 **한 번도 이름을 적지 않은** 파일:

| 파일 | 무엇인가 | v2에 시사하는 것 |
|---|---|---|
| `app/game-engine/.../turn/RehydrateService.kt` | 재시작 재수화 (`:143-156` `information_schema` 존재 프로브) | 재시작-무손실 게이트(`OPENSAM-149`)가 v2 원장에도 필요 — **오픈 후 항목으로 명시**(R1~R6 범위 밖) |
| `app/game-engine/.../config/DaemonLoopConfig.kt` | 런타임 조립 (`:108`·`:269`·`:440`) | **T2 신설 4행** |
| `logic/.../memory/HotColdCatalog.kt` | 읽기 경계 카탈로그 — **`logic/` = T1 하드** | v2가 v1 로더/스캔 디렉터리를 건드리면 **T1 수정을 강요당한다** ⇒ 전면 회피 |
| `infra/.../read/BettingRepository.kt` · `persistence/NgBettingRowMapper.kt` | 신규 파일로 추가된 것 | v2도 동형 — 신규 파일은 편집이 아니다 |

### 3-1. R1~R6 × 메커니즘 (제안서 본문과 동일 표)

| 티켓 | 부팅 등록·조립점 | 게이트가 실제로 걸리는 지점 | DataSource·트랜잭션·마이그레이션 | wire·직렬화 | 아키텍처 테스트 충돌 |
|---|---|---|---|---|---|
| R1 | 신규 `@Configuration`(`engine.v2`) — 컴포넌트 스캔(`GameEngineApplication.kt:8`), **편집 0**. 소비 조립점 = `DaemonLoopConfig.kt:440`(→`TurnRunService`) | 그 `@Bean`의 `@ConditionalOnProperty`(0A-b, `01-backbone-micro.md:76`). off ⇒ 빈 없음 ⇒ SELECT·풀 없음 | v2 Hikari 래퍼(선례 `ReadBarrierDataSourceConfig.kt:23-45`). flush 호출점 `TurnRunService.kt:402-408`. 마이그레이션은 **0A-c** 분리 location(`01-backbone-micro.md:77`) — `db/migration`에 두면 v1 두 서비스가 v1 DB에 적용(`application.yml:14` ×2) | — | **`WorldSnapshotLoader`·`InMemoryTurnWorld` 경유 불가**(`HotColdWorldCatalogGuardTest`), 신규 파일은 `engine.v2`에만 |
| R2 | `EngineEventConfig.kt:79-81` 빈이 팩토리를 만드는 **유일 프로덕션 지점** | 이름 비충돌 + 0A-b | v2 원장 접근은 R1 store 경유(자체 SQL 없음) | — | `WorldActionContext.kt`는 `engine/world` = 스캔 대상 ⇒ v2 타입 이름이 `Repository`/`Reader`로 끝나면 **T1 카탈로그 수정을 강요당한다** |
| R3 | R2와 동일(소비자) | R2와 동일 | 동일 | — | 동일 |
| R4·R5 | `CommandWireMapper.kt:43,140-149` + `TurnDaemonCommandDispatcher.kt:326,397`. 컨트롤러·레지스트리는 신규 파일 + 컴포넌트 스캔 | 인테이크 코드 집합 확대(`:147`)는 v1 코드에 도달 불가 | 커맨드는 v1 `command_inbox` durable 경로를 그대로 탄다(원장 쓰기만 v2 싱크) | `WireJson.kt:11-16`에 **다형 등록 없음**(`classDiscriminator`만) ⇒ **U9 유효**. 반면 `TurnDaemonCommandWireTest`의 27종 검사는 `sealedSubclasses`가 아니라 **테스트 코퍼스**를 보므로 **v2 variant를 막지 않는다** | 핸들러를 `engine/intake`(스캔 대상)가 아니라 `engine.v2`에 둔다 |
| R6 | `GameApiApplication.kt:8` 컴포넌트 스캔(컨트롤러·`@Configuration`) | v2 `@Configuration`의 `@ConditionalOnProperty` | **`:9-10` JPA 화이트리스트가 진짜 등록점** ⇒ v2 read는 `JdbcTemplate`(`application.yml:8-10` `ddl-auto: validate`) | — | 없음 |

### 3-2. 역추적이 새로 세운 두 개의 구조적 사실

1. **"신규 파일 추가는 T1도 허용"이 무조건 참이 아니다.** `HotColdCatalog.runtimeSourceDirectories`(`:135-144`)의 8개 엔진 디렉터리 안에서는 **신규 파일도** v1 가드를 깬다 — `*Repository`/`*Reader` 필드를 갖고 메서드를 부르면 `runtimeCallKeys`/`runtimeCallCounts` `assertEquals`가, `JdbcTemplate`/`Connection`/`DataSource` 필드를 가지면 `runtimeDirectSqlBoundarySources` `assertEquals`가 깨지고, **기대값은 전부 T1 파일에 박혀 있다.** 대응: v2 엔진 클래스를 전부 `opensamguk.engine.v2` 한 패키지에 격리(그 디렉터리는 위 8개에도, `DaemonWriteGuard.writePathPackages` 4개에도 없다).
2. **`app/*/src/main/resources/**`가 §7.2 게이트 ②③의 사각지대다.** `spring.flyway.locations`·`ddl-auto`·`datasource.*`가 전부 거기 있고 한 줄이 v1 부팅을 깰 수 있는데 두 게이트 어디에도 걸리지 않는다. 게이트 **⑤를 신설**하고 "v2는 설정 리소스를 한 글자도 고치지 않는다"를 설계 제약으로 선언했다.

---

## 4. 설계가 실제로 바뀐 지점 (문구 정정과 구분)

1. **R1의 읽기 경로가 `InMemoryTurnWorld`에서 떨어져 나왔다.** v2 원장은 월드 스냅샷에 실리지 않고 `engine.v2`의 조건부 store가 자체 v2 DataSource로 읽는다. — 4차 설계의 핵심 전제(“데몬의 읽기 진리는 `InMemoryTurnWorld`”)를 v2에 한해 포기한 것이다.
2. **R1의 쓰기 경로가 `FlushPayload`/`JdbcFlushExecutor`에서 떨어져 나왔다.** v2 델타는 `DirtyState`/`ChangeRecorder`에 실리되 `FlushPayload`(v1 DB 계약)에는 타지 않고, `TurnRunService.flushWithGeneration`(`:402-408`)에서 별도 v2 싱크로 나간다. **부작용: v1·v2 커밋의 교차 원자성이 없다** → §11 U11 + R1 DoD의 멱등 UPSERT 요구.
3. **`WorldActionContext` 생성자를 넓히기로 했다.** 4차는 "넓히지 않는다"고 선언했는데, 넓히지 않으면 v2 store가 leaf까지 도달할 경로가 없다 — 선언과 설계가 어긋나 있었다. 후행 nullable 기본 파라미터(선례 `:111-114`에 4개)를 쓰므로 생성 4지점 중 3곳은 무편집이고, 값을 실제로 넘겨야 하는 `WorldEventContextFactory.kt:72` → `DaemonLoopConfig.kt:269` 한 줄기만 열린다.
4. **v2 마이그레이션 위치가 확정됐다.** `OPENSAM-35` **0A-c**(`01-backbone-micro.md:77`)를 소비한다. 신설 아님 — 이미 있던 확장점을 못 찾고 있었다.
5. **v2 신규 파일에 위치 제약이 생겼다.** 엔진은 `engine.v2`, game-api는 `gameapi.v2` + JPA 금지. 자유롭던 것에 규칙이 붙었다.
6. **게이트 ⑤(설정 리소스 무수정)가 신설됐다.**

---

## 5. 최종 수량

| 항목 | 4차 | 5차 |
|---|---|---|
| **T2 행 수** | 12 (편집 11 + 마이그레이션 1) | **10 (편집 9 + 마이그레이션 1)** |
| 그중 삭제 | — | **5** (`DatabaseHooks.kt` · `JdbcFlushExecutor.kt` · `TruncateContract.kt` · `InMemoryTurnWorld.kt` · `WorldSnapshotLoader.kt`) |
| 그중 신설 | — | **3** (`TurnRunService.kt` · `DaemonLoopConfig.kt` · `WorldEventContextFactory.kt`) |
| 채점자 지목 2파일 | — | **둘 다 미추가** — `BootstrapConfig.kt`는 경유하지 않게 설계를 바꿔서, `GameApiApplication.kt`는 JPA를 쓰지 않아서 |
| **오픈 경로** | 20 단일값 | **20 단일값 (불변)** — 티켓을 더하거나 빼지 않았다. 바뀐 것은 R1의 산출물 구성뿐 |
| §11 UNKNOWN | 9 | **11** (U10 부팅 순서 · U11 교차 DB 원자성 신설) |
| 게이트 | ①②③④ | **①②③④⑤** |

---

## 6. 잔여 UNKNOWN (신설 2건)

| # | UNKNOWN | 확인 방법 | 확인 실패 시 |
|---|---|---|---|
| U10 | **v2 마이그레이션·시드가 `SeedBootstrap` 부팅 순서와 어떻게 맞물리는가.** v1은 `WorldSnapshotLoader.kt:51-53`이 읽기 전에 `ensureSeeded`를 부르는 것으로 보장되는데, v2 원장은 그 경로를 경유하지 않으므로 **보장 밖**이다 | R1 착수 시 `@DependsOn`/`ApplicationRunner` 순서 실측 | v2 store를 **lazy 초기화**로 두면 순서 의존이 사라진다. **T2·수량 불변** |
| U11 | **v1 DB와 v2 DB에 걸친 flush의 원자성.** 두 커밋 사이 크래시 시 한쪽만 남는다 | 오픈 전 크래시-리플레이 관측 | ADR-LITE-018(별도 DB)의 **필연**이며 이 설계안이 만든 결함이 아니다. 완화 = 멱등 UPSERT + 턴 번호 재적용(R1 DoD) |

**U9는 유효하게 남는다** — `WireJson.kt:11-16`이 `serializersModule` 다형 등록을 하지 않아 파일 밖 최상위 서브클래스 등록 여부는 컴파일로만 확인된다. 다만 역추적으로 **하나는 해소됐다**: 코퍼스 테스트(`TurnDaemonCommandWireTest`)는 `sealedSubclasses`가 아니라 테스트 리소스를 검사하므로 v2 variant 추가를 막지 않는다.

---

## 7. 저자가 남긴 주의 (오케스트레이터 확인 필요)

1. 개정 3·4차와 동일 — `git status`에 이 세션이 손대지 않은 파일 수정이 다수 있고, 그중 `docs/superpowers/plans/2026-07-17-v2-ticket-backlog/README.md`·`product-spec.md`·`LEDGER.md`는 이 설계안이 인용하는 파일이다. **커밋 전 diff와 인용 줄번호 재검증 필요.**
2. 이번 세션이 수정한 파일은 `round3-proposal-city-guanxi.md` **하나**, 새로 만든 것은 이 기록 하나. 코드·골든·`docs/wiki/raw/**` 무수정, 커밋 없음.
3. **개정 5차의 새 경로는 소스 독해로 추론한 것이고 컴파일·부팅으로 확인한 것이 아니다.** `engine.v2` 단일 패키지 격리, v2 Hikari 래퍼, `TurnRunService` 후행 파라미터 — 셋 다 근거는 파일에 있으나 실행 확인은 R1 착수 시점이다. U10·U11이 그 추론의 두 구멍이며 자기채점 취약점 **0-bis**에 명시했다.
4. **이번 라운드에서 새로 배운 실패형을 기록한다** — 네 바퀴 동안 "빠뜨렸다"만 고쳤고 **"설계가 물리적으로 성립하지 않는다"는 가능성은 한 번도 검사하지 않았다.** 소스를 텍스트로 읽는 아키텍처 테스트가 파일을 봉인하고 있다는 사실은 어떤 확장점 추적으로도 나오지 않고, "이 파일을 고치면 무엇이 깨지나"를 **반대 방향에서** 물어야만 나온다. 개정 5차 규율로 제안서에 못 박았다.
