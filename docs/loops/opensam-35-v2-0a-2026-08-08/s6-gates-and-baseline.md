# S6 — 0A-g 기준선 artifact + 게이트 전량 실행 결과 (2026-08-08)

계획: `docs/superpowers/plans/2026-08-08-opensam-35-v2-0a-isolation-plan.md` §3 S6.
범위: **증거 수집·게이트 실행만.** 수정 0건. 커밋·푸시·PR·머지·배포 0건.

## 0. 판정 요약

| 게이트 | 판정 | 비고 |
|---|---|---|
| ① `tools/parity/gate.sh backend` | **PASS (fresh remediation 후 재실행)** | 599 suites / 5023 tests / fail 0 / err 0 / skip 1(기존 백로그), gateway-api 포함 |
| ② T1 diff 0 | **PASS** | 빈 출력 (교정 명령 재측정 §12) |
| ③ T2 diff = 사전 선언(공집합) | **1차 판정 무효(공허) → 교정 후 PASS** | §3에 결함 이력, §12에 교정 재측정 |
| ⑤ 설정 리소스 무수정 | **1차 판정 무효(공허) → 교정 후 PASS** | 동상 |
| C1 추가 확인 | **PASS** | 빈 출력 (§12) |
| 프론트 `pnpm typecheck && pnpm test` | **PASS (fresh)** | typecheck 무출력, 54 files / 288 tests pass |
| artifact 4종 | **3종 생성 + 1종 "해당 없음"(근거 명시)** | §7 |

**초기 차단 이력(현재는 전부 해소, 결함 이력을 보존한다):** 아래 B1~B3는 최초 S6 실행
시점의 사실이다. 현재 판정은 §14의 fresh 재측정이 대체한다.

- **B1 — 게이트 ③·⑤의 pathspec이 vacuous.** §3 참조. 지금 상태로는 위반을 절대 검출하지 못하는
  false-green 명령이다. **게이트 명령의 정본(계획서 §3 S6 / §4)을 반드시 고쳐야 한다** — S6는
  고치지 않는다(하드 제약). 병렬 GATE-f 리뷰어도 같은 건을 blocker로 잡았고, 팀 리드가 교정
  명령을 지시했다. 교정 명령 재측정 결과는 §12. **결함 이력은 통과했다고 지우지 않는다.**
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

### 실행 명령

```
tools/parity/gate.sh backend > <scratchpad>/gate-backend.log 2>&1
```

### 출력 tail (원시)

```
BUILD SUCCESSFUL in 23m 9s
29 actionable tasks: 6 executed, 4 from cache, 19 up-to-date
Configuration cache entry reused.
XML gate green: 571 suites, 4862 tests
```

전체 로그: `baseline/a4-backend-gate.log` (sha256 `4d60d74…`, 줄 끝 공백 1개 정규화).

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

### 이번 티켓의 v2 suite가 실제로 실행됐는지 확인 (XML 실재)

```
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

```
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

```
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

```
M	.ai/current-state.md
M	.ai/ownership.md
M	.ai/task.md
M	web/game/middleware.ts
```

**이 문서의 게이트 ②③⑤는 `origin/main` 기준과 merge-base 기준 둘 다 기록한다.**
어느 쪽을 정본 baseline으로 삼을지는 사람 판단 사항이다(리베이스 = 브랜치 변경이라 S6 제약상 금지).

---

## 3. 게이트 ③ — T2 diff **+ 명령 결함 B1**

### 계획서 그대로의 명령

```
$ git diff --name-only --diff-filter=MD origin/main -- \
    'app/*/src/main/kotlin/' infra/src/main/kotlin/ infra/src/main/resources/db/migration/
(빈 출력)
```

빈 출력 = 형식상 PASS. **그러나 이 명령은 신뢰할 수 없다.**

### 결함 실증

`origin/main`에는 `app/game-engine/src/main/kotlin/…/DatabaseHooks.kt` 차이가 **실재한다**
(§2 참조). 그런데 위 명령은 그것을 잡지 못했다:

```
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

### 교정 명령으로 재실행한 실제 판정

```
$ git diff --name-only --diff-filter=MD origin/main -- \
    'app/*/src/main/kotlin/**' infra/src/main/kotlin/ infra/src/main/resources/db/migration/
app/game-engine/src/main/kotlin/opensamguk/engine/flush/DatabaseHooks.kt      ← ad0c8c53(남의 것, B2)

$ git diff --name-only --diff-filter=MD <merge-base> -- \
    'app/*/src/main/kotlin/**' infra/src/main/kotlin/ infra/src/main/resources/db/migration/
(빈 출력)
```

