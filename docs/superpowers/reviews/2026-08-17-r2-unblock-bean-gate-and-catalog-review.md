# OPENSAM-184 · OPENSAM-189 — R2 차단 해제 (빈 게이트 allowlist + S5 카탈로그 등재)

Scope: app/game-engine/src/main/kotlin/opensamguk/engine (flush, v2) · app/game-engine/src/test/kotlin/opensamguk/engine/v2 · logic/src/main/kotlin/opensamguk/logic/memory · docs/superpowers/plans/2026-08-08-opensam-35-v2-0a-isolation-plan.md
Verdict: cleared

- 대상: 브랜치 `unblock-r2-bean-gate-and-catalog`, base `origin/main` = `2db5ea06`.
- 닫는 대상: `docs/superpowers/reviews/2026-08-16-opensam-150-v2-city-ledger-review.md` §2(BLOCKER B1) · §4 D-2.
- **이 문서의 성격을 정직하게 밝힌다:** 작성자(구현 레인) 측 **증거 기록**이다. 아래 판정은 전부
  실측 출력이지만, CLAUDE.md가 요구하는 **독립 에이전트 적대적 비평은 아직 없다.** 이 PR은 열어만
  두고 머지하지 않는다 — 독립 레인이 공격한 뒤에 머지 판단을 한다.
- R2(OPENSAM-151) 자체는 구현하지 않았다. `DaemonLoopConfig` 배선·v2 store 빈 등록·원장 초기 적재
  전부 범위 밖이다.

---

## 1. T-A (OPENSAM-184) — 빈 게이트를 리터럴 `assertEquals` → 명시 allowlist

**바꾼 것.** `app/game-engine/src/test/kotlin/opensamguk/engine/v2/V2ProductionContextBeanGateIT.kt`

- 신설 `internal val APPROVED_V2_BEAN_NAMES: Set<String>` — 승인된 v2 빈 이름이 모이는 **한 곳**.
- `V2BothConditionsBeanGateIT`(`:186-190`)의 리터럴 4개 `assertEquals`를
  `assertEquals(emptySet(), byPackage.keys - APPROVED_V2_BEAN_NAMES, ...)` 부분집합 단언으로 교체.
- 신설 `V2BeanAllowlistSelfCheckTest` — allowlist가 비거나 패턴/접두/패키지로 넓어지면 실패.
  컨테이너 불필요(Docker 없는 환경에서도 이 자기검증은 실행된다).
- **무수정:** `v2PackageBeans()`(`:54-58`) 스캔 로직, `assertNoV2Beans()`(`:64`)의
  `assertEquals(emptyMap(), v2PackageBeans())`. 프로덕션 컨텍스트의 v2 빈 0 요구는 allowlist와 무관하다 —
  즉 "0A-b 게이트를 안 붙이고 등록" 탈출구는 여전히 없다.

**설계 의도 보존 판정.** allowlist에 없는 v2 빈은 여전히 게이트에서 죽는다. 항목 추가는
소스 한 줄 편집 = PR 리뷰 지점이다. `assertEquals`가 `emptySet()`을 기대하므로 실패 메시지에
초과 빈 이름과 전체 빈 맵이 그대로 찍힌다.

### 증명 A1 — allowlist 밖 v2 빈은 게이트를 통과하지 못한다 (mutation)

임시로 `V2SandboxConfiguration`에 `@Bean fun v2MutationProbe(): V2MutationProbe` + 같은 패키지
`class V2MutationProbe`를 추가하고 실행:

```text
$ ./gradlew :app:game-engine:test --tests '*V2BothConditionsBeanGateIT*' --rerun-tasks
V2BothConditionsBeanGateIT > both conditions register the v2 beans() FAILED
2 tests completed, 1 failed
BUILD FAILED in 2m 55s

TEST-opensamguk.engine.v2.V2BothConditionsBeanGateIT.xml:
  tests="2" skipped="0" failures="1" errors="0"
  message="org.opentest4j.AssertionFailedError: v2 package beans outside APPROVED_V2_BEAN_NAMES —
   add the name there deliberately or drop the bean; all v2 beans:
   {v2SandboxConfiguration=opensamguk.engine.v2.V2SandboxConfiguration,
    v2MutationProbe=opensamguk.engine.v2.V2MutationProbe,
    v2SandboxMarker=opensamguk.infra.v2.V2SandboxMarker,
    v2ContentCatalog=opensamguk.infra.v2.V2ContentCatalog, v2CityCatalogAdapt…"
```

probe 제거 후 green 복귀는 §5 전체 게이트가 관측한다(Docker 가용 환경 = IT skip 아님).

