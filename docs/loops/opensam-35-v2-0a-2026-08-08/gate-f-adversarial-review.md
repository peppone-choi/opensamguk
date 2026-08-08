# GATE-f — OPENSAM-35 V2-0A 독립 적대적 리뷰

- 리뷰어: 외부 fresh reviewer (`op35-gatef-review`), 읽기·검증 전용
- 일시: 2026-08-08
- 대상: 브랜치 `op-35-v2-0a` 워킹트리 (merge-base `fb90eac1`)
- 판정: **fix-required** (blocker 2건 — 게이트 ③·⑤가 측정 불능)

---

## 1. 결함

### blocker

- `docs/superpowers/plans/2026-08-08-opensam-35-v2-0a-isolation-plan.md:497` — 게이트 ③의 pathspec `'app/*/src/main/kotlin/'`가 **아무것도 매치하지 않는다**. 직접 측정(git 2.50.1): `origin/main` 대비 `app/game-engine/src/main/kotlin/opensamguk/engine/flush/DatabaseHooks.kt`가 M 상태인데도 이 명령은 빈 출력이고, `:(glob)app/*/src/main/kotlin/**`로 바꾸면 해당 파일이 출력된다. ⇒ "T2 공집합, 기대값 빈 출력"은 `app/**`에 대해 **공허하게 참**이며 게이트 ③은 app 모듈의 T2 위반을 원리적으로 검출할 수 없다.
- `docs/superpowers/plans/2026-08-08-opensam-35-v2-0a-isolation-plan.md:469` — 게이트 ⑤의 pathspec `'app/*/src/main/resources/'`도 같은 결함. 직접 측정: 커밋 `e1c00b8e`(세 앱의 `application.yml`을 동시 수정)을 대상으로 `git diff --name-only e1c00b8e~1 e1c00b8e -- 'app/*/src/main/resources/'` → **빈 출력**, `:(glob)app/*/src/main/resources/**` → 3개 파일 출력. ⇒ 게이트 ⑤("설정 리소스 무수정")는 `application.yml` 수정을 **통과시킨다**. 0A-c가 `application.yml` 무수정 경로를 택한 근거 전체가 이 게이트에 걸려 있으므로 게이트가 고쳐지기 전까지 "게이트 ⑤ 유지"는 증거가 아니다. (참고: pathspec 중 wildcard 없는 `infra/src/main/kotlin/`·`infra/src/main/resources/` 부분은 정상 동작하며, 게이트 ②는 wildcard가 없어 영향 없음.)

### fix-required

- `web/game/app/game/v2-lab/layout.tsx:9-11` — "값 규약은 백엔드 S2 조건부 빈 게이트와 동일하게 `V2_ENABLED=true` 정확 일치다"는 **거짓**이다. `spring-boot-autoconfigure-3.4.1`의 `OnPropertyCondition$Spec.isMatch` 바이트코드는 `String.equalsIgnoreCase`를 쓴다(javap 확인). 즉 `V2_ENABLED=TRUE`/`True`는 **백엔드 게이트를 연다**. 프론트는 `!== 'true'`로 닫는다 ⇒ 두 층의 규약이 실제로 다르다. 계획 §S2가 "미측정"으로 남긴 항목인데 코드 주석은 측정 없이 반대 결론을 단정했다.
- `web/game/__tests__/v2-lab-route.test.tsx:52` — 같은 오류. `'TRUE'` → 404를 assert하면서 주석에 "백엔드 `havingValue=\"true\"`와 동일 규약"이라 적었다. assert 자체는 프론트 동작으로 옳지만 근거 문장이 틀렸고, 이 테스트는 백엔드 비대칭을 **가려준다**.
- `app/gateway-api/**` — 세 번째 Spring 앱에 v2 게이트가 **없다**. `V2SandboxConfiguration`도, 0A-f 빈 카운트 IT도 없다(`find` 확인: `app/gateway-api` 아래 `v2` 디렉터리 0건). 동시에 `docker-compose.v2-sandbox.yml:82-107`의 gateway-api에는 `V2_ENABLED`·`SPRING_PROFILES_ACTIVE`가 **주입되지 않는다**. ⇒ 계획 §3 S5가 "v2 스택은 6개를 명시 주입"이라 한 것과 실제가 불일치하고, gateway-api에 v2 빈이 생기면 v1·v2 양쪽 컨텍스트에서 무조건 등록된다. S2 규약 1·2와 S4 실측이 이 앱을 전혀 덮지 않는다.
- `.ai/current-state.md` (신규 절, `+`10~11행) — "**S1 진행 중**", "**미실행: S2~S6**"으로 남아 있다. 실제로는 S5까지 완료됐고 계획서 본문이 그 결과를 담고 있다. 세션 상태 SSoT가 계획서와 모순된다(ADR-LITE-020).