**판정: 이 티켓 귀속 기준(merge-base)으로 T2 수정/삭제 0건 = 계획 §4의 사전 선언(공집합)과 정확히 일치. PASS.**
`origin/main` 기준의 1건은 `ad0c8c53`의 것으로 이 티켓 산출물이 아니다(§2).

---

## 4. 게이트 ② — T1 diff 0

```
$ git diff --name-only --diff-filter=MD origin/main -- \
    logic/src/main/kotlin/ common/src/main/kotlin/ logic/src/test/resources/golden/
(빈 출력)

$ git diff --name-only --diff-filter=MD <merge-base> -- \
    logic/src/main/kotlin/ common/src/main/kotlin/ logic/src/test/resources/golden/
(빈 출력)
```

세 pathspec 모두 와일드카드가 없는 순수 디렉터리 접두라 §3의 결함에 해당하지 않는다.
**PASS — 양쪽 기준 모두 빈 출력.**

보강 증거: golden 트리 객체 해시가 HEAD·origin/main 동일
(`3650b814950fb9f0d784ae1e4031a05658919ea4`), 워킹트리 golden 경로 변경 0건.

---

## 5. 게이트 ⑤ — 설정 리소스 무수정

```
$ git diff --name-only --diff-filter=MD origin/main -- \
    'app/*/src/main/resources/' infra/src/main/resources/
(빈 출력)
```

`'app/*/src/main/resources/'`는 §3과 **동일한 vacuous 결함**을 갖는다(와일드카드 + 트레일링 슬래시).
`infra/src/main/resources/` 쪽은 정상 동작한다.

교정 재실행:

```
$ git diff --name-only --diff-filter=MD origin/main -- \
    'app/*/src/main/resources/**' infra/src/main/resources/
(빈 출력)

$ git diff --name-only --diff-filter=MD <merge-base> -- \
    'app/*/src/main/resources/**' infra/src/main/resources/
(빈 출력)
```

**PASS — 교정 명령으로도 빈 출력.** `application.yml` 등 설정 리소스 수정 0건이 실제로 확인된다.
(`infra/src/main/resources/db/migration_v2/`·`content/v2/`는 **신규 파일**이라 `--diff-filter=MD`에 걸리지 않는다 — 계획 §4 예상대로.)

---

## 6. 추가 C1 확인

```
$ git diff --name-only --diff-filter=MD origin/main -- \
    docker-compose.production.yml docker-compose.yml tools/agent-system/check.py
(빈 출력)

$ git diff --name-only --diff-filter=MD <merge-base> -- (동일)
(빈 출력)
```

**PASS.** 계획 §2 C1 결정대로 `docker-compose.production.yml` 무수정 ·
`tools/agent-system/check.py` 수정 0 · v2는 신규 `docker-compose.v2-sandbox.yml`로 분리.
해당 신규 파일에서 확인된 값: `V2_ENABLED: "true"`(3개 서비스),
`SCENARIO_SEED_ENABLED: ${V2_SCENARIO_SEED_ENABLED:-true}` — production 불변식과 파일이 분리돼 있다.

---

## 7. 프론트 게이트

`corepack`이 호스트에 없어 `pnpm` 직접 호출(S3-b와 동일).

