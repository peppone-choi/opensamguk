# S2 — 0A-b `V2_ENABLED` + `v2-sandbox` 동시 조건 빈 게이트 (실측)

- 티켓: OPENSAM-35 / 계획 `docs/superpowers/plans/2026-08-08-opensam-35-v2-0a-isolation-plan.md` §3 S2
- 일자: 2026-08-08 · 브랜치 `op-35-v2-0a`
- 선행: S0(env 오버라이드 PASS) · S1(v2 location = `classpath:db/migration_v2` 확정)
- 리포 최초의 `@ConditionalOnProperty` 도입 (계획 §0.4: 기존 사용처 0건)

---

## 1. 산출물 (전부 신규 파일)

| 경로 | 역할 |
|---|---|
| `infra/src/main/kotlin/opensamguk/infra/v2/V2SandboxGate.kt` | 이름 정본 상수(`PROPERTY`/`PROFILE`) + 마커 타입 `V2SandboxMarker` |
| `app/game-engine/src/main/kotlin/opensamguk/engine/v2/V2SandboxConfiguration.kt` | game-engine 조건부 `@Configuration` |
| `app/game-api/src/main/kotlin/opensamguk/gameapi/v2/V2SandboxConfiguration.kt` | game-api 조건부 `@Configuration` |
| `app/game-engine/src/test/kotlin/opensamguk/engine/v2/V2SandboxConfigurationTest.kt` | 4조합 + false + env 매핑 실측 (6 test) |
| `app/game-api/src/test/kotlin/opensamguk/gameapi/v2/V2SandboxConfigurationTest.kt` | 4조합 + false 실측 (5 test) |

기존 파일 수정·삭제 **0건**. `application.yml` 무수정, `application-v2-sandbox.yml` **미생성**(불필요 —
프로파일은 `SPRING_PROFILES_ACTIVE=v2-sandbox` env로만 활성화하며, 프로파일 전용 설정값이 아직 하나도 없다).

---

## 2. 4조합 판정 — 실측

### 실행 명령

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew \
  :app:game-engine:test --tests '*V2SandboxConfigurationTest' \
  :app:game-api:test    --tests '*V2SandboxConfigurationTest' \
  --rerun-tasks 2>&1 | tail -40
```

출력 tail: `BUILD SUCCESSFUL in 1m 24s` / `20 actionable tasks: 20 executed`.

### XML 판정 (exit code 아님)

```
app/game-engine/build/test-results/test/TEST-opensamguk.engine.v2.V2SandboxConfigurationTest.xml
  tests="6" skipped="0" failures="0" errors="0"
app/game-api/build/test-results/test/TEST-opensamguk.gameapi.v2.V2SandboxConfigurationTest.xml
  tests="5" skipped="0" failures="0" errors="0"
