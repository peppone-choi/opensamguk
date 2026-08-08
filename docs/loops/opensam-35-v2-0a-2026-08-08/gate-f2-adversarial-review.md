# GATE-f2 — OPENSAM-35 V2-0A 2차 독립 적대적 리뷰

- 리뷰어: 외부 fresh reviewer (`op35-gate-f2`), 읽기·검증 전용. 리포 수정 0건.
- 일시: 2026-08-08
- 대상: 브랜치 `op-35-v2-0a` 워킹트리 (merge-base `fb90eac1f1241b92c5a3746cc7e30d445f174744`)
- 1차 문서: `gate-f-adversarial-review.md` (덮어쓰지 않음)
- 판정: **fix-required** — blocker 0 / fix-required 4 / question 8 / nit 3

표기 규약: **[실측]** = 이 리뷰가 직접 명령을 실행해 얻은 결과. **[판독]** = 코드/문서를 읽어
확정한 사실(실행 없음). **[추측]** = 근거가 일반 지식뿐이고 이 환경에서 재지 못한 것.
**[UNKNOWN]** = 재려 했으나 재지 못한 것.

---

## 1. 결함

### blocker

없음. 티켓이 지키겠다고 선언한 8개 제약(T1 / T2 공집합 / 게이트 ⑤ / C1 / 0A-a / 0A-b·f /
0A-c / 승인된 유일 수정 1건)을 전부 직접 재측정했고, **하나도 깨뜨리지 못했다.** 근거는 §3.

### fix-required

- `docs/superpowers/plans/2026-08-08-opensam-35-v2-0a-isolation-plan.md:580` — "이름 휴리스틱 탐지 테스트는 오탐만 늘리므로 만들지 않았다"는 **미측정 단정**이다. **[실측]** `grep -rn "class V2\|object V2" app infra common logic --include="*.kt" | grep /src/main/` = 6건이고 그중 5건이 이미 `opensamguk.*.v2.*` 패키지 안, 나머지 1건은 `infra/src/main/kotlin/db/migration/V26__npc_lifecycle_phase_units.kt:13`(`V2` 뒤가 숫자라 `V2[A-Z]` 패턴 비매치). 즉 오늘 기준 이름 휴리스틱 게이트의 **오탐은 0건**이다. 1차가 blocker 아래에서 잡은 `havingValue` 주석과 **정확히 같은 실패 유형**(측정 없이 반대 결론을 단정)이며, 이 문장이 Q4를 미룬 유일한 근거다.
- `app/gateway-api/src/test/kotlin/opensamguk/gateway/v2/V2ProductionContextBeanGateIT.kt:46` — "컨텍스트가 실제로 떴다"의 대체 확인이 목적을 달성하지 못한다. **[판독]** 어서션이 `it.startsWith("opensamguk.gateway") || it.contains("Controller")`인데, 컴포넌트 스캔 빈의 정의 이름은 FQN이 아니라 decapitalized 단순명이라 좌변은 실동작상 죽은 항이고, 우변은 **gateway 고유 빈이 아니어도** 이름에 `Controller`가 든 프레임워크 빈 하나로 충족된다(**[추측]** `basicErrorController` / `ErrorMvcAutoConfiguration` — 이 컨텍스트에서 직접 재지 못했다. XML에는 빈 목록이 없다). 문서(`s4-…md`, 이 파일 KDoc:36-39)가 "양성 대조군이 없는 대신 이걸로 대체한다"고 명시적으로 제시한 근거이므로, 근거가 목적을 encode 하지 못하는 것은 서술 오류가 아니라 판정 근거의 결함이다.
- `docs/loops/opensam-35-v2-0a-2026-08-08/baseline/a4-backend-gate.log` + `a4-backend-gate-xml-summary.txt` — 게이트 ① 증거가 **최종 트리를 덮지 않는다**. **[실측]** 요약의 최신 XML mtime은 `app/game-api 11:20:12`·`app/game-engine 11:09:31`인데, 그 뒤 추가된 테스트가 실재한다: `app/game-engine/.../V2SandboxConfigurationTest.xml` `tests="7"`(대소문자 실측 케이스 추가분, mtime 11:34), `app/gateway-api/.../v2/*.xml` 4개(mtime 11:37). 즉 기록된 ① 증거는 1차 지적 처리 **이전** 상태의 것이다. (팀 리드가 재실행 중임을 확인 — §4 참조.)
- `tools/parity/gate.sh:15` — 백엔드 게이트 정본에 `:app:gateway-api:test`가 없다. **[실측]** `tasks=( ":common:test" ":logic:test" ":infra:test" ":app:game-engine:test" ":app:game-api:test" )`. 이 티켓이 1차 fix-required("gateway-api 미커버")의 해답으로 내놓은 gateway IT는 **이 티켓 자신의 acceptance 게이트(계획 §3 게이트 ①)로는 영원히 실행되지 않는다.** 팀 리드의 의심 4는 절반만 맞다 — CI에서는 죽지 않는다: **[실측]** `.github/workflows/ci.yml:28`·`deploy.yml:38`이 `./gradlew build`(모듈 전체 `test` 포함)를 돌린다. 그러나 "기록만 남기고 안 고침"은 게이트 정본과 acceptance 기준이 실제로 어긋난 상태를 그대로 둔다.

