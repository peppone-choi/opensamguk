# OPENSAM-188 — V2-0A 게이트 스크립트 결함 3건 폐쇄

Scope: scripts/agent/v2-isolation-gate.sh · docs/loops/v2-planning-2026-07-12/round3-proposal-city-guanxi.md · docs/superpowers/plans/2026-08-08-opensam-35-v2-0a-isolation-plan.md · infra/src/main/resources/db/migration_v2/README.md
Verdict: cleared

- 발견 출처: `docs/superpowers/reviews/2026-08-16-opensam-150-v2-city-ledger-review.md` §5 M1/M3/M4
- 브랜치 `fix-188-v2-0a-gate-defects`, 기준 `origin/main` = `2db5ea06`
- `tools/agent-system/check.py` 무편집. 골든·테스트 약화·skip 0건. 게이트는 **더 엄격해지거나 같아졌다**(단 1건의 완화는 §2에서 mutation으로 손실 0 실증).

---

## 1. 결함 ① — 게이트 ③ pathspec 무효 (M3)

### 1.1 재현 (git 2.50.1, Apple Git-155)

범위 `2db5ea06^..2db5ea06`(#412, OPENSAM-150 R1)은 `app/game-engine/src/main/kotlin/` 아래
파일 3개를 **실제로 수정**했다. 대조군(pathspec 없음):

```
$ git diff --name-only --diff-filter=MD 2db5ea06^ 2db5ea06
app/game-engine/src/main/kotlin/opensamguk/engine/flush/DatabaseHooks.kt
app/game-engine/src/main/kotlin/opensamguk/engine/turn/ChangeRecorder.kt
app/game-engine/src/main/kotlin/opensamguk/engine/turn/DirtyState.kt
infra/src/main/kotlin/opensamguk/infra/persistence/JdbcFlushExecutor.kt
```

같은 범위에 게이트 ③의 pathspec을 건 실측:

| pathspec | 출력 |
|---|---|
| `'app/*/src/main/kotlin/'` (**정본에 적혀 있던 것**) | **(빈 출력)** |
| `'app/*/src/main/kotlin'` | (빈 출력) |
| `'app/**/src/main/kotlin/**'` (교정형 A) | `DatabaseHooks.kt` · `ChangeRecorder.kt` · `DirtyState.kt` |
| `':(glob)app/*/src/main/kotlin/**'` (교정형 B, 채택) | `DatabaseHooks.kt` · `ChangeRecorder.kt` · `DirtyState.kt` |
| `app/` (와일드카드 없음 — 대조) | 동일 3파일 |
| `'app/*/src/main/resources/'` (게이트 ⑤ app 절반) | **(빈 출력)** |

원인: git pathspec에 와일드카드가 있으면 디렉터리 접두 매칭이 아니라 wildmatch **전체 경로**
매칭이 되므로, 트레일링 슬래시로 끝나는 패턴은 어떤 파일 경로와도 일치하지 않는다.
⇒ 게이트 ③의 `app/` 절반과 게이트 ⑤의 `app/` 절반은 **지금까지 아무것도 검사하지 않았다**.
"빈 출력 = PASS"가 공허하게 참이었다.

교정형 A/B는 출력이 동일하다. **B(`:(glob)`)를 채택**한 이유: A의 `**`는 기본 wildmatch에서
`*`가 `/`도 먹기 때문에 우연히 동작하는 것이고, B는 pathmatch 의미를 명시적으로 고정한다.
계획서 §4-1이 이미 B를 쓰고 있어 표기도 통일된다.

### 1.2 전수 조사 — `--diff-filter` + pathspec 사용처 전량 검증

`docs/` · `scripts/` · `tools/` · `.github/` · `.ai/` 전수 grep(`--diff-filter`) 결과 43개 지점.
`scripts/` · `tools/` · `.github/`에는 **0건**(게이트가 실행 코드로 존재한 적이 없다는 뜻이며,
이것이 결함 ③의 근본 원인이다). 나머지 문서 지점의 판정:

| 지점 | pathspec | 판정 |
|---|---|---|
| `round3-proposal-city-guanxi.md:1199` 게이트 ③ | `app/*/src/main/kotlin/` | **무효 — 본 티켓에서 교정** |
| `round3-proposal-city-guanxi.md:1218` 게이트 ⑤ | `'app/*/src/main/resources/'` | **무효(app 절반) — 본 티켓에서 교정** |
| `round3-proposal-city-guanxi.md:1190` 게이트 ② | 와일드카드 없음 | 유효 (기준선만 교정) |
| `plans/2026-08-08-…-isolation-plan.md:594-612` §4-1 | `:(glob)…/**` | **이미 유효** (2026-08-08 개정본) |
| `loops/…/s6-gates-and-baseline.md:161-274` | 무효형 포함 | 유효 — **결함 재현 목적의 historical transcript**이며 §15가 정본임을 문서가 명시(`:152-156`, `:186`) |
| `loops/…/s6-gates-and-baseline.md:594-608` §15.1 | `:(glob)…/**` | 이미 유효 (canonical) |
| `loops/…/s4-production-context-bean-gate.md:232,235` | 무효형 | 유효 — 같은 파일 `:239-`에 "**위 두 명령은 무효다**" 사후 정정이 이미 달려 있음 |
| `loops/…/s3a-content-v2-loader.md:185`, `s2-conditional-bean-gate.md:195` | `…` 축약 | 유효 — 본문이 "historical, no current PASS claim"으로 무효화 |
| `loops/…/gate-f-adversarial-review.md:31,72-73` | 와일드카드 없음 | 유효 (기준선 함정을 기록한 원출처) |
| `reviews/2026-08-16-opensam-36/37-…` | 와일드카드 없음(`origin/main...HEAD`) | 유효 |
| `loops/v2-planning-…/REVIEW*·REVISION*` | 산문 인용 | 검사기 아님 |

⇒ **살아 있는 무효 pathspec은 `round3-proposal-city-guanxi.md` §7.2 2건뿐**이었고 둘 다 닫았다.
`s6`/`s4`는 이미 자기 무효성을 문서에 박아둔 결함 이력이므로 보존한다(CLAUDE.md: 결함 이력은 지우지 않는다).

### 1.3 소급 재검 — 교정형 pathspec으로 다시 잰 결과

`origin/main` 머지 커밋 8건(#405~#412)은 각각 `<sha>^..<sha>`로, 미머지 v2 브랜치 5건은
`merge-base(branch, origin/main)..branch`로 재검했다. 게이트 ②③⑤ + C1 전부 교정형.

| 대상 | ② T1 | ③ T2 | ⑤ 설정 | C1 |
|---|---|---|---|---|
| #405 `65397158` OPENSAM-73/74/75 계약 동결 | 빈 | 빈 | 빈 | 빈 |
| #406 `dffef8bc` OPENSAM-111 백로그 | 빈 | 빈 | 빈 | 빈 |
| #409 `dcb205d8` 전투 정본 ADR | 빈 | 빈 | 빈 | 빈 |
| #407 `bf3b6ce5` OPENSAM-36 행정 계약 | 빈 | 빈 | 빈 | 빈 |
| #408 `acbc7bff` OPENSAM-37 출처 계약 | 빈 | 빈 | 빈 | 빈 |
| #410 `d0f9d47f` OPENSAM-41 3D 증명 | 빈 | 빈 | 빈 | 빈 |
| #411 `1deb203c` 정복 멸망 로그 (**v1 패러티 수정, v2 레인 아님**) | `ConquerCity.kt` + `ConquerCityCollapseTest.kt` | 빈 | 빈 | 빈 |
| #412 `2db5ea06` OPENSAM-150 R1 | 빈 | **4파일** | 빈 | 빈 |
| `origin/codex/op-35-v2-0a-final` (MB `b847c351`) | 빈 | 빈 | 빈 | 빈 |
| `origin/codex/v2-3d-foundation` | 빈 | 빈 | 빈 | 빈 |
| `origin/codex/v2-battle-ticket-ledger` (MB `fbfe095f`) | 빈 | 빈 | 빈 | 빈 |
| `origin/op-150-v2-city-ledger-r1` (MB `d63f6fec`) | 빈 | **4파일** | 빈 | 빈 |
| `origin/codex/op-43-v2-0b-runtime` (MB `e9cc3b31`) | **6파일** | **2파일** | **2파일** | 빈 |

판정:

- **#412 / `op-150-…-r1`의 ③ 4파일** = `DatabaseHooks.kt`·`ChangeRecorder.kt`·`DirtyState.kt`·
  `JdbcFlushExecutor.kt`. OPENSAM-150 티켓 T2 선언과 **정확히 일치**한다(150 리뷰 §1 A3이 이미
  교정형으로 확인). **초과 0 — 위반 아님.**
- **#411**은 v1 패러티 버그픽스이며 V2-0A 격리 게이트의 적용 대상이 아니다. **위반 아님.**
- **머지된 커밋 중 T2 선언 초과는 0건이다.** 즉 게이트 ③이 눈감고 있던 기간에도 실제 초과 수정은
  일어나지 않았다. 계측기가 고장이었을 뿐 제약은 지켜지고 있었다.
- **미머지 `origin/codex/op-43-v2-0b-runtime` 1건만 교정형에서 빨개진다** — §3에 별도 보고.

---

## 2. 결함 ② — 게이트 ⑤ 과잉 차단 (M1)

### 2.1 무엇을 동결하려던 것인가 (근거)

`round3-proposal-city-guanxi.md` §7.2 게이트 ⑤ 신설 문단의 사유:
"`spring.flyway.locations`·`spring.jpa.hibernate.ddl-auto`·`spring.datasource.*`가 전부 거기 있고,
그 한 줄이 v1 부팅을 깰 수 있다". 즉 **의도는 v1 런타임을 바꿀 수 있는 리소스의 동결**이다.
같은 문단과 계획서 `:109`·`:173`은 v2 신규 디렉터리에 대해 "신규 파일이라 `--diff-filter=MD`에
걸리지 않는다"고 반복해 말한다 — **v2 소유 디렉터리를 동결 대상으로 삼은 적이 없다.**
그 디렉터리 안 파일이 한 번 커밋된 뒤에는 `M`이 되어 걸린다는 사실을 문서가 고려하지 못했을 뿐이다.

### 2.2 채택한 좁히기 — `README.md`만 제외

150 리뷰 §5 M1의 제안(`'**/src/main/resources/**/*.yml'`)은 **채택하지 않았다.** 그렇게 좁히면
동결 대상 중 다음이 전부 빠져 **실질적 약화**가 된다(실측 파일 수):

| 대상 | 파일 수 | `*.yml` 좁히기 시 |
|---|---|---|
| `app/*/src/main/resources/**/*.yml` | 3 | 유지 |
| `app/gateway-api/…/profile-icons/*.json` | 1 | **빠짐** |
| `infra/…/db/migration/**` (v1 마이그레이션) | 39 | **빠짐** |
| `infra/…/scenario/**` (v1 시나리오 시드) | 31 | **빠짐** |
| `infra/…/map/**` | 8 | **빠짐** |
| `infra/…/db/migration_v2/**`·`content/v2/**` (v2 소유) | 4 | 빠짐(의도) |

채택안은 **pathspec을 그대로 두고 `README.md`만 제외**한다:

```
':(glob,exclude)app/*/src/main/resources/**/README.md'
':(glob,exclude)infra/src/main/resources/**/README.md'
```

`README.md`가 안전한 제외인 근거(추정 아님, 코드 실측):
- Flyway는 location을 재귀 스캔하되 `V*.sql`/`R__`/`U__` 명명만 마이그레이션으로 취급한다.
- `infra/src/main/kotlin/opensamguk/infra/v2/V2ContentCatalog.kt:66` = `getResources("classpath*:$location/*.json")`,
  `:85`·`:93`이 `.json` 접미사를 재차 강제 — **`README.md`는 로더 시야 밖**이다.
- 리포 전수 grep: `README`를 참조하는 프로덕션·테스트 코드는 KDoc 주석 2건뿐
  (`V2SandboxConfiguration.kt:40`, `V2CityLedgerFlushIT.kt:36`) — 어떤 단언도 내용에 걸려 있지 않다.
⇒ v1 런타임을 바꿀 수 없는 유일한 파일 종류이며, 이보다 더 좁힐 수는 없다.

### 2.3 mutation 증명 — 좁히기가 놓치는 것이 없음

`scripts/agent/v2-isolation-gate.sh`를 clean tree(PASS)에서 출발해 파일 1개씩 변조 후 재실행,
매회 `git checkout -- .`로 복원. 10종 전량:

| # | 변조 파일 | 기대 | 관측 |
|---|---|---|---|
| 1 | `app/game-engine/src/main/resources/application.yml` | ⑤ 빨강 | **VIOLATION ⑤**, exit 1 |
| 2 | `infra/src/main/resources/db/migration/V1__baseline.sql` | ⑤ 빨강 | **VIOLATION ⑤**, exit 1 |
| 3 | `infra/src/main/resources/scenario/scenario_1010.json` | ⑤ 빨강 | **VIOLATION ⑤**, exit 1 |
| 4 | `infra/src/main/resources/db/migration_v2/V901__v2_city_ledger.sql` | ⑤ 빨강 | **VIOLATION ⑤**, exit 1 |
| 5 | `app/gateway-api/src/main/resources/profile-icons/shared-manifest.json` | ⑤ 빨강 | **VIOLATION ⑤**, exit 1 |
| 6 | `infra/src/main/resources/db/migration_v2/README.md` | ⑤ 통과 | PASS, exit 0 |
| 7 | `infra/src/main/resources/content/v2/README.md` | ⑤ 통과 | PASS, exit 0 |
| 8 | `logic/src/main/kotlin/…/war/ConquerCity.kt` | ② 빨강 | **VIOLATION ②**, exit 1 |
| 9 | `docker-compose.production.yml` | C1 빨강 | **VIOLATION C1**, exit 1 |
| 10 | `app/game-engine/src/main/kotlin/…/ChangeRecorder.kt` | ③ 목록 출현 | LIST에 해당 파일 1행 |

케이스 4가 핵심이다 — **v2 SQL조차 여전히 동결**된다(README §4의 "적용된 `V*.sql`은 절대 수정하지
않는다" 규약을 게이트가 계속 뒷받침한다). 좁히기가 잃은 보호는 **0**이다.

### 2.4 README §5 갱신

`infra/src/main/resources/db/migration_v2/README.md` §5의 "production `db/migration_v2/`에는 아직
SQL이 없다"는 `V901__v2_city_ledger.sql`(#412) 때문에 거짓이었다. 사실로 고치고, 이 절이
왜 4개월간 갱신 불가였는지와 무엇이 그것을 닫았는지를 각주로 남겼다.

---

## 3. 소급 재검에서 나온 **실제 위반 후보 1건** — 수정하지 않고 보고 (지시대로)

**대상: `origin/codex/op-43-v2-0b-runtime`** (OPENSAM-43 V2-0B 런타임, **미머지**).
MB = `e9cc3b31` (= #370, OPENSAM-35 격리 게이트 머지 커밋). 교정형 게이트 실측 10파일:

**게이트 ② T1 위반 (6파일, 전부 `M`)** — §7.2 게이트 ②는 `app/*/src/test/kotlin/` ·
`infra/src/test/kotlin/`를 **통째로** 동결하고 "수정·삭제 0건, 예외 없음"을 요구한다.

```
M app/game-api/src/test/kotlin/opensamguk/gameapi/v2/V2ProductionContextBeanGateIT.kt
M app/game-api/src/test/kotlin/opensamguk/gameapi/v2/V2SandboxConfigurationTest.kt
M app/game-engine/src/test/kotlin/opensamguk/engine/v2/V2ContentCatalogBeanTest.kt
M app/game-engine/src/test/kotlin/opensamguk/engine/v2/V2ProductionContextBeanGateIT.kt
M app/game-engine/src/test/kotlin/opensamguk/engine/v2/V2SandboxConfigurationTest.kt
M infra/src/test/kotlin/opensamguk/infra/v2/V2ContentCatalogTest.kt
```

**게이트 ③ T2 (2파일)** — OPENSAM-43 티켓 본문 선언과 대조 필요(본 레인은 그 선언을 보유하지 않음):

```
M app/game-engine/src/main/kotlin/opensamguk/engine/v2/V2SandboxConfiguration.kt
M infra/src/main/kotlin/opensamguk/infra/v2/V2ContentCatalog.kt
```

**게이트 ⑤ (2파일)** — 본 티켓의 `README.md` 제외로 **이미 해소**됨:

```
M infra/src/main/resources/content/v2/README.md
M infra/src/main/resources/db/migration_v2/README.md
```

### 3.1 판단 — 이것은 M1과 **동형의 게이트 설계 결함**이지 저자의 잘못이 아닐 수 있다

문제의 10파일은 **전부 OPENSAM-35(#370)가 만든 v2 소유 파일**이다. 게이트 ②가 v2 테스트
디렉터리까지 통째로 얼어 있으므로, **v2 후속 티켓이 자기가 만든 v2 테스트를 진화시킬 방법이
구조적으로 없다.** 게이트 ⑤에서 발견한 것(v2 소유 문서가 영구 갱신 불가)과 정확히 같은 형태이며,
게이트 ②는 T1(최강 방어선)이라 본 레인이 임의로 손대지 않았다.

**게이트 소유자 결정 필요(별도 티켓 대상):** 게이트 ②의 테스트 동결에서
`**/src/test/kotlin/opensamguk/**/v2/**`를 제외할 것인가, 아니면 v2 후속 티켓이 테스트를
**신규 파일로만** 추가하도록 강제할 것인가. 전자는 T1을 느슨하게 하므로 근거와 mutation 증명이
따라야 하고, 후자는 v2 테스트가 계속 파편화된다.

**UNKNOWN으로 명시하는 것:** OPENSAM-43 티켓 본문의 T2 사전선언 목록을 본 레인은 확인하지 못했다.
따라서 위 ③ 2파일이 "선언 초과"인지 "선언 내"인지는 **판정하지 않는다**. ② 6파일은 게이트 ②가
"예외 없음"이므로 선언과 무관하게 위반이다.

---

## 4. 결함 ③ — 게이트 기준선 (M4) → 실행 가능 스크립트

`origin/main`을 기준선으로 쓰면 분기 후 머지된 타 브랜치가 섞여 **거짓 위반**이 뜬다
(`gate-f-adversarial-review.md:31`과 150 리뷰 §1 A2가 각각 독립으로 관측). 기준선은
**merge-base 고정 또는 리베이스 후 실행**이다.

문서에 문장으로만 적으면 계속 틀리므로 **`scripts/agent/v2-isolation-gate.sh`** 를 신설했다:

- `MB=$(git merge-base <ref> origin/main)`을 스스로 계산 — 사람이 기준선을 고를 여지가 없다.
- pathspec을 `:(glob)…/**` 로 고정 — 결함 ①이 재발할 수 없다.
- `<ref>` 생략 시 `HEAD`이며 **워킹트리 미커밋 변경까지 포함**한다(더 엄격한 쪽).
- 게이트 ②·⑤·C1은 빈 출력 강제, 위반 시 **exit 1 fail-closed**. 게이트 ③은 "빈 출력"이
  요구사항이 아니라 "티켓 선언과 정확히 일치"이므로 목록만 출력하고 사람이 대조한다
  (기계가 티켓 본문을 읽을 수 없으므로 여기서 자동 판정을 지어내지 않는다).
- `V2_GATE_BASE_REF` env로 기준 브랜치 교체 가능(기본 `origin/main`).

정본 문서 2곳(§7.2 코드블록, 계획서 §4-1)에 "실행은 스크립트가 정본, 문서 블록과 어긋나면
스크립트가 이긴다"를 명시했다.

### 4.1 스크립트 자체에서 잡아 고친 결함 — bash 3.2 침묵 통과

초판은 `"${TO[@]}"`(빈 배열)를 `set -u` 아래서 확장했다. bash 5.3(homebrew)에서는 정상이지만
**macOS 기본 `/bin/bash` 3.2.57에서는 `TO[@]: unbound variable`로 게이트 함수가 죽고,
그럼에도 스크립트는 `exit 0` / `GATE RESULT: PASS`로 끝났다** — 검사기가 침묵하는데 초록이
뜨는, 이 티켓이 닫으려는 결함과 정확히 같은 형태다. `${TO[@]+"${TO[@]}"}` 로 교정하고
`git diff` 실패 시 `rc=1` fail-closed를 추가했다.

검증: `/bin/bash`(3.2.57)와 `bash`(5.3.15) 양쪽에서
① clean tree → `PASS` / `exit 0`, ② `origin/codex/op-43-v2-0b-runtime` → `VIOLATION ②` / `FAIL`,
③ `application.yml` 변조 → `VIOLATION ⑤` / `FAIL` 로 **세 결과 모두 동일**함을 실행해 확인했다.

---

## 5. 게이트 재실행 (exit code 아님 — test XML 집계)

`JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :common:test :logic:test :infra:test :app:game-engine:test :app:game-api:test --rerun-tasks`

`BUILD SUCCESSFUL in 17m 19s` · `29 actionable tasks: 29 executed` · `GRADLE_EXIT=0`.
아래는 exit code가 아니라 `**/build/test-results/test/*.xml` 파싱 집계다.

| 모듈 | tests | failures | errors | skipped |
|---|---|---|---|---|
| common | 232 | 0 | 0 | 0 |
| logic | 3227 | 0 | 0 | 0 |
| infra | 239 | 0 | 0 | 0 |
| app:game-engine | 848 | 0 | 0 | 1 |
| app:game-api | 510 | 0 | 0 | 0 |
| **합계** | **5056** | **0** | **0** | **1** |

150 리뷰(§6, 5008)와의 차이 +48은 그 사이 머지된 #411·기타 레인의 `logic` 테스트 증가분
(3179 → 3227)이며 본 티켓이 추가·삭제한 테스트는 **0**이다.

`python3 tools/agent-system/check.py --strict --base origin/main` → **findings 0** (`check.py` 무수정).

본 티켓의 코드 변경은 **`scripts/agent/v2-isolation-gate.sh` 신규 1개뿐**이며 컴파일 대상이 아니다.
나머지는 문서 4개(정본 2 + README 1 + 본 문서). 테스트 수 변동이 없어야 정상이다.

## 6. 결론

- 결함 ①(pathspec 무효): 재현·교정 완료. 살아 있던 무효 지점 2건 모두 닫았고, 나머지 무효형은
  자기 무효성을 이미 문서화한 결함 이력이라 보존했다.
- 소급 재검: **머지된 커밋에서 T2 선언 초과 0건.** 계측기는 고장이었지만 제약은 지켜지고 있었다.
  미머지 `op-43-v2-0b-runtime` 1건이 교정형에서 빨개지며 §3에 목록으로 보고했다(수정 안 함).
- 결함 ②(과잉 차단): `README.md`만 제외하는 최소 좁히기로 닫았고, mutation 10종으로 보호 손실 0을
  실증했다. 150 리뷰가 제안한 `*.yml` 좁히기는 79파일의 보호를 잃으므로 기각했다.
- 결함 ③(기준선): 실행 가능 스크립트로 사람의 오지정 여지를 제거했다.
- 격리(quarantine) 항목 없음. 골든·테스트 무편집.
