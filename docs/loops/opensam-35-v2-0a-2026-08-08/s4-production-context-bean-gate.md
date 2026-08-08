# S4 — 0A-f production 컨텍스트 v2 빈 0개 **실측**

- 티켓: OPENSAM-35 / 계획 `docs/superpowers/plans/2026-08-08-opensam-35-v2-0a-isolation-plan.md` §3 S4
- 근거 요구: `.ai/decisions.md` ADR-LITE-021 (iii) "선언이 아니라 실측", 계획 §0.5
- 일시: 2026-08-08, 브랜치 `op-35-v2-0a`, 메인 워킹트리

## 1. 무엇을 새로 쟀나 (S2·S3-a와 층이 다른 이유)

| 층 | 도구 | 무엇을 재나 |
|---|---|---|
| S2·S3-a | `ApplicationContextRunner` | `@Profile`/`@ConditionalOnProperty` **조건 평가**만. DB·자동설정·컴포넌트 스캔 없음 |
| 기존 리포 아키텍처 테스트 | 클래스파일 상수풀 / 소스 정규식 | **정적 스캔**. 컨텍스트를 띄우지 않음 |
| **S4 (이 문서)** | `@SpringBootTest` + Testcontainers PostgreSQL | v1 프로세스와 **같은 모양의 컨텍스트를 실제로 부팅**하고 `getBeansOfType` + 전체 빈 정의 스캔으로 실측 |

S4는 S2의 중복이 아니다. S2가 재지 못하는 것 — 컴포넌트 스캔이 실제로 도는 상태에서, 전체 자동설정과
Flyway 마이그레이션이 끝난 진짜 컨텍스트에 v2 빈이 하나도 없는지 — 가 여기서 처음 측정된다.

## 2. 산출물

- `app/game-engine/src/test/kotlin/opensamguk/engine/v2/V2ProductionContextBeanGateIT.kt`
- `app/game-api/src/test/kotlin/opensamguk/gameapi/v2/V2ProductionContextBeanGateIT.kt`
- `app/gateway-api/src/test/kotlin/opensamguk/gateway/v2/V2ProductionContextBeanGateIT.kt` (S4 후속, §3.3)

셋 다 **신규 파일**. 기존 파일 수정 0건(§7 게이트 출력 참조). 파일당 4개 클래스 = 판정 매트릭스 4칸이며,
Testcontainers 관례는 기존 IT(`GameEngineApplicationTests`, `EmptyWorldBootIT`, `GameApiApplicationTests`)를
그대로 따랐다 — `@Testcontainers(disabledWithoutDocker = true)` + `@Container @JvmStatic` +
`@DynamicPropertySource`, 포트는 Testcontainers 랜덤 포트, `webEnvironment`는 기본값 `MOCK`(임베디드 서버
미기동 ⇒ 포트 점유 0, 다른 세션과 충돌 없음). 컨테이너는 JUnit 확장이 클래스 종료 시 stop 한다
(`TESTCONTAINERS_RYUK_DISABLED=true`가 `tasks.test`에 배선돼 있어 Ryuk에 의존하지 않는 편이 안전하다).

game-engine 컨텍스트에만 붙인 두 가지는 기존 관례를 승계한 것이다: security 자동설정 4종 제외
(game-api `:mainClassesForTest`의 transitive security 스타터가 **테스트 클래스패스**에만 올라오는 아티팩트,
`GameEngineApplicationTests`의 주석이 근거) + `opensamguk.daemon.enabled=false`(턴 루프가 Redis 스트림을
실제로 소비하는 것을 막음, 빈은 그대로 생성). 둘 다 v2 게이트와 무관한 축이다.

## 3. 판정 매트릭스 — 실측

두 앱 각각 4칸. 조회 대상은 `V2SandboxMarker` + `V2ContentCatalog`(S3-a가 S4에 넘긴 요구) + **패키지 기반 스캔**.

### game-engine

| # | 컨텍스트 | `V2SandboxMarker` | `V2ContentCatalog` | `opensamguk.*.v2.*` 패키지 빈 | 판정 |
|---|---|---|---|---|---|
| ① | production shape (env 미설정 · 프로파일 미활성) | 0 | 0 | **{}** | PASS |
| ② | `v2.enabled=true`만 | 0 | 0 | **{}** | PASS |
| ③ | 프로파일 `v2-sandbox`만 | 0 | 0 | **{}** | PASS |
| ④ | 둘 다 (**양성 대조군**) | 1 | 1 | `V2SandboxConfiguration` + `V2SandboxMarker` + `V2ContentCatalog` 포함 | PASS |

### game-api