### question

- `app/game-engine/src/main/kotlin/opensamguk/engine/flush/DaemonWriteGuard.kt:29-34` vs `app/game-engine/src/main/kotlin/opensamguk/engine/v2/V2SandboxConfiguration.kt:19-21` — **[실측]** `writePathPackages`는 `engine/flush|turn|run|nationbulk` 4개뿐이고 `engine/v2`는 없다. 설정 클래스 주석은 이를 "v1 가드 테스트를 건드리지 않는다"는 **이점**으로만 적었는데, 같은 사실이 **커버리지 구멍**이기도 하다 — 앞으로 `opensamguk.engine.v2`에 들어올 v2 원장 store가 `EntityManager`를 써도 `DaemonNoEntityManagerTest`가 잡지 않는다. one-daemon-write-rule은 이 리포의 하드 불변식이므로 소비자 티켓 규약(§4-2)에 이 항목이 빠진 것이 맞는가.
- `docs/loops/opensam-35-v2-0a-2026-08-08/s3b-v2lab-route.md:321` — `output: standalone` 서버에서 `V2_ENABLED` 런타임 읽기를 재측정하지 않았다(문서가 스스로 한계로 기록). **[판독]** `docker/web-game.Dockerfile` 경로의 프로덕션 런타임은 standalone이고, `docker-compose.v2-sandbox.yml:297`은 빌드 인자가 아니라 **런타임 env**로 `V2_ENABLED: "true"`를 준다. 실패 방향은 fail-closed(빌드타임 인라인이면 `undefined` → 404)라 **누출 위험은 없으나**, v2 스택이 실제로 열리는지가 미검증이다.
- `infra/src/main/resources/content/v2/README.md:6-14` · `infra/src/main/resources/db/migration_v2/README.md:32-37` — v2 **데이터**는 v1 production 이미지에 그대로 구워진다(jar 베이크가 설계 근거로 채택됨). 게이팅되는 것은 리더 빈(`V2ContentCatalog`)과 Flyway location뿐이다. 0A의 위협모델("v2 코드가 v1로 새지 않는다")에는 부합하나, 콘텐츠·마이그레이션 SQL이 v1 아티팩트에 동봉되는 것이 격리 티켓의 의도와 일치하는지 명시가 없다.
- `infra/src/test/kotlin/opensamguk/infra/v2/V2ContentCatalogTest.kt:26` — 운영 location(`content/v2`)의 파일 수가 0임을 assert한다. OPENSAM-150이 첫 v2 콘텐츠 JSON을 넣는 순간 **무관한 테스트가 빨개진다.** 의도된 fail-loud인가, 아니면 픽스처로 옮겨야 하는가(다른 6개 케이스는 이미 픽스처 기반이다).
- `docs/superpowers/plans/2026-08-08-opensam-35-v2-0a-isolation-plan.md:551-552` — 게이트 ⑤가 `--diff-filter=MD`라 `app/*/src/main/resources/**`·`infra/src/main/resources/**`의 **신규 추가**를 원리적으로 못 잡는다. 이번 티켓은 실제로 그 아래 2개 파일을 추가했다(**[실측]** `infra/src/main/resources/content/v2/README.md`, `.../db/migration_v2/README.md` — 둘 다 `??`). 이번엔 무해하나, 같은 게이트로 `app/game-engine/src/main/resources/application-v2-sandbox.yml` 신설도 통과한다. "설정 리소스 무수정"의 범위가 M/D로 충분한가.
- `app/gateway-api/src/test/kotlin/opensamguk/gateway/v2/V2ProductionContextBeanGateIT.kt:63-83` — 4칸(①②③④)이 **전부 동일한 assert(0)** 이다. gateway-api에는 게이트도 v2 빈도 없으므로 4칸 모두 항진명제이며, 스프링 컨텍스트 4개를 띄워 같은 사실을 4번 잰다. 트립와이어로서의 가치는 1칸으로 충분하지 않은가.
- `v2-sandbox.env.example` — **[실측]** `grep -rn "v2-sandbox.env.example\|docker-compose.v2-sandbox" README.md AGENTS.md CLAUDE.md .env.example docs/agent/` = 0건. 1차 question 3("운영자가 값을 알 방법이 리포에 없다")은 파일 신설로 닫혔지만 **발견 경로는 여전히 compose 헤더 주석(`docker-compose.v2-sandbox.yml:25`) 하나뿐**이다.
- `docker-compose.v2-sandbox.yml:315` — v2 스택 nginx가 v1과 **같은** `./infra/nginx/nginx.conf`를 마운트한다. 서비스명이 동일해 upstream DNS는 v2 네트워크 안에서 해소될 것으로 보이나(**[추측]**, 스택을 띄워 재지 않았다), v1 전용 `server_name`/포트/경로 접두사가 들어 있으면 v2 스택에서만 조용히 어긋난다. **[UNKNOWN]**

