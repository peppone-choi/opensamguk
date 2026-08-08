# S6 — 0A-g baseline artifact + gate history / PR Round 1 provenance (2026-08-08)

계획: `docs/superpowers/plans/2026-08-08-opensam-35-v2-0a-isolation-plan.md` §3 S6.
원래 범위는 **증거 수집·게이트 실행만**이었다. 이 문서는 그 historical artifact를 보존하고
PR #370 Round 1의 현재 provenance를 함께 기록한다. 커밋·푸시·merge·release·deploy·production
관측은 수행하지 않았다.

## 0. 판정 요약

| 게이트 | 판정 | 비고 |
|---|---|---|
| ① `tools/parity/gate.sh backend` | **CURRENT Round 1 backend evidence** | Java 21 `--rerun-tasks` six-root one run / no retry: 601 suites / 5050 tests / fail 0 / err 0 / skip 1; independent dirty-tree review cleared (§17), post-commit review/CI는 별도 |
| ② T1 diff 0 | **HISTORICAL scope result** | canonical merge-base glob remeasurement은 §15에서 current state로 기록 |
| ③ T2 diff = 사전 선언(공집합) | **current scope result** | 원 pathspec은 vacuous였고, canonical current glob result는 §15; source remediation은 observed resolved |
| ⑤ 설정 리소스 무수정 | **historical result only** | current canonical result는 §15 |
| C1 추가 확인 | **historical result only** | current canonical result는 §15 |
| 프론트 `pnpm typecheck && pnpm test` | **current direct-pnpm evidence + historical A4** | historical 54 files / 288 tests; historical dependency failure 뒤 current typecheck green, Vitest JSON 132 suites / 288 tests / 0 failures |
| artifact 4종 | **A1/A2/A4 생성 · A3 scope/inventory proof** | A3은 PHP replay/pass claim이 아니다 (§8) |

**초기 차단 이력:** 아래 B1~B3는 최초 S6 실행 시점의 사실이다. historical remediation이
있었다는 것과 PR #370 Round 1을 clear할 수 있다는 것은 별개다. current controlling disposition은
review artifact의 23-thread dirty-tree `cleared`이며 §15와 §17이 current evidence boundary다.

- **B1 — 게이트 ③·⑤의 pathspec이 vacuous.** §3 참조. 지금 상태로는 위반을 절대 검출하지 못하는
  false-green 명령이다. **게이트 명령의 정본(계획서 §3 S6 / §4)을 반드시 고쳐야 한다** — S6는
  고치지 않는다(하드 제약). 병렬 GATE-f 리뷰어도 같은 건을 blocker로 잡았고, 팀 리드가 교정
  명령을 지시했다. canonical remeasurement 결과는 §15. **결함 이력은 통과했다고 지우지 않는다.**
- **B2 — 브랜치가 `origin/main`보다 1커밋 뒤처져 있다.** §2 참조. 그 결과 `origin/main` 기준 diff에
  **이 티켓과 무관한 파일 3개**가 섞여 들어온다. 게이트 판정의 기준점(baseline) 선택이 필요하다.
- **B3 — 게이트 ①의 커버리지 구멍 + 시점 stale (팀 리드 추가, 2026-08-08).**
  ㉠ `tools/parity/gate.sh:15`의 `backend` 태스크 목록은
  `:common :logic :infra :app:game-engine :app:game-api` **5개뿐이며 `:app:gateway-api`가 없다.**
  따라서 이 티켓이 새로 넣은 `app/gateway-api/.../v2/V2ProductionContextBeanGateIT.kt`는
  **표준 백엔드 게이트에 잡히지 않는다.** 팀 리드가 별도 실행해 확인함:
  `:app:gateway-api:test --tests '*V2*' --rerun-tasks` → `BUILD SUCCESSFUL in 1m 50s`,
  XML 4칸 각 `tests="1" failures="0" errors="0"`
  (`V2ProductionShapeBeanGateIT`·`V2PropertyOnlyBeanGateIT`·`V2ProfileOnlyBeanGateIT`·`V2BothConditionsBeanGateIT`).
  `gate.sh` 자체는 **고치지 않는다** — 공유 도구라 이 티켓 범위 밖이고 별도 승인이 필요하다. 기록만 남긴다.
  ㉡ 이 게이트 실행(11:22)은 이후 들어온 두 수정보다 **앞선다** —
  gateway-api IT(11:21)와 `V2SandboxConfigurationTest` 대소문자 케이스(11:31).
  팀 리드가 영향 모듈만 재실행해 확인함: game-engine `*V2Sandbox*` 7/0/0/0
  (`BUILD SUCCESSFUL in 2m 29s`), 프론트 24/24. **커밋 승인 시점에 게이트 ① 전량 재실행이 필요하다.**
- **B3 — `tools/parity/gate.sh backend`가 `:app:gateway-api:test`를 포함하지 않는다.** §11 참조.
  게이트 ① 종료 후 병렬 세션이 gateway-api에 v2 아키텍처 테스트를 추가했는데, 표준 백엔드
  게이트의 5개 task에 gateway-api가 없어 **§1의 4862건에 포함되지 않는다.** 해당 모듈 XML은
  현재 RED(의도된 mutation probe 잔재로 보임).