| # | 컨텍스트 | `V2SandboxMarker` | `V2ContentCatalog` | `opensamguk.*.v2.*` 패키지 빈 | 판정 |
|---|---|---|---|---|---|
| ① | production shape | 0 | 0 | **{}** | PASS |
| ② | `v2.enabled=true`만 | 0 | 0 | **{}** | PASS |
| ③ | 프로파일 `v2-sandbox`만 | 0 | 0 | **{}** | PASS |
| ④ | 둘 다 (**양성 대조군**) | 1 | **0**(의도) | `V2SandboxConfiguration` + `V2SandboxMarker` 포함 | PASS |

### gateway-api (S4 후속 — 적대적 리뷰 fix-required 대응)

| # | 컨텍스트 | `V2SandboxMarker` | `V2ContentCatalog` | `opensamguk.*.v2.*` 패키지 빈 | 판정 |
|---|---|---|---|---|---|
| ① | production shape | 0 | 0 | **{}** | PASS |
| ② | `v2.enabled=true`만 | 0 | 0 | **{}** | PASS |
| ③ | 프로파일 `v2-sandbox`만 | 0 | 0 | **{}** | PASS |
| ④ | 둘 다 | 0 | 0 | **{}** | PASS |

**gateway-api에는 `V2SandboxConfiguration`을 두지 않는다.** 인증/프로필 서비스라 v2 마커 빈을 소비할
대상이 0건이고, 쓰지 않을 조건부 빈을 "일관성"만으로 세 번째 복사하는 것은 투기적 코드다. 0A가 실제로
요구하는 것은 설정의 대칭이 아니라 **"production 컨텍스트에 v2 빈이 0개"라는 증명**이므로 게이트(IT)만
가져왔다. 그래서 ④도 0이며, 이것이 gateway-api 표가 나머지 둘과 다른 유일한 지점이다.

④가 양성 대조군 역할을 못 하므로(켜도 등록될 v2 빈이 없다) "컨텍스트가 실제로 떴는지"는 네 칸 모두에서
`beanDefinitionNames`에 gateway 애플리케이션 빈이 존재하는지로 따로 확인한다. 컨텍스트는 기존
`GatewayApiApplicationTests`와 같은 방식(테스트 `application.yml`의 H2 + `flyway.enabled=false`)으로 띄운다 —
gateway-api에는 PostgreSQL 의존 컨텍스트 관례가 따로 없어 Testcontainers를 쓰지 않았고, 따라서 이 4칸은
Docker 없이도 `skipped="0"`으로 돈다.

### 3-way 커버 매트릭스

| 서비스 | `V2SandboxConfiguration` | production 컨텍스트 v2 빈 0 IT | compose `V2_ENABLED`/`SPRING_PROFILES_ACTIVE` |
|---|---|---|---|
| game-engine | 있음 | 있음 | 주입 |
| game-api | 있음 | 있음 | 주입 |
| gateway-api | **없음(의도)** | **있음** | v2 compose service **없음** — external shared v1 gateway 재사용 |

**S5 ADR-LITE-023 보정:** 초기 측정 때 존재하던 local v2 gateway-api와 그 v2 DB/Flyway 연결은
superseded됐다. 최종 `docker-compose.v2-sandbox.yml`은 gateway-api를 build·seed·기동하지 않고,
external shared network의 기존 v1 `gateway-api:8080`를 사용한다. 따라서 gateway에
`SPRING_FLYWAY_LOCATIONS`·`V2_ENABLED`·`SPRING_PROFILES_ACTIVE`를 주입하는 경로 자체가 없다.
이 문서의 gateway IT는 compose 서비스 존재가 아니라 동일 애플리케이션 이미지의 production-context
격리 규칙을 계속 검증한다.

game-api ④의 `V2ContentCatalog` 0은 결함이 아니라 S3-a의 결정이다 — game-api에 v2 콘텐츠 소비자가 0건이라
로더를 등록하지 않았다. "게이트가 열려도 여기엔 없다"를 테스트가 명시적으로 고정한다.

**양성 대조군의 역할**: ①~③의 0이 "게이트가 닫혀서 0"인지 "컨텍스트가 아예 안 떠서 0"인지를 가른다.
④가 같은 부팅 경로에서 실제 빈을 잡으므로 앞 3칸의 0은 살아 있는 컨텍스트에서 잰 0이다.

## 4. 패키지 기반 방어 — **가능. 넣었다.**

타입 이름 하드코딩만으로는 "새 v2 빈을 만들고 테스트 목록에 추가하지 않으면 게이트가 조용히 뚫린다"가
그대로 남는다. 그래서 타입 목록과 **독립적인** 두 번째 assertion을 넣었다:

```kotlin
internal fun ApplicationContext.v2PackageBeans(): Map<String, String> =
    beanDefinitionNames.mapNotNull { name ->
        val type = runCatching { getType(name, false) }.getOrNull()?.name ?: return@mapNotNull null
        if (type.startsWith("opensamguk.") && type.contains(".v2.")) name to type else null
    }.toMap()
```

- 실현 가능성 근거: `getType(name, allowFactoryBeanInit = false)`는 **빈을 만들지 않고** 타입만 해석한다.
  ①~③의 실측 결과가 `{}`이므로 조회 자체가 동작함이 확인됐고, ④에서 3개(engine)/2개(api)를 잡으므로
  탐지력도 확인됐다 — ④가 `V2SandboxConfiguration` **자신**까지 잡는다는 점이 스캔이 타입 목록보다
  넓게 본다는 증거다(설정 클래스는 하드코딩 목록의 조회 대상이 아니다).
- 오탐 위험 확인: 리포 전체에서 `opensamguk.*.v2.*` 패키지는 S2·S3-a가 만든 3개 소스뿐이다
  (`grep -rn --include='*.kt' '^package .*\.v2' app common infra logic`). v1 코드에 `.v2.` 패키지는 없다.
  코드에 있는 다른 "V2"는 계획 §0.1이 정리한 대로 전부 스키마 세대 문자열이라 **패키지명이 아니다.**
- 남는 한계(정직하게): 이 스캔은 **패키지 명명 규약**에 의존한다. 누가 v2 빈을 `opensamguk.engine.ledger`
  같은 v2 아닌 패키지에 만들면 잡지 못한다. 그 경우의 방어는 S2가 고정한 규약 1
  ("v2 빈은 전부 `V2SandboxConfiguration` 안 `@Bean`")과 리뷰다. 자동 판정 대상 아님.

  ⚠️ **규약 미준수 시 이 게이트는 조용히 무력화된다** (GATE-f Q4). `@Component class V2CityLedgerStore`를
  `opensamguk.engine.ledger`에 두면 하드코딩 타입 목록(①②③)과 패키지 스캔(④)을 **둘 다** 빠져나가
  게이트가 초록으로 통과한다 — 실패하지 않고, 경고도 없다. 즉 이 테스트가 재는 것은
  "v2 코드가 `opensamguk.*.v2.*`에 있다는 전제 하에서의 누출"이지 "v2 누출 일반"이 아니다.
  이름 휴리스틱 탐지(타입명이 `V2`로 시작하면 실패 등)는 오탐만 늘리므로 넣지 않았다.
  대신 **패키지 규약을 계획 §4-2에 소비자 티켓(OPENSAM-150/151)의 준수 조건으로 명문화**했다.

## 5. 비공허성 검증 — 뮤테이션 프로브 3회

| 프로브 | 뮤테이션 | 기대 | 실측 |
|---|---|---|---|
| **P-1** | game-engine `V2SandboxConfiguration`의 `@Profile` 제거 | ② property only 실패 | `4 tests completed, 1 failed` — `V2PropertyOnlyBeanGateIT` `failures="1"`, 메시지 `expected: <0> but was: <1>`. 나머지 3칸은 `failures="0"` |
| **P-2** | game-api `V2SandboxConfiguration`의 `@ConditionalOnProperty` 제거 | ③ profile only 실패 | `4 tests completed, 1 failed` — `V2ProfileOnlyBeanGateIT` `failures="1"` |
| **P-3** | game-engine `opensamguk.engine.v2`에 **게이트 밖** `@Component class V2ProbeLeakedComponent` 추가 (타입 목록에 없는 새 타입) | ①②③ 전부 실패 — **패키지 스캔만이** 잡을 수 있는 누출 | `4 tests completed, 3 failed`. 실패 메시지에 `v2ProbeLeakedComponent=opensamguk.engine.v2.V2ProbeLeakedComponent` |

| **P-4** (gateway-api) | 테스트 소스에 `@Component class ProbeV2Leak`을 `opensamguk.gateway.v2.probe`에 추가 | ① production shape 실패 | `V2ProductionShapeBeanGateIT > gateway-api registers no v2 bean() FAILED`, XML 메시지 `expected: <{}> but was: <{probeV2Leak=opensamguk.gateway.v2.probe.ProbeV2Leak}>`. 프로브 삭제 후 4칸 전부 `failures="0"`, `git status --short`에 흔적 없음 |

