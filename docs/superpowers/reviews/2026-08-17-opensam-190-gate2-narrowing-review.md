# OPENSAM-190 — V2-0A 격리 게이트 ② 좁히기 (테스트 루트의 v2 디렉터리 제외) — 독립 비평

Scope: `scripts/agent/v2-isolation-gate.sh` 게이트 ②의 테스트 루트 `**/v2/**` 제외와 그에 맞춘 정본 문서(`docs/superpowers/plans/2026-08-08-opensam-35-v2-0a-isolation-plan.md`, `docs/loops/v2-planning-2026-07-12/round3-proposal-city-guanxi.md`) 개정을, 보호 상실 범위·우회 가능성·타 게이트 잔존·bash 3.2/5.3 동형성 기준으로 독립 재측정한다.
Verdict: cleared

**이 문서는 독립 비평이다.** 구현 레인(`gate2-narrowing-190`)이 아닌 **별도 리뷰 레인의 Claude
Opus critic 에이전트**가 자체 워크트리에서 PR head `1e58cdcb`를 체크아웃해 작성했다. 구현 레인이
남긴 자기측정 기록을 **대체**한다. 아래 증거는 레인의 주장을 인용한 것이 **아니라** 전량 재실행한
실측이며, 레인 주장과 어긋난 항목은 그대로 적었다(§7에 대조표).

**판정 한 줄:** `cleared`. 보호를 잃는 것은 v2 티켓이 저작한 v2 소유 테스트 **16파일뿐**(저작 커밋
16/16 재확인)이고, v1 패러티 코어·골든·v1 가드 테스트는 **동결 유지**이며, 이동 우회는 오히려
좁히기 **이후에** 막힌다(§3-C — 좁히기 전에는 이름변경 탐지로 뚫렸다). 다만 **차단 사유는 아니나
정본에 남겨야 할 잔존 리스크 2건**을 §6에 기록한다.

---

## 1. 무엇을 바꾸는가

게이트 ②(T1)의 pathspec 끝에 `:(glob,exclude)` 4줄을 더해 네 모듈 **테스트 루트**의 `**/v2/**`
디렉터리를 동결에서 뺀다. **보호를 의도적으로 제거하는 변경**이므로 비평의 초점은 "편했는가"가
아니라 "제거 범위가 정확한가 / 우회가 열렸는가"다.

레인이 든 사유 — `V2ProductionContextBeanGateIT`가 "production 컨텍스트에 v2 빈 0개"를 **v2 빈
타입을 하나씩 열거해** 증명하므로 동결하면 v2가 자랄수록 증명이 낡는다 — 는 **독립 확인됐다.**
`origin/codex/op-43-v2-0b-runtime`(`debc9190`)의 해당 diff를 직접 읽은 결과 편집은 실제로 **강화**다:

- `assertNoV2Beans()`에 `V2CityCatalogAdapter` 0개 단언 신설
- `containsAll` 부분집합 단언 → `assertEquals(setOf("v2SandboxConfiguration", "v2SandboxMarker",
  "v2ContentCatalog", "v2CityCatalogAdapter"), byPackage.keys, ...)` **정확집합** 단언. 초과 빈까지
  잡힌다(엄격해진 쪽)
- Flyway 격리 단언 `assertV1DefaultRuntime()` / `assertV2SandboxRuntime()` 신설

즉 게이트 ②는 **격리를 강화하는 커밋을 차단**하고 있었다. 사유는 사실이다.

## 2. 보호를 잃는 전수 — 16파일, 저작 커밋 16/16 재확인

네 테스트 루트의 `**/v2/**` 전량. 저작 커밋을 파일별로 `--diff-filter=A` 재확인했고
**16/16이 v2 티켓 산출물**이다 — v1 패러티 내용 0건.