---

## 1. 최초 게이트 ① — `tools/parity/gate.sh backend` (역사적 실행)

### 실행 명령 (historical raw transcript; current evidence 아님)

```text
tools/parity/gate.sh backend > <scratchpad>/gate-backend.log 2>&1
```

### 출력 tail (원시)

```text
BUILD SUCCESSFUL in 23m 9s
29 actionable tasks: 6 executed, 4 from cache, 19 up-to-date
Configuration cache entry reused.
XML gate green: 571 suites, 4862 tests
```

이 historical full log는 current Round 1 artifact로 교체되면서 더 이상
`baseline/a4-backend-gate.log`에 보존되지 않는다. 이 section의 raw tail만 historical record로 남긴다.

### 테스트 XML 독립 집계 (exit code 미사용)

gate.sh 내부 python 집계와 별개로, 5개 모듈의 `build/test-results/test/TEST-*.xml`을 직접 파싱했다.

| module | suites | tests | failures | errors | skipped | newest XML mtime |
|---|---:|---:|---:|---:|---:|---|
| common | 38 | 225 | 0 | 0 | 0 | 2026-08-08T10:59:20 |
| logic | 277 | 3173 | 0 | 0 | 0 | 2026-08-08T10:59:26 |
| infra | 55 | 226 | 0 | 0 | 0 | 2026-08-08T11:22:07 |
| app/game-engine | 129 | 770 | 0 | 0 | 1 | 2026-08-08T11:09:31 |
| app/game-api | 72 | 468 | 0 | 0 | 0 | 2026-08-08T11:20:12 |
| **TOTAL** | **571** | **4862** | **0** | **0** | **1** | |

**`failures`/`errors`가 0이 아닌 suite: 0건.**

**skipped 1건 (정체 명시):** `opensamguk.engine.golden.LongSimReplayGateTest` →
`12 month structural replay matches PHP golden()`. CLAUDE.md P5 항목이 이미 백로그로 기록한
"long-sim multi-turn (gate dim c)"이며 **이번 티켓이 만든 skip이 아니다.**

### 이번 티켓의 v2 suite가 실제로 실행됐는지 확인 (historical XML snapshot)

```text
app/game-api  : V2SandboxConfigurationTest, V2BothConditionsBeanGateIT,
                V2ProductionShapeBeanGateIT, V2ProfileOnlyBeanGateIT, V2PropertyOnlyBeanGateIT
app/game-engine: V2SandboxConfigurationTest, V2ContentCatalogBeanTest, V2BothConditionsBeanGateIT,
                V2ProductionShapeBeanGateIT, V2ProfileOnlyBeanGateIT, V2PropertyOnlyBeanGateIT
infra          : V2ContentCatalogTest
```

### 한계 (정직 고지)

`gate.sh`는 `--rerun-tasks`를 쓰지 않는다. 이번 실행에서 `:logic:test`는 `FROM-CACHE`,
`:common:test` 등도 캐시/UP-TO-DATE로 처리됐다(`6 executed, 4 from cache, 19 up-to-date`).
XML mtime이 전부 이번 실행 구간(10:59~11:22) 안이므로 **직전 세션의 유령 XML은 아니다.**
다만 logic/common은 이번에 **재실행된 것이 아니라 캐시 복원**이다 — 캐시 키가 소스 입력이고
게이트 ②가 그 입력의 무변경을 증명하므로 논리적으로는 유효하나, CLAUDE.md가 권고하는
`--rerun-tasks` 실행은 하지 않았다. **UNKNOWN: `--rerun-tasks` 강제 실행 결과.**

---

## 2. B2 — 브랜치가 `origin/main`보다 1커밋 뒤처져 있다

```text
$ git rev-list --left-right --count origin/main...HEAD
1	0
$ git merge-base --is-ancestor origin/main HEAD  →  false
$ git log --oneline origin/main -1
ad0c8c53 fix(engine): 같은 틱에 멸망한 국가의 diplomacy UPDATE를 flush 페이로드에서 제외 (#365)
$ git merge-base origin/main HEAD
fb90eac1  (= HEAD)
```

`op-35-v2-0a`는 **커밋 0개**(ahead 0) — S1~S5 산출물은 전부 미커밋 워킹트리 상태다.
반면 `origin/main`에는 우리가 갖지 않은 `ad0c8c53`이 있다.

따라서 `git diff … origin/main`은 **우리가 하지 않은 변경 3건을 우리 것처럼 보고한다:**

```text
$ git diff --name-status origin/main
M	.ai/current-state.md
M	.ai/ownership.md
M	.ai/task.md
M	app/game-engine/src/main/kotlin/opensamguk/engine/flush/DatabaseHooks.kt        ← ad0c8c53 (남의 것)
M	app/game-engine/src/test/kotlin/opensamguk/engine/flush/FlushPayloadConvergenceTest.kt ← ad0c8c53 (남의 것)
D	docs/superpowers/reviews/2026-08-08-diplomacy-flush-deleted-nation-review.md    ← ad0c8c53 (남의 것)
M	web/game/middleware.ts
```