### question

- `docs/loops/opensam-35-v2-0a-2026-08-08/s5-v2-stack-env-separation.md:38` — "**모든** v2 값은 `V2_`-접두 치환 변수를 쓴다"는 전수 주장이 거짓이다. `docker-compose.v2-sandbox.yml`에서 v1 이름을 그대로 상속하는 변수 7개: `TZ`(:39 외 전 서비스), `NODE_ENV`(:245,:270), `JWT_ACCESS_EXPIRATION`/`JWT_REFRESH_EXPIRATION`(:96-97), `GATEWAY_API_JAVA_OPTS`(:107), `GAME_API_JAVA_OPTS`(:158), `GAME_ENGINE_JAVA_OPTS`(:215). 전부 무증상 계열로 보이나, "전수 적용"이라 쓴 것과 "load-bearing만 적용"은 다른 주장이다. 예외 목록을 명시하거나 문장을 축소할 것.
- `docker-compose.v2-sandbox.yml:117,167,224` — `V2_SCENARIO_HOST_DIR`의 `:?` fail-closed는 **변수 미설정**만 막는다. 값이 v1 시나리오 호스트 디렉터리를 가리키는 것은 막지 못하며, 그 경우 `V2_SCENARIO_CODE`로 v1 시나리오 코드를 지정하면 v1 콘텐츠가 v2 DB에 시드된다. U4-d 대응이 `GAME_DATABASE_URL` 분리뿐이라는 전제는 DB 방향으로는 맞으나 콘텐츠 방향은 열려 있다. (v1 DB 오염은 없음 — v2 스택은 자기 네트워크의 `postgres` 서비스만 참조하고 별도 볼륨·별도 DB명을 쓴다. compose 안에서 v1 DB로 붙는 경로는 찾지 못했다.)
- `docker-compose.v2-sandbox.yml:18` — 필수 `V2_*` 5종이 `.env.example`·`README.md`·`AGENTS.md` 어디에도 없다(`grep` 확인: 이 compose와 s5 문서에만 등장). fail-closed라 사고는 안 나지만 운영자가 값을 알 방법이 리포에 없다.
- `app/game-engine/src/test/kotlin/opensamguk/engine/v2/V2ProductionContextBeanGateIT.kt:44-48` — 패키지 스캔은 **타입 이름에 `.v2.`가 있을 때만** 잡는다. `@Component class V2CityLedgerStore`를 `opensamguk.engine.ledger`에 두면 하드코딩 타입 목록과 패키지 스캔을 **둘 다** 빠져나간다. S4 문서가 한계로 명시했으나(정직함) 이를 강제하는 테스트는 없고 방어는 "리뷰의 몫"으로 남아 있다. S2 규약 1이 코드로 강제되지 않는 상태다.
- `app/game-engine/src/test/kotlin/opensamguk/engine/v2/V2ContentCatalogBeanTest.kt:45-50` — `ApplicationRunner`/`CommandLineRunner` 0개 assert는 `V2SandboxConfiguration` 하나만 등록한 맨몸 `ApplicationContextRunner`에서 잰 것이다. 실제 앱 컨텍스트에는 `ScenarioSeedRunner` 등이 존재하므로 이 0은 "게이트가 열린 **앱** 컨텍스트에서도 seed runner가 없다"를 뜻하지 않는다. s3a 문서 ④의 "게이트가 열린 컨텍스트에서도" 표현이 측정 범위를 과대 진술한다.
- 게이트 ②③⑤의 기준선이 `origin/main`인데 `origin/main`이 분기점 `fb90eac1`을 지나 `ad0c8c53`까지 전진했다. 현재 `git diff --name-only --diff-filter=MD origin/main`에는 이 브랜치가 건드린 적 없는 `app/game-engine/src/test/kotlin/opensamguk/engine/flush/FlushPayloadConvergenceTest.kt`(D)·`.../flush/DatabaseHooks.kt`(M)가 섞여 나온다. T1의 "기존 테스트 삭제 0건"을 현재 `origin/main` 기준으로 판정하면 **거짓 위반**이 뜬다. 게이트는 merge-base를 고정하거나 리베이스 후 실행해야 한다.
- `web/game/middleware.ts:118` — matcher가 `/game`·`/game/:path*`뿐이라 `/_next/**` 정적 자산은 게이트 밖이다. 현재 v2-lab은 순수 서버 컴포넌트라 프로덕션 빌드에서 클라이언트 청크가 나오는지 확인하지 못했다(**UNKNOWN** — dev 빌드에서는 `.next/static/chunks/app/game/v2-lab/{layout,page}.js`가 생성된다). v2-lab에 클라이언트 컴포넌트가 붙는 순간 그 번들은 v1 이미지에서 무게이트로 서빙된다. 0A-a의 "404" 판정 범위에 정적 자산이 포함되는지 명시할 것.