| # | 파일 | 저작 커밋 (재확인) |
|---|---|---|
| 1 | `app/game-api/.../gameapi/v2/V2ProductionContextBeanGateIT.kt` | `e9cc3b31` OPENSAM-35 (#370) |
| 2 | `app/game-api/.../gameapi/v2/V2SandboxConfigurationTest.kt` | `e9cc3b31` OPENSAM-35 (#370) |
| 3 | `app/game-engine/.../engine/v2/V2CityLedgerFlushIT.kt` | `2db5ea06` OPENSAM-150 (#412) |
| 4 | `app/game-engine/.../engine/v2/V2CityLedgerReadBoundGuardTest.kt` | `2db5ea06` OPENSAM-150 (#412) |
| 5 | `app/game-engine/.../engine/v2/V2ContentCatalogBeanTest.kt` | `e9cc3b31` OPENSAM-35 (#370) |
| 6 | `app/game-engine/.../engine/v2/V2FlywayIsolationIT.kt` | `90c442cb` OPENSAM-43 (#371) |
| 7 | `app/game-engine/.../engine/v2/V2MigrationConventionTest.kt` | `90c442cb` OPENSAM-43 (#371) |
| 8 | `app/game-engine/.../engine/v2/V2NamingConventionGuardTest.kt` | `e9cc3b31` OPENSAM-35 (#370) |
| 9 | `app/game-engine/.../engine/v2/V2ProductionContextBeanGateIT.kt` | `e9cc3b31` OPENSAM-35 (#370) |
| 10 | `app/game-engine/.../engine/v2/V2SandboxConfigurationTest.kt` | `e9cc3b31` OPENSAM-35 (#370) |
| 11 | `app/gateway-api/.../gateway/v2/V2ProductionContextBeanGateIT.kt` | `e9cc3b31` OPENSAM-35 (#370) |
| 12 | `common/.../common/wire/v2/V2WireContractTest.kt` | `90c442cb` OPENSAM-43 (#371) |
| 13 | `infra/.../infra/v2/V2CityCatalogAdapterTest.kt` | `90c442cb` OPENSAM-43 (#371) |
| 14 | `infra/.../infra/v2/V2ContentCatalogTest.kt` | `e9cc3b31` OPENSAM-35 (#370) |
| 15 | `logic/.../logic/v2/evidence/EvidenceContractValidatorTest.kt` | `acbc7bff` OPENSAM-37 (#408) |
| 16 | `logic/.../logic/v2/geo/GeoValidatorsTest.kt` | `bf3b6ce5` OPENSAM-36 (#407) |

**v1 패러티 혼입 정밀 검사.** 위 16개에 RNG·라운딩·한글로그·골든·flush 키워드를 grep해 3건이
걸렸으나 전량 **v2 자기 계약** 측정임을 본문으로 확인했다 — `V2CityLedgerFlushIT`(v2 원장 →
`ChangeRecorder` → `JdbcFlushExecutor`의 **v2 step**), `V2CityLedgerReadBoundGuardTest`(v2 store가
직접 쓰지 않고 `ChangeRecorder` 경유임을 단언), `V2ContentCatalogTest`(문자열 리터럴 하나).
**v1 골든·RNG·한글 로그를 재는 파일은 0건.**

**테스트 리소스는 애초에 게이트 ② 대상이 아니다.** `infra/src/test/resources/**/content/v2/*.json`
17개가 `/v2/` 경로에 있으나, ②는 테스트 리소스 중 `logic/src/test/resources/golden/**`만 잠근다.
이 PR로 잃는 것이 아니라 **원래 보호 밖**이었다 — 좁히기와 무관.

## 3. mutation 실측 (전량 재실행, bash 3.2.57 / 5.3.15 양판 동일)

절차: clean tree PASS 확인 → 변조 → `scripts/agent/v2-isolation-gate.sh` 실행 → 복원.
판정 = 게이트 ② `VIOLATION` 출력 유무 및 프로세스 exit code.

clean tree (양쪽 bash 동일): `MB=67202e46` / `② PASS` `⑤ PASS` `C1 PASS` / `GATE RESULT: PASS` `exit=0`.

### A. 보호가 남는 것 — KEPT (rc=1), 양쪽 bash 동일

| 변조 대상 | 기대 | 실측 |
|---|---|---|
| `logic/src/main/kotlin/opensamguk/logic/BuildInfo.kt` (v1 main) | VIOLATION | **VIOLATION rc=1** |
| `logic/src/test/kotlin/opensamguk/logic/BuildInfoTest.kt` (v1 test) | VIOLATION | **VIOLATION rc=1** |
| `logic/src/test/resources/golden/entrance/장수생성-fixtures.json` (골든) | VIOLATION | **VIOLATION rc=1** |
| `common/src/main/kotlin/opensamguk/common/BuildInfo.kt` (v1 main) | VIOLATION | **VIOLATION rc=1** |
| `common/src/test/kotlin/opensamguk/common/BuildInfoTest.kt` (v1 test) | VIOLATION | **VIOLATION rc=1** |
| `app/game-api/.../gameapi/GameApiApplicationTests.kt` (app v1 test) | VIOLATION | **VIOLATION rc=1** |
| `infra/.../persistence/V2BriefMigrationTest.kt` (**Flyway 버전명 함정**) | VIOLATION | **VIOLATION rc=1** |

마지막 행이 좁히기 설계의 핵심 검증이다. `V2BriefMigrationTest.kt`·`V26NpcLifecycleMigrationTest.kt`·
`V28YearbookServerIdMigrationTest.kt`·`V29LogEntryYearMonthIndexMigrationTest.kt`는 **파일명**에 Flyway
버전 번호가 든 v1 테스트다. 제외가 **디렉터리 세그먼트 `/v2/`** 기준이고 이들은 `persistence/`에
있으므로 걸리지 않는다 — 파일명 패턴으로 좁혔다면 조용히 풀렸을 넷이다. 설계 판단이 옳다.

### B. 보호를 잃는 것 — LOSS (rc=0), 의도대로

`V2ProductionContextBeanGateIT.kt`(engine) · `V2CityCatalogAdapterTest.kt`(infra) ·
`GeoValidatorsTest.kt`(logic) 변조 → 3/3 **PASS rc=0**, 양쪽 bash 동일. §2의 16파일 경로 집합과 일치.

### C. 우회 시도 — 5종, 전부 방어 성공 (다만 이유가 레인 설명보다 미묘하다)

| 시도 | 실측 |
|---|---|
| v1 test → `/v2/` 디렉터리로 순수 이동 | **VIOLATION rc=1** — 원경로 `D` |
| 이동 + 전면 재작성 (유사도 임계 회피) | **VIOLATION rc=1** — 원경로 `D` |
| 원본 삭제 + `/v2/`에 무관한 내용으로 신규 생성 | **VIOLATION rc=1** — 원경로 `D` |
| `/v2/`에 v1 패러티풍 **신규** 파일 생성 | PASS rc=0 — **좁히기와 무관**(②는 `--diff-filter=MD`라 신규 파일은 원래 전부 허용) |
| v2 격리 증명 테스트 **삭제/무력화** | PASS rc=0 — §6-①에 기록 |

**레인 설명을 한 군데 정정한다.** 레인은 이동 우회가 "원경로 삭제가 `--diff-filter=MD`에 걸리므로"
막힌다고 썼는데, 그 진술은 맞되 **좁히기 이전에는 성립하지 않았다.** 실측 — 제외 pathspec 없이
(= `origin/main` 상태) 순수 이동을 걸면 `--name-status`는 `R100`을 내고, 같은 조건의
`--name-only --diff-filter=MD`는 **빈 출력**이다. 이름변경이 `R`로 잡혀 `MD` 필터에서 빠지기 때문이다.

제외 pathspec이 목적지를 pathspec 밖으로 밀어내면 이름변경 쌍이 깨지면서 `D`가 드러난다 —
이 PR 상태에서 같은 이동은 원경로가 `D`로 노출돼 `VIOLATION`이다.

**결론: 이 PR은 `/v2/`를 목적지로 하는 이동 우회에 한해 게이트를 오히려 강화한다.** 보호 제거
변경이 특정 축에서 순증인 경우이므로 명시해 둔다. (테스트 루트 내 **비-`/v2/`** 경로로의 이동은
여전히 안 보이지만 이는 OPENSAM-190 이전부터의 선재 구멍이며 본 PR의 책임 범위 밖 — 별도 티켓감.)

## 4. op-43 언블록 — BEFORE/AFTER 재현 (브랜치 무수정)

`origin/codex/op-43-v2-0b-runtime` (`debc9190`, merge-base `e9cc3b31`)에 대해
**BEFORE = `origin/main`의 스크립트**, **AFTER = 이 PR의 스크립트**로 각각 양쪽 bash 실행.

**BEFORE — ② VIOLATION 6파일, `GATE RESULT: FAIL`, exit=1** (양쪽 bash 동일):

```
VIOLATION ② T1 parity core + existing tests
  app/game-api/src/test/kotlin/opensamguk/gameapi/v2/V2ProductionContextBeanGateIT.kt
  app/game-api/src/test/kotlin/opensamguk/gameapi/v2/V2SandboxConfigurationTest.kt
  app/game-engine/src/test/kotlin/opensamguk/engine/v2/V2ContentCatalogBeanTest.kt
  app/game-engine/src/test/kotlin/opensamguk/engine/v2/V2ProductionContextBeanGateIT.kt
  app/game-engine/src/test/kotlin/opensamguk/engine/v2/V2SandboxConfigurationTest.kt
  infra/src/test/kotlin/opensamguk/infra/v2/V2ContentCatalogTest.kt
```

**AFTER — ②·⑤·C1 전부 PASS, `GATE RESULT: PASS`, exit=0** (양쪽 bash 동일). ③은 rc에 반영되지 않는
목록이며 두 줄(`V2SandboxConfiguration.kt`, `V2ContentCatalog.kt`)은 **차단이 아니라 티켓 사전선언
대조 대상**이다 — OPENSAM-43 티켓 본문이 두 파일을 T2로 선언해야 규정대로 통과한다.

차단 6파일이 §2의 16파일 집합 부분집합임을 확인했다. **언블록은 실재하며 과잉이 아니다.**

## 5. bash 3.2 vs 5.3 — 동형 확인 (PASS, UNKNOWN 아님)

`/bin/bash` = **3.2.57(1)-release**(macOS 기본), `/usr/local/bin/bash` = **5.3.15(1)-release** 양쪽
가용. §3의 mutation 전량과 §4의 BEFORE/AFTER를 양쪽에서 실행해 **출력·exit code 전건 일치**했다.
OPENSAM-188(#415)이 고친 빈배열 + `set -u` 방어(`TO` 배열 확장)가 유지되며, 이 PR은 `gate()`
함수·`TO` 배열 처리를 건드리지 않는다(diff는 pathspec 인자와 주석뿐). **3.2에서 조용히 전건 PASS로
떨어지는 재발 없음.**

## 6. 잔존 리스크 — 차단 사유 아님, 그러나 정본에 남긴다

### ① v2 격리 증명 테스트의 **삭제·무력화**가 완전 무출력이다 (신규 상실)

실측: `app/game-engine/.../v2/V2ProductionContextBeanGateIT.kt`를 **통째로 삭제**해도, 본문을
`package` 한 줄로 **무력화**해도 게이트 ②는 `PASS rc=0`, 출력 0줄(양쪽 bash).

좁히기 **전에는 삭제가 `D`로 잡혔다.** 구현 레인의 자기기록 §5는 "향후 **편집**을 기계적으로
검증하는 장치는 없다"고만 적었는데, 실제로 잃은 것은 편집 가시성뿐 아니라 **삭제 가시성**이다.
그리고 잃는 대상이 하필 `V2ProductionContextBeanGateIT`(0A-f 증명) · `V2FlywayIsolationIT` ·
`V2NamingConventionGuardTest` · `V2SandboxConfigurationTest` — **격리 증명 그 자체**다. 게이트 ⑤의
README 제외가 안전한 이유는 README가 **불활성**이기 때문인데, 테스트 파일은 불활성이 아니다.
"동형 결함"이라는 레인의 프레이밍은 **동기**에 대해서만 참이고 **위험 등급**에 대해서는 참이 아니다.

- 기계적 보상통제 부재를 확인했다: `tools/agent-system/check.py`에 이 파일들에 대한 규칙 0건,
  `.github/` 워크플로에 참조 0건. `V2NamingConventionGuardTest`는 **main 루트만** 스캔하므로
  (`scannedRoots` = `*/src/main/kotlin` 6개) 테스트 루트 배치를 강제하지 못한다.
- **현실적 최악**은 v1 프로덕션 파손이 아니라 **격리 증명의 부패**다. v2 런타임은 `V2_ENABLED` +
  프로필 이중 게이트 뒤에 있고, C1이 `docker-compose.production.yml`을 동결하며, ③이 v2 main
  소스 편집을 계속 목록화한다. 탐지 시점은 PR 리뷰. 이 완화 요인들 때문에 **차단 사유로 올리지
  않는다.**
- **권고(비차단, 저비용):** ③과 대칭으로, 제외된 v2 테스트의 변경을 **`LIST` 한 줄로 노출**하라.
  rc에 반영하지 않으므로 op-43을 다시 막지 않으면서 "조용한 삭제"만 없앤다. 대상 pathspec은
  `':(glob)*/src/test/kotlin/**/v2/**'` 한 줄이면 충분하다.

### ② 정본 문서가 이 잔존 리스크를 담지 않는다

문서 정직성은 **대체로 양호**하다. 특히 `docs/superpowers/plans/...isolation-plan.md`의 ② 스니펫은
개정 전에 pathspec 3줄뿐이라 **스크립트 실물과 이미 어긋나 있었고**, 이 PR이 테스트 루트 4줄까지
포함시켜 **문서를 실물에 맞춰 정합화**했다 — 순증이다. `round3-proposal-city-guanxi.md`도 동일.
"v1 패러티 코어·골든·v1 가드 테스트는 전부 동결 유지"라는 서술은 §3-A 실측과 일치하며 과장 없다.

다만 두 정본 어디에도 **§6-①(삭제·무력화 무출력)**이 없다. 잔존 리스크가 리뷰 파일에만 있고
정본에 없으면 다음 v2 티켓이 정본만 읽고 착수한다. **권고(비차단):** 두 정본 개정 문단에 한 문장
추가 — "제외된 v2 테스트는 삭제·무력화도 게이트에 잡히지 않는다; 방어선은 PR 리뷰다."

## 7. 구현 레인 자기기록과의 대조

| 레인 주장 | 독립 재측정 |
|---|---|
| 보호 상실 16파일, 전부 v2 티켓 저작 | **일치** — 저작 커밋 16/16 재확인 |
| v1 패러티 코어·골든·v1 가드 동결 유지 | **일치** — 7종 재측정 전건 rc=1 |
| Flyway 버전명 v1 테스트 안 걸림 | **일치** — `V2BriefMigrationTest.kt` rc=1 |
| op-43 BEFORE ② 6파일 FAIL → AFTER 전 게이트 PASS | **일치** — 양쪽 bash 재현 |
| op-43 편집이 게이트를 강화 | **일치** — diff 직접 확인(부분집합→정확집합 등) |
| bash 3.2/5.3 동일 | **일치** — 전건 재측정 |
| 이동 우회는 "여전히" 위반 | **부분 정정** — 결론은 맞으나 좁히기 **이전에는** 이름변경이 `R`로 빠져 안 잡혔다. 이 PR이 해당 축을 **강화**한다(§3-C) |
| §5 "향후 **편집** 검증 장치 없음" | **범위 확대 필요** — 편집뿐 아니라 **삭제·무력화**도 무출력(§6-①) |

레인 기록에 **허위·과장은 없었다.** 정정 2건은 모두 "레인이 자기 변경을 실제보다 **낮게** 평가한
쪽(이동 우회)"과 "리스크를 실제보다 **좁게** 기술한 쪽(삭제)"이다.

## 8. 남기는 UNKNOWN (승격하지 않음)

- **v2 소유 main 소스(`logic`·`common`의 v2 패키지)는 여전히 T1 하드 동결.** 동형 결함이 잠재하나
  이를 실제로 막고 있다는 사례를 이번 조사에서도 관측하지 못했다. `op-43`이 건드리는 v2 main은
  `app/`·`infra/`뿐이라 게이트 ③ 경로로 처리된다. 레인의 **UNKNOWN 판정을 유지**한다 — 실제 차단
  사례가 나오면 별도 티켓에서 같은 절차로 판정.
- 테스트 루트 내 **비-`/v2/`** 경로로의 이동이 ②에 안 잡히는 선재 구멍(§3-C 말미). 본 PR이 만든
  것이 아니고 본 PR로 악화되지도 않았다. 별도 티켓 권고이며 여기서는 **UNKNOWN이 아니라 범위 밖**.

## 9. 검증

- `python3 tools/agent-system/check.py --strict --base origin/main` → **Errors: 0 / Warnings: 0**
- clean tree 게이트 → 전 게이트 PASS exit 0 (bash 3.2.57 / 5.3.15)
- Kotlin·리소스 변경 0건이므로 gradle 테스트 대상 없음 (변경은 `.sh` 1 + `.md` 3)
