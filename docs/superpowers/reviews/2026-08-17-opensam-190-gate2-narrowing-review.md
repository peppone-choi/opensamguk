# OPENSAM-190 — V2-0A 격리 게이트 ② 좁히기 (테스트 루트의 v2 디렉터리 제외)

Scope: `scripts/agent/v2-isolation-gate.sh` 게이트 ②에서 테스트 루트의 `**/v2/**` 디렉터리를 제외하고, 그 좁히기가 v1 패러티 동결을 하나도 놓치지 않음을 mutation 27종 × bash 3.2/5.3으로 실증한다.
Verdict: cleared

**판정 한 줄:** 좁힌다(narrow). v1 패러티 코어·골든·v1 가드 테스트는 전부 동결 유지이고, 보호를
잃는 것은 v2 티켓이 저작한 v2 소유 테스트 16파일뿐이며, 그 중 `V2ProductionContextBeanGateIT`는
동결이 오히려 격리 증명을 좀먹고 있었다.

**독립 비평 상태:** 이 문서는 **자기 측정 기록**이다. `cleared`는 §3의 mutation 27종 × bash 2판이
전부 AS-EXPECTED임을 뜻하며, 독립 에이전트/프로바이더의 cross-agent critique는 **아직 없다** —
PR 리뷰에서 받는다.

---

## 1. 문제