### nit

- `infra/src/main/kotlin/opensamguk/infra/v2/V2SandboxGate.kt:34` — `V2SandboxMarker`가 파일명과 다른 타입으로 같은 파일에 산다.
- `docs/loops/opensam-35-v2-0a-2026-08-08/s4-production-context-bean-gate.md:185` — engine `V2SandboxConfigurationTest tests="6"`으로 기록돼 있으나 현재 트리는 `tests="7"`(**[실측]** XML). 시점 측정 문서라 오류는 아니나 최신 상태와 어긋난다.
- `web/game/middleware.ts:48-50` — `RESERVED_PATH_SERVER_IDS`의 `'v2-lab'`은 자신이 주석에 적은 대로 도달 불가한 죽은 항목이다(**[실측]** `PATH_SERVER_ID = /^[a-z0-9]{1,48}$/`가 `has()` 앞에서 하이픈을 거른다). 1차 nit의 요청대로 넣은 것이므로 되돌리라는 뜻은 아니다.

---

## 2. 1차 지적 15건 처리 검증

| # | 1차 항목 | 판정 | 근거 |
|---|---|---|---|
| B1 | 게이트 ③ pathspec `'app/*/src/main/kotlin/'` vacuous | **해소** | **[실측]** 계획서 §4-1(:537-560)에 `:(glob).../**` + merge-base 정본 신설. 내가 `MB=$(git merge-base HEAD origin/main)`(=`fb90eac1`)로 ③ 재실행 → 빈 출력. `origin/main`은 실제로 `b7735659`까지 전진해 있어 기준선 고정이 필수임도 재확인 |
| B2 | 게이트 ⑤ pathspec vacuous | **해소** | **[실측]** ⑤ `:(glob)app/*/src/main/resources/**` `:(glob)infra/src/main/resources/**` → 빈 출력. C1 pathspec도 빈 출력 |
| F1 | `layout.tsx:9-11` "백엔드와 동일 규약" 거짓 주석 | **해소** | **[판독]** 주석(:9-14)이 "의도적으로 더 엄격 / 백엔드는 `equalsIgnoreCase`"로 정정됐고 `@Profile` 2차 조건이라는 비대칭 허용 근거를 명시. **[실측]** `V2SandboxConfigurationTest:60-68` `property value is matched case-insensitively`가 `TRUE/True/tRuE` → 빈 1개를 실측으로 고정(XML `tests="7" failures="0"`) |
| F2 | `v2-lab-route.test.tsx:52` 거짓 근거 주석 | **해소** | **[실측]** 주석 정정(:52-54) + `TRUE/True/tRuE` → 404 케이스가 layout(:55-62)과 middleware(:125-132) 양쪽에 추가됨. vitest 17 tests 통과 |
| F3 | gateway-api 미커버 | **부분 해소** | IT 4칸 신설·compose:103-106에 "의도적 부재" 명시로 커버 의도는 닫혔으나, 양성 대조 어서션이 목적 미달(§1 fix-required 2)이고 `tools/parity/gate.sh:15`가 이 IT를 실행하지 않는다(§1 fix-required 4) |
| F4 | `.ai/current-state.md` stale | **부분 해소** | **[판독]** 갱신됐으나 **다시 stale**이다: `.ai/current-state.md`의 신규 절이 "**S6 진행 중** — artifact 4종 + MANIFEST + 게이트 ①⑤ 실행 중", "잔여 fix-required 3건 **처리 중**"으로 남아 있다. 실제로는 `baseline/MANIFEST.md`+아티팩트 5종이 존재하고 s6 문서가 ②③⑤ C1 PASS를 기록했으며 fix-required 3건도 코드에 반영돼 있다 |
| Q1 | s5 "모든 v2 값에 `V2_` 접두" 전수 주장 거짓 | **해소** | **[판독]** `docker-compose.v2-sandbox.yml:17-21`이 예외 7종(`TZ`·`NODE_ENV`·JWT 만료 2종·JAVA_OPTS 3종)을 명시하고 "전수 적용이 아니다"로 주장을 축소 |
| Q2 | `:?`가 값 **내용**을 검증하지 않음 | **해소(수용 정당)** | **[판독]** compose:29-34 + `v2-sandbox.env.example:26-29`에 한계 명시. **[실측]** 오염 방향은 v1 콘텐츠 → v2 DB 단방향이고, v2 스택은 자기 네트워크의 `postgres`만 참조하며 시나리오 마운트는 `:ro`(:137,:187,:244)다. production을 오염시키는 경로가 아니므로 0A(= production 격리)의 DoD와 무관하다는 판단은 정당하다. 팀 리드 의심 5에 대한 답: **막았어야 하는 게 아니다** |
| Q3 | 필수 `V2_*` 5종이 문서 어디에도 없음 | **해소(잔여 nit)** | **[실측]** `v2-sandbox.env.example` 신설(7,338 B, 전수 변수). 단 README/AGENTS/.env.example 어디서도 참조되지 않음(§1 question 7) |
| Q4 | 패키지 규약이 코드로 강제되지 않음 | **부분 해소 + 새 결함 유발** | 계획 §4-2(:569-586)에 규약을 명문화하고 소비자 티켓(OPENSAM-150/151)에 귀속시킨 것은 정당하다 — **[실측]** 현재 트리에 v2 런타임 코드가 0건이므로 강제할 대상 자체가 없고, ADR-LITE-021 (iii)이 요구한 것은 "실측 빈 카운트"이지 "미래 규약 강제"가 아니다. 따라서 **미룸 자체는 DoD를 충족한다**(팀 리드 의심 6에 대한 답). 그러나 미룸의 근거 문장이 미측정 단정이고 실측하면 거짓이다(§1 fix-required 1) |
| Q5 | `V2ContentCatalogBeanTest` 측정범위 과대진술 | **해소** | **[판독]** 테스트 KDoc(:40-46)이 "맨몸 `ApplicationContextRunner`이며 이 0은 앱 컨텍스트를 뜻하지 않는다"고 범위를 스스로 축소 |
| Q6 | 기준선 `origin/main` 드리프트 | **해소** | **[실측]** §4-1이 merge-base 고정을 정본화. 내가 재실행한 5개 명령 전부 기대값과 일치(§3.1) |
| Q7 | 정적 자산이 미들웨어 matcher 밖 | **해소** | **[판독]** `s3b-…md` §7이 프로덕션 빌드로 실측: 청크는 200으로 서빙되나 내용이 556 B 빈 스텁이고(대조군 `/game/join` 20,417 B), 누출되는 것은 Sentry 라우트 매니페스트의 **경로 이름 하나**뿐. 이 전제를 지키는 회귀 가드(`'use client'` 소스 스캔)도 함께 신설 |
| nit1 | `RESERVED_PATH_SERVER_IDS`에 `v2-lab` 없음 | **해소** | **[실측]** `middleware.ts:50` 추가, 도달 불가 사유를 주석에 정직하게 병기 |