```
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

**PASS.** 전체 출력: `baseline/a4-web-gate.log`.

**변동 고지:** 3회 실행 중 1회차만 `284 passed`, 2·3회차는 `287 passed`(파일 수는 3회 모두 54,
모든 회차 전부 pass, 실패 0). 3건 차이의 원인 **UNKNOWN** — 수정하지 않고 기록만 한다.

---

## 8. artifact 4종 생성 현황

상세·해시·생성 절차는 `baseline/MANIFEST.md`. 요약:

| # | artifact | 상태 | 근거 |
|---|---|---|---|
| A1 | v1 schema dump | **생성** | `postgres:16-alpine` 일회성 컨테이너에 V*.sql 37개를 Flyway 버전 순서로 적용 후 `pg_dump --schema-only`. 비결정 라인 제거 후 **2회 덤프 byte-identical** 확인. 45 테이블/49 인덱스. 컨테이너 삭제 완료 |
| A2 | seed hash | **생성** | `data/extracted/scenario/` tracked 시드 소스 82개 파일별 sha256. RTK14 생성본은 git-ignore 규약상 제외(미커밋 정본) |
| A3 | PHP golden | **"해당 없음"** (필요한데 못 한 것 아님) | ① T1 diff 0 ② golden 트리 해시 HEAD=origin/main 동일 ③ v2 Kotlin 10파일에 `opensamguk.logic`/`opensamguk.common` import·`RandUtil`/`PhpRound`/`LiteHashDrbg`/`ConvertLog`/`Josa` **전부 0 hit**. RNG·라운딩·로그 경로 접점 0 ⇒ 새 캡처 불필요. 골든 불변은 게이트 ①의 `:logic:test` 3173건 green으로 입증. 회귀 비교용 골든 273개 파일별 sha256 인벤토리는 별도 생성 |
| A4 | backend/web gate | **생성** | `a4-backend-gate.log` + XML 독립 집계 + `a4-web-gate.log` |

---

## 9. 이번 티켓의 전체 변경 목록

기준: `git status --short` + `git diff --stat <merge-base>` (§2의 이유로 merge-base 기준).

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
| `web/game/__tests__/v2-lab-route.test.tsx` | S3-b | 라우트 게이트 테스트 16건 |
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

```
app/gateway-api/src/test/kotlin/opensamguk/gateway/v2/V2ProductionContextBeanGateIT.kt   (mtime 11:21)
```

S6 착수 시점 `git status`에는 없었다 = **병렬로 도는 다른 세션(S4 계열)의 in-flight 산출물**이다.
S6는 아무것도 고치지 않으므로 그대로 보고한다. 확인된 사실 3가지:

1. **게이트 ①이 이 파일을 커버하지 않는다.** `tools/parity/gate.sh`의 `backend` 타깃 tasks는
   `:common:test :logic:test :infra:test :app:game-engine:test :app:game-api:test` 5개뿐이고
   **`:app:gateway-api:test`는 빠져 있다**(`grep gateway tools/parity/gate.sh` → 0 hit).
   따라서 §1의 571 suites / 4862 tests에 gateway-api는 **포함되지 않는다.**

2. **해당 모듈의 XML은 현재 RED다** (11:31 기록, 게이트 ① 종료 이후):

   ```
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

```
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

## 13. 정리 상태

- 측정용 컨테이너 `opensam35-s6-v1schema` 삭제 완료 (`docker ps -a | grep opensam35` → 0건).
- 비표준 포트 55435만 사용, 볼륨·네트워크 잔여 0.
- 리포에 수정 0건 (본 문서와 `baseline/`은 신규 문서 산출물).

---

## 14. remediation 후 최종 재측정 (현재 정본)

### 14.1 backend

```text
$ JAVA_HOME=$(/usr/libexec/java_home -v 21) tools/parity/gate.sh backend
BUILD SUCCESSFUL in 8m 46s
XML gate green: 599 suites, 5023 tests
```

위 8m 46s 실행은 세 false-green remediation 전 실행이라 최종 artifact로 사용하지 않는다.
remediation 후 첫 exact-tree 실행은 Docker API HTTP 500으로 `GameApiApplicationTests`의
Testcontainers 초기화가 실패했다. Docker daemon 정상화 확인 뒤 같은 명령을 한 번만 재실행했고,
그 실행이 현재 정본이다:

```text
BUILD SUCCESSFUL in 19m 36s
XML gate green: 599 suites, 5023 tests
```

`tools/parity/gate.sh`가 `:app:gateway-api:test` 실행과 gateway-api XML root 채점을 모두
포함하고 세 remediation까지 반영한 exact-tree 결과다. XML 독립 집계:

| module | suites | tests | failures | errors | skipped |
|---|---:|---:|---:|---:|---:|
| common | 38 | 225 | 0 | 0 | 0 |
| logic | 277 | 3173 | 0 | 0 | 0 |
| infra | 55 | 226 | 0 | 0 | 0 |
| app/game-engine | 130 | 772 | 0 | 0 | 1 |
| app/game-api | 72 | 468 | 0 | 0 | 0 |
| app/gateway-api | 27 | 159 | 0 | 0 | 0 |
| **TOTAL** | **599** | **5023** | **0** | **0** | **1** |

전체 로그는 `baseline/a4-backend-gate.log`, 독립 집계는
`baseline/a4-backend-gate-xml-summary.txt`에 보존했다. skipped 1은 기존
`LongSimReplayGateTest` 백로그다.

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

exit 0, 전체 출력은 `baseline/a4-web-gate.log`에 보존했다. v2-lab route suite 17건 포함.

### 14.3 diff 제약

`MB=$(git merge-base HEAD origin/main)` 기준으로 ② T1, ③ T2, ⑤ 설정 리소스, C1 production
stack 불변 경로는 모두 빈 출력이다. final-review remediation 뒤 전체 M/D는 정확히 다음 7개다.

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
수정이다. 둘 다 T2 production source 범위가 아니다. 계획서 §4-1 기대 목록도 같은 7개로 교정했다.