### 증명 A2 — allowlist 무력화는 자기검증에서 죽는다 (mutation ×2)

```text
# ① setOf("v2*") — 패턴으로 넓히기
V2BeanAllowlistSelfCheckTest > allowlist names concrete v2 beans and never widens to a pattern() FAILED
message="AssertionFailedError: APPROVED_V2_BEAN_NAMES must hold literal bean names,
 not wildcards/prefixes/packages: 'v2*'"

# ② emptySet() — 비우기
V2BeanAllowlistSelfCheckTest > allowlist names concrete v2 beans and never widens to a pattern() FAILED
message="AssertionFailedError: an empty APPROVED_V2_BEAN_NAMES makes the ④ subset assertion vacuous"
```

둘 다 원복 후 green(§5).

---

## 2. T-B (OPENSAM-189) — `V2CityLedgerStore`를 S5 카탈로그에 등재

**바꾼 것.**

| 파일 | 변경 |
|---|---|
| `logic/src/main/kotlin/opensamguk/logic/memory/HotColdCatalog.kt` | `runtimeSourceDirectories`에 `app/game-engine/src/main/kotlin/opensamguk/engine/v2` 1줄 추가 · `runtimeDirectSqlBoundaries`에 `V2CityLedgerStore.kt` 경계 1건 추가. 기존 항목 무수정·무삭제 |
| `app/game-engine/src/main/kotlin/opensamguk/engine/flush/DaemonWriteGuard.kt` | `writePathPackages`에 `opensamguk/engine/v2` 추가 |
| `app/game-engine/src/test/kotlin/opensamguk/engine/v2/V2CityLedgerReadBoundGuardTest.kt` | 케이스 ③ 삭제, KDoc 갱신 |
| `V2CityLedgerStore.kt` · `V2SandboxConfiguration.kt` | 거짓이 된 KDoc 진술 정정(로직 무변경) |

**등재 값과 근거.**

```kotlin
DirectSqlBoundary(
    sourceFile = "app/game-engine/src/main/kotlin/opensamguk/engine/v2/V2CityLedgerStore.kt",
    relation = "v2_city_ledger",
    temperature = DataTemperature.QUERY_ONLY_COLD,
    boundary = AccessBoundary.BOOT_SNAPSHOT,
    bound = AccessBound.HOT_KEYSET,
    ordering = "world_id = :world_id exact, city_id ASC",
    followUp = "S5-T2 folds the v2 ledger into the boot snapshot loader once R2 wires it into the loop.",
)
```

- `temperature`는 **선택지가 없다** — `HotColdWorldCatalogGuardTest:208`이 모든 direct-SQL 경계에
  `QUERY_ONLY_COLD`를 강제한다. 의미상으로도 맞다: 이 SELECT는 월드당 lazy 1회이고 이후 접근은
  전부 메모리다(턴마다 도는 hot read가 아니다).
- `bound = HOT_KEYSET` — 결과가 월드 1개분 도시-키 집합이 되어 메모리 hot 상태가 된다. 형제
  `loadGameEnv`·`loadDiplomacy`와 같은 분류다.
- `boundary = BOOT_SNAPSHOT` — 첫 접근 1회 적재. `followUp`이 S5-T2에서 스냅샷 로더로 접는 후속을 명시
  (가드가 `"S5-T2"` 포함을 요구하기도 한다).

**`runtimeSourceDirectories`·`writePathPackages`도 추가한 이유 (판단 근거).**

- `runtimeSourceDirectories`: **경계 등재만으로는 이 파일 하나만 커버된다.** 디렉터리를 넣어야
  `engine/v2`에 새로 들어올 파일이 자동으로 스캔 대상이 되어, 미등재 JDBC 수신자나 미등재
  `*Repository`/`*Reader` 호출이 가드에서 죽는다. R2가 이 패키지를 키울 예정이므로 지금 넣는 게 맞다.
  실효성은 증명 B1이 보여준다(경계를 지웠을 때 **디렉터리 스캔이 파일을 찾아내** 가드가 빨개진다).
- `DaemonWriteGuard.writePathPackages`: `V2CityLedgerStore`는 `ChangeRecorder`에 델타를 기록해
  **데몬 쓰기 경로에 닿는다.** 그렇다면 JPA `EntityManager` 금지 불변식이 v1 패키지와 동일하게
  적용돼야 한다. 현재 위반 0(`DaemonNoEntityManagerTest` green)이고, 앞으로의 유입을 막는다.

### 증명 B1 — 등재가 실효적이다 (mutation)

`HotColdCatalog`에서 `V2CityLedgerStore` 경계 항목만 제거:

```text
$ ./gradlew :app:game-engine:test --tests '*HotColdWorldCatalogGuardTest*' --rerun-tasks
HotColdWorldCatalogGuardTest > direct SQL calls stay in cataloged cold boundaries() FAILED
10 tests completed, 1 failed
BUILD FAILED in 1m 45s
message="AssertionFailedError: expected: <[app/game-engine/.../turn/RehydrateService.kt,
 infra/.../JdbcFlushExecutor.kt]> but was: <[app/game-engine/.../turn/RehydrateService.kt, app/g…"
```

이 한 mutation이 **두 가지를 동시에 증명**한다. (i) 경계 등재가 없으면 가드가 즉시 빨개진다 =
등재가 장식이 아니다. (ii) `expected`에서 빠진 파일이 `was`에는 **discovered**로 들어 있다 —
그 발견 경로는 `runtimeSourceDirectories`의 `engine/v2`뿐이다(경계는 방금 지웠으므로).
즉 디렉터리 등재가 탐지원으로 실제 작동한다. 원복 후 green(§5).

### 증명 B2 — 무제한 스캔·직접 쓰기 주입 (mutation ×2)

```text
# ① "SELECT city_id, gold, rice, garrison FROM …" → "SELECT * FROM …"
V2CityLedgerReadBoundGuardTest > store의 유일한 SQL은 world-scoped 결정적 정렬 SELECT다() FAILED
tests="2" failures="1"
message="AssertionFailedError: 투영은 명시 컬럼이어야 한다(SELECT * 금지):
 SELECT * FROM v2_city_ledger WHERE world_id = :world_id ORDER BY city_id"

# ② load() 안에 jdbc.update("UPDATE v2_city_ledger SET gold = 0", MapSqlParameterSource()) 주입
V2CityLedgerReadBoundGuardTest > store는 절대 직접 쓰지 않는다 -- 쓰기는 ChangeRecorder 경유() FAILED
V2CityLedgerReadBoundGuardTest > store의 유일한 SQL은 world-scoped 결정적 정렬 SELECT다() FAILED
12 tests completed, 2 failed
```

②는 `HotColdWorldCatalogGuardTest`를 **같은 실행에 함께** 돌렸다: 12건 중 실패는 위 2건뿐 =
**카탈로그 가드 10건은 green**이었다. 직접 쓰기가 들어와도 카탈로그는 침묵한다는 실측이며,
이것이 케이스 ①②를 남긴 근거다(§3). 둘 다 원복 후 green(§5).

---

## 3. 삭제/유지한 테스트 케이스와 사유

| 케이스 | 처분 | 사유 |
|---|---|---|
| ③ `engine v2는 아직 S5 hot-cold 카탈로그 밖이다` | **삭제** | 등재로 의도대로 빨개졌다(선삭제가 아니라 **먼저 red를 관측한 뒤** 삭제). 원 KDoc이 "등재되면 이 테스트를 지우고 카탈로그 단언에 넘겨라 — 실패가 곧 좋은 소식인 유일한 케이스"라고 못박은 계획된 수명 종료다. 남기면 등재를 되돌리라는 압력이 되어 정확히 반대로 작동한다 |
| ① `유일한 SQL은 world-scoped 결정적 정렬 SELECT다` | **유지** | 카탈로그 가드는 "이 파일이 직접 SQL을 쓴다"는 *사실*만 등재와 대조하고 SQL **본문**은 보지 않는다. `SELECT *`·`WHERE` 제거는 카탈로그에서 green이다(증명 B2 ①이 이 가드에서만 빨개졌다) |
| ② `절대 직접 쓰지 않는다 — 쓰기는 ChangeRecorder 경유` | **유지** | `isDirectSqlMethod`는 `update`도 통과시키므로 카탈로그 관점에서 `jdbc.update`는 그냥 "카탈로그된 direct SQL"이다. one-daemon-write rule은 여기서만 단언된다(증명 B2 ②에서 카탈로그 10건 green) |

케이스 ③이 참조하던 `HotColdCatalog` import를 제거했다. `@Disabled`/skip/약화 0건.

---

## 4. 이름 회피 재검토 — **회피는 불필요하다. 이름은 되돌리지 않는다 (판단 + 근거)**

원 KDoc은 `*Repository`/`*Reader` 접미사를 뺀 이유를 "소비자(R2 `DaemonLoopConfig`)에서 가드를
깨므로"라고 적었다 = 수신자-이름 탐지의 **이름 회피**다. 세 가지를 실측했다.

**(a) 회피가 오늘 실제로 필요한가 — 아니다 (rename probe).**
클래스·파일·참조를 전부 `V2CityLedgerRepository`로 임시 개명하고 등재 경로도 새 파일명으로 맞춘 뒤:

```text
$ ./gradlew :app:game-engine:test --tests '*HotColdWorldCatalogGuardTest*' \
    --tests '*V2CityLedgerReadBoundGuardTest*' --rerun-tasks
BUILD SUCCESSFUL in 2m 49s
```

**개명해도 가드는 깨지지 않았다.** 원 KDoc의 "가드를 깬다"는 진술은 오늘 기준으로 **거짓**이다
(소비자가 아직 없으므로 당연하다). 회피의 사실적 근거가 없다는 뜻이다. 개명은 즉시 원복했다
(`git mv` + 역치환, 잔여 `V2CityLedgerRepository` 문자열 0건).

**(b) 그럼 왜 `Store`를 유지하는가 — 이름이 실체와 맞기 때문이다.**
이 클래스는 v1 `InMemoryTurnWorld`와 같은 **메모리 보유자**다: DB 읽기는 lazy 1회고 이후 모든
접근(`entry`/`entries`/`adjust`)은 메모리이며, 쓰기는 `ChangeRecorder` 델타로만 나간다. 조회
리포지터리가 아니다. `*Repository`로 개명하면 이 저장소에서 그 접미사가 뜻하는 것
(`infra/read/*Repository` = DB 조회 시임)과 **다른 것**을 그렇게 부르게 된다. 즉 (a)가 회피의
필요를 없앴으므로, 남은 판단 기준은 정확성뿐이고 정확한 이름은 `Store`다. **바꾼 것은 이름이 아니라
KDoc의 사유다** — "가드를 피하려고"를 지우고 실체 근거로 대체했다.

**(c) 남는 사실 하나(결함 아님, R2 유의사항).**
수신자-이름 탐지는 **이름 기반**이므로, R2가 `DaemonLoopConfig`에 이 store를 주입해도 자동으로는
잡히지 않는다. 그 호출들은 `HotColdCatalog.runtimeReadSeams`에 **손으로** 등재해야 한다. 이 사실을
`V2CityLedgerStore` KDoc에 명시했다. 이것은 "가드가 정당한 등재를 깬다"가 아니라 탐지기의 알려진
한계이며(`V2NamingConventionGuardTest` KDoc이 같은 종류의 한계를 이미 고지한다), 이번 티켓에서
가드를 고치는 것은 범위 밖이다.

---

## 5. 게이트 (exit code 아님 — test XML 집계)

`JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :common:test :logic:test :infra:test
:app:game-engine:test :app:game-api:test --rerun-tasks`

| 모듈 | tests | failures | errors | skipped |
|---|---|---|---|---|
| common | 232 | 0 | 0 | 0 |
| logic | 3227 | 0 | 0 | 0 |
| infra | 239 | 0 | 0 | 0 |
| app:game-engine | 848 | 0 | 0 | 1 |
| app:game-api | 510 | 0 | 0 | 0 |
| **합계** | **5056** | **0** | **0** | **1** |

`BUILD SUCCESSFUL in 17m 15s`. 위 숫자는 exit code가 아니라 각 모듈
`build/test-results/test/TEST-*.xml`의 `tests/failures/errors/skipped` 합계다.

game-engine 집계 내역: `V2CityLedgerReadBoundGuardTest` 3 → 2(케이스 ③ 삭제),
`V2BeanAllowlistSelfCheckTest` +1 → 순증감 0. Docker 가용 환경이라 v2 IT는 skip 없이 실행됐다.

`python3 tools/agent-system/check.py --strict --base origin/main` → **findings 0** (`check.py` 무수정).

---

## 6. 결론과 남은 것

- B1(빈 게이트)·D-2(카탈로그 밖 런타임 읽기) 둘 다 닫혔고, 각각 mutation 실측으로 실효성을 증명했다.
  R2는 이제 (i) allowlist에 빈 이름 한 줄, (ii) `runtimeReadSeams`에 소비자 호출 등재만 하면 된다.
- 동결(게이트 ②) 예외는 `docs/superpowers/plans/2026-08-08-opensam-35-v2-0a-isolation-plan.md` §4.0b에
  파일별 사유와 함께 승인 기록으로 남겼다. 그 5파일 밖 T1/T2 수정은 여전히 위반이다.
- **UNKNOWN:** 독립 에이전트 적대적 비평 결과(미실시). Docker 없는 CI 러너에서의 v2 IT 거동은
  이번에 측정하지 않았다(로컬은 Docker 가용).
- 골든 수정·생성 0건, 테스트 약화/`@Disabled` 0건, `tools/agent-system/check.py` 무수정,
  R2 배선 코드 0줄.