**새 결함 유발 여부(팀 리드 의심 1):** 없음. §3.2 참조.

---

## 3. 공격했으나 뚫리지 않은 것

### 3.1 diff 게이트 5종 — 직접 재실행 **[실측]**

```text
MB=fb90eac1f1241b92c5a3746cc7e30d445f174744   (origin/main = b7735659, 전진 상태)
② T1   :(glob)logic/src/main/kotlin/** common/src/main/kotlin/** logic/src/test/resources/golden/**  → (빈 출력)
③ T2   :(glob)app/*/src/main/kotlin/** infra/src/main/kotlin/** infra/src/main/resources/db/migration/** → (빈 출력)
⑤ 설정 :(glob)app/*/src/main/resources/** infra/src/main/resources/**                                  → (빈 출력)
C1     docker-compose.production.yml docker-compose.yml tools/agent-system/check.py                     → (빈 출력)
전체 M/D → .ai/current-state.md · .ai/ownership.md · .ai/task.md · web/game/middleware.ts (4개, 정확히 일치)
```

T1 수정·삭제 0건, T2 공집합, 설정 리소스 무수정, C1 무수정, 승인된 유일 기존 파일 수정 1건 —
**전부 재현됨.** 계측기 결함(B1/B2)은 실제로 고쳐졌고 제약도 실제로 지켜졌다.

