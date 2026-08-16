# OPENSAM-150 (R1) v2 도시 원장 — 독립 적대적 리뷰

Scope: app/game-engine/src/main/kotlin/opensamguk/engine (turn, flush, v2) · app/game-engine/src/test/kotlin/opensamguk/engine/v2 · infra/src/main/kotlin/opensamguk/infra/persistence · infra/src/main/resources/db/migration_v2 · docs/loops/opensam-150-v2-city-ledger-2026-08-16
Verdict: cleared

- 리뷰 대상: 브랜치 `op-150-v2-city-ledger-r1`, 작성자 커밋 `a8283c1b`, merge-base `d63f6fec`
- 리뷰어: 독립 레인 (구현자 아님). **작성자 보고를 근거로 쓰지 않고 전 항목을 직접 명령으로 재현했다.**
- 리뷰 중 발견 결함은 이 브랜치에서 직접 수정했다(§4). 동결 테스트 파일 무편집, `tools/agent-system/check.py` 무편집.

---

## 1. 작성자 주장 재현 결과

| # | 주장 | 판정 | 재현 근거 |
|---|---|---|---|
| A1 | 변경 9파일 전부 순수 추가, 삭제 0줄 | **PASS** | `git diff --stat d63f6fec..a8283c1b` = `9 files changed, 628 insertions(+)` — deletion 표기 0 |
| A2 | 게이트 ②(T1 잠금) 비어 있음 | **PASS (단, 기준선 주의)** | merge-base 기준 `git diff --name-only --diff-filter=MD d63f6fec -- logic/src/main/kotlin/ common/src/main/kotlin/ logic/src/test/resources/golden/ logic/src/test/kotlin/ common/src/test/kotlin/ infra/src/test/kotlin/ app/game-engine/src/test/kotlin/ app/game-api/src/test/kotlin/` → 빈 출력. **`origin/main` 기준으로는 비지 않는다** — 그 사이 머지된 #407/#408(레인 B·C)의 `logic/.../v2/**` 8파일이 섞여 나온다. 이 브랜치가 건드린 적 없는 파일이므로 거짓 위반이며, `docs/loops/opensam-35-v2-0a-2026-08-08/gate-f-adversarial-review.md:31`이 같은 함정을 이미 기록했다. **게이트는 merge-base 고정 또는 리베이스 후 실행해야 한다** |
| A3 | 게이트 ③(T2 선언 일치) 초과 0 | **PASS** | 설계안 §7.2의 pathspec `'app/*/src/main/kotlin/'`은 git wildmatch에서 **아무 파일도 매치하지 않는다**(디렉터리 접미사 `/` + 와일드카드). `'app/**/src/main/kotlin/**'`로 교정 실행 시 정확히 4파일: `DatabaseHooks.kt`, `ChangeRecorder.kt`, `DirtyState.kt`, `JdbcFlushExecutor.kt` = 티켓 T2 선언과 일치. **게이트 ③ 스크립트 자체의 결함**은 §5 M3로 보고 |
| A4 | 게이트 ⑤(설정 리소스 무수정) | **PASS** | `git diff --name-only --diff-filter=MD d63f6fec -- 'app/*/src/main/resources/' infra/src/main/resources/` → 빈 출력. `V901__v2_city_ledger.sql`은 신규(A)라 MD에 안 걸린다 |
| A5 | 이탈 ① — 네이밍 가드가 `V2*`를 v2 패키지 밖에서 실제로 막는다 | **PASS (mutation으로 실증)** | `infra/src/main/kotlin/opensamguk/infra/persistence/V2NamingProbeTmp.kt`에 `class V2NamingProbeTmp`를 임시 추가 → `V2NamingConventionGuardTest > V2-prefixed declarations live in an opensamguk v2 package() FAILED (V2NamingConventionGuardTest.kt:72)`. 파일 삭제 후 green 복귀. **추정이 아니라 실측이다** |
| A6 | 이탈 ② — `world_id integer`의 근거 | **PASS** | `infra/src/main/resources/db/migration/V1__baseline.sql:11` = `id serial PRIMARY KEY`; `common/src/main/kotlin/opensamguk/common/world/WorldId.kt:17` = `value class WorldId(val value: Int)`. `bigint` FK는 `integer` PK를 참조할 수 없다(타입 불일치) — 이탈이 아니라 **유일한 정답**이다 |
| A7 | 이탈 ③ — flush IT를 engine에 둔 이유 | **PASS (단, 대가 있음)** | `settings.gradle.kts` 의존 방향상 `:infra`는 `:app:game-engine`을 볼 수 없어 `ChangeRecorder` 체인을 infra 테스트에서 증명할 수 없다. 다만 `tools/agent-system/check.py`의 `BEHAVIOR_AREAS["infra/src"] = ("infra/src/test/",)` 매핑 때문에 `parity-evidence` ERROR가 뜬다 — 이 리뷰 문서가 `infra/src`를 명시해 닫는다(check.py 무수정) |
| A8 | T2 표 row 5 `DaemonLoopConfig.kt` 미접촉 | **PASS** | diff에 부재 |
| A9 | `historyRows` 무편집 | **PASS** | `git diff d63f6fec -- JdbcFlushExecutor.kt \| grep -c historyRows` = 0. `HotColdWorldCatalogGuardTest` 10/0/0 green으로 교차 확인 |
| A10 | 게이트 ① 5004 tests / 0 failures / 0 errors | **PASS (§6에 재측정치)** | 아래 §6 XML 집계 |