```

### 조합 표 (양 모듈 동일 결과)

| `V2_ENABLED` (`v2.enabled`) | 프로파일 `v2-sandbox` | 기대 v2 빈 | 실측 | 테스트 이름 |
|---|---|---|---|---|
| 미설정 | 미활성 | 0 | **0** | `neither condition - no v2 bean` |
| `true` | 미활성 | 0 | **0** | `property only - no v2 bean` |
| 미설정 | 활성 | 0 | **0** | `profile only - no v2 bean` |
| `true` | 활성 | 등록 | **1** | `both conditions - v2 bean registered` |
| `false` | 활성 | 0 | **0** | `property set to false with profile active - no v2 bean` |

game-engine 케이스 목록 (XML `<testcase>` 실측):

```
PASS neither condition - no v2 bean()
PASS profile only - no v2 bean()
PASS V2_ENABLED env var maps onto the gate property()
PASS property set to false with profile active - no v2 bean()
PASS property only - no v2 bean()
PASS both conditions - v2 bean registered()
```

측정 도구는 `ApplicationContextRunner`(DB 불필요). Testcontainers 풀 컨텍스트 실측은 **S4(0A-f)의 몫**이라
여기서 중복하지 않는다.

### 2-1. 비공허성(non-vacuity) 뮤테이션 프로브 2회

"전부 PASS"가 조건이 실제로 작동해서인지, 아니면 테스트가 아무것도 재지 않아서인지를 갈랐다.
프로브는 측정 후 원본으로 복원했다(`diff` = 동일 확인).

| 프로브 | 조작 | 결과 XML | 뒤집힌 케이스 |
|---|---|---|---|
| P-1 | engine config에서 `@Profile` 제거 | `tests="6" failures="1" errors="0"` | `property only - no v2 bean` → `expected: <0> but was: <1>` |
| P-2 | engine config에서 `@ConditionalOnProperty` 제거 | `tests="6" failures="2" errors="0"` | `profile only` + `property set to false with profile active` |

⇒ 두 조건이 **각각 독립적으로 게이트를 닫고 있음**이 실측됐다. 한쪽만 걸린 상태는 반드시 실패한다.

---

## 3. 배치 위치 결정 근거

### 3-1. 컴포넌트 스캔 범위 — 실제 확인 방법과 결과

`@SpringBootApplication`은 선언 클래스의 패키지를 스캔 루트로 삼는다. 세 앱의 선언 위치를 직접 읽었다:

| 앱 | 클래스 | 스캔 루트 |
|---|---|---|
| game-engine | `app/game-engine/src/main/kotlin/opensamguk/engine/GameEngineApplication.kt:8` | `opensamguk.engine.**` |
| game-api | `app/game-api/src/main/kotlin/opensamguk/gameapi/GameApiApplication.kt:8` | `opensamguk.gameapi.**` |
| gateway-api | `app/gateway-api/src/main/kotlin/opensamguk/gateway/GatewayApiApplication.kt:7` | `opensamguk.gateway.**` |

세 앱 모두 `@ComponentScan`을 따로 선언하지 않는다(`grep -rn "@ComponentScan" app/*/src/main/kotlin` = 0건).
`@EntityScan`/`@EnableJpaRepositories`의 `basePackages`에 `opensamguk.infra`가 들어 있으나 이는 **JPA
엔티티/리포지토리 전용**이며 일반 `@Configuration` 스캔과 무관하다.

**infra의 `@Configuration`은 컴포넌트 스캔으로 등록되지 않는다** — 실측 근거:
`infra/src/main/kotlin/opensamguk/infra/read/SideReadRepositoryConfiguration.kt:15`는 앱 쪽에서
명시적으로 `@Import`해야 등록된다
(`app/game-engine/.../config/SideReadWorldScopeConfiguration.kt:10`,
`app/game-api/.../config/SideReadWorldScopeConfiguration.kt:10`).
`META-INF/spring/**` 오토컨피그 등록 파일은 리포 전역 0건(`find . -path "*META-INF/spring*"`).

⇒ **조건부 `@Configuration`은 각 앱의 스캔 루트 안에 있어야 한다.** infra에 두면 기존 앱 파일에
`@Import`를 추가해야 하고, 그것은 T2 기존 파일 수정 = 하드 제약 위반이다.

### 3-2. 왜 game-engine과 game-api **양쪽**인가

`docs/loops/v2-planning-2026-07-12/round3-proposal-city-guanxi.md:1164`가 명시한다 — 엔진 측 v2 클래스는
`app/game-engine/.../v2/`, **game-api 측(v2 read 컨트롤러, v2 인테이크 컨트롤러, v2 `@Configuration`)은
`app/game-api/.../v2/`**. 즉 v2 빈은 두 앱 모두에 생긴다. 한쪽에만 게이트를 설치하면 다른 앱의 v2 빈이
production 컨텍스트에 무조건 등록되므로 0A-b가 성립하지 않는다.

gateway-api는 제외했다 — auth/profile 전용이고 어느 정본 문서에도 v2 빈 계획이 없다.

### 3-3. 왜 `v2` 하위 패키지인가

같은 근거 라인(`:1164`)이 지시한다: `HotColdCatalog.runtimeSourceDirectories`(`logic/.../HotColdCatalog.kt:151`)와
`DaemonWriteGuard.writePathPackages`(`app/game-engine/.../flush/DaemonWriteGuard.kt:29`) 어디에도
`opensamguk/engine/v2`가 없으므로 v1 가드 테스트를 물리적으로 건드리지 않는다.

### 3-4. 왜 마커 타입·상수는 infra인가

마커 **타입**은 스캔될 필요가 없다(조건부 `@Configuration`이 `@Bean`으로 직접 생성). 양 앱이 모두
`implementation(project(":infra"))`를 갖고 있어 공유 타입 자리로 쓸 수 있다
(`app/game-engine/build.gradle.kts`, `app/game-api/build.gradle.kts`).

프로퍼티 키·프로파일 이름을 `const val` 한 곳에 두는 이유: 두 앱에 문자열을 각각 적으면 **철자가 갈리는
순간 한쪽 게이트가 조용히 열린다.** 게이트의 정확성이 문자열 일치에 걸려 있으므로 중복을 만들지 않는다.
`infra/.../read/`만 스캔하는 `WorldScopedSideReadArchitectureTest`의 범위 밖이라 기존 infra 테스트에
영향이 없다(신규 패키지 `opensamguk/infra/v2/`).

---

## 4. env ↔ 프로퍼티 키 대응

| 컨테이너 env | Spring 프로퍼티 키 | 게이트에서의 쓰임 |
|---|---|---|
| `V2_ENABLED=true` | `v2.enabled` | `@ConditionalOnProperty(name = ["v2.enabled"], havingValue = "true")` |
| `SPRING_PROFILES_ACTIVE=v2-sandbox` | `spring.profiles.active` | `@Profile("v2-sandbox")` |

철자 정본: `docs/superpowers/plans/2026-07-17-v2-ticket-backlog/01-backbone-micro.md:76`
(round3 proposal `:457`·`:1034`·`:1096`가 인용) — `V2_ENABLED` + `v2-sandbox`.

Spring의 `SystemEnvironmentPropertySource`가 `_` → `.` 치환 + 소문자화로 relaxed binding을 수행한다.
**이 대응은 기억이 아니라 실측으로 고정했다** —
`V2_ENABLED env var maps onto the gate property()` 테스트가 실제 `SystemEnvironmentPropertySource`에
`{"V2_ENABLED": "true"}`를 넣고 빈 1개 등록을 확인한다(PASS).

## 5. `matchIfMissing` 처리

`@ConditionalOnProperty.matchIfMissing`의 **기본값은 `false`** = 프로퍼티 미설정이면 조건 불일치 =
빈 미등록. fail-safe 방향이 기본값과 일치한다.

그럼에도 **명시적으로 `matchIfMissing = false`를 적었다.** 기본값에 의존하면 나중에 누가 "정리"하다
`true`로 뒤집어도 diff가 자연스러워 보이기 때문이다. 게이트 방향은 load-bearing이라 코드에 드러낸다.
`havingValue = "true"`이므로 `v2.enabled=false`도 비활성이다(§2 표 5행 실측).

## 6. UNKNOWN (측정하지 않은 것 — 추측으로 메우지 않음)

1. **풀 Spring Boot 컨텍스트에서의 동작.** `ApplicationContextRunner`는 조건 평가만 잰다. 실제
   `@SpringBootTest` + Testcontainers 부팅에서 v2 빈 0개인지는 **S4(0A-f)에서 실측**한다. S2는 그
   전제(안정된 조회 타입 `V2SandboxMarker`)만 제공한다.
2. **실제 OS 환경변수 주입 경로.** §4의 relaxed binding은 `SystemEnvironmentPropertySource`를 직접
   넣어 쟀다. 컨테이너에서 `V2_ENABLED` env가 실제로 이 property source에 실리는지는 S5(v2 스택 env
   분리) 부팅에서 관측한다.
3. **프로파일 활성 방식 다양성.** `spring.profiles.active` 프로퍼티로만 쟀다.
   `spring.profiles.include`·`spring.config.activate.on-profile` 경유는 미측정.
4. **`v2.enabled`의 다른 truthy 표기.** `TRUE`/`1`/`yes` 등은 미측정. `havingValue = "true"`는
   대소문자 무시 비교를 하지만 이 리포에서 재지 않았다 — 운영 env는 소문자 `true`로 고정한다.
5. **gateway-api.** 게이트를 설치하지 않았다. v2 빈 계획이 문서에 없다는 근거뿐이고, 없음을 실측한
   테스트는 없다.
6. **v2 빈이 실제로 들어온 뒤의 동작.** 현재 게이트 안에는 마커 1개뿐이다. 실제 v2 store/핸들러가
   들어왔을 때의 의존성 주입(오토컨피그 `NamedParameterJdbcTemplate` 등)은 미측정 — 해당 티켓 소관.

## 7. 하드 제약 준수 (게이트 ②③⑤)

```
$ git diff --name-only --diff-filter=MD origin/main -- \
    logic/src/main/kotlin/ common/src/main/kotlin/ logic/src/test/resources/golden/
(빈 출력)

$ git diff --name-only --diff-filter=MD origin/main -- \
    'app/*/src/main/kotlin/' infra/src/main/kotlin/ infra/src/main/resources/db/migration/
(빈 출력)

$ git diff --name-only --diff-filter=MD origin/main -- \
    'app/*/src/main/resources/' infra/src/main/resources/
(빈 출력)
```

T1 수정·삭제 0건 · T2 기존 파일 수정 0건(사전선언 공집합 유지) · 설정 리소스 무수정.
커밋·푸시 없음. `.env*`·키·토큰 미접근.