게이트 ②(T1)는 `logic|common|infra|app/*` 의 **모든 테스트 루트를 통째로** `--diff-filter=MD` 동결한다.
그 결과 v2 후속 티켓이 OPENSAM-35(#370)가 만든 **자기 v2 테스트를 수정할 구조적 방법이 없다.**
게이트 ⑤의 동형 결함(v2 소유 README 영구 갱신 불가)은 OPENSAM-188/PR #415가 이미 닫았다.

핵심은 단순한 불편이 아니다. **동결이 격리를 지키는 것이 아니라 좀먹는다:**
`V2ProductionContextBeanGateIT`의 `assertNoV2Beans()`는 "production 컨텍스트에 v2 빈 0개"를
**v2 빈 타입을 하나씩 열거해서** 증명한다. v2에 새 빈이 생길 때마다 이 테스트에 한 줄이 늘어야
증명이 유지되는데, 얼려 두면 그 줄을 못 넣는다 ⇒ v2가 자랄수록 0A-f 증명이 낡는다.

미머지 브랜치 `origin/codex/op-43-v2-0b-runtime`의 실제 diff가 그 증거다 — 이 브랜치의
`V2ProductionContextBeanGateIT` 편집은 게이트를 **약화가 아니라 강화**한다:

- `assertNoV2Beans()`에 `V2CityCatalogAdapter` 0개 단언 추가 (신규 v2 빈을 production 금지 목록에 등재)
- `assertTrue(byPackage.values.containsAll(...))` → `assertEquals(setOf(...), byPackage.keys)`
  (부분집합 단언 → **정확 집합** 단언, 초과 빈도 잡힌다)
- Flyway 격리 단언(`V1_FLYWAY_LOCATION` / `V900` 미적용) 신설

즉 게이트 ②는 지금 **격리 강화 커밋을 차단하고 있다.**

## 2. 좁히기

게이트 ②의 pathspec 끝에 네 줄을 더한다(다른 게이트는 무변경):

```
':(glob,exclude)logic/src/test/kotlin/**/v2/**'
':(glob,exclude)common/src/test/kotlin/**/v2/**'
':(glob,exclude)infra/src/test/kotlin/**/v2/**'
':(glob,exclude)app/*/src/test/kotlin/**/v2/**'
```

좁게 만든 세 가지 결정:

1. **디렉터리 세그먼트 `/v2/`만 본다** — 파일명이 아니다. `infra/.../persistence/`의
   `V26NpcLifecycleMigrationTest.kt`·`V28YearbookServerIdMigrationTest.kt`·
   `V29LogEntryYearMonthIndexMigrationTest.kt`·`V2BriefMigrationTest.kt`는 **Flyway 버전 번호**가
   이름에 든 v1 테스트다. 파일명 패턴으로 좁혔으면 이 넷이 조용히 풀렸을 것이다(§3-B에서 실증).
2. **`logic/src/main/kotlin/**`·`common/src/main/kotlin/**`·`logic/src/test/resources/golden/**`은
   제외 대상이 아니다.** v2 소유 **main** 소스(`logic/.../v2/evidence/`)도 T1 동결 유지다.
3. **이동 우회는 막힌다.** v1 테스트를 `/v2/` 디렉터리로 옮겨서 고치면 원경로가 `D`로 잡혀
   여전히 위반이다(§3-C에서 실증).

## 3. mutation 증명

절차: clean tree PASS → 파일 1개에 한 줄 추가 → 게이트 실행 → 복원. `bash 3.2.57`(macOS 기본)과
`bash 5.3.15` 양쪽에서 동일 결과. 판정 = 게이트 ② `VIOLATION` 출력 유무.

clean tree (bash 3.2 / 5.3 동일):

```
MB=67202e46...  REF=HEAD (67202e46)
PASS      ② T1 parity core + existing tests (테스트 루트의 v2 디렉터리 제외)
LIST      ③ T2 boundary edits
PASS      ⑤ configuration resources (README.md 제외)
PASS      C1 production compose + checker
GATE RESULT: PASS   exit=0
```

### A. 보호를 잃는 파일 — **전수 16개**, 전부 LOSS 확인

`git ls-files -- ':(glob)*/src/test/kotlin/**/v2/**'` 전량이다. 각 파일의 저작 커밋을
`git log --diff-filter=A`로 확인했고 **16/16이 v2 티켓 산출물**이다 — v1 패러티 내용은 0건.

| # | 파일 | 저작 커밋 |
|---|---|---|
| 1 | `app/game-api/src/test/kotlin/opensamguk/gameapi/v2/V2ProductionContextBeanGateIT.kt` | `e9cc3b31` OPENSAM-35 (#370) |
| 2 | `app/game-api/src/test/kotlin/opensamguk/gameapi/v2/V2SandboxConfigurationTest.kt` | `e9cc3b31` OPENSAM-35 (#370) |
| 3 | `app/game-engine/src/test/kotlin/opensamguk/engine/v2/V2CityLedgerFlushIT.kt` | `2db5ea06` OPENSAM-150 (#412) |
| 4 | `app/game-engine/src/test/kotlin/opensamguk/engine/v2/V2CityLedgerReadBoundGuardTest.kt` | `2db5ea06` OPENSAM-150 (#412) |
| 5 | `app/game-engine/src/test/kotlin/opensamguk/engine/v2/V2ContentCatalogBeanTest.kt` | `e9cc3b31` OPENSAM-35 (#370) |
| 6 | `app/game-engine/src/test/kotlin/opensamguk/engine/v2/V2FlywayIsolationIT.kt` | `90c442cb` OPENSAM-43 (#371) |
| 7 | `app/game-engine/src/test/kotlin/opensamguk/engine/v2/V2MigrationConventionTest.kt` | `90c442cb` OPENSAM-43 (#371) |
| 8 | `app/game-engine/src/test/kotlin/opensamguk/engine/v2/V2NamingConventionGuardTest.kt` | `e9cc3b31` OPENSAM-35 (#370) |
| 9 | `app/game-engine/src/test/kotlin/opensamguk/engine/v2/V2ProductionContextBeanGateIT.kt` | `e9cc3b31` OPENSAM-35 (#370) |
| 10 | `app/game-engine/src/test/kotlin/opensamguk/engine/v2/V2SandboxConfigurationTest.kt` | `e9cc3b31` OPENSAM-35 (#370) |
| 11 | `app/gateway-api/src/test/kotlin/opensamguk/gateway/v2/V2ProductionContextBeanGateIT.kt` | `e9cc3b31` OPENSAM-35 (#370) |
| 12 | `common/src/test/kotlin/opensamguk/common/wire/v2/V2WireContractTest.kt` | `90c442cb` OPENSAM-43 (#371) |
| 13 | `infra/src/test/kotlin/opensamguk/infra/v2/V2CityCatalogAdapterTest.kt` | `90c442cb` OPENSAM-43 (#371) |
| 14 | `infra/src/test/kotlin/opensamguk/infra/v2/V2ContentCatalogTest.kt` | `e9cc3b31` OPENSAM-35 (#370) |
| 15 | `logic/src/test/kotlin/opensamguk/logic/v2/evidence/EvidenceContractValidatorTest.kt` | `acbc7bff` OPENSAM-37 (#408) |
| 16 | `logic/src/test/kotlin/opensamguk/logic/v2/geo/GeoValidatorsTest.kt` | `bf3b6ce5` OPENSAM-36 (#407) |

**왜 잃어도 되는가.** 이 16개는 v1 패러티(RNG·라운딩·한글 로그·골든·flush)를 하나도 재지
않는다. 전부 v2 소유 계약(빈 등록 게이트·Flyway 격리·카탈로그 로더·wire 봉투·증거 계약·지리
validator)을 재며, 그 계약을 정의한 티켓과 같은 티켓 계열이 저작했다. 그리고 이 16개를 얼리면
§1이 보인 것처럼 격리 증명 자체가 성장할 수 없다. 남는 방어는 mechanical 게이트가 아니라 PR
리뷰이며, 이는 v2 소유 산출물에 대해 원래 적용되는 방어선이다.

실측(bash 3.2 / 5.3 동일, 16/16 AS-EXPECTED):

```
LOSS   LOSS   rc=0  AS-EXPECTED app/game-api/.../v2/V2ProductionContextBeanGateIT.kt
LOSS   LOSS   rc=0  AS-EXPECTED app/game-api/.../v2/V2SandboxConfigurationTest.kt
LOSS   LOSS   rc=0  AS-EXPECTED app/game-engine/.../v2/V2CityLedgerFlushIT.kt
LOSS   LOSS   rc=0  AS-EXPECTED app/game-engine/.../v2/V2CityLedgerReadBoundGuardTest.kt
LOSS   LOSS   rc=0  AS-EXPECTED app/game-engine/.../v2/V2ContentCatalogBeanTest.kt
LOSS   LOSS   rc=0  AS-EXPECTED app/game-engine/.../v2/V2FlywayIsolationIT.kt
LOSS   LOSS   rc=0  AS-EXPECTED app/game-engine/.../v2/V2MigrationConventionTest.kt
LOSS   LOSS   rc=0  AS-EXPECTED app/game-engine/.../v2/V2NamingConventionGuardTest.kt
LOSS   LOSS   rc=0  AS-EXPECTED app/game-engine/.../v2/V2ProductionContextBeanGateIT.kt
LOSS   LOSS   rc=0  AS-EXPECTED app/game-engine/.../v2/V2SandboxConfigurationTest.kt
LOSS   LOSS   rc=0  AS-EXPECTED app/gateway-api/.../v2/V2ProductionContextBeanGateIT.kt
LOSS   LOSS   rc=0  AS-EXPECTED common/.../wire/v2/V2WireContractTest.kt
LOSS   LOSS   rc=0  AS-EXPECTED infra/.../v2/V2CityCatalogAdapterTest.kt
LOSS   LOSS   rc=0  AS-EXPECTED infra/.../v2/V2ContentCatalogTest.kt
LOSS   LOSS   rc=0  AS-EXPECTED logic/.../v2/evidence/EvidenceContractValidatorTest.kt
LOSS   LOSS   rc=0  AS-EXPECTED logic/.../v2/geo/GeoValidatorsTest.kt
```

또한 좁히기는 **현재 파일 16개가 아니라 경로 집합**을 푼다 — 향후 저 네 루트의 `/v2/`
디렉터리에 생기는 테스트도 동결 대상이 아니다. 신규 파일은 이미 `--diff-filter=MD`로 허용돼
있었으므로 신규에 대한 구멍은 새로 생기지 않는다.

### B. 보호가 남는 것 — 10종 전부 KEPT (rc=1)

```
KEPT  KEPT  rc=1  AS-EXPECTED logic/src/main/kotlin/opensamguk/logic/util/PhpRound.kt
KEPT  KEPT  rc=1  AS-EXPECTED logic/src/main/kotlin/opensamguk/logic/v2/evidence/EvidenceContracts.kt
KEPT  KEPT  rc=1  AS-EXPECTED common/src/main/kotlin/opensamguk/common/rng/LiteHashDrbg.kt
KEPT  KEPT  rc=1  AS-EXPECTED logic/src/test/kotlin/opensamguk/logic/util/PhpRoundTest.kt
KEPT  KEPT  rc=1  AS-EXPECTED logic/src/test/kotlin/opensamguk/logic/stats/ActionPipelineIdentityTest.kt
KEPT  KEPT  rc=1  AS-EXPECTED logic/src/test/resources/golden/entrance/장수생성-fixtures.json
KEPT  KEPT  rc=1  AS-EXPECTED app/game-engine/src/test/kotlin/opensamguk/engine/flush/DaemonNoEntityManagerTest.kt
KEPT  KEPT  rc=1  AS-EXPECTED app/game-engine/src/test/kotlin/opensamguk/engine/flush/FlushPayloadConvergenceTest.kt
KEPT  KEPT  rc=1  AS-EXPECTED infra/src/test/kotlin/opensamguk/infra/persistence/V26NpcLifecycleMigrationTest.kt
KEPT  KEPT  rc=1  AS-EXPECTED infra/src/test/kotlin/opensamguk/infra/persistence/V2BriefMigrationTest.kt
```

(첫 실행에서는 `V28…`·`V29…`도 함께 KEPT로 재측정했다.) 요점 셋:
`EvidenceContracts.kt` — v2 소유라도 **main 소스는 동결 유지**.
`DaemonNoEntityManagerTest`·`FlushPayloadConvergenceTest` — §7.2가 "app 테스트 루트를 잠근다"는
근거로 삼은 v1 가드 테스트가 **그대로 잠겨 있다**.
`V26…`/`V2Brief…` — **Flyway 버전 이름 함정**이 파일명이 아닌 디렉터리 기준 덕에 걸리지 않았다.

### C. 이동 우회 — 여전히 위반

v1 가드 테스트를 `engine/flush/` → `engine/v2/`로 `git mv` 후 변조:

```
VIOLATION ② T1 parity core + existing tests (테스트 루트의 v2 디렉터리 제외)
  app/game-engine/src/test/kotlin/opensamguk/engine/flush/DaemonNoEntityManagerTest.kt
rc=1
```

원경로의 `D`가 `--diff-filter=MD`에 잡힌다. 우회 불가.

## 4. op-43 언블록 확인 (브랜치 무수정)

`origin/codex/op-43-v2-0b-runtime` (`debc9190`, merge-base `e9cc3b31`) 를 게이트에 걸었다.

**BEFORE — `origin/main`의 스크립트:**
```
VIOLATION ② T1 parity core + existing tests
  app/game-api/src/test/kotlin/opensamguk/gameapi/v2/V2ProductionContextBeanGateIT.kt
  app/game-api/src/test/kotlin/opensamguk/gameapi/v2/V2SandboxConfigurationTest.kt
  app/game-engine/src/test/kotlin/opensamguk/engine/v2/V2ContentCatalogBeanTest.kt
  app/game-engine/src/test/kotlin/opensamguk/engine/v2/V2ProductionContextBeanGateIT.kt
  app/game-engine/src/test/kotlin/opensamguk/engine/v2/V2SandboxConfigurationTest.kt
  infra/src/test/kotlin/opensamguk/infra/v2/V2ContentCatalogTest.kt
GATE RESULT: FAIL   exit=1
```

**AFTER — 좁힌 스크립트:**
```
PASS      ② T1 parity core + existing tests (테스트 루트의 v2 디렉터리 제외)
LIST      ③ T2 boundary edits (티켓 사전선언과 대조할 것 — 초과 = 위반)
  app/game-engine/src/main/kotlin/opensamguk/engine/v2/V2SandboxConfiguration.kt
  infra/src/main/kotlin/opensamguk/infra/v2/V2ContentCatalog.kt
PASS      ⑤ configuration resources (README.md 제외)
PASS      C1 production compose + checker
GATE RESULT: PASS   exit=0
```

게이트 ②·⑤·C1 전부 PASS. 게이트 ③의 두 줄은 **차단이 아니라 사전선언 대조 대상**이며,
OPENSAM-43 티켓 본문이 두 파일을 T2로 선언하면 규정대로 통과한다(③은 rc에 반영되지 않는다).

## 5. 남기는 UNKNOWN / 미처리

- **`logic|common/src/main/kotlin/**/v2/**`(v2 소유 main 소스)는 여전히 T1 하드 동결이다.**
  동형 결함이 잠재하지만 이를 막고 있다는 실측 사례를 아직 관측하지 못했으므로 좁히지 않았다.
  (`op-43`이 건드리는 v2 main은 `app/`·`infra/`뿐이라 게이트 ③ 경로로 처리된다.)
  실제 차단 사례가 나오면 별도 티켓에서 같은 절차로 판정한다 — 지금은 **UNKNOWN**.
- 좁혀진 16파일의 향후 편집을 기계적으로 검증하는 장치는 없다. 방어선은 PR 리뷰다.