### 3.2 v1 라우팅 회귀 (팀 리드 의심 1) **[실측]**

- `node_modules/.bin/vitest run __tests__/middleware.test.ts __tests__/v2-lab-route.test.tsx` → **25 passed** (기존 8 무수정 + 신규 17).
- `node_modules/.bin/tsc --noEmit -p tsconfig.json` → 오류 0.
- 접두 충돌: `/game/v2-lab-x` → **[실측]** `next()` 호출(테스트 :141-145) + **[판독]** `isV2LabPath`가 `rest[0] === 'v2-lab'` 완전 일치라 접두 매치 불가.
- 다른 serverId 경유: `/game/<타서버>/v2-lab` → **[실측]** `find web/game/app -name '*[*'` 결과 `/game` 하위에 동적 세그먼트가 **0개**(`app/api/game/[...path]`만 존재) ⇒ 미들웨어가 통과시켜도 Next 라우터가 진짜 404. 우회 경로 아님.
- `?server=` 쿼리 경로: **[판독]** v2 게이트가 `middleware()` 첫 문(:89)으로 쿼리 분기(:94)·rewrite 분기(:103)보다 앞이라 우회 불가.
- 쿠키 분기: **[판독]** `setServerCookie`는 v2 게이트 이후 분기에서만 호출되고, 404 반환 경로는 쿠키를 건드리지 않는다. 기존 `middleware.test.ts`가 무수정으로 전부 green.

### 3.3 신규 테스트 비공허성 — 변이 프로브 **[실측]**

원복 전제 하에 두 곳을 동시에 변이시켰다.

| 변이 | 결과 |
|---|---|
| `middleware.ts:83` `rest[0] === 'v2-lab'` → `'v2-labZZZ'` | 8 tests FAIL |
| `app/game/v2-lab/page.tsx` 첫 줄에 `'use client';` 삽입 | 정적 자산 스캔 테스트 FAIL (`clientFiles`에 page.tsx 출력) |

합계 **8 failed / 17 passed**. 원복 후 재실행 **25 passed**, `git diff --stat -- web/game/middleware.ts` =
`1 file changed, 21 insertions(+)`(변이 전과 동일), `page.tsx` 첫 줄 `/**` 복원, `git status --porcelain`이
리뷰 시작 시점과 동일. **원복 증명 완료.**

`'use client'` 스캔의 커버리지(팀 리드 의심 2) **[판독]**: 경로는 `join(__dirname,'..','app','game','v2-lab')`로
정확하고, `walk()`가 **확장자 무관 전 파일**을 훑으며, 정규식 `/^\s*['"]use client['"]/m`이 홑/겹따옴표와
첫 줄이 아닌 위치를 모두 덮는다. 디렉터리 부재 시 `readdirSync`가 throw하고, `expect(files.length).toBeGreaterThan(0)`가
빈 스캔을 막는다. **조용히 통과하는 경로를 찾지 못했다.** 한계는 테스트 KDoc(:158-159)이 스스로
정직하게 기록한 그대로(외부 클라이언트 컴포넌트 import는 미탐지).