## 2. BLOCKER B1 분석의 정오 — **정확하다. 오히려 과소 진술이다**

- 단언 실재 확인: `app/game-engine/src/test/kotlin/opensamguk/engine/v2/V2ProductionContextBeanGateIT.kt:186-190`
  — 클래스 `V2BothConditionsBeanGateIT` 안에서 `assertEquals(setOf("v2SandboxConfiguration","v2SandboxMarker","v2ContentCatalog","v2CityCatalogAdapter"), byPackage.keys, ...)`.
  `byPackage`는 `:54-58`의 `v2PackageBeans()` = **타입 이름이 `opensamguk.`로 시작하고 `.v2.`를 포함하는 모든 빈**. `V2CityLedgerStore`(`opensamguk.engine.v2`)를 빈으로 올리면 무조건 걸린다.
- 동결 여부 확인: 그 파일은 `app/game-engine/src/test/kotlin/` 소속 = 게이트 ② pathspec에 포함 → **수정하면 T1 위반**. 작성자 판단 정확.
- **작성자가 놓친 부분(B1을 더 강하게 만든다)**: 막는 단언은 하나가 아니라 **둘**이다. 같은 파일 `:64`의 `assertNoV2Beans()`가 프로덕션 컨텍스트에서 `assertEquals(emptyMap(), v2PackageBeans())`를 요구하므로, 0A-b 게이트 **없이** 무조건 등록하는 우회로도 막힌다. 즉 "게이트를 안 붙이면 된다"는 탈출구가 없고, B1은 진짜 사람 결정 대기 사항이다. 이 사실을 `V2CityLedgerStore.kt` KDoc에 반영했다.
- 파일명/클래스명 혼동 정정: 클래스는 `V2BothConditionsBeanGateIT`, **파일은 `V2ProductionContextBeanGateIT.kt`**. 원 KDoc은 클래스명만 적어 파일을 찾기 어려웠다 — 경로를 KDoc에 명시했다.

## 3. 스텁·하드코딩·가짜 완료 스캔

- `TODO`/`FIXME`/`XXX`/`test.skip`/`.only`/`@Disabled`/`@Ignore`/`fail("not implemented")` — 이 브랜치의 9파일 전수 **0건**.
- `V2CityLedgerFlushIT`는 빈 껍데기가 아니다. Testcontainers `postgres:16-alpine` + 실제 Flyway(`classpath:db/migration,classpath:db/migration_v2`)로 **7/0/0 실행 확인**(Docker 가용 환경, skip 아님). 특히 케이스 5는 v2 step에서 NOT NULL을 강제 위반시켜 **이미 실행된 v1 `world_state` UPDATE의 롤백**을 관측한다 — 단일 트랜잭션 주장의 진짜 증명이다.
- 골든·기존 테스트 수정/약화 0건(게이트 ②). 조작된 상수·가짜 수치 없음(v2 원장은 PHP 오라클이 없는 v2 신규 표면이며, 패러티 값과 교차하지 않는다).

## 4. 발견하고 **수정한** 결함

### D-1 (MEDIUM, 잠재 로직) — `entries()`가 `city_id ASC`를 보장하지 못했다
`entries()`가 내부 `LinkedHashMap`을 `toMap()`으로 그대로 반환했다. 적재는 `ORDER BY city_id`지만 `adjust()`가 **아직 행이 없던 도시**를 처음 만지면 그 키가 맨 뒤에 append 되어 순서가 깨진다(예: 40, 60 적재 후 50을 만지면 40→60→50). 설계안 §8 R3은 공백지화 판정을 `city_id ASC` 순회로 못박았으므로, 이 상태로 R3를 얹으면 **도시 처리 순서가 데이터 이력에 의존**하게 된다 — CLAUDE.md의 삽입-순서 규칙이 겨냥하는 종류의 조용한 결함이다.