### nit

- `web/game/middleware.ts:9-50` — `RESERVED_PATH_SERVER_IDS`에 `v2-lab`이 없다. `PATH_SERVER_ID = /^[a-z0-9]{1,48}$/`가 하이픈을 막으므로 실제 위험은 없으나, 목록에 이미 `battle-center` 등 하이픈 항목이 있어 규약이 일관되지 않는다.

---

## 2. 공격했으나 뚫리지 않은 것 (근거)

### 2.1 프론트 404 게이트 — 실요청으로 확인

`next dev -p 3399`, `SERVER_ID=pep`. RUN A = `V2_ENABLED` 미설정, RUN B = `V2_ENABLED=true`. 동일 소스.

| 요청 (`curl --path-as-is`) | RUN A | RUN B | 해석 |
|---|---|---|---|
| `/game/v2-lab` | 404 | **200** | 게이트가 닫은 것 |
| `/game/v2-lab/` | 308 → `/game/v2-lab` | — | 정규화 후 게이트 |
| `//game/v2-lab` | 308 → `/game/v2-lab` | — | 정규화 후 게이트 |
| `/game/./v2-lab` | 404 | — | |
| `/game/pep/../v2-lab` | 404 | 404 | 라우트 부재(게이트 아님) |
| `/game/pep/v2-lab` | 404 | **200** | rewrite 우회 폐쇄 확인 |
| `/game/v2%2Dlab` | 404 | 404 | Next가 디코드하지 않음 ⇒ 라우트 부재 |
| `/game/%76%32-lab` | 404 | 404 | 동일 |
| `/game/v2-lab..%2f` | 404 | 404 | 동일 |
| `/game/v2-lab?_rsc=abc` | 404 | — | RSC 요청도 게이트 통과 못 함 |
| `/game/rankings` | 200 | — | v1 회귀 없음 대조군 |

RUN B에서 `/game/v2-lab`·`/game/pep/v2-lab`만 200이고 인코딩 변형은 여전히 404 ⇒ **인코딩 변형의 404는 "게이트가 아니라 라우트가 없어서"이며, 실제로 도달 가능한 두 경로는 게이트가 닫고 있다**. 쿼리 진입(`?server=`)은 게이트가 line 86의 early-return으로 line 91의 쿼리 분기보다 앞에 있어 우회 불가.