`git diff --name-status <merge-base>`는 이 티켓의 진짜 변경만 남긴다:

```text
M	.ai/current-state.md
M	.ai/ownership.md
M	.ai/task.md
M	web/game/middleware.ts
```

**이 문서의 게이트 ②③⑤는 `origin/main` 기준과 merge-base 기준 둘 다 기록한다.**
어느 쪽을 정본 baseline으로 삼을지는 사람 판단 사항이다(리베이스 = 브랜치 변경이라 S6 제약상 금지).

---

## 3. 게이트 ③ — T2 diff **+ historical 명령 결함 B1**

이 절의 raw `origin/main` transcript는 결함을 재현하기 위해 보존한다. wildcard pathspec은
current diff evidence가 아니며, 이 절의 과거 빈 출력/PASS를 PR Round 1 판정에 쓰지 않는다.
current canonical merge-base glob command와 observed output은 §15만 정본이다.

### 당시 계획서의 결함 명령 (재실행 금지; non-evidence)

```text
$ git diff --name-only --diff-filter=MD origin/main -- \
    'app/*/src/main/kotlin/' infra/src/main/kotlin/ infra/src/main/resources/db/migration/
(빈 출력)
```

빈 출력 = 형식상 PASS. **그러나 이 명령은 신뢰할 수 없다.**

### 결함 실증 (historical)

`origin/main`에는 `app/game-engine/src/main/kotlin/…/DatabaseHooks.kt` 차이가 **실재한다**
(§2 참조). 그런데 위 명령은 그것을 잡지 못했다:

```text
$ git diff --name-only --diff-filter=MD origin/main -- app/
app/game-engine/src/main/kotlin/opensamguk/engine/flush/DatabaseHooks.kt
app/game-engine/src/test/kotlin/opensamguk/engine/flush/FlushPayloadConvergenceTest.kt

$ git diff --name-only --diff-filter=MD origin/main -- 'app/*/src/main/kotlin/'
(빈 출력)                                    ← 같은 파일을 놓친다

$ git diff --name-only --diff-filter=MD origin/main -- 'app/*/src/main/kotlin'
(빈 출력)                                    ← 슬래시를 떼도 마찬가지

$ git diff --name-only --diff-filter=MD origin/main -- 'app/*/src/main/kotlin/**'
app/game-engine/src/main/kotlin/opensamguk/engine/flush/DatabaseHooks.kt   ← 교정형은 잡는다

$ git --version
git version 2.50.1 (Apple Git-155)
```

원인: git pathspec에 와일드카드가 있으면 wildmatch로 **경로 전체**를 매칭하므로,
`app/*/src/main/kotlin/`은 디렉터리 접두 매칭으로 취급되지 않고 그대로 실패한다.
`**`를 붙이거나 `:(glob)` 매직을 써야 한다.

⇒ **계획서 §3 S6 / §4의 게이트 ③ 명령은 어떤 T2 위반도 검출할 수 없는 vacuous 명령이다.**
이것은 "PASS"가 아니라 "판정 불능"에 가깝다. 지시대로 **고치지 않고 보고만 한다.**

### 당시의 교정 시도 (historical; `:(glob)` + merge-base 정본은 §15)

```text
$ git diff --name-only --diff-filter=MD origin/main -- \
    'app/*/src/main/kotlin/**' infra/src/main/kotlin/ infra/src/main/resources/db/migration/
app/game-engine/src/main/kotlin/opensamguk/engine/flush/DatabaseHooks.kt      ← ad0c8c53(남의 것, B2)

$ git diff --name-only --diff-filter=MD <merge-base> -- \
    'app/*/src/main/kotlin/**' infra/src/main/kotlin/ infra/src/main/resources/db/migration/
(빈 출력)
```

당시 결과는 “이 티켓 귀속 merge-base에서 T2 수정/삭제 0”이었다. 그러나 위 command form은
`:(glob)`를 쓰지 않았고 current PR Round 1 working tree에는 delegated source edits가 있으므로,
이 문장은 current PASS가 아니다. `origin/main` 기준의 1건은 `ad0c8c53`의 것으로 이 티켓 산출물이
아니었다(§2).

---

## 4. 게이트 ② — T1 diff 0 (historical transcript)

```text
$ git diff --name-only --diff-filter=MD origin/main -- \
    logic/src/main/kotlin/ common/src/main/kotlin/ logic/src/test/resources/golden/
(빈 출력)

$ git diff --name-only --diff-filter=MD <merge-base> -- \
    logic/src/main/kotlin/ common/src/main/kotlin/ logic/src/test/resources/golden/
(빈 출력)
```

세 pathspec 모두 와일드카드가 없는 순수 디렉터리 접두라 §3의 결함에 해당하지 않았다. 이
historical result는 A3 scope inventory에만 쓰며 current PR pass는 §15의 canonical measurement로
판정한다.

보강 증거: golden 트리 객체 해시가 HEAD·origin/main 동일
(`3650b814950fb9f0d784ae1e4031a05658919ea4`), 워킹트리 golden 경로 변경 0건.

---

## 5. 게이트 ⑤ — 설정 리소스 무수정 (historical transcript)