- 수정: `V2CityLedgerStore.kt` — `load(worldId).toSortedMap()` + 사유 KDoc.
- 회귀 테스트: `V2CityLedgerFlushIT > entries는 신규 도시를 만진 뒤에도 city_id 오름차순이다`.
- **mutation 검증**: `toSortedMap()` → `toMap()`으로 되돌리면 그 케이스만 FAILED(`7 tests completed, 1 failed`), 복원 시 green. 테스트가 실제로 이 결함을 잡는다.

### D-2 (MEDIUM, 아키텍처 회피) — v2 store의 런타임 읽기가 어떤 S5 카탈로그에도 없다
원 KDoc은 클래스 이름에서 `*Repository`/`*Reader` 접미사를 뺀 이유를 "**소비자(R2 `DaemonLoopConfig`)에서 가드를 깨므로**"라고 명시했다. 이것은 `HotColdWorldCatalogGuardTest`의 수신자-이름 탐지를 **이름으로 회피**한 것이다. 교차 확인: `logic/src/main/kotlin/opensamguk/logic/memory/HotColdCatalog.kt:151-160`의 `runtimeSourceDirectories` 8개에 `engine/v2`가 **없고**, `runtimeDirectSqlBoundaries`(`:162-181`)에도 이 파일이 없다. 즉 `V2CityLedgerStore.load()`의 `jdbc.query`는 **어떤 `assertEquals`에도 묶여 있지 않다** — 무제한 스캔이나 직접 쓰기가 들어와도 기존 가드는 침묵한다. (같은 이유로 `engine/v2`는 `DaemonWriteGuard.writePathPackages`(`DaemonWriteGuard.kt:29-34`) 밖이기도 하다. R1 코드가 `EntityManager`를 쓰지 않으므로 **현재 위반은 없다**.)

R1 시점의 실해는 0이다(빈도 아니고 턴 루프 배선도 없다). 그러나 R2가 이 store를 루프에 물리는 순간 카탈로그 밖 런타임 읽기가 된다.

- 수정(보완 통제): 신규 `app/game-engine/src/test/kotlin/opensamguk/engine/v2/V2CityLedgerReadBoundGuardTest.kt` 3케이스 —
  ① 이 파일의 SQL 리터럴은 **정확히 하나**이며 `SELECT`·`FROM v2_city_ledger`·`WHERE world_id = :world_id`·`ORDER BY city_id`를 갖고 `SELECT *`가 아니다,
  ② `jdbc.update`/`batchUpdate`/`execute` **0건**이고 쓰기는 `recorder.recordCityLedgerV2Upsert(`로만 나간다,
  ③ `engine/v2`가 아직 `HotColdCatalog`에 미등재라는 **사실 자체를 고정**한다(등재되면 이 테스트가 빨개지고, 그때 삭제하고 진짜 카탈로그 단언에 넘긴다).
- **R2 선행 조건(사람/후속 티켓)**: `HotColdCatalog.runtimeDirectSqlBoundaries`에 `V2CityLedgerStore.kt`를 등재할 것. `HotColdCatalog.kt`는 `logic/src/main/kotlin/` = **T1 동결 영역**이므로 R1 범위에서 손댈 수 없다 — 이것이 이 결함을 R1에서 완전히 닫지 못하는 유일한 이유다. KDoc에 그대로 적었다.

### D-3 (MINOR, 문서) — B1 참조가 파일을 특정하지 못했다
`V2BothConditionsBeanGateIT:186-190`은 클래스명 + 다른 파일의 줄번호 조합이라 grep으로 찾을 수 없다. KDoc에 전체 경로를 넣고, 막는 단언이 둘이라는 사실을 추가했다(§2).

## 5. 발견했으나 **수정하지 않은** 결함 — 사유 명시

### M1 (MINOR, 문서 drift) — `infra/src/main/resources/db/migration_v2/README.md` §5가 거짓이 됐다
그 §5는 "production `db/migration_v2/`에는 아직 SQL이 없다"고 단언하는데, 이 브랜치가 `V901__v2_city_ledger.sql`을 거기 넣었다. **고치지 않은 이유: 게이트 ⑤가 `infra/src/main/resources/` 전체에 `--diff-filter=MD`를 걸어 이 README 수정을 금지한다.** 즉 게이트 설계상 이 README는 **영원히 갱신 불가**이며 v2 leaf가 늘어날수록 drift가 커진다. 리뷰어가 게이트를 우회해 고치는 것은 "게이트 통과를 위해 검사기를 약화"와 동형이므로 하지 않았다.
**게이트 소유자(OPENSAM-35 후속) 결정 필요**: 게이트 ⑤ pathspec을 `'**/src/main/resources/**/*.yml'`(원래 의도인 설정 파일)로 좁히거나 README를 명시 예외로 둘 것. 그 전까지 정정 사실은 본 문서와 `docs/loops/opensam-150-v2-city-ledger-2026-08-16/r1-implementation-and-blockers.md`가 보유한다.