P-1·P-2는 두 조건이 **각각 독립적으로** 게이트를 닫고 있음을, P-3은 **패키지 기반 방어가 공허하지 않음**을
보인다(하드코딩 타입 3종은 전부 0이었는데도 실패했다 — 즉 그 실패는 패키지 스캔이 만든 것이다).

프로브 복원 확인:

- P-1 / P-2: `diff <파일> <백업>` **출력 없음**(동일).
- P-3: 프로브 파일 삭제 후 `git status --short`에 흔적 없음(§7).
- 복원 후 두 모듈 v2 테스트 전량 재실행 `BUILD SUCCESSFUL` (§6).

## 6. 테스트 XML 원문 (복원 후 최종, `--rerun-tasks`)

명령:

```
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-engine:test :app:game-api:test \
  --tests 'opensamguk.*.v2.*' --rerun-tasks
```

`BUILD SUCCESSFUL in 4m 29s`.

```
<testsuite name="opensamguk.engine.v2.V2ProductionShapeBeanGateIT"  tests="1" skipped="0" failures="0" errors="0" time="1.085">
<testsuite name="opensamguk.engine.v2.V2PropertyOnlyBeanGateIT"     tests="1" skipped="0" failures="0" errors="0" time="0.023">
<testsuite name="opensamguk.engine.v2.V2ProfileOnlyBeanGateIT"      tests="1" skipped="0" failures="0" errors="0" time="0.044">
<testsuite name="opensamguk.engine.v2.V2BothConditionsBeanGateIT"   tests="1" skipped="0" failures="0" errors="0" time="0.053">
<testsuite name="opensamguk.gameapi.v2.V2ProductionShapeBeanGateIT" tests="1" skipped="0" failures="0" errors="0" time="0.041">
<testsuite name="opensamguk.gameapi.v2.V2PropertyOnlyBeanGateIT"    tests="1" skipped="0" failures="0" errors="0" time="0.031">
<testsuite name="opensamguk.gameapi.v2.V2ProfileOnlyBeanGateIT"     tests="1" skipped="0" failures="0" errors="0" time="0.853">
<testsuite name="opensamguk.gameapi.v2.V2BothConditionsBeanGateIT"  tests="1" skipped="0" failures="0" errors="0" time="0.053">
```

gateway-api(S4 후속, 프로브 P-4 제거 후 재실행):

```
$ JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:gateway-api:test --tests '*V2*' --rerun-tasks
BUILD SUCCESSFUL in 2m 15s
13 actionable tasks: 13 executed

<testsuite name="opensamguk.gateway.v2.V2ProductionShapeBeanGateIT" tests="1" skipped="0" failures="0" errors="0" time="1.478">
<testsuite name="opensamguk.gateway.v2.V2PropertyOnlyBeanGateIT"    tests="1" skipped="0" failures="0" errors="0" time="0.038">
<testsuite name="opensamguk.gateway.v2.V2ProfileOnlyBeanGateIT"     tests="1" skipped="0" failures="0" errors="0" time="0.05">
<testsuite name="opensamguk.gateway.v2.V2BothConditionsBeanGateIT"  tests="1" skipped="0" failures="0" errors="0" time="0.048">
```

compose 문법 점검: `docker compose -f docker-compose.v2-sandbox.yml config -q`는
`required variable V2_JWT_SECRET is missing a value`로 중단한다 — 환경변수 미설정 시 **fail-closed**로
설계한 `${...:?}` 가드가 의도대로 동작하는 것이며, 문법 오류가 아니다(파서가 보간 단계까지 진행했다).

(같은 실행에서 S2·S3-a 회귀도 함께 green: `V2SandboxConfigurationTest` engine `tests="6"` / api `tests="5"`,
`V2ContentCatalogBeanTest tests="3"`, 전부 `skipped="0" failures="0" errors="0"`.)

**`skipped="0"`이 8칸 전부에 찍혀 있다** — Docker가 실제로 떠 있는 상태에서 8개 컨텍스트가 모두 부팅됐다는
뜻이다. skip된 결과는 증거가 아니라는 요구를 충족한다.

## 7. Docker 실행 환경 · 정리

- Docker Engine `29.3.1` (macOS), `DOCKER_CONTEXT=default` · `api.version=1.44` ·
  `TESTCONTAINERS_RYUK_DISABLED=true` — 전부 각 모듈 `build.gradle.kts`의 `tasks.test`에 이미 배선된 값
  (`app/game-engine/build.gradle.kts:152-161`, `app/game-api/build.gradle.kts:50-56`). 새로 발명한 설정 0건.
- 이미지 `postgres:16-alpine`(기존 IT와 동일), 포트 고정 없음(Testcontainers 랜덤 매핑).
- 실행 후 `docker ps` 출력 **없음** — 이 세션이 띄운 컨테이너 잔여 0. 병렬 세션 컨테이너에는 손대지 않았다.

