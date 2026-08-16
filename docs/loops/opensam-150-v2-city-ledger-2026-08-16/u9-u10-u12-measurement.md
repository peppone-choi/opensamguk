# OPENSAM-150 (R1) 착수 실측 — U9 · U10 · U12

- 티켓: OPENSAM-150 [R1] v2 도시 원장 기반
- 설계 정본: `docs/loops/v2-planning-2026-07-12/round3-proposal-city-guanxi.md` §11
- 측정일: 2026-08-16
- 브랜치: `op-150-v2-city-ledger-r1` (base `origin/main` @ `d63f6fec`)
- 규율: 추측 금지. 아래는 전부 실제 컴파일·실행 결과이며, 확인하지 못한 것은 UNKNOWN으로 남긴다.

---

## U9 — `@Serializable` sealed 서브클래스를 원 파일 밖 신규 파일에 둘 수 있는가

**판정: PASS.** 대안 (a)(v2 전용 wire sealed 타입) · (b)(`TurnDaemonCommand.kt` T1 예외 사람 승인)
**둘 다 불필요하다.** R4·R5는 같은 패키지의 **신규 파일**에 최상위 서브클래스를 선언하면 된다.

### 방법

`common/src/main/kotlin/opensamguk/common/wire/U9ProbeCommand.kt` (신규, throwaway)에 최상위 서브클래스
1개를 선언하고 — `TurnDaemonCommand.kt`는 **한 글자도 고치지 않았다** —

```kotlin
package opensamguk.common.wire

@Serializable
@SerialName("u9Probe")
data class U9ProbeCommand(val cityId: Int, val requestId: String? = null) : TurnDaemonCommand() {
    override val type: String get() = "u9Probe"
}
```

`common/src/test/kotlin/opensamguk/common/wire/U9ProbeRoundTripTest.kt` (신규, throwaway)에서
**부모 serializer로** 왕복시켰다(`WireJson.encodeToString(TurnDaemonCommand.serializer(), cmd)` →
`decodeFromString`). 부모 serializer를 쓴 것이 핵심이다 — 다형 디스패치가 성립해야 wire 경로가 성립한다.

```
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :common:test --tests '*U9ProbeRoundTripTest*' -i
```

### 원시 출력

```
    U9-PROBE-ENCODED={"type":"u9Probe","cityId":7}
    U9-PROBE-DECODED-OK=opensamguk.common.wire.U9ProbeCommand
BUILD SUCCESSFUL in 1m 19s
```

### 이 측정이 증명하는 것 / 증명하지 못하는 것

**증명한다**
1. sealed 계층의 서브클래스를 **원 파일 밖**에 두어도 컴파일된다(Kotlin sealed 규칙: 같은 패키지 + 같은
   컴파일 모듈이면 충분. `common` main이 한 모듈이다).
2. kotlinx.serialization 플러그인이 그 서브클래스를 **수동 등록 없이** 다형 계층에 편입한다.
3. `WireJson`의 `classDiscriminator`(`type`)에 `@SerialName` 값이 그대로 실린다 — 기존 74개 중첩 variant와
   동일한 wire 모양.

**증명하지 못한다**
1. **다른 모듈**(예: `app/game-engine`)에서의 선언 — Kotlin sealed는 같은 모듈을 요구하므로 **성립하지
   않을 것으로 예상되나 실측하지 않았다.** R4·R5의 v2 커맨드 variant는 `common` 모듈 안에 두어야 한다.
2. `TurnDaemonCommandResult`·`TurnDaemonEvent`·`RealtimeEvent` 계열 — 같은 메커니즘이지만 실측 대상은
   `TurnDaemonCommand` 하나다.

### 정리

프로브 파일 2개는 측정 후 **삭제했다**(`git status` clean 확인). 리포에 남지 않으며, 소비자인 R4가
착수할 때 이 문서의 절차를 그대로 재현하면 된다.

---

## U10 — v2 마이그레이션·시드와 부팅 순서의 맞물림

**판정: 순서 의존을 만들지 않는 쪽으로 닫았다 — `V2CityLedgerStore`는 lazy 초기화다.**
`@DependsOn`도 `ApplicationRunner` 순서도 선언하지 않는다. §11이 "확인 실패 시" 대안으로 적어 둔 바로
그 경로이며, 순서를 실측으로 고정하는 것보다 의존 자체를 제거하는 편이 싸다는 판단이다.

### 근거