```text
$ git diff --name-only --diff-filter=MD origin/main -- \
    'app/*/src/main/resources/' infra/src/main/resources/
(빈 출력)
```

`'app/*/src/main/resources/'`는 §3과 **동일한 vacuous 결함**을 갖는다(와일드카드 + 트레일링 슬래시).
`infra/src/main/resources/` 쪽은 정상 동작한다.

교정 재실행:

```text
$ git diff --name-only --diff-filter=MD origin/main -- \
    'app/*/src/main/resources/**' infra/src/main/resources/
(빈 출력)

$ git diff --name-only --diff-filter=MD <merge-base> -- \
    'app/*/src/main/resources/**' infra/src/main/resources/
(빈 출력)
```

이 historical remeasurement은 당시 `application.yml` 등 설정 리소스 수정 0을 보였다.
`infra/src/main/resources/db/migration_v2/`·`content/v2/`는 **신규 파일**이라 `--diff-filter=MD`에
걸리지 않는다. Current claim은 하지 않으며 §15 command가 정본이다.

---

## 6. 추가 C1 확인 (historical transcript)

```text
$ git diff --name-only --diff-filter=MD origin/main -- \
    docker-compose.production.yml docker-compose.yml tools/agent-system/check.py
(빈 출력)

$ git diff --name-only --diff-filter=MD <merge-base> -- (동일)
(빈 출력)
```

당시 결과는 C1 결정대로 `docker-compose.production.yml`·`tools/agent-system/check.py` 수정 0,
v2 신규 `docker-compose.v2-sandbox.yml` 분리였다. Current PR claim은 하지 않으며 §15 command가
정본이다.
해당 신규 파일에서 확인된 값: `V2_ENABLED: "true"`(3개 서비스),
`SCENARIO_SEED_ENABLED: ${V2_SCENARIO_SEED_ENABLED:-true}` — production 불변식과 파일이 분리돼 있다.

---

## 7. 프론트 gate (historical transcript)

`corepack`이 호스트에 없어 `pnpm` 직접 호출(S3-b와 동일).

```text
$ cd web/game && pnpm typecheck
> @opensamguk/web-game@0.0.1 typecheck
> tsc --noEmit
(무출력, exit 0)

$ pnpm test
 ✓ __tests__/v2-lab-route.test.tsx (16 tests) 570ms
   ✓ /game/v2-lab 네임스페이스 게이트 > V2_ENABLED=true면 자식을 렌더하고 404를 내지 않는다  395ms
 …
 Test Files  54 passed (54)
      Tests  287 passed (287)
   Duration  79.27s
```

이것은 pre-PR A4 historical transcript이며 current PR acceptance가 아니다. 해당 A4 log의 final
XML/log record는 54 files / **288** tests, v2-lab route **17** tests, middleware **8** tests다.
현재 verifier에서는 dependencies 부재로 `tsc: command not found`; typecheck failed, tests unexecuted였다.

**변동 고지:** 3회 실행 중 1회차만 `284 passed`, 2·3회차는 `287 passed`(파일 수는 3회 모두 54,
모든 회차 전부 pass, 실패 0). 3건 차이의 원인 **UNKNOWN** — 수정하지 않고 기록만 한다.

---

## 8. artifact 4종 생성 현황

상세·해시·생성 절차는 `baseline/MANIFEST.md`. 요약:

| # | artifact | 상태 | 근거 |
|---|---|---|---|
| A1 | v1 schema dump | **생성** | `postgres:16-alpine` 일회성 컨테이너에 SQL **36개**를 Flyway 버전 순서로 적용 후 `pg_dump --schema-only`. Inventory 37개는 SQL 36 + V29 `.conf` 1 metadata 파일이다. 비결정 라인 제거 후 **2회 덤프 byte-identical** 확인. 45 테이블/49 인덱스. |
| A2 | seed hash | **생성** | `data/extracted/scenario/` tracked 시드 소스 82개 파일별 sha256. RTK14 생성본은 git-ignore 규약상 제외(미커밋 정본) |
| A3 | PHP golden | **scope/inventory proof** | canonical T1 diff, golden inventory/head object, 0A dependency inventory만 증명한다. PHP capture/draw-for-draw replay가 실행·통과했다는 claim이 아니며 A4를 대체하지 않는다. T1/parity를 바꾸는 후속 ticket은 PHP replay가 별도로 필요하다. |
| A4 | backend/web gate | **current backend evidence + historical web artifact** | backend log/XML은 Java 21 `--rerun-tasks` one-run/no-retry 601/5050/0/0/1 (§16); `a4-web-gate.log`만 historical 54/288 record |

---

## 9. initial implementation의 historical 변경 목록

기준: 당시 `git status --short` + `git diff --stat <merge-base>` (§2의 이유로 merge-base 기준).
이는 PR #370 Round 1 working tree inventory가 아니며, current source/doc remediation은 §15의 canonical
command로 다시 판정한다.

### 수정한 기존 파일 — 4개 (기대와 정확히 일치)