### M2 (MINOR, 관측) — flush 실패 후 store 캐시가 DB와 갈라진다
`adjust()`는 메모리를 먼저 움직이고 델타를 기록한다. flush가 롤백되면 DB는 되돌아가지만 store 캐시는 갱신된 값을 유지하며, `load()`는 `loadedWorldId`가 같으면 다시 읽지 않는다. 이는 v1 `InMemoryTurnWorld`와 **동일한 성질**이고(메모리가 진실, 실패 시 프로세스 재기동 → 재적재), R1은 루프 배선이 없어 현재 경로가 존재하지 않는다. R2가 배선할 때 `FlushRecoveryGate`와의 상호작용을 확인할 것. 지금 캐시 무효화를 넣는 것은 근거 없는 선제 복잡도라 하지 않았다.

### M3 (MINOR, 게이트 스크립트) — 설계안 §7.2 게이트 ③의 pathspec이 무효다
`git diff --name-only --diff-filter=MD <base> -- 'app/*/src/main/kotlin/'`은 git wildmatch에서 **항상 빈 출력**이다(§1 A3). 즉 게이트 ③의 절반(`app/` 쪽)이 **지금까지 아무것도 검사하지 않았다**. `docs/loops/opensam-35-v2-0a-2026-08-08/s6-gates-and-baseline.md:178-184`가 같은 변형들을 이미 실측해 두었다. 설계안 §7.2 코드블록은 T1/T2 정본 문서라 리뷰 레인이 고칠 대상이 아니므로 여기 보고한다. 교정형: `'app/**/src/main/kotlin/**'`.

### M4 (정보) — 게이트 기준선을 `origin/main`으로 잡으면 거짓 위반이 뜬다
§1 A2 참조. 이 브랜치 자체의 결함이 아니라 게이트 실행 절차의 문제다.

### B2 (범위 밖, 작성자 기록 유지) — v2 원장 초기 적재 경로 없음
R1은 빈 원장에서 출발한다. R2 소관이라는 작성자 판단에 동의한다. R1의 어떤 판정도 초기값에 의존하지 않음을 코드로 확인했다.

## 6. 게이트 재실행 (exit code 아님 — test XML 집계)

`JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :common:test :logic:test :infra:test :app:game-engine:test :app:game-api:test --rerun-tasks`

| 모듈 | tests | failures | errors | skipped |
|---|---|---|---|---|
| common | 232 | 0 | 0 | 0 |
| logic | 3179 | 0 | 0 | 0 |
| infra | 239 | 0 | 0 | 0 |
| app:game-engine | 848 | 0 | 0 | 1 |
| app:game-api | 510 | 0 | 0 | 0 |
| **합계** | **5008** | **0** | **0** | **1** |

game-engine이 작성자 보고(844)보다 4 늘어난 것은 이 리뷰가 추가한 회귀 테스트 4개(`V2CityLedgerReadBoundGuardTest` 3 + `V2CityLedgerFlushIT` 순서 케이스 1) 때문이다. Docker 가용 환경이라 v2 IT는 skip 없이 실행됐다.

`python3 tools/agent-system/check.py --strict --base origin/main` → **findings 0** (`check.py` 무수정).

## 7. 결론

R1 산출물은 **v1 패러티 표면을 구조적으로 건드리지 않는다**: 수정 4파일이 전부 순수 추가이고, v2 채널이 비면 flush step이 미진입하며(IT 케이스 6), 마이그레이션은 분리 location의 신규 파일이다. 이탈 3건은 전부 실측 근거가 있고, 그중 ①은 mutation으로 재현했으며 ②는 애초에 선택지가 없었다. B1은 실재하는 블로커이고 작성자 분석보다 오히려 강하다.

발견 결함 D-1(순서)·D-3(문서)는 이 브랜치에서 닫았고, D-2(카탈로그 회피)는 보완 통제 테스트로 막아두되 항구적 해법(카탈로그 등재)을 **R2의 선행 조건**으로 코드 KDoc과 이 문서에 명시했다. M1/M3은 이 브랜치가 아니라 게이트 정의 소유자의 결정 사항이다. 격리(quarantine) 항목은 없다.