### 2.2 v1 회귀

`node_modules/.bin/vitest run __tests__/middleware.test.ts __tests__/v2-lab-route.test.tsx` → **21 tests passed** (기존 8 + 신규 13). 기존 `middleware.test.ts`는 무수정이며 rewrite·쿼리쿠키·reserved·대문자 경로를 모두 덮는다. `isV2LabPath`는 `segments[1] !== 'game'`에서 즉시 false를 반환하고 v1 분기는 코드 이동 없이 그대로다.

### 2.3 C1 불변식

`python3 tools/agent-system/check.py --format json` → `"findings": [], "ok": true`. `check.py:381-390`은 `docker-compose.production.yml` **파일 하나**만 검사하므로 신규 `docker-compose.v2-sandbox.yml:197`의 `SCENARIO_SEED_ENABLED=true`는 불변식을 건드리지 않는다. 계획 §2 C1의 주장은 참.

### 2.4 T1 / 프로브 복원

- 게이트 ② `git diff --name-only --diff-filter=MD <origin/main | merge-base> -- logic/src/main/kotlin/ common/src/main/kotlin/ logic/src/test/resources/golden/` → 양쪽 기준 모두 **빈 출력**.
- merge-base 기준 `--diff-filter=MD` 전체 = `.ai/*` 3개 + `web/game/middleware.ts` 1개뿐. 골든·기존 테스트 수정·삭제 0건.
- S1~S4 문서가 "복원했다"고 주장한 뮤테이션 프로브(`V2ProbeLeakedComponent`, 재귀 패턴, `CommandLineRunner` 구현, `@Profile`/`@ConditionalOnProperty` 제거)의 흔적이 워킹트리에 **0건**이다(`git status --short`, v2 디렉터리 전체 파일 목록 확인). 게이트 코드는 두 조건이 모두 명시된 상태로 존재한다(`V2SandboxConfiguration.kt:27-28` 양쪽 앱).
- 날조 징후 없음: `db/migration_v2/`·`content/v2/`는 README만 있고 문서가 "빈 디렉터리"라고 정확히 기술한다. S5가 판정 첫 절을 UNKNOWN으로 남긴 것도 실제 코드 상태와 일치한다.

### 2.5 Flyway 격리 (문서 재검이 아니라 코드 대조)

`docker-compose.v2-sandbox.yml:29-31`의 앵커가 세 JVM 서비스 전부(`:86`, `:134`, `:184`)에 적용되고 값은 `classpath:db/migration,classpath:db/migration_v2` — S0의 치환 semantics·S1의 형제 경로 결론과 일치한다. v1 compose 2종에는 `SPRING_FLYWAY_LOCATIONS`가 없어 기본값 `classpath:db/migration`이 유지되고 `db/migration_v2`는 형제 경로라 재귀에 걸리지 않는다.

---

## 3. 결론

프론트 404 게이트와 조건부 빈 게이트 자체는 내가 시도한 우회 입력을 모두 막았고, T1·프로브 복원·C1 불변식·v1 회귀는 실측으로 깨끗하다.

그러나 **게이트 ③과 ⑤가 pathspec 결함으로 아무것도 재지 못한다**. 이 둘은 "T2 공집합"·"설정 리소스 무수정"이라는 이 티켓의 두 하드 제약을 증명하는 유일한 장치이므로, 고치고 재실행하기 전까지 두 제약은 **증명되지 않은 상태**다. 여기에 백엔드/프론트 `havingValue` 대소문자 비대칭(코드 주석이 반대로 단정)과 gateway-api 미커버가 겹친다.

`cleared` 아님. blocker 2건 수정 + 게이트 재실행, fix-required 4건 처리 후 재리뷰 필요.