| 파일 | 분류 | 승인 상태 |
|---|---|---|
| `web/game/middleware.ts` | 코드 (S3-b soft 404 수정) | 팀 리드 승인됨 |
| `.ai/task.md` | 세션 상태 | 계획 §6-1 승인 대상 |
| `.ai/current-state.md` | 세션 상태 | 〃 |
| `.ai/ownership.md` | 세션 상태 (foundation owner 등록) | 계획 §6-2 승인 대상 |

**초과 수정 0건.** `origin/main` 기준으로 추가로 보이는 3건
(`DatabaseHooks.kt`, `FlushPayloadConvergenceTest.kt`, `…diplomacy-flush-deleted-nation-review.md`)은
전부 `ad0c8c53`의 변경이며 이 티켓 소산이 아니다(§2).

### 신규 파일 (untracked) — 전량

| 경로 | 단계 | 내용 |
|---|---|---|
| `infra/src/main/resources/db/migration_v2/README.md` | S1 | v2 Flyway location (형제 경로), 규약 README만 — 마이그레이션 0건 |
| `infra/src/main/kotlin/opensamguk/infra/v2/V2SandboxGate.kt` | S2 | v2 게이트 조건 |
| `infra/src/main/kotlin/opensamguk/infra/v2/V2ContentCatalog.kt` | S3-a | read-only 콘텐츠 카탈로그 로더 |
| `infra/src/main/resources/content/v2/README.md` | S3-a | v2 콘텐츠 루트, README만 — 콘텐츠 0건 |
| `infra/src/test/kotlin/opensamguk/infra/v2/V2ContentCatalogTest.kt` | S3-a | 로더 테스트 |
| `infra/src/test/resources/v2-catalog-fixture/` | S3-a | 로더 테스트 픽스처 |
| `app/game-api/src/main/kotlin/opensamguk/gameapi/v2/V2SandboxConfiguration.kt` | S2 | 조건부 `@Configuration` |
| `app/game-api/src/test/kotlin/opensamguk/gameapi/v2/` (V2SandboxConfigurationTest, V2ProductionContextBeanGateIT) | S2/S4 | 빈 게이트 테스트 |
| `app/game-engine/src/main/kotlin/opensamguk/engine/v2/V2SandboxConfiguration.kt` | S2 | 조건부 `@Configuration` |
| `app/game-engine/src/test/kotlin/opensamguk/engine/v2/` (V2SandboxConfigurationTest, V2ContentCatalogBeanTest, V2ProductionContextBeanGateIT) | S2/S3-a/S4 | 빈 게이트·카탈로그 빈 테스트 |
| `app/gateway-api/src/test/kotlin/opensamguk/gateway/v2/V2ProductionContextBeanGateIT.kt` | S4 | **S6 실행 중(11:21) 병렬 세션이 추가** — §11 참조 |
| `web/game/app/game/v2-lab/` | S3-b | v2-lab 라우트 네임스페이스 |
| `web/game/__tests__/v2-lab-route.test.tsx` | S3-b | stage 당시 16건; A4 final historical log에는 17건 |
| `docker-compose.v2-sandbox.yml` | S5 | v2 스택 (C1 결정: production compose 분리) |
| `docs/superpowers/plans/2026-08-08-opensam-35-v2-0a-isolation-plan.md` | 계획 | — |
| `docs/loops/opensam-35-v2-0a-2026-08-08/` (측정 6종 + 본 문서 + `baseline/`) | S0~S6 | 증거 |

전부 **신규**이므로 `--diff-filter=MD` 게이트 ②③⑤ 어디에도 걸리지 않는다 — 계획 §4 예상대로.

---

## 10. UNKNOWN 목록

1. **게이트 ③·⑤ 원문 pathspec의 유효성** — vacuous임을 실증했으나(§3), 계획서 문구 교정은
   사람 판단 사항이라 하지 않았다. 교정 명령 기준 판정만 §3·§5에 병기.
2. **baseline 기준점** — `origin/main` vs merge-base. 리베이스는 S6 제약(브랜치 변경 금지)상 미수행.
3. **`--rerun-tasks` 강제 실행 결과** — gate.sh가 쓰지 않음. logic/common은 캐시 복원.
4. **프론트 테스트 284 → 287 변동 원인.**
5. **라이브 Flyway 부팅 스키마 dump** — A1은 psql 재현본, `flyway_schema_history` 미포함.
6. **컨테이너 안 v2 빈 0/1 재측정** — S5가 이미 UNKNOWN 처리(`/actuator/beans` 미노출, 노출하려면
   `application.yml` 수정 = 게이트 ⑤ 위반). S6도 재측정하지 않았다.
7. **외부 적대적 리뷰 GATE-f** — 별도 에이전트 소관, 본 문서 범위 밖.
8. **gateway-api v2 아키텍처 테스트의 현재 green 여부** — §11. 게이트 ① 커버리지 밖이고,
   남아 있는 XML은 RED다. S6는 재실행하지 않았다(소유 세션 소관 + 하드 제약).

---

## 11. B3 — 게이트 ① 실행 후 나타난 gateway-api v2 테스트 (동시 세션 산출물)

게이트 ① 완료(11:22) 직후 워킹트리를 재확인했더니 착수 시점에 없던 경로가 생겨 있었다:

```text
app/gateway-api/src/test/kotlin/opensamguk/gateway/v2/V2ProductionContextBeanGateIT.kt   (mtime 11:21)
```

S6 착수 시점 `git status`에는 없었다 = **병렬로 도는 다른 세션(S4 계열)의 in-flight 산출물**이다.
S6는 아무것도 고치지 않으므로 그대로 보고한다. 확인된 사실 3가지:

1. **게이트 ①이 이 파일을 커버하지 않는다.** `tools/parity/gate.sh`의 `backend` 타깃 tasks는
   `:common:test :logic:test :infra:test :app:game-engine:test :app:game-api:test` 5개뿐이고
   **`:app:gateway-api:test`는 빠져 있다**(`grep gateway tools/parity/gate.sh` → 0 hit).
   따라서 §1의 571 suites / 4862 tests에 gateway-api는 **포함되지 않는다.**

2. **해당 모듈의 XML은 현재 RED다** (11:31 기록, 게이트 ① 종료 이후):

   ```text
   app/gateway-api: 1 suites, 1 tests, failures 1
   opensamguk.gateway.v2.V2ProductionShapeBeanGateIT > "gateway-api registers no v2 bean()"
     AssertionFailedError: beans whose type lives in an opensamguk *.v2.* package
       expected: <{}> but was: <{probeV2Leak=opensamguk.gateway.v2.probe.ProbeV2Leak}>
     at V2ProductionContextBeanGateIT.kt:51
   ```

3. **이 RED는 의도된 mutation probe로 보인다.** 실패 원인인 `ProbeV2Leak`은
   **소스 트리에도 build 산출물에도 존재하지 않는다**(`find … -name 'ProbeV2Leak*'` → 0건).
   테스트가 실제로 발화하는지 확인하려고 일시 주입했다가 제거한 흔적이며, 남은 XML은
   그 시점의 잔재다. 다만 **S6는 이를 재실행해 green을 확인하지 않았다** — 소유 세션 소관이다.

**요청 사항(사람 판단):**
- (a) gateway-api v2 게이트 테스트의 현재 green 여부 — 소유 세션이 재실행해 확정할 것.
- (b) `tools/parity/gate.sh backend`에 `:app:gateway-api:test`를 넣을지 — 넣지 않으면
  이 티켓이 만든 gateway-api 아키텍처 테스트는 **표준 백엔드 게이트가 영원히 검증하지 않는다.**
  S6는 게이트 스크립트를 수정하지 않았다(하드 제약: 아무것도 고치지 마라).

---

## 12. 교정 게이트 재측정 (B1·B2 반영, 2026-08-08 2차)

팀 리드 지시 + GATE-f blocker에 따라 **`:(glob)` 매직 + `**` 접미 + merge-base 기준**으로 전량 재실행했다.
아래는 이 세션이 직접 실행한 **원시 출력**이다(타인의 결과 인용 아님).

```text
$ MB=$(git merge-base HEAD origin/main)
MB=fb90eac1f1241b92c5a3746cc7e30d445f174744

$ git diff --name-only --diff-filter=MD $MB -- \
    ':(glob)logic/src/main/kotlin/**' ':(glob)common/src/main/kotlin/**' \
    ':(glob)logic/src/test/resources/golden/**'
(빈 출력)

$ git diff --name-only --diff-filter=MD $MB -- \
    ':(glob)app/*/src/main/kotlin/**' ':(glob)infra/src/main/kotlin/**' \
    ':(glob)infra/src/main/resources/db/migration/**'
(빈 출력)

$ git diff --name-only --diff-filter=MD $MB -- \
    ':(glob)app/*/src/main/resources/**' ':(glob)infra/src/main/resources/**'
(빈 출력)

$ git diff --name-only --diff-filter=MD $MB -- \
    docker-compose.production.yml docker-compose.yml tools/agent-system/check.py
(빈 출력)

$ git diff --name-only --diff-filter=MD $MB
.ai/current-state.md
.ai/ownership.md
.ai/task.md
web/game/middleware.ts
```

**판정: ② ③ ⑤ C1 전부 PASS.** 전체 M/D는 `.ai/*` 3개 + `web/game/middleware.ts` 1개 =
§9의 기대 목록과 정확히 일치. 초과 0건.

**§3·§5의 결함 기록은 남긴다.** 1차 판정이 "빈 출력이라 PASS"였던 것은 **공허하게 참**이었고,
교정 후에야 유효한 PASS가 됐다. 두 사실은 다르며 이력으로 보존한다.

### 정본 수정 필요 (S6는 하지 않음)

- `docs/superpowers/plans/2026-08-08-opensam-35-v2-0a-isolation-plan.md` §3 S6 / §4의 명령 문자열
  → 팀 리드가 교정 중(재측정 시점에 §4 하단이 이미 `:(glob)` 형태로 갱신돼 있음을 확인).
- 기준선을 `origin/main`이 아니라 **merge-base**로 못박아야 한다(B2).

### 동일 결함 전수 점검 (리포 전역)

`git (diff|ls-files|log|status|grep)` 뒤에 와일드카드 pathspec을 쓰는 곳을 전수 grep했다.
`:(glob)`이 붙지 않은 취약형 hit:

| 위치 | 문자열 | 성격 |
|---|---|---|
| `docs/loops/v2-planning-2026-07-12/round3-proposal-city-guanxi.md:1218` | `-- 'app/*/src/main/resources/' 'infra/src/main/resources/'` | 문서(게이트 ⑤ 원출처). **같은 결함** |
| `docs/loops/opensam-35-v2-0a-2026-08-08/s4-production-context-bean-gate.md:217,220` | `-- 'app/*/src/main/kotlin/'`, `-- 'app/*/src/main/resources/'` | 이 티켓 S4 측정 문서. **같은 결함** |
| `docs/superpowers/plans/2026-08-08-…-isolation-plan.md` | 이미 `:(glob)` 형태로 교정됨 | 해당 없음 |
| 본 문서 §3의 인용 | 결함 실증 목적의 의도적 인용 | 해당 없음 |

**실행 코드(스크립트·CI·훅)에는 취약형이 0건이다.** 확인 범위: `scripts/`, `tools/`, `.github/`,
`.claude/`, `.codex/`, `.agents/`. `scripts/agent/verify-changes.sh`,
`tools/agent-system/check.py`, `.github/workflows/*`는 전부 pathspec 없는
`git diff --name-only <ref>` 형태라 이 결함에 해당하지 않는다.

⇒ **이 티켓 밖 항목(round3 proposal, S4 문서)은 기록만 남기고 고치지 않는다.**

---

## 13. 정리 상태 / deletion authorization boundary

- 이전 측정 기록은 컨테이너/포트/볼륨/네트워크 잔여가 없었다고 적지만, 이 artifact에는 그
  삭제에 대한 별도 target-specific approval record가 없다.
- 따라서 **BLOCKED — do not run or repeat cleanup/destructive commands**. 컨테이너·volume·network·image·worktree
  정리는 사용자의 별도 명시 deletion approval 전에는 실행하거나 “승인된 cleanup”으로 해석하면 안 된다.
- 리포 cleanup은 이 문서 범위 밖이며 current working tree는 PR Round 1 doc/source remediation을 포함한다.

---

## 14. post-remediation A4 historical remeasurement (backend artifact superseded)

### 14.1 backend

```text
$ JAVA_HOME=$(/usr/libexec/java_home -v 21) tools/parity/gate.sh backend
BUILD SUCCESSFUL in 8m 46s
XML gate green: 599 suites, 5023 tests
```

위 8m 46s 실행은 세 false-green remediation 전 historical run이라 A4 artifact로 사용하지 않는다.
remediation 후 첫 exact-tree 실행은 Docker API HTTP 500으로 `GameApiApplicationTests`의
Testcontainers 초기화가 실패했다. Docker daemon 정상화 확인 뒤 같은 명령을 한 번만 재실행했고,
그 실행이 A4 historical artifact다:

```text
BUILD SUCCESSFUL in 19m 36s
XML gate green: 599 suites, 5023 tests
```

`tools/parity/gate.sh`가 `:app:gateway-api:test` 실행과 gateway-api XML root 채점을 모두
포함하고 세 remediation까지 반영했던 historical tree의 결과다. 이는 `--rerun-tasks`가 아닌
non-forced run이므로 current exact-SHA final backend provenance가 아니다. XML 독립 집계:

| module | suites | tests | failures | errors | skipped |
|---|---:|---:|---:|---:|---:|
| common | 38 | 225 | 0 | 0 | 0 |
| logic | 277 | 3173 | 0 | 0 | 0 |
| infra | 55 | 226 | 0 | 0 | 0 |
| app/game-engine | 130 | 772 | 0 | 0 | 1 |
| app/game-api | 72 | 468 | 0 | 0 | 0 |
| app/gateway-api | 27 | 159 | 0 | 0 | 0 |
| **TOTAL** | **599** | **5023** | **0** | **0** | **1** |

위 historical transcript와 집계는 이 section에만 보존한다. `baseline/a4-backend-gate.log`와
`baseline/a4-backend-gate-xml-summary.txt`는 §16의 current Round 1 one-run artifact로 교체됐다.
skipped 1은 기존 `LongSimReplayGateTest` 백로그다.

### 14.2 web/game

최초 격리 러너의 `corepack pnpm typecheck`는 로컬 `corepack` 명령 부재로 exit 127이었다.
제품·의존성 실패가 아니므로 저장소에 설치된 동일 package manager를 직접 실행했다.

```text
$ cd web/game
$ pnpm typecheck
$ pnpm test
Test Files  54 passed (54)
Tests       288 passed (288)
```

이는 historical exit 0이며 전체 출력은 `baseline/a4-web-gate.log`에 보존했다. v2-lab route suite
17건, middleware suite 8건을 포함한다. Dependencies 부재(`tsc: command not found`)는 earlier verifier의
**historical failure**다. Later current frontend typecheck is green and Vitest JSON reports 132 suites / 288
tests / 0 failures. That frontend evidence does not replace post-commit exact-SHA review or PR CI.

### 14.3 historical diff snapshot

이 section의 old output은 당시 final-review remediation 직후 snapshot이다. current PR Round 1의
source/doc remediation은 이후 완료됐으므로 아래 seven-file listing과 “빈 출력”을 current pass로 쓰지
않는다. canonical replacement는 §15다.