### 3.4 Flyway 격리 0A-c (팀 리드 의심 8) **[실측]**

`docker-compose.v2-sandbox.yml:45-47`의 앵커 `x-v2-flyway-locations`가 **3개 JVM 서비스 전부**에
적용된다: `gateway-api :102`, `game-api :154`, `game-engine :204`. 값은 세 곳 모두
`classpath:db/migration,classpath:db/migration_v2` — S0의 치환 semantics(v1 location 필수 포함)와
S1의 형제 경로 결론에 정확히 일치한다. **빠지거나 다른 서비스 0건.**
v1 compose 2종에는 `SPRING_FLYWAY_LOCATIONS`가 없어 기본값이 유지된다(**[실측]** `grep -n SPRING_FLYWAY_LOCATIONS docker-compose*.yml` → v2 파일만 hit).
S1 문서는 형제 격리를 프로브 `V902__s1_probe_sibling.sql`로 실측했고(U2: v1 38행, v2행 0건),
프로브 잔여는 트리에 0건(**[실측]** `migration_v2/`에 README.md만).

### 3.5 compose 유효성·fail-closed **[실측]**

```text
$ V2_SCENARIO_CODE=… V2_SCENARIO_HOST_DIR=… V2_POSTGRES_PASSWORD=… V2_JWT_SECRET=… V2_ADMIN_PASSWORD=… \
    docker compose -f docker-compose.v2-sandbox.yml config -q   → COMPOSE_CONFIG_OK
$ docker compose -f docker-compose.v2-sandbox.yml config -q      → error: required variable V2_POSTGRES_PASSWORD is missing a value
```

`:?` fail-closed가 실제로 동작한다. 파일 자체도 유효한 compose 스펙이다.

### 3.6 프로파일 활성화의 v1 부작용 **[실측]**

`SPRING_PROFILES_ACTIVE=v2-sandbox` 주입이 v1 동작을 바꾸는지 공격했다.
`grep -rn "@Profile" app infra common logic --include="*.kt"` → v2 게이트 2건 외 **0건**.
`app/*/src/main/resources/*.yml`에 profile 섹션 **0건**. ⇒ 프로파일 전환이 v1 빈 구성을 바꾸지 않는다.

### 3.7 컴포넌트 스캔 루트·게이트 조건 **[실측]/[판독]**

- 스캔 루트 확인: `opensamguk.gameapi` / `opensamguk.engine` / `opensamguk.gateway`(각 `*Application.kt:1`) ⇒ `…gameapi.v2` / `…engine.v2`는 루트 안, gateway엔 v2 설정 자체가 없음 — 문서 주장과 일치.
- 게이트 AND 조건: `V2SandboxConfigurationTest` engine `tests="7"` / api `tests="5"`, `failures="0" errors="0"`(XML 실측). `profile only`=0 · `property only`=0 · `both`=1이 함께 green이므로 두 조건이 **각각** 유효함이 증명된다(한쪽만 유효해도 통과하는 배치가 아니다).
- `V2ContentCatalogTest` `tests="7" failures="0"`, 상수풀 스캔이 비공허성 양성 대조(:92-93 `PathMatchingResourcePatternResolver`·`content/v2` 존재 assert)를 갖는다.
- `V2ContentCatalog.read()` 경로 탈출: 파일명 완전 일치 대조라 `../v2-decoy/decoy.json`이 구조적으로 불가(:37-40, 테스트 :50).

### 3.8 문서-코드 대조 (팀 리드 의심 7) **[실측]**

1차 정정 문장들을 코드로 재대조했다. **어긋난 것을 찾지 못했다.**