- `V2CityLedgerStore.load()`는 생성자가 아니라 **첫 접근**에서 `SELECT`를 돌린다
  (`app/game-engine/src/main/kotlin/opensamguk/engine/v2/V2CityLedgerStore.kt`).
  Flyway·`SeedBootstrap`·`ScenarioSeedRunner`·`WorldSnapshotLoader` 중 어느 것이 언제 돌든,
  store가 처음 읽히는 시점에는 이미 컨텍스트가 기동을 마친 뒤다.
- 실측: `V2CityLedgerFlushIT`의 `store는 lazy 적재라 부팅 순서에 의존하지 않는다` 테스트가
  **행을 먼저 INSERT한 뒤 store를 생성**하고, 첫 접근이 그 행을 읽어오는 것을 확인한다(green).
- v2 프로세스가 자기 DB를 가리키므로 v1 시드와의 간섭이 없다는 §11의 확인은 이 티켓에서 바뀌지 않는다.

### UNKNOWN으로 남는 것

- v2 프로세스를 **실제로 부팅**해 `ScenarioSeedRunner`가 v2 DB에 시나리오를 심는 순서 — 측정하지 않았다.
  이 티켓의 산출물이 그 순서에 의존하지 않으므로 결론의 전제가 아니다. v2 원장의 **초기 적재**(도시별
  시작 금·병량·도시병사를 어디서 넣을 것인가)는 R2 이후의 문제이며 R1은 빈 원장에서 출발한다.

---

## U12 — `SPRING_FLYWAY_LOCATIONS` env 오버라이드로 0A-c location 추가

**판정: 이미 닫혀 있다(OPENSAM-35 0A-c가 main에 머지됨). 이 티켓에서 재실측했다.**

### 선행 근거 (재확인)

- `docs/loops/opensam-35-v2-0a-2026-08-08/u12-flyway-locations-measurement.md` — 실제 `java -jar` +
  OS 환경변수로 오버라이드가 동작함을 확인. **오버라이드는 추가가 아니라 치환**이므로 v1 location을
  반드시 함께 넣어야 한다.
- `infra/src/main/resources/db/migration_v2/README.md` — 운영 채택값은
  `SPRING_FLYWAY_LOCATIONS=classpath:db/migration,classpath:db/migration_v2` (sibling classpath 쌍).
  `db/migration/v2/` 같은 **하위** 경로는 Flyway 재귀 스캔 때문에 격리가 반대로 깨진다.

### 이 티켓의 재실측

R1이 그 location에 **첫 product leaf**를 넣었다(`V901__v2_city_ledger.sql`). 아래가 전부 green이다:

| 테스트 | tests/failures/errors | 무엇을 증명하나 |
|---|---|---|
| `V2MigrationConventionTest` | 17 / 0 / 0 | V901 파일명·forward-only 첫 줄·주석 밖 `world_id NOT NULL` |
| `V2BothConditionsBeanGateIT` | 2 / 0 / 0 | 실제 Spring Boot 컨텍스트 + env override로 두 location 해석, 적용된 v2 마이그레이션의 world-scope를 PostgreSQL 카탈로그로 검사 |
| `V2ProductionShapeBeanGateIT` | 2 / 0 / 0 | v1 기본 컨텍스트는 `classpath:db/migration`만 해석 — v2 표가 v1 DB에 생기지 않음(0A-e/0A-f) |
| `V2FlywayIsolationConstraintMutationIT` | 4 / 0 / 0 | world-scope 가드가 우회되지 않음 |
| `V2CityLedgerFlushIT` | 6 / 0 / 0 | sibling 쌍 location으로 실DB에 `v2_city_ledger`가 실제로 생기고 flush가 쓴다 |

### 설계안 §2.1과의 의도적 차이 1건 — `world_id` 타입

§2.1 스케치는 `world_id bigint`이나 **`integer`로 넣었다.** 근거:

- `world_state.id`는 `serial`(=integer) — `infra/src/main/resources/db/migration/V1__baseline.sql:11`.
- `WorldId.value`는 `Int` — `common/src/main/kotlin/opensamguk/common/world/WorldId.kt:17`.
- 0A-c 규약과 V900 probe가 `world_id integer NOT NULL REFERENCES world_state(id)`
  (`app/game-engine/src/test/resources/db/migration_v2/V900__v2_sandbox_probe.sql`).

`gold`/`rice`는 스케치대로 `bigint`, `garrison`은 `integer`다. 이 차이는 마이그레이션 파일 주석에도 적었다.