```text
.ai/current-state.md
.ai/decisions.md
.ai/ownership.md
.ai/task.md
app/game-engine/build.gradle.kts
tools/parity/gate.sh
web/game/middleware.ts
```

`build.gradle.kts`는 cross-module naming guard의 raw source inputs를 선언하고, `tools/parity/gate.sh`는
gateway-api 아키텍처 테스트를 표준 gate에 포함하고 root별 XML 부재를 fail-closed하기 위한 S6 gate
수정이었다. current live source diff disposition은 §15와 PR Round 1 ledger가 정한다.

---

## 15. PR #370 Round 1 — current evidence boundary

All 23 actionable threads are resolved/dispositioned. The pre-PR GATE-f/f2/f3 and historical A4 artifacts did
not clear Round 1; the independent terminal dirty-tree reviewer did, with no findings (fingerprint `3c1b357c…`).
That clearance is exact reviewer-inspected dirty-tree only, not an immutable commit-SHA review. Merge/release/deploy
plus the linked OPENSAM-177 consumer execution are not performed.

### 15.1 canonical merge-base glob commands

```shell
MB=$(git merge-base HEAD origin/main) # current base: b847c351ff7f574c744e1f4f3da7c0410a1cbe38

# ② T1
git diff --name-only --diff-filter=MD "$MB" -- \
  ':(glob)logic/src/main/kotlin/**' ':(glob)common/src/main/kotlin/**' \
  ':(glob)logic/src/test/resources/golden/**'

# ③ T2
git diff --name-only --diff-filter=MD "$MB" -- \
  ':(glob)app/*/src/main/kotlin/**' ':(glob)infra/src/main/kotlin/**' \
  ':(glob)infra/src/main/resources/db/migration/**'

# ⑤ configuration resources
git diff --name-only --diff-filter=MD "$MB" -- \
  ':(glob)app/*/src/main/resources/**' ':(glob)infra/src/main/resources/**'

# C1 immutable production paths
git diff --name-only --diff-filter=MD "$MB" -- \
  docker-compose.production.yml docker-compose.yml tools/agent-system/check.py
```

### 15.2 observed canonical snapshot (2026-08-08; current working tree)

Observed `MB` is `b847c351ff7f574c744e1f4f3da7c0410a1cbe38` and `HEAD` is
`d8ce2abfc428b725142bfa07aa4a35a787eecbcc`. After the delegated source lanes stopped changing the tree,
each of the four commands in §15.1 produced **empty output**:

| Check | Observed output |
|---|---|
| ② T1 (`logic`/`common`/golden) | empty |
| ③ T2 existing source/migration paths | empty |
| ⑤ application/infra configuration resources | empty |
| C1 production Compose/checker paths | empty |

This is canonical scope evidence only. It does not turn the historical A4 web run into current frontend
provenance and does not replace the current backend artifact in §16; the independent dirty-tree clearance is
recorded separately in §17.

---

## 16. PR #370 Round 1 current backend gate provenance

`baseline/a4-backend-gate.log` is the supplied complete log from one current dirty-tree run of
`tools/parity/gate.sh backend`. The script enforces Java 21 and invokes Gradle with `--rerun-tasks` over all six
test roots. This was **one run / no retry**:

```text
BUILD SUCCESSFUL in 12m 35s
35 actionable tasks: 35 executed
XML gate green: 601 suites, 5050 tests
```

Its exact SHA256 is
`a35ea5cf8352e2fe518daa32dbe95343f92bf62c95dc41a3673e924aa9fcaad1`.
`baseline/a4-backend-gate-xml-summary.txt` independently records the corresponding XML roots:

| module | suites | tests | failures | errors | skipped |
|---|---:|---:|---:|---:|---:|
| common | 38 | 225 | 0 | 0 | 0 |
| logic | 277 | 3173 | 0 | 0 | 0 |
| infra | 55 | 226 | 0 | 0 | 0 |
| app/game-engine | 132 | 799 | 0 | 0 | 1 |
| app/game-api | 72 | 468 | 0 | 0 | 0 |
| app/gateway-api | 27 | 159 | 0 | 0 | 0 |
| **TOTAL** | **601** | **5050** | **0** | **0** | **1** |

The lone skip is the existing `LongSimReplayGateTest` backlog. This backend result closes the Round 1 execution
evidence for source items; it is neither an immutable committed-SHA review nor authorization for merge, release,
deploy, production observation, or OPENSAM-177 execution. The independent dirty-tree reviewer cleared Round 1 in
§17; post-commit exact-SHA review and PR CI remain residual.

---

## 17. PR #370 Round 1 independent dirty-tree terminal review

The independent terminal reviewer returned **cleared, no findings** for the exact reviewer-inspected dirty working
tree, fingerprint `3c1b357c…`. All 23 Round 1 threads are resolved/dispositioned in the review artifact.

This is intentionally not a committed-SHA claim: after a commit is created, a new independent exact-SHA review and
PR CI remain required. It neither authorizes nor records commit, push, merge, release, deploy, production
observation, or OPENSAM-177 execution.