| 주장 | 위치 | 대조 결과 |
|---|---|---|
| layout `notFound()`만으로는 200 — `/game/**`가 client 경계 | `middleware.ts:73-76` | `app/game/layout.tsx`가 `AuthGate`를 렌더하고 `components/AuthGate.tsx:1`이 `'use client'` — 참 |
| `/game/<serverId>/v2-lab`이 rewrite로 접힌다 | `middleware.ts:80-82` | rewrite 분기(:103-113)와 `isV2LabPath`(:82)가 **같은** `segments[2]===configuredServerId()` 조건 — 참 |
| `v2-lab`은 `PATH_SERVER_ID` 때문에 도달 불가 | `middleware.ts:48-50` | `isPublicServerId`가 정규식 → Set 순서 — 참 |
| `V2_ENABLED` env ↔ `v2.enabled` relaxed binding | `V2SandboxGate.kt:14-17` | `V2_ENABLED env var maps onto the gate property` 테스트가 실측 고정 — 참 |
| `engine.v2`는 `writePathPackages`/`HotColdCatalog`에 없다 | `V2SandboxConfiguration.kt:19-21` | `DaemonWriteGuard.kt:29-34` 4개 패키지에 미포함 — 참(단 §1 question 1) |
| gateway IT는 매 빌드 검증된다 | `docker-compose.v2-sandbox.yml:105` | `ci.yml:28` `./gradlew build`가 실행 — 참. 단 `gate.sh backend`로는 아님(§1 fix-required 4) |

### 3.9 날조·스텁 흔적 **[실측]**

`grep -rn "TODO\|FIXME\|XXX\|test.skip\|\.only(\|@Disabled\|@Ignore"`를 v2 소스·테스트·프론트 전 파일에
돌려 **0건**. 두 README가 "빈 디렉터리다"라고 정확히 기술하고 실제로 README만 있다.

---

## 4. UNKNOWN (재려 했으나 재지 못함)

- **game-engine `V2ProductionContextBeanGateIT` 4칸의 독립 재현.** 리뷰 시작 시점부터 팀 리드가
  전체 백엔드 게이트를 백그라운드 실행 중이었고(`ps` 확인), 내가 띄운
  `./gradlew :app:game-engine:test :app:game-api:test :app:gateway-api:test :infra:test --tests '*V2*'`는
  10분 이상 프로젝트 락 대기 상태에서 출력 0바이트였다. **팀 리드의 XML을 덮어써 그쪽 게이트 증거를
  훼손할 위험이 있어 내 실행을 중단했다**(지시대로 억지로 돌리지 않음). 리뷰 시점 트리의 해당 XML은
  0바이트(쓰기 중, mtime 12:41). 근거로 남은 것은 `s4-production-context-bean-gate.md:158-161`의
  인용(`tests="1" skipped="0" failures="0" errors="0"` × 4)뿐이다. game-api·gateway-api 8칸은 XML로
  직접 확인했다(전부 `failures="0" errors="0" skipped="0"`).
- **v2 스택 실부팅.** `docker compose up`은 하지 않았다(범위 밖·부작용). nginx 설정 공유(§1 question 8),
  standalone 런타임 env(§1 question 2)는 그래서 미측정이다.

---

## 5. 결론

**fix-required.** blocker는 0건이고, 이 티켓이 지키겠다고 선언한 제약은 내가 던진 공격
(pathspec 재측정·변이 프로브·접두 충돌·serverId 우회·쿼리 경로·프로파일 부작용·compose 유효성·
문서-코드 대조)을 **전부 견뎠다.** 1차 blocker 2건은 실제로 해소됐다.

그러나 **fix-required 4건**이 남는다.

1. Q4를 미룬 근거 문장(`계획 §4-2:580`)이 **미측정 단정이고 실측하면 거짓**이다 — 1차가 잡은
   `havingValue` 주석과 같은 실패 유형이 같은 티켓 안에서 재발했다.
2. gateway IT의 "컨텍스트가 실제로 떴다"는 대체 근거가 프레임워크 빈 하나로 충족되어 목적을
   encode 하지 못한다.
3. 게이트 ① 증거(`baseline/a4-*`)가 1차 지적 처리 **이전** 상태의 것이다.
4. 그 gateway IT가 `tools/parity/gate.sh:15` 백엔드 게이트 정본에 들어 있지 않다 — CI `./gradlew build`는
   덮지만, 이 티켓의 acceptance 게이트는 덮지 않는다.

1·2는 문서·어서션 정정이고, 3은 게이트 재실행(진행 중으로 보임)으로, 4는 한 줄 추가 또는 acceptance
기준의 명시적 정정으로 닫힌다. 추가로 `.ai/current-state.md`가 다시 stale이다(§2 F4).