게이트 명령 출력(최종):

```
$ git status --short
 M .ai/current-state.md
 M .ai/ownership.md
 M .ai/task.md
 M web/game/middleware.ts
?? app/game-api/src/main/kotlin/opensamguk/gameapi/v2/
?? app/game-api/src/test/kotlin/opensamguk/gameapi/v2/
?? app/game-engine/src/main/kotlin/opensamguk/engine/v2/
?? app/game-engine/src/test/kotlin/opensamguk/engine/v2/
?? docs/loops/opensam-35-v2-0a-2026-08-08/
?? docs/superpowers/plans/2026-08-08-opensam-35-v2-0a-isolation-plan.md
?? infra/src/main/kotlin/opensamguk/infra/v2/
?? infra/src/main/resources/content/
?? infra/src/main/resources/db/migration_v2/
?? infra/src/test/kotlin/opensamguk/infra/v2/
?? infra/src/test/resources/v2-catalog-fixture/
?? web/game/__tests__/v2-lab-route.test.tsx
?? web/game/app/game/v2-lab/

$ git diff --name-only --diff-filter=MD origin/main -- logic/src/main/kotlin/ common/src/main/kotlin/ logic/src/test/resources/golden/
(빈 출력)

$ git diff --name-only --diff-filter=MD origin/main -- 'app/*/src/main/kotlin/' infra/src/main/kotlin/ infra/src/main/resources/db/migration/
(빈 출력)

$ git diff --name-only --diff-filter=MD origin/main -- 'app/*/src/main/resources/' infra/src/main/resources/
(빈 출력)
```

`M web/game/middleware.ts`는 S3-b 소유(승인된 변경)이며 S4는 `web/game/**`을 건드리지 않았다.
`.ai/*` 3건은 세션 상태 파일로 다른 세션 소관이다.

> **[2026-08-08 사후 정정 — 위 `:217`·`:220` 두 명령은 무효다]**
> `'app/*/src/main/kotlin/'` 형태의 pathspec은 git 2.50.1에서 **매치 0건**이라
> 두 줄의 "(빈 출력)"은 **공허하게 참**이었다. 기준선 `origin/main`도 분기 후
> 전진해 있어 이 티켓 귀속 판정에 쓸 수 없다. 원문은 결함 이력 보존을 위해
> 지우지 않는다. 유효한 판정은 `:(glob).../**` + merge-base로 재측정한
> `s6-gates-and-baseline.md` §12이며, 명령 정본은 계획서 §4-1이다.
> `:214`(T1)는 와일드카드가 없어 이 결함에 해당하지 않는다.

## 8. UNKNOWN (측정하지 않은 것 — 추정 금지)

1. **컨테이너/운영 배포에서의 동일성.** 여기서 잰 것은 테스트 JVM이 띄운 컨텍스트다. 실제 이미지가
   `SPRING_PROFILES_ACTIVE`·`V2_ENABLED` 없이 뜰 때의 빈 목록은 S5(compose env 분리)가 재야 한다.
2. **아직 존재하지 않는 v2 빈.** 현재 v2 빈은 마커 1 + 로더 1뿐이다. 앞으로 들어올 v2 원장 store·핸들러가
   같은 게이트 안에 들어오는지는 규약(S2 규약 1)과 리뷰의 몫이며, 이 테스트는 **패키지 규약을 지키는 한**
   자동으로 덮는다(§4의 한계 참조).
3. ~~**gateway-api.**~~ **해소됨** — S4 후속에서 gateway-api에도 게이트 IT를 추가해 3개 JVM 서비스를 모두
   덮었다(§3.3, §3.4). 남는 UNKNOWN은 하나: gateway-api 컨텍스트는 H2 위에서 쟀으므로 PostgreSQL 위
   부팅에서의 빈 목록은 재지 않았다(v2 빈 등록은 DB와 무관하나 측정하지 않은 것은 측정하지 않은 것이다).
4. **동시 실행 간섭.** 다른 세션이 같은 Docker 데몬을 쓰는 중이었다. 포트 고정을 피하고 컨테이너를
   클래스 종료 시 stop 하도록 했으나, 데몬 부하로 인한 타이밍 영향은 재지 않았다.
5. **security 자동설정 제외의 영향 범위.** engine 컨텍스트에서 4종을 제외한 것은 기존 IT 관례의 승계다.
   제외하지 않은 컨텍스트에서 v2 빈 수가 달라질 이유는 없으나 그 조합은 측정하지 않았다.
