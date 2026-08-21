# OPENSAM-221 / OPENSAM-220 — game-api 테스트 매달림 + 슬림 JWT 후속 리뷰 (PR #488)

Scope: app/game-api/ app/gateway-api/ web/game/ .github/workflows/ tools/ docs/ — 브랜치 `opensam-220-slim-jwt-issuance`, base `origin/main`, 5커밋
Verdict: cleared

판정 주석 (2026-08-21 3차 독립 검증: N1 은 `9709c799` 로 닫혔고 신규 결함 없음. fix-required 잔여 0건 — 아래 「3차 재검증」 참조. should-fix 3건은 열려 있으나 차단 아님)

작성 레인과 분리된 컨텍스트에서 독립 리뷰어(opus)가 read-only로 공격했다. 근본원인 주장 자체는
**증거로 확인됐고** 매달림 수정도 게임 API 안에서는 빈틈이 없다.

**1차 판정(2026-08-21 13:5x)** — `fix-required` 1건(F1: `web/gateway` 초상 헬퍼 미적용).
**2차 판정(2026-08-21 14:0x, 이 문서 하단 §후속)** — F1 은 `6229ce59` 로 **닫혔다(재현 검증 완료)**.
그러나 같은 커밋이 넣은 `tools/parity/gate.sh` 수정이 반쪽이라 **새 `fix-required`(F2)** 가 생겼다.
1차 서술은 이력으로 그대로 보존한다.

---

## 검증 대상 5커밋

| commit | 범위 | 판정 |
| --- | --- | --- |
| `bc0f4ce2` | OPENSAM-220 — `getRoleFromToken` 제거 + 클레임 키 전량 단언 | cleared |
| `8cd38313` | OPENSAM-221 — shadow yml 삭제 + `@ActiveProfiles("test")` 7클래스 | cleared |
| `5e6fd815` | deploy.yml `timeout-minutes: 40` + 모듈별 XML 카운트 | cleared (관측 도구, 게이트 아님 — 아래 §9) |
| `73668122` | 문서 드리프트 정정 | should-fix 1건 + UNKNOWN 2건 |
| `3e681e30` | OPENSAM-214 공유 CDN 초상 화이트리스트 | **fix-required** — 쌍둥이 헬퍼 미적용 |

---

## fix-required

### F1. `web/gateway/lib/portrait.ts` 가 같은 구멍을 그대로 갖고 있다

`3e681e30` 은 `web/game/lib/portrait.ts:26` 에 `SHARED_ICON` 화이트리스트를 걸고 `:45` 에서
imgsvr=0 분기를 막았다. 그런데 **완전히 같은 헬퍼가 하나 더 있다**:

- `web/gateway/lib/portrait.ts:12-20` — `portraitUrl(picture, imageServer)`. imgsvr=1 분기는
  `MANAGED_ICON` 가드가 있지만(`:16`), imgsvr=0 분기(`:18-19`)는 DB `picture` 를 **무검사로**
  `${PORTRAIT_CDN}/` 뒤에 그대로 이어붙인다.
- 호출자: `web/gateway/components/board/BoardAuthor.tsx`, `app/lobby/page.tsx`,
  `app/account/page.tsx`, `components/board/BoardList.tsx`, `app/board/posts/[postId]/page.tsx`.
- 데이터 출처는 `web/game` 쪽과 동일한 `users.picture` 다. 즉 노출면이 **같은 등급**이다.
- `web/gateway/__tests__/portrait.test.tsx` 에는 imgsvr=1 비정규 파일명 8케이스는 있어도
  **공유 CDN(imgsvr=0) 비화이트리스트 케이스가 0건**이다. 회귀 잠금도 없다.

커밋 메시지가 선언한 목표("공유 CDN 초상 경로에도 화이트리스트를 건다")가 저장소 수준에서
달성되지 않았다. 티켓이 이름 붙인 경로만 고치고 형제 호출자를 남긴 형태다.

추가로 두 사본이 **이미 갈라져 있다** — gateway 쪽은 `picture?.trim()` 을 하고(`:13`),
game 쪽은 하지 않는다. 그래서 `web/game` 은 이번 변경으로 `" 1001 "`(앞뒤 공백)이
기존 `.../ 1001 .jpg` 대신 default 로 폴백하도록 **동작이 바뀌었고**, gateway 는 계속 정상 해석한다.
정규식만 복사-붙여넣기 하면 두 앱의 계약이 서로 다른 채로 굳는다.

**요구 조치(둘 중 하나):**
1. `web/gateway/lib/portrait.ts` 에 동일 화이트리스트 + 회귀 테스트를 추가하고 trim 처리를 두 앱에서 통일한다. 또는
2. 헬퍼 하나를 정본으로 두고 다른 쪽이 재사용한다(보안 관련 헬퍼 사본 2개 유지는 그 자체가 부채다).

---

## 확인 항목별 판정

### 1. #487 근본원인 주장 — **맞다 (증거 확인)**

주장: `be3efd36` 이 넣은 `app/game-api/src/test/resources/application.yml` 이 main 의
`application.yml` 을 **파일 단위로** 가려서 `spring.flyway.postgresql.transactional-lock: false`
가 사라졌고, V29 의 `CREATE INDEX CONCURRENTLY` 가 Flyway 트랜잭션형 advisory lock 과 데드락 났다.

증거:

- `git log --diff-filter=A -- app/game-api/src/test/resources/application.yml` → **`be3efd36` 단독**.
  파일 내용은 `jwt.public-key` / `legacy-secret` / `legacy-accept-until` 4줄뿐이었다.
- 파일 단위 가림은 실제 Spring Boot 의미론이 맞다. `classpath:/application.yml` 은 단일 리소스
  조회로 해석되며(`ClassLoader.getResource` = 클래스패스 첫 매치), Gradle `testRuntimeClasspath` 는
  `build/resources/test` 를 `build/resources/main` 앞에 둔다. **머지가 아니라 치환**이다.
  같은 이름을 피하는 profile 파일(`application-test.yml`)은 이 문제가 없다 — 별도 리소스라
  main `application.yml` 위에 겹쳐진다.
- 잃어버린 키: `app/game-api/src/main/resources/application.yml:17-21`.
- 매달림 지점: `infra/src/main/resources/db/migration/V29__log_entry_year_month_index.sql:26-27`
  (`DROP INDEX CONCURRENTLY` + `CREATE INDEX CONCURRENTLY`) × 사이드카
  `V29__log_entry_year_month_index.sql.conf` 의 `executeInTransaction=false`. 마이그레이션 주석
  자체(`:20-24`)가 이 잠금 요건을 이미 명시하고 있었다.
- CI 상관 증거: `gh run view 32440712964` — `47461ced`(= 그 웨이브 머지) 의 `build-jvm` 이
  `02:41:20Z → 03:53:51Z`(72분) 뒤 cancelled. 직전 성공 런들은 13~22분.

**다만 원인 기술이 좁다(교정 필요, 차단은 아님).** 가려진 것은 flyway 키 하나가 아니라
**main yml 전체**다. `jwt.*` 와 `opensamguk.world-id` 는 shadow 파일이 스스로 다시 공급했기 때문에
컨텍스트 기동은 성공했고, 그래서 증상이 "부팅 실패"가 아니라 "무한 매달림"으로 나왔다.
사후 기록을 "flyway 키가 사라졌다"로만 남기면 다음 사람이 같은 함정을 다른 키로 다시 밟는다.
`application-test.yml:1-5` 의 헤더 주석은 이 점을 대체로 잘 적어놨다.

### 2. gateway-api / board-api 에 동종 shadow yml 이 없는가 — **있다. 다만 매달리지는 않는다**

"없다"로 읽으면 **틀렸다**:

- `app/gateway-api/src/test/resources/application.yml` — 존재. main(`:1-69`)을 통째로 가린다.
- `app/board-api/src/test/resources/application.yml` — 존재. main(`:1-41`)을 통째로 가린다.

매달리지 않는 이유는 확인했다: 둘 다 `spring.flyway.enabled: false` + H2 인메모리라
V29 자체가 실행되지 않는다(gateway `:11-12`, board `:11-12`). board 의 Flyway 실행 IT는
`app/board-api/.../GatewayBoardMigrationIT.kt:75` 에서 `@DynamicPropertySource` 로
`spring.flyway.postgresql.transactional-lock=false` 를 직접 넣는다. `infra/src/test` 의
Flyway 프로그램 호출 48건도 전부 `flyway.postgresql.transactional.lock=false` 를 건다(누락 0건).

그래도 **같은 함정이 두 모듈에 그대로 살아 있다** — 두 앱의 main yml 에 새 필수 속성을 넣으면
테스트에서 조용히 사라진다. `#487` 은 그 조합이 "조용히"가 아니라 "72분 매달림"으로 터진 사례일 뿐이다.
should-fix: 두 모듈도 `application-test.yml` + `@ActiveProfiles("test")` 로 옮기거나,
최소한 game-api 와 동일한 경고 주석을 남긴다.

### 3. `@ActiveProfiles("test")` 클래스 단위 전수 — **누락 없음**

파일이 아니라 클래스 단위로 훑었다. `app/game-api/src/test` 의 `@SpringBootTest` 는 4파일 7클래스:

| 클래스 | 위치 | `@ActiveProfiles` |
| --- | --- | --- |
| `GameApiApplicationTests` | `GameApiApplicationTests.kt:18` | `:15` ✅ |
| `CommandControllerIT` | `web/CommandControllerIT.kt:48` | `:40` ✅ |
| `ReadConsistencyBarrierIT` | `consistency/ReadConsistencyBarrierIT.kt:43` | `:29` ✅ |
| `V2ProductionShapeBeanGateIT` | `v2/V2ProductionContextBeanGateIT.kt:71` | `:68` ✅ |
| `V2PropertyOnlyBeanGateIT` | 같은 파일 `:90` | `:87` ✅ |
| `V2ProfileOnlyBeanGateIT` | 같은 파일 `:109` | `:107` ✅ (`"test", V2SandboxGate.PROFILE`) |
| `V2BothConditionsBeanGateIT` | 같은 파일 `:133` | `:130` ✅ |

`@DataJpaTest` 11클래스(`read/*`, `owner/SelectNpcTokenRepositoryIT`)는 이전부터 전부 보유.
컨텍스트를 띄우지 않는 IT 는 Flyway 를 아예 호출하지 않는다 —
`reserve/CommandReserveServiceIT.kt` 는 하드 DDL(`:153` 주석 명시), `sse/RealtimeRelayIT.kt` 도 무관.
**game-api 안에 잠복 재매달림은 없다.**

should-fix: 이 규칙을 지키게 하는 것이 **YAML 주석 하나뿐**이다. 다음에 `@SpringBootTest` 를
추가하는 사람은 그 주석을 볼 이유가 없다. 저장소에 이미 아키텍처 테스트 관행이 있으므로,
"game-api 의 모든 `@SpringBootTest`/`@DataJpaTest` 는 `test` 프로파일을 갖는다" +
"`app/*/src/test/resources/application.yml` 은 존재하지 않는다" 한 줄 테스트가 실제 방어막이다.

### 4. shadow yml 삭제 안전성 — **안전 (확인)**

삭제된 3개 값이 전부 profile 파일에 동일하게 존재한다:

| 값 | 삭제된 파일 | 현재 위치 |
| --- | --- | --- |
| `jwt.public-key: ""` | shadow `:2` | `application-test.yml:20` |
| `jwt.legacy-secret` (동일 base64) | shadow `:3` | `application-test.yml:21` |
| `jwt.legacy-accept-until: 2099-01-01T00:00:00Z` | shadow `:4` | `application-test.yml:22` |

main yml `:27` 의 기본값 없는 `${JWT_PUBLIC_KEY}` 는 문제되지 않는다 — profile 파일이 우선순위에서
이기고 Spring 은 placeholder 를 **접근 시점에 지연 해석**하므로 해석 자체가 일어나지 않는다.
`opensamguk.world-id` 도 `application-test.yml:24` 가 `${OPENSAMGUK_WORLD_ID}` 를 덮는다.
반대로 이제 main yml 의 나머지(`sentry`, `management`, `read-barrier`, redis 기본값)가 테스트에도
적용되는데, 전부 기본값이 있고 `sentry.dsn` 은 빈 값 → SDK no-op 이라 부작용이 없다.

### 5. `jwt.public-key` 기본값을 주지 않는 결정(fail-closed) — **옳다**

`app/game-api/.../application.yml:27` 과 `app/board-api/.../application.yml:16` 은 기본값이 없다.
검증자(verifier)가 키 없이 뜨는 것보다 못 뜨는 게 맞고, 운영 표면이 이미 같은 계약이다 —
`docker-compose.yml:199`(game-api 블록)이 `JWT_PUBLIC_KEY: ${JWT_PUBLIC_KEY:?JWT_PUBLIC_KEY required — see .env.example}`
로 강제하고(gateway `:95`, board `:151` 동일), README 빠른 시작도 `Required: … JWT_PUBLIC_KEY` 로 표기한다.
과도하지 않다.

비대칭 1건만 기록한다(차단 아님): 발급자인 `app/gateway-api/.../application.yml:33` 은
`${JWT_PUBLIC_KEY:}` 로 빈 기본값이다. 발급자는 자체 keypair 정합성 검증을 기동 시 수행하므로
새 구멍은 아니지만(테스트 `RS256 startup rejects a mismatched key pair` 가 이를 잠근다),
세 앱의 규칙이 서로 다르다는 사실은 문서화할 가치가 있다.

### 6. `bc0f4ce2` — **cleared**

- **데드코드 여부: 진짜 죽어 있었다.** `getRoleFromToken` 저장소 전수 grep 결과 살아있는 호출자 0건.
  히트는 전부 `.omo/integration-wave/`, `.omo/teams/.../worktrees/` 의 **추적되지 않는 스크래치
  워크트리**다. 이름이 비슷한 `GameApiJwtVerifier.getRole` 은 **다른 클래스**이고 살아 있다
  (`app/game-api/.../AdminReadController.kt:84`, `AdminWriteController.kt:169`) — 영향 없음.
  ROLE 클레임 발급 자체도 유지되며 새 단언이 그것을 잠근다.
- **클레임 키 단언은 실제로 잠근다.** `claims.keys` 대 `setOf("sub","iat","exp",TOKEN_TYPE,ROLE,"iss","aud")`
  전량 비교라, 표시 클레임이 다시 새면 즉시 깨진다. 다만 커버 범위는 **RS256 × `includeProfileClaims=false`
  경로 하나**다. 레거시 HS256 슬림 발급 경로에는 같은 잠금이 없다(관측 사항, 차단 아님).
- **`Clock.fixed` 는 실제 만료 버그를 가리지 않는다.** provider 가 이미 `Clock.fixed(now)` 로
  생성되므로 토큰 `exp` 는 고정 시각 기준이고, 원시 JJWT 파서만 벽시계를 쓴다 — 파서에 같은 시계를
  주는 것은 **검사 대상이 아닌 도구를 정렬**하는 일이다. 실제 만료 거절 계약은 별도 테스트
  `expired token is rejected without wall clock sleeps`(`now.plusSeconds(61)`)가 그대로 잠그고 있고,
  그 테스트는 이 diff 에서 손대지 않았다.
- 실행 증거: `:app:gateway-api:test --tests '*JwtTokenProviderTest*' --rerun-tasks` →
  `BUILD SUCCESSFUL`, XML `tests=7 failures=0 errors=0 skipped=0`.

### 7. 하드코딩 / 가짜 완료 / 테스트 약화 / frozen-baseline — **없음**

- diff 는 `infra/src/main/resources/db/migration/**`, `tools/php-golden/**`, golden·fixture 파일을
  **일절 건드리지 않는다**(`git diff --stat origin/main...HEAD` 23파일 전량 확인).
- 제거된 단언은 `assertEquals("USER", provider.getRoleFromToken(access))` 1건뿐이고,
  같은 테스트 안에서 **더 강한** 클레임 키 전량 단언 + `claims[ROLE] == "USER"` 로 대체됐다.
  통과시키려고 기대값을 깎은 흔적 아님.
- 새로 추가된 `test.skip`/`.only`/TODO 스텁 0건.
- `web/game` 초상 테스트는 케이스를 **추가**만 했다(6케이스). 실행 확인:
  `pnpm vitest run __tests__/portrait.test.tsx` → 28 passed.

### 8. `73668122` 문서 커밋 — should-fix 1건, UNKNOWN 2건

사실 확인이 된 주장(전부 참):

| 주장 | 증거 |
| --- | --- |
| compose 9서비스 | `docker-compose.yml` postgres·redis·gateway-api·board-api·game-api·game-engine·web-gateway·web-game·nginx = 9. production 도 동일 9 |
| board-api :8083 존재 | `app/board-api/`, nginx `infra/nginx/nginx.conf:126` `location /api/board/` |
| `game_server` 테이블이 런타임 정본 | `V43__game_server_registry.sql`, `V44__game_server_registry_transition.sql`; `ServerRegistry.kt:61` 요청 시점 `jdbc.query(... FROM game_server)` |
| `SERVER_REGISTRY_JSON` 은 최초 시드 전용 | `ServerRegistry.kt:56` `seedEmptyRegistry()` → `:243` `SELECT COUNT(*) FROM game_server` 가 0 일 때만 INSERT(`:251`) |
| `settings.gradle` → `settings.gradle.kts` | 실제 파일명 확인 |
| JWT 표시 클레임 게이트 | `includeProfileClaims` 플래그 실재, `docs/operations/jwt-key-rollout.md` 실재 |

**should-fix — 커밋 자신의 목표를 하나 놓쳤다.** `AGENTS.md:71` 과 `CLAUDE.md:58` 은 풀 체크에
`:app:board-api:test` 를 추가했는데, `tools/parity/gate.sh:17-18` 의 backend 태스크 배열은
여전히 `common logic infra game-engine game-api gateway-api` 6개로 board-api 를 빼먹었다.
그런데 `CLAUDE.md` 는 gate.sh backend 를 "표준 백엔드 증명"으로, `docs/agent/verification.md:17` 은
"백엔드 광역/커밋 전"으로 규정한다. 즉 문서가 가리키는 두 경로가 서로 다른 모듈 집합을 검증한다 —
"문서와 구현 사이 드리프트를 고친다"는 커밋에서 남은 드리프트다. `gate.sh` 에 한 줄 추가가 맞다.

**부정확 1건.** `docs/agent/issue-priority-tiers.md` 의 `#468` 항목 "테스트 8개가 존재한다" —
`app/board-api/src/test` 는 **테스트 파일 8개 / `@Test` 42개**다. "8개"가 테스트 수로 읽힌다.

**UNKNOWN 2건 (틀렸다는 뜻이 아니라 이 저장소에서 검증 불가).**
`docs/reference/core2026-docs-audit-2026-08-20.md` 의
(a) 원본 commit `2a73f80b`, (b) "로컬 워킹트리가 origin/main 보다 1234 커밋 뒤진 stale",
(c) URL 변경 `gitea.hided.net` → `storage.hided.net`.
`legacy/` 는 git-ignore 이고 원격은 외부 Gitea 라 저장소 내부 증거로 확인할 수 없다.
로컬 `legacy/devsam-core2026` HEAD 가 `14da014a` 인 것만 확인했다. 검증자에게는 `UNKNOWN` 이다.

### 9. `5e6fd815` deploy.yml — cleared, 다만 게이트가 아니다

`timeout-minutes: 40` 은 옳은 봉쇄 조치다. 주석의 사실 관계도 실측과 일치한다(§1 의 72분 런).
`concurrency: deploy-production, cancel-in-progress: false` 조합에서 6시간 기본 타임아웃이
후속 배포 전체를 큐에 가두는 것도 사실이다.

관측 사항: 모듈별 XML 카운트 루프는 **진단 출력일 뿐 실패를 만들지 않는다** — 어떤 모듈이 0건이어도
스텝은 green 이다. 실제 실패 신호는 앞 스텝의 `./gradlew build` 가 담당하므로 문제는 없지만,
커밋 메시지의 "모듈별 테스트 결과를 드러낸다"를 게이트로 오해하지 않도록 기록해 둔다.
`|| true` 가 `find` 뒤에 붙은 것도 같은 맥락(진단 전용)이라 수용 가능하다.

### 10. 워킹트리 위생 (참고)

`qa/` 가 워킹트리에 untracked 로 남아 있고 어느 커밋에도 포함돼 있지 않다. 리뷰 의뢰문은
`qa/design_audit_capture.py` 를 `73668122` 범위로 적었으나 그 커밋(12파일)에는 없다.
diff 결함은 아니지만 의도 확인이 필요하다(커밋 누락인지, 의도적 로컬 산출물인지).

---

## 검증 증거

| 항목 | 명령 | 결과 |
| --- | --- | --- |
| gateway JWT | `:app:gateway-api:test --tests '*JwtTokenProviderTest*' --rerun-tasks` | `BUILD SUCCESSFUL`, XML `tests=7 failures=0 errors=0 skipped=0` |
| web/game 초상 | `pnpm vitest run __tests__/portrait.test.tsx` | 28 passed / 1 file |
| **game-api 매달림 회귀** | `:app:game-api:test --rerun-tasks` (Docker 가용) | `BUILD SUCCESSFUL in 4m 31s`. XML 79클래스 합산 `tests=542 failures=0 errors=0 skipped=0` — `skipped=0` 이므로 Testcontainers IT 가 실제로 실행됐고 매달림이 재현되지 않았다 |
| CI 매달림 사실 | `gh run view 32440712964 --json jobs` | `build-jvm cancelled 02:41:20Z → 03:53:51Z` (72분) |
| shadow yml 도입 시점 | `git log --diff-filter=A -- app/game-api/src/test/resources/application.yml` | `be3efd36` 단독 |
| 데드코드 | 저장소 전수 `getRoleFromToken` grep | 추적 파일 히트 0건(`.omo/` 스크래치 워크트리 제외) |
| frozen-baseline | `git diff --stat origin/main...HEAD` | migration/golden/fixture 변경 0건 |

## 1차 요약 (이력 — 아래 §후속 검증에서 갱신)

| 심각도 | 항목 |
| --- | --- |
| **fix-required** | F1 — `web/gateway/lib/portrait.ts` imgsvr=0 화이트리스트 미적용 + 회귀 테스트 부재. 두 사본의 trim 계약도 갈라짐 → **`6229ce59` 로 닫힘** |
| should-fix | `tools/parity/gate.sh` backend 태스크에 `:app:board-api:test` 누락 — 문서와 여전히 불일치 → **반쪽 반영, F2 로 승격** |
| should-fix | gateway-api·board-api 의 `src/test/resources/application.yml` shadow 패턴 잔존(현재 무해, 함정은 동일) |
| should-fix | `@ActiveProfiles("test")` 규칙 강제 수단이 YAML 주석뿐 — 아키텍처 테스트 1줄 권장 → **`6229ce59` 로 닫힘** |
| 관측 | 근본원인 기술이 "flyway 키 소실"로 좁다. 실제로는 main yml **전체** 치환 |
| 관측 | 클레임 키 잠금은 RS256×`includeProfileClaims=false` 경로만 커버 |
| 관측 | `#468` "테스트 8개" = 파일 8개 / `@Test` 42개 |
| 관측 | deploy.yml XML 카운트는 진단 출력이며 실패를 만들지 않는다 |
| UNKNOWN | core2026 감사의 원본 commit `2a73f80b`, "1234 커밋 뒤짐", URL 변경 — 외부 Gitea·git-ignore `legacy/` 라 저장소 내 검증 불가 |

---

# 후속 검증 (2026-08-21 14:0x) — F1 닫힘, F2 신규

Verdict(갱신): **fix-required** — F1 은 닫혔고, 그것을 닫은 커밋이 새 결함 F2 를 남겼다.

대상 커밋 2개. 두 커밋 모두 저자 보고를 믿지 않고 직접 재현했다.

| commit | 무엇을 닫았나 | 판정 |
| --- | --- | --- |
| `6229ce59` | F1(gateway 화이트리스트) + should-fix 2건(gate.sh, `@ActiveProfiles` 강제) | F1 **cleared** / gate.sh **F2 fix-required** / 가드 **cleared** |
| `b7baf20d` | `5e6fd815` 가 가드를 엉뚱한 워크플로에 넣은 것을 정정 | cleared |

## F1 — 닫혔다 (동작으로 확인)

`web/gateway/lib/portrait.ts:15` 에 `SHARED_ICON` 이 추가됐고 `:24` 가 imgsvr=0 분기를 막는다.
`web/game/lib/portrait.ts:43` 에 `trim()` 이 추가돼 두 사본의 계약이 합쳐졌다.

**(a) 두 앱이 실제로 동등한가 — 정규식 문자열 비교가 아니라 동작으로.**
두 사본의 함수 본문을 각각 그대로 떼어내 차등 퍼즈를 돌렸다. 입력 집합은 경로 구분자·상위 이동·
쿼리/프래그먼트·절대 URL·백슬래시·퍼센트 인코딩(`%2e`/`%2f`/`%00`)·유니코드 공백류
(`\t \n \r \v \f` U+00A0 U+2028 U+2029 U+3000 U+FEFF 등)·확장자 조합의 곱집합이다.

- 8,090 (입력 × imgsvr 플래그) 조합: **mismatch 0**.
- 불변식 검사 동시 수행 — 모든 결과는 (i) `DEFAULT_PORTRAIT` 이거나, (ii) `${PORTRAIT_CDN}/` +
  `[A-Za-z0-9_.-]` 단일 세그먼트(`..` 불포함) 이거나, (iii) `/d_pic/` + `MANAGED_ICON` 통과 문자열
  중 하나다. **escape 0**.
- 별도 880 조합 재검에서도 **leak 0**.

즉 동등성은 "같은 정규식을 복사했다"가 아니라 **출력 동일성 + 출력 형태 불변식** 두 축으로 확인됐다.

**(b) trim 을 먼저 돌리는 순서가 새 구멍을 여는가 — 열지 않는다. 오히려 좁힌다.**

- `String.prototype.trim()` 이 제거하는 문자는 WhiteSpace + LineTerminator 뿐이고, 경로에서
  의미를 갖는 문자(`/ \ . ? # :`)는 하나도 포함하지 않는다. 따라서 trim 이 화이트리스트를
  우회시킬 수 있는 문자는 존재하지 않는다.
- 결정적으로 **URL 에 박히는 값도 trim 된 값(`normalizedPicture`)** 이다. 검사 대상과 사용 대상이
  같은 문자열이므로 TOCTOU 형태의 어긋남이 없다. (검사만 trim 하고 원본을 쓰면 그게 구멍이다.)
- 확대되는 집합은 "앞뒤 공백만 다른 값"뿐이고 그 결과도 다시 화이트리스트를 통과해야 한다.
- 앵커 관련해서 한 가지 확인: JS 의 `$` 는 `m` 플래그가 없으면 입력 끝에서만 매치한다
  (`/^a$/.test("a\n") === false` 실측). Python `re` 와 달리 개행 앞에서 매치하지 않으므로
  `"1001.jpg\n"` 류의 앵커 우회는 애초에 성립하지 않는다. trim 유무와 무관하게 안전하다.
- U+200B(zero-width space)는 `trim()` 대상이 아니지만 화이트리스트 문자집합 밖이라 폴백된다(실측).

**(c) 다른 초상 헬퍼 사본 — 헬퍼는 더 없다.**
저장소 전체에서 `portraitUrl` 정의는 `web/game/lib/portrait.ts:39` 와
`web/gateway/lib/portrait.ts:17` **둘뿐**이다. `web/gateway/lib/profileIcon.ts` 는 업로드 전
캔버스 정규화이지 URL 생성기가 아니다. 서버측도 URL 을 만들지 않는다 —
`app/board-api/.../GatewayBoardContracts.kt:58-59` 가 원본값만 내리고 URL 은 프런트가 만든다고
명시한다. `picture` 를 렌더하는 화면은 전부 `portraitUrl` 을 거친다(`join/page.tsx:450`,
`account/page.tsx:118`, `BoardAuthor.tsx:26`, generals/my-generals/rankings/select-pool/
GeneralBasicCard, `lobby/page.tsx:152`).

## F2 (신규, fix-required) — `gate.sh` 에 board-api 를 반만 넣었다

`tools/parity/gate.sh:17` 은 `tasks` 에 `:app:board-api:test` 를 추가했는데,
`:18` 의 `xml_roots` 에는 `app/board-api` 를 **추가하지 않았다**. 바로 두 줄 위(`:16`)에 있는
이 파일 자신의 규약이 이것이다:

> Keep tasks and xml_roots aligned so every executed task is also evaluated.

`:109-146` 의 python 게이트는 `xml_roots` 에 있는 모듈의 XML 만 파싱한다. 결과적으로
`gate.sh backend` 는 board-api 테스트를 **돌리지만 평가하지 않는다**.

양쪽을 다 적는다 —

- 완화 요인: board-api 테스트가 실패하면 gradle 이 `BUILD FAILED` 를 찍고 `:104` 의
  `grep -q "BUILD SUCCESSFUL"` 에서 걸린다. **실패 자체는 새어나가지 않는다.**
- 그럼에도 차단으로 두는 이유: (1) `xml_roots` 의 존재 이유인 **"태스크가 돌았는데 XML 이 0건"
  탐지**(`:120-127` `missing_roots`)에서 board-api 만 면제된다. (2) 게이트 마지막 줄이
  `XML gate green: N suites, M tests` 를 찍는데 그 N/M 에 board-api 가 **들어 있지 않다** —
  읽는 사람은 board-api 가 평가됐다고 믿는다. 커버되지 않은 것을 커버된 것처럼 보이게 하는
  증거 표시는 이 저장소가 명시적으로 금지하는 형태다(CLAUDE.md 하드 룰: 미검증은 UNKNOWN).
  (3) 수정이 한 단어다.

**요구 조치:** `xml_roots` 배열에 `"app/board-api"` 추가.

## should-fix 2건 — 닫혔다

**`@ActiveProfiles` 강제.** `app/game-api/src/test/kotlin/opensamguk/gameapi/config/TestResourceShadowingTest.kt`
가 주석을 테스트로 승격했다. 두 케이스가 서로를 보증하는 구조라 vacuous pass 가 불가능하다 —
1번(`:25` shadow 파일 부재)만 있으면 작업 디렉터리가 틀려도 통과해버리는데, 2번(`:36`)이
**같은 상대 경로로** `application-test.yml` 을 실제로 읽어 `transactional-lock: false` 를 확인한다.
2번이 green 이라는 것은 경로 해석이 진짜라는 뜻이고, 따라서 1번도 vacuous 가 아니다.
실행: `:app:game-api:test --tests '*TestResourceShadowingTest*' --rerun-tasks` → `BUILD SUCCESSFUL`,
XML `tests=2 failures=0 errors=0 skipped=0`. (유도 실패는 저장소를 변경해야 해서 재현하지 않았다 —
위 상호보증 구조로 충분하다.)

**`b7baf20d` CI 가드 위치 정정.** 지적이 맞다. 실제로 매달린 잡은 `ci.yml` 의 `jvm` 이고
`5e6fd815` 는 `deploy.yml` 의 `build-jvm` 에 가드를 넣었다. `.github/workflows/ci.yml:26-30` 에
`timeout-minutes: 40` 이 들어갔고 근거 주석도 사실과 맞다. `deploy.yml` 쪽 가드를 남긴 판단도
옳다 — 두 워크플로 모두 같은 상한이 필요하다. 다만 §9 의 관측은 그대로 유효하다: 모듈별 XML
카운트는 **진단 출력이지 게이트가 아니다**(0건이어도 스텝은 green).

## 신규 관측 — 이 PR 범위 밖의 인접 결함 (차단 아님)

`portraitUrl` 을 우회해 **DB `picture` 원본값이 그대로 `<img src>` 에 들어가는 경로**가 하나 있다.
이 PR 이 만든 것이 아니고 이 PR 이 건드리지도 않았으므로 F 항목으로 올리지 않는다. 별도 티켓 감이다.

- `app/game-engine/.../world/WorldActionContext.kt:2141` — `"icon" to (general.meta["picture"]?.toString() ?: "")`
  즉 `icon` 은 URL 이 아니라 **`picture` 원본값**이다.
- `app/game-engine/.../intake/DiplomacyLetterHandler.kt:453` 이 그 값을 aux 의 `generalIcon` 으로 저장하고,
- `app/game-api/.../controller/DiplomacyController.kt:146` 이 그대로 내려주며,
- `web/game/app/game/diplomacy/page.tsx:605` 가 `src={party.generalIcon}` 로 **접두어 없이** 렌더한다.

`web/game`·`web/gateway` 의 화이트리스트보다 노출이 넓다 — 접두어가 없으므로 절대 URL 이면
그대로 외부 출처를 가리킨다(기존 테스트 픽스처 `F4ReadControllersTest.kt:303` 이 실제로
`"//cdn/sunyuk.png"` 라는 프로토콜 상대 URL 을 쓴다). 다만 생산자(`WorldActionContext`)가
`imgsvr` 을 함께 싣지 않아 `portraitUrl(picture, imgsvr)` 로 그냥 바꿀 수 없다 — 설계가 필요하다.
`web/gateway/components/admin/MemberControl.tsx:392` 의 `user.icon`(`AdminDto.kt:114`)도 같은 모양이며
어드민 전용 화면이다. **UNKNOWN**: 이 두 경로에 임의 문자열을 넣을 수 있는 쓰기 지점이 실재하는지는
확인하지 않았다(업로드 경로는 gateway-api 가 8자리 hex canonical 로 정규화한다).

## 후속 검증 증거

| 항목 | 명령 | 결과 |
| --- | --- | --- |
| 두 사본 동등성 + 출력 불변식 | 본문 추출 차등 퍼즈 8,090 조합 | mismatch 0 / escape 0 |
| trim-우회 재검 | 유니코드 공백류 × 주입 문자열 880 조합 | leak 0 |
| JS 앵커 의미론 | `/^a$/.test("a\n")` | `false` (개행 앞 매치 없음 — 우회 불가) |
| gateway 초상 회귀 | `pnpm vitest run __tests__/portrait.test.tsx` | 34 passed |
| game 초상 회귀 | `pnpm vitest run __tests__/portrait.test.tsx` | 29 passed |
| 재발 가드 | `:app:game-api:test --tests '*TestResourceShadowingTest*' --rerun-tasks` | `BUILD SUCCESSFUL`, XML `tests=2 failures=0 errors=0` |
| 헬퍼 사본 전수 | `portraitUrl` 정의 grep(web 전체) | 2건뿐 — 서버측 URL 생성 0건 |
| F2 | `tools/parity/gate.sh:17-18` 대조 | `tasks` 7개 / `xml_roots` 6개 — board-api 미평가 |

## 갱신 요약

| 심각도 | 항목 |
| --- | --- |
| **fix-required** | F2 — `tools/parity/gate.sh:18` `xml_roots` 에 `app/board-api` 누락. 태스크는 돌지만 XML 평가 대상이 아니고, 게이트 출력이 커버되지 않은 것을 커버된 것처럼 보이게 한다 |
| cleared | F1 — gateway 화이트리스트 + 두 앱 계약 통일. 동작 동등성·불변식·trim 순서 모두 재현 확인 |
| cleared | `@ActiveProfiles` 강제를 주석에서 테스트로 승격(vacuous pass 불가 구조) |
| cleared | `b7baf20d` — 가드를 실제 매달린 `ci.yml:26-30` 으로 이동 |
| 관측(신규) | `generalIcon`/`user.icon` 경로가 `picture` 원본값을 접두어 없이 `<img src>` 로 렌더. 이 PR 범위 밖, 별도 티켓 |
| 유지 | 1차 요약의 나머지 should-fix·관측·UNKNOWN 항목은 그대로 유효 |

---

# 후속 재검증 (2026-08-21, 독립 검증자 2차)

위 원본 리뷰(F1 = `fix-required`)와 **분리된 컨텍스트**의 독립 검증자가 read-only 로 재검증했다.
코드도 원본 리뷰도 이 검증자가 쓰지 않았다. 대상: `6229ce59`(F1 + should-fix 2건 대응),
`b7baf20d`(ci.yml 매달림 가드 이동).

**후속 판정: `fix-required` 유지.** F1 자체는 실증적으로 닫혔다. 그러나 `6229ce59` 가
should-fix 를 닫으려다 **`tools/parity/gate.sh backend` 를 실행 불가로 만들었다**(N1). 이건
새 결함이고, CLAUDE.md 가 "표준 백엔드 증명"으로 지정한 명령이 지금 테스트를 한 개도 못 돌린다.

## 커밋별 후속 판정

| commit | 항목 | 판정 |
| --- | --- | --- |
| `6229ce59` | F1 — gateway imgsvr=0 화이트리스트 + 두 앱 trim 통일 + 회귀 잠금 | **cleared** |
| `6229ce59` | should-fix — `TestResourceShadowingTest` (#487 재발 가드) | **cleared** (범위 한정, 아래 §E) |
| `6229ce59` | should-fix — `gate.sh` 에 `:app:board-api:test` | **fix-required (N1, 신규 결함)** |
| `b7baf20d` | ci.yml `jvm` timeout-minutes | **cleared** (다만 §F 의 should-fix 1건) |

---

## N1 (신규 fix-required). `tools/parity/gate.sh backend` 가 지금 아무 테스트도 못 돌린다

`6229ce59` 는 `tools/parity/gate.sh:17` 의 `tasks` 배열에 `:app:board-api:test` 를 넣었지만
바로 다음 줄 `:18` 의 `xml_roots` 배열은 6개 그대로 뒀다. 그런데 그 두 줄 **바로 위 주석
(`:16`)이 "Keep tasks and xml_roots aligned so every executed task is also evaluated." 라고
적혀 있고**, 스크립트 자신이 `:47-50` 에서 길이 불일치를 하드 abort 로 잡는다.

실측:

```
$ bash tools/parity/gate.sh backend
Gate task/XML root count mismatch: 7 tasks, 6 roots
EXIT=1
```

Gradle 호출(`:104`)까지 도달하지 못하고 죽는다. 즉 이 커밋 이후 **표준 백엔드 게이트는
`common`·`logic`·`infra`·`game-engine`·`game-api`·`gateway-api`·`board-api` 어느 것도 실행하지
않는다.** 문서 불일치(should-fix)를 고치려다 게이트 자체를 0-테스트로 만든 형태다.

커밋 메시지의 검증란은 `bash -n tools/parity/gate.sh 통과`만 적었는데, `bash -n` 은 **문법만**
본다 — 런타임 배열 길이 검사는 통과 여부와 무관하다. 실제 실행 증거가 없었던 자리다.

**요구 조치:** `xml_roots` 에 `"app/board-api"` 를 추가(1줄)하고 `bash tools/parity/gate.sh backend`
실행 증거를 남긴다. 스크립트의 `:52-60` 순서 검사도 있으므로 `tasks` 와 같은 위치(마지막)여야 한다.

---

## 검증 항목별 결과

### A. gateway imgsvr=0 가드가 실재하고 web/game 과 **행위적으로** 동일한가 — 예

- `web/gateway/lib/portrait.ts:15` `SHARED_ICON`, `:24` 에서 imgsvr=0 분기 차단. `:18` trim 선행.
- `web/game/lib/portrait.ts:27` / `:50` / `:43` — 동일 위치·동일 순서.
- 정규식 문자열 비교가 아니라 **실입력 차분 실행**으로 확인했다. 두 사본의 함수 본문을 node 로
  각각 재현하고 880 케이스(= 220 입력 × `imgsvr` ∈ {0,1,null,undefined})를 돌렸다. 입력에는
  traversal(`../x.jpg`), 경로 주입(`a/b.jpg`), 쿼리(`1001?x=1`), 절대 URL(`https://e/a.jpg`),
  확장자 없는 dot(`..`, `.`), 대문자 확장자, 그리고 JS `trim` 이 벗기는 **17개 공백류 문자
  전량**(`\t \n \r \v \f`, U+0020, U+00A0, U+1680, U+2000–U+200A, U+2007, U+2028, U+2029,
  U+202F, U+205F, U+3000, U+FEFF)을 앞/뒤/양쪽에 붙인 조합을 포함했다.
- 결과: **두 앱 결과 불일치 0건, CDN 디렉터리 밖으로 새는 출력 0건, `/ ? # \ :` 주입 0건.**
  두 사본이 지금은 동일 계약이다.

### B. `.trim()` 이 우회를 여는가 — 열지 않는다

- **정규식 앵커 구멍 없음.** JS `$` 는 (Python 과 달리) `m` 플래그 없이 입력 끝에서만 매치한다 —
  실측 `/^a$/.test("a\n") === false`. 후행 개행으로 화이트리스트를 통과시킬 수 없고, 어차피
  trim 이 먼저 벗긴다.
- **trim 이 거절값을 승인값으로 바꿀 수 있는 경우는 "앞뒤 공백만 붙은 정상값" 하나뿐이다.**
  `" 1001 "` → `1001.jpg`. 산출 URL 은 CDN 디렉터리 안이며 경로 구분자·쿼리·스킴을 새로 만들지
  못한다(§A 의 880 케이스가 이를 실증). 반대로 trim 이 없으면 그 값이 폴백으로 새서 **같은
  계정이 두 앱에서 다른 초상**을 받았다 — 원본 리뷰가 지적한 바로 그 갈라짐이다.
- JS `trim` 이 벗기지 **않는** zero-width space(U+200B)는 화이트리스트가 정상 거절한다(→ default).

### C. 다른 초상 URL 조립 지점 — 없다

두 앱의 `picture`/`imageServer` → `src` 경로 전수 확인. 12개 호출 지점 **전부** `portraitUrl` 경유:

- `web/game`: `app/game/generals/page.tsx:268`, `app/game/join/page.tsx:450`,
  `app/game/my-generals/page.tsx:147`, `app/game/rankings/generals/page.tsx:139`,
  `app/game/select-pool/page.tsx:176`, `components/game/GeneralBasicCard.tsx:238`.
  (`app/game/battle-center/page.tsx:102` 는 `picture` 를 DTO 로 옮기기만 하고 렌더는
  `GeneralBasicCard` 가 한다 — URL 조립 아님.)
- `web/gateway`: `app/lobby/page.tsx:152`, `app/account/page.tsx:118`,
  `components/board/BoardAuthor.tsx:26`(`components/board/BoardList.tsx:25` 와
  `app/board/posts/[postId]/page.tsx:117,134` 가 이걸 통해서만 렌더).
- **서버 사이드 조립 없음.** Kotlin 쪽에서 `picture` 를 URL 로 만드는 코드는 0건이고,
  `app/board-api/.../GatewayBoardContracts.kt:59` 는 그 규약이 FE 한 군데에만 있어야 한다고
  명시한다. nginx 는 `infra/nginx/nginx.conf:105`·`default.conf:39,203` 에 독립적인
  `/d_pic/` canonical 정규식을 따로 갖고 있다(FE 화이트리스트와 이중 방어).

### D. 추가된 테스트가 실제로 잠그는가 — 잠근다 (공허하지 않음)

- `web/gateway/__tests__/portrait.test.tsx:38-47` — imgsvr=0 비화이트리스트 6케이스. 가드를
  빼면 각각 `C/../../secret.jpg`, `C/a/b.jpg`, `C/1001.svg`, `C/name with space.jpg`,
  `C/1001?x=1.jpg`, `C/https://evil.test/a.jpg` 를 만들어 **전부 default 와 달라져 실패**한다.
- `:53` / `web/game/__tests__/portrait.test.tsx:26` trim 케이스 — trim 을 빼면 `"  1001  "` 가
  화이트리스트에서 떨어져 default 로 가고 기대값 `C/1001.jpg` 와 어긋나 **실패**한다. 즉 이
  케이스는 화이트리스트가 아니라 **trim 계약**을 잠근다(둘 다 필요).
- 실행: `npx vitest run __tests__/portrait.test.tsx` — gateway **34 passed**, game **29 passed**.

### E. `TestResourceShadowingTest` — #487 을 실제로 잡고, CI 에서 깨지지 않는다

- `app/game-api/src/test/kotlin/opensamguk/gameapi/config/TestResourceShadowingTest.kt:26-32`
  가 `src/test/resources/application.{yml,yaml,properties}` 존재 자체를 실패로 만든다. `be3efd36`
  이 추가했던 파일이 정확히 `application.yml` 이므로 **그 커밋을 그대로 재현하면 이 테스트가
  깨진다** — 회귀를 잡는다. `:36-45` 는 `application-test.yml` 의
  `transactional-lock: false` 소실도 별도로 잠근다(가림이 아닌 경로로 그 키가 지워지는 경우).
- **작업 디렉터리 의존은 CI 에서 안전하다.** `Path.of("src","test","resources")` 는 상대경로지만
  Gradle `Test` 태스크의 `workingDir` 기본값은 해당 프로젝트의 `projectDir`(= `app/game-api`)다.
  `app/game-api/build.gradle.kts` 는 `workingDir` 을 재정의하지 않는다(`:54` 의
  `systemProperty("api.version", …)` 뿐). CI 의 `./gradlew build --no-daemon` 도 같은 기본값을
  쓴다. 실행 증거: `:app:game-api:test --tests '*TestResourceShadowingTest*' --rerun-tasks`
  → `BUILD SUCCESSFUL`, XML `tests=2 failures=0 errors=0`.
- **범위 한정(원본 should-fix 2건 중 1건은 여전히 열려 있음, 이 커밋이 닫았다고 주장하지도 않음):**
  가드는 **game-api 에만** 있다. `app/gateway-api/src/test/resources/application.yml` 과
  `app/board-api/src/test/resources/application.yml` 은 **지금도 존재한다**(둘 다 실측 확인).
  현재 무해한 이유는 원본 리뷰 §2 그대로이며, 함정은 그대로 살아 있다. 원본 요약표의
  should-fix "shadow 패턴 잔존"은 **미해결**로 남는다.
- `@ActiveProfiles("test")` 강제(원본 should-fix 3번)는 여전히 테스트로 강제되지 않는다. 다만
  근본 원인인 **파일 가림** 쪽이 잠겼으므로 잔여 위험은 낮다 — 관측으로 격하한다.

### F. `b7baf20d` ci.yml — jvm 은 닫혔고, `web`·`agent-system` 은 열려 있다

- `.github/workflows/ci.yml:30` `timeout-minutes: 40` — `jvm` 잡에 실재한다(`:26` 아래).
- YAML 유효: `yaml.safe_load` 성공, `jobs` = `['agent-system','jvm','web']`,
  `timeout-minutes` = `{agent-system: None, jvm: 40, web: None}`.
- **should-fix:** `web`(`:53`, matrix 2레그로 `pnpm install`+`pnpm build`)과
  `agent-system`(`:9`)에는 상한이 없다. 레지스트리 응답이 멈추면 같은 6시간 러너 기본 상한을
  그대로 문다. `#487` 이 난 잡은 아니므로 차단은 아니지만, "매달림 가드를 넣는다"는 커밋의
  목적상 같은 파일 안에 남은 구멍이다.
- `Surface test results`(`:40-51`)의 모듈별 카운트는 원본 §9 와 동일하게 **진단 출력이며 실패를
  만들지 않는다**(0건 모듈이어도 green).

### G. 잔여 관측 (차단 아님)

- **두 `portrait.ts` 사본은 `onPortraitError` 에서 아직 갈라져 있다.**
  `web/gateway/lib/portrait.ts:29-35` 는 `baseURI` 로 절대화해 정확히 canonical default 일 때만
  중단하고, `web/game/lib/portrait.ts:56-59` 는 `img.src.endsWith('/default.jpg')` 로 판단해
  **무관한 중첩 `default.jpg`(예: `https://example.test/nested/default.jpg`)도 폴백하지 않는다.**
  gateway 테스트 `:66-86` 이 정반대 동작을 명시적으로 잠그고 있으므로, 두 앱은 같은 입력에
  다르게 반응한다. 보안 노출은 아니고 엑박 UX 차이다. 원본 F1 의 요구 조치 2("헬퍼 하나를
  정본으로")를 택했다면 같이 사라졌을 부채다.
- 커밋 메시지 검증란의 "`bash -n` 통과"는 N1 을 놓치는 근거였다. 스크립트 변경은 문법이 아니라
  **실행**으로 증명해야 한다.

## 후속 검증 증거

| 항목 | 명령 | 결과 |
| --- | --- | --- |
| **gate.sh 실행** | `bash tools/parity/gate.sh backend` | **`Gate task/XML root count mismatch: 7 tasks, 6 roots`, exit 1 — 신규 결함 N1** |
| 두 앱 행위 동치 | node 차분 실행, 880 케이스(공백류 17종 포함) | 불일치 0 / CDN 이탈 0 / 경로·쿼리 주입 0 |
| 정규식 앵커 | `node -e '/^a$/.test("a\n")'` | `false` (후행 개행 우회 없음) |
| gateway 초상 | `npx vitest run __tests__/portrait.test.tsx` (web/gateway) | 34 passed |
| game 초상 | `npx vitest run __tests__/portrait.test.tsx` (web/game) | 29 passed |
| shadow 가드 | `:app:game-api:test --tests '*TestResourceShadowingTest*' --rerun-tasks` | `BUILD SUCCESSFUL`, XML `tests=2 failures=0 errors=0` |
| 잔존 shadow yml | `ls app/{gateway-api,board-api}/src/test/resources/` | 둘 다 `application.yml` **존재** |
| ci.yml | `python3 yaml.safe_load` | 유효, `{agent-system: None, jvm: 40, web: None}` |
| 초상 조립 지점 전수 | `grep -rn portraitUrl` + `picture` → `src=` 전수 | 12개 호출 전부 `portraitUrl` 경유, 서버 조립 0건 |

## 후속 요약

| 심각도 | 항목 | 상태 |
| --- | --- | --- |
| ~~fix-required~~ | F1 — gateway imgsvr=0 화이트리스트 + trim 계약 통일 | **`6229ce59` 로 닫힘 (실증)** |
| **fix-required** | **N1 — `gate.sh backend` 가 배열 길이 불일치로 exit 1. 표준 백엔드 증명이 0테스트** | **열림 (신규)** |
| should-fix | `ci.yml` `web`·`agent-system` 잡에 `timeout-minutes` 없음 | 열림 |
| should-fix | `gateway-api`·`board-api` 의 shadow `application.yml` 잔존 | 열림 (원본 그대로) |
| 관측 | `onPortraitError` 는 두 앱에서 여전히 다르게 동작(UX 한정) | 열림 |
| 관측 | `@ActiveProfiles("test")` 강제 수단 부재 — 근본 원인은 잠겨 위험 낮음 | 격하 |

---

# 3차 재검증 (2026-08-21, 독립 검증자 3차) — N1 닫힘, 신규 결함 없음

위 1·2차 리뷰와 **분리된 컨텍스트**의 세 번째 독립 검증자가 재검증했다. 코드도 앞 두 리뷰도
이 검증자가 쓰지 않았다. 범위는 **N1 의 종결 여부 + 그것을 닫은 커밋(`9709c799`)이 새로 만든
결함 유무**로 한정했다. 앞 패스들이 남긴 미결 2건(shadow yml, `TestResourceShadowingTest`
작업 디렉터리 의존)에도 독립 판정을 붙인다.

2차 패스가 N1 을 놓친 이유가 `bash -n`(문법만 검사)이었으므로, 이번 패스는 **모든 판정을
실제 실행으로** 세웠다. 실행하지 않은 것은 아래 §「실행하지 않은 것」에 명시한다.

**3차 판정: `cleared`.** fix-required 잔여 0건.

## N1 — 닫혔다 (실행으로 확인)

`9709c799` 는 `tools/parity/gate.sh:18` 의 `xml_roots` 에 `"app/board-api"` 를 `tasks` 와 **같은
마지막 위치**로 추가했다. 순서 검사(`:52-60`)까지 만족한다.

**(a) 정렬 + 파생 루프 — 전 타깃 실측.** 스크립트를 Java 검사 직전(`:62`)까지 그대로 잘라낸
사본에 `echo "${#tasks[@]} / ${#xml_roots[@]}"` 만 덧붙여 12개 타깃 + 무인자 + 미지 타깃을 돌렸다.
원본 파일은 손대지 않았다.

| 타깃 | 결과 |
| --- | --- |
| `backend`, 무인자 | `7 tasks / 7 roots` → `common logic infra app/game-engine app/game-api app/gateway-api app/board-api`, exit 0 |
| `common` `logic` `infra` | 각 `1/1`, exit 0 |
| `engine` `game-engine` | `1/1` → `app/game-engine`, exit 0 |
| `api` `game-api` | `1/1` → `app/game-api`, exit 0 |
| `gateway` `gateway-api` (신규) | `1/1` → `app/gateway-api`, exit 0 |
| `board` `board-api` (신규) | `1/1` → `app/board-api`, exit 0 |
| `bogus` | exit **64** + `Usage: tools/parity/gate.sh [backend|common|logic|infra|engine|api|gateway|board]` |

즉 신규 타깃 2개도 `:app:board-api:test` → `app/board-api` 파생 루프를 통과하고, usage 문자열도
갱신돼 있다. `api|game-api` 패턴이 `gateway-api`/`board-api` 를 삼키지 않는다(case 는 부분일치가
아니라 glob 완전일치).

**(b) 실제 스크립트가 Gradle 호출까지 도달한다.** `bash tools/parity/gate.sh backend` 를 그대로
실행했다:

```
Running gate 'backend' with Java 21 at /Library/Java/JavaVirtualMachines/temurin-21.jdk/.../bin/java
Calculating task graph as no cached configuration is available for tasks:
  :common:test :logic:test :infra:test :app:game-engine:test :app:game-api:test
  :app:gateway-api:test :app:board-api:test
```

정렬 검사·Java 21 검사·`./gradlew` 호출을 모두 통과했고 **태스크 그래프에 7개가 전부** 들어갔다.
2차 패스가 기록한 `Gate task/XML root count mismatch: 7 tasks, 6 roots` / exit 1 은 더 이상 나오지
않는다. 전체 스위트(~30분)는 의도적으로 여기서 중단했다(§실행하지 않은 것).

**(c) `:app:board-api:test` 는 실재 태스크다.** `./gradlew :app:board-api:test --dry-run` →
`BUILD SUCCESSFUL`, `:app:board-api:test SKIPPED`(dry-run 표기). `settings.gradle.kts:19` 가
`include("app:gateway-api", "app:game-api", "app:game-engine", "app:board-api")` 로 모듈을 등록하고
있고 `app/board-api/src/test` 에 테스트 8파일이 실재한다.

**(d) `missing_roots` 는 fail-closed 다.** 스크립트 말미 python 블록(`:109-146`)을 **원문 그대로**
추출해 직접 돌렸다. 존재하지 않는 루트를 섞으면
`No Gradle test XML files found for selected module roots: app/does-not-exist` + **exit 1**.
"태스크는 돌았는데 XML 0건"은 조용히 통과하지 않는다. 실패 XML(`failures`/`errors`)도 `RED` 출력
후 exit 1 이며, 그 앞단에 `grep -q "BUILD SUCCESSFUL"`(`:104`) 이 한 겹 더 있다.

**(e) `9709c799` 가 범위 밖을 건드렸는가 — 아니다.** `git show 9709c799 --stat` →
`tools/parity/gate.sh | 12 ++++++++++--` **1파일 / +10 −2**. diff 전문 확인 결과 변경은
(i) `xml_roots` 1항목 추가, (ii) `gateway|gateway-api`·`board|board-api` case 2블록 추가,
(iii) usage 문자열 1줄 — 그 셋뿐이다. 소스·마이그레이션·골든·픽스처 변경 0건.

**(f) 신규 결함(N2) — 없다.** 위 (a)~(e) 외에 이 커밋이 만든 새 결함을 찾지 못했다.

## 앞 패스 미결 2건 — 독립 판정

### 1. `gateway-api`·`board-api` 의 shadow `application.yml` — **차단 아님. 별도 티켓.**

두 파일 모두 실재한다(실측). 각각이 main 을 파일 단위로 가리는 것도 사실이다. 그러나 **#487 을
지금 재현할 수 있는 모듈은 없다.** 근거:

- **`board-api`**: main `application.yml:12-13` 이 이미 `flyway.enabled: false` 다. 즉 board-api 는
  **운영에서 Flyway 를 돌리지 않는다** — 잃어버릴 `transactional-lock` 키가 main 에 애초에 없다.
  이 계약은 `BoardApiRuntimeConfigurationTest.kt:17-18`(`assertEquals(false, ...spring.flyway.enabled)`)
  이 테스트로 잠그고 있다. Flyway 를 실제로 켜는 유일한 테스트 `GatewayBoardMigrationIT.kt:74-75`
  는 `enabled=true` 와 `transactional-lock=false` 를 **자기가 함께** 넣는다. → shadow 로 인한 손실 0.
- **`gateway-api`**: main `application.yml:12-21` 은 `flyway.enabled: true` + `transactional-lock: false`
  를 갖고, test shadow(`:11-12`)가 그것을 `enabled: false` 로 덮는다. 여기까지는 #487 과 **같은 모양**
  이다. 다만 Flyway 를 다시 켜는 IT 3개가 전부 잠금 키를 손으로 같이 넣는다 —
  `AdminSeederPostgresIT.kt:71-72`, `NicknameChangePostgresIT.kt:175-176`,
  `ProfileIconPostgresConcurrencyIT.kt:113-114`. 저장소 전수 grep 결과
  `spring.flyway.enabled = true` 3건 / `transactional-lock = false` 3건, **누락 0건**.

판정: 현재 결함 아님(재현 경로 없음), 이 PR 이 만들거나 악화시킨 것도 아님. 그러나 함정은 살아
있다 — gateway-api 에 Flyway 를 켜는 IT 를 **하나 더 추가하면서 잠금 키를 빠뜨리면 곧바로 #487 이
재현된다**(main 의 기본값이 shadow 로 지워져 있으므로 폴백이 없다). `TestResourceShadowingTest`
는 game-api 에만 있어 이 경우를 잡지 못한다. → **별도 티켓**(가드를 두 모듈로 확장하거나 shadow 를
`application-test.yml` + `@ActiveProfiles("test")` 로 이관). 차단은 아니다.

### 2. `TestResourceShadowingTest` 의 작업 디렉터리 의존 — **공허 통과 불가. 다만 조건부다.**

- 1번 단언(`:25-33`)은 상대경로 `src/test/resources/application.yml` 의 **부재**를 확인하므로,
  CWD 가 틀리면 그 자체로는 공허하게 통과한다 — 이 지적은 원리상 옳다.
- 그러나 2번 단언(`:36-45`)이 **같은 상대경로**로 `application-test.yml` 을 열어 실제 내용
  (`transactional-lock:\s*false`)까지 읽는다. 2번이 green 이면 CWD 는 `app/game-api` 가 확실하고,
  따라서 1번도 공허가 아니다. 두 단언이 서로를 보증한다.
- 실행 증거(이번 패스에서 직접 재실행): `:app:game-api:test --tests '*TestResourceShadowingTest*'
  --rerun-tasks` → `BUILD SUCCESSFUL in 3m 35s`, XML `tests="2" skipped="0" failures="0" errors="0"`.
  2번이 통과했으므로 1번은 실제 파일 시스템을 본 것이다.
- CWD 근거도 재확인: Gradle `Test` 태스크의 `workingDir` 기본값은 `projectDir` 이고, 저장소 전체
  (`build.gradle.kts` 루트 + 모든 `app/*/build.gradle.kts`)에 `workingDir` 재정의가 **0건**이다
  (`grep -rn workingDir` 결과 없음). `app/game-api/build.gradle.kts:52-58` 의 `tasks.test` 블록도
  `useJUnitPlatform` + systemProperty/environment 뿐이다.

판정: **견고하다 — 단, 2번 단언에 의존한다.** 누군가 2번을 지우거나 `application-test.yml` 이
사라지면 1번은 그날부터 조용히 공허해진다. 관측으로 기록한다(차단 아님). 1번을 자립시키려면
`Path.of("build.gradle.kts").exists()` 류의 앵커 단언 한 줄이면 된다.

## 실행하지 않은 것 (명시)

- **`bash tools/parity/gate.sh backend` 의 전체 Gradle 스위트를 끝까지 돌리지 않았다.** ~30분+
  이고 이번 패스의 판정 대상(N1 = 배열 정렬로 인한 조기 exit)은 Gradle 호출 도달 여부로 결정되므로,
  `Calculating task graph ... :app:board-api:test` 확인 직후 중단했다. 따라서 **7개 모듈 전체가
  green 인지는 이 패스가 주장하지 않는다 — UNKNOWN.** 그 증명은 PR 머지 전 별도 풀런이 필요하다.
- `:app:board-api:test` 를 실제 실행하지 않았다(`--dry-run` 으로 태스크 실재만 확인).
- 초상 헬퍼(F1)·`ci.yml`·`portrait` 테스트는 2차 패스가 실행 증거로 닫았고 `9709c799` 가 건드리지
  않았으므로(§(e) 1파일 diff) 재실행하지 않았다.
- gateway-api/board-api 의 Flyway IT 를 실제로 돌려 데드락 부재를 실증하지는 않았다 — 위 §1 판정은
  **정적 전수 대조**(enabled=true 3건 ↔ lock=false 3건)에 기반한다.

## 3차 검증 증거

| 항목 | 명령 | 결과 |
| --- | --- | --- |
| **N1 정렬** | 잘라낸 사본으로 12타깃 + 무인자 + 미지타깃 실행 | `backend` **7/7**, 개별 전부 1/1, 미지타깃 exit 64 |
| **N1 실행 도달** | `bash tools/parity/gate.sh backend` | Java 21 통과 → `Calculating task graph ... 7개 태스크 전부`. mismatch 메시지 소멸 |
| board-api 태스크 실재 | `./gradlew :app:board-api:test --dry-run` | `BUILD SUCCESSFUL`, `:app:board-api:test` 그래프 포함 |
| 모듈 등록 | `settings.gradle.kts:19` | `include(..., "app:board-api")` |
| **fail-closed** | gate.sh 의 python 블록 원문 추출 후 없는 루트로 실행 | `No Gradle test XML files found ... app/does-not-exist`, **exit 1** |
| 커밋 범위 | `git show 9709c799 --stat` + diff 전문 | `tools/parity/gate.sh` **1파일 +10 −2**, 그 외 0 |
| shadow yml 실재 | `cat app/{gateway-api,board-api}/src/{main,test}/resources/application.yml` | 둘 다 test shadow 존재. board main `flyway.enabled: false`, gateway main `enabled: true`+`lock: false` |
| Flyway 재활성 IT 전수 | `grep -rn flyway app/{gateway-api,board-api}/src/test` | `enabled=true` 3건 ↔ `transactional-lock=false` 3건, **누락 0** |
| 재발 가드 재실행 | `:app:game-api:test --tests '*TestResourceShadowingTest*' --rerun-tasks` | `BUILD SUCCESSFUL in 3m 35s`, XML `tests=2 failures=0 errors=0 skipped=0` |
| workingDir 재정의 | `grep -rn workingDir build.gradle.kts app/*/build.gradle.kts` | **0건** (기본값 = projectDir) |

## 3차 요약

| 심각도 | 항목 | 상태 |
| --- | --- | --- |
| ~~fix-required~~ | **N1 — `gate.sh backend` 배열 불일치로 0테스트** | **`9709c799` 로 닫힘 (실행 증거)** |
| — | N2(신규 결함) | **없음** |
| should-fix | `gateway-api` shadow yml — main 의 `transactional-lock: false` 를 지워 Flyway IT 3개가 손으로 재공급 중. 다음 IT 가 빠뜨리면 #487 재현 | 열림 → **별도 티켓** |
| should-fix | `board-api` shadow yml — 동일 패턴이나 main 이 Flyway 미소유라 손실 키 없음 | 열림 (위험 낮음, 같은 티켓) |
| should-fix | `ci.yml` `web`·`agent-system` 잡에 `timeout-minutes` 없음 (2차 패스 지적) | 열림 |
| 관측 | `TestResourceShadowingTest` 1번 단언은 2번 단언이 CWD 를 보증할 때만 유효 — 2번이 사라지면 공허해짐 | 신규 |
| 관측 | `onPortraitError` 두 앱 동작 차이(UX 한정) | 유지 |
| **UNKNOWN** | `gate.sh backend` **전체 7모듈 green 여부** — 이 패스는 Gradle 도달까지만 확인했다 | 머지 전 풀런 필요 |

### 3차 패스 동시성 고지 (중요 — 위 실행 증거를 읽을 때 같이 볼 것)

3차 검증 중 같은 워크트리에서 다른 독립 검증자(`pr488-reverify`)가 병렬로 gradle 을 돌리고 있었고,
**내 gradle 실행이 그것과 시간대가 겹쳤다.** 로그 파일명(gate.sh 가 시각을 인코딩)과 mtime 기준 사실:

| 실행 | 주체 | 시각 |
| --- | --- | --- |
| `gate.sh board` ×2 | 타 검증자 | 14:06:15, 14:07:28 (~14:11:06 종료) |
| `bash tools/parity/gate.sh backend` (task graph 확인 후 중단) | **3차 검증자(나)** | **14:08:59 → 14:11:29 kill** |
| `./gradlew :app:board-api:test --dry-run` | **3차 검증자(나)** | 14:11~14:12 (테스트 미실행) |
| `gate.sh backend` (풀런) | 타 검증자 | 14:12:08 시작 |
| `:app:game-api:test --tests '*TestResourceShadowingTest*' --rerun-tasks` | **3차 검증자(나)** | **~14:10 → 14:13:53 종료** |

즉 **내 game-api 단일 테스트 실행이 타 검증자의 backend 풀런 초반과 겹쳤다.** 결과 해석:

- 내 3건 중 **정렬/파생 루프 검증(순수 bash 사본)과 python XML 블록 검증은 gradle 을 타지 않으므로
  충돌 영향이 없다.** 이 둘이 N1 종결 판정의 핵심 근거다.
- 내 `gate.sh backend` 도달 확인은 **구성 단계(Calculating task graph) 출력**만 근거로 삼았다 —
  `build/` 산출물이 아니라 태스크 그래프 계산 결과다. 다만 동시 실행이었던 것은 사실이다.
- `TestResourceShadowingTest` 결과(`tests=2 failures=0`)는 파일 시스템만 보는 테스트라 내용상
  오염 여지가 없으나, **타 검증자의 backend 풀런이 그 시각 `app/game-api/build/` 를 공유했다.**
  타 검증자 쪽 game-api 결과에 `Could not write XML test results` / `NoClassDefFoundError` 류가
  보이면 **회귀가 아니라 이 충돌**일 가능성을 먼저 배제해야 한다.
- 내가 위 §3차 검증 증거에서 인용한 `app/board-api` XML(8스위트/52테스트)은 **타 검증자의
  14:11:05 실행 산출물**이다. 내가 돌린 것이 아니다.

이후 팀리드 지시로 **3차 검증자는 gradle 실행을 전면 중단했다.** `gate.sh backend` 의 7모듈 풀런
green 여부는 위 요약표대로 **UNKNOWN** 이며, 타 검증자의 풀런 결과로 채워야 한다 — 그 결과는
내가 실행한 것이 아니므로 인용할 때 출처를 명시할 것.

---

## 3차 UNKNOWN 종결 — CI 풀런 (기록: 작성 레인)

3차 패스가 남긴 유일한 UNKNOWN(`gate.sh backend` 전체 7모듈 green 여부)은 **CI 가 이미
답했다.** `ci.yml` 의 `jvm` 잡은 `./gradlew build --no-daemon` 으로 전 모듈을 빌드·테스트한다.

- 런 `32449185250` / 잡 `96674285041`, head `9709c799` — `BUILD SUCCESSFUL in 9m 9s`.
- 모듈별 XML 개수(같은 잡의 `Surface test results` 단계 출력):
  `common 45 / logic 294 / infra 61 / app/gateway-api 31 / app/game-api 80 /
  app/game-engine 151 / app/board-api 8` — **7모듈 전부 0건 아님**.

로컬 풀런보다 이쪽이 증거로 강하다 — 깨끗한 러너 단독 실행이라 동시 Gradle 충돌
가능성이 없다. 로컬에서 관측된 `NoClassDefFoundError` 류 `:common:test` 실패는 같은
커밋이 CI 에서 green 인 것으로 **환경 아티팩트로 확정**된다(같은 트리, 충돌 없는 실행).

판정 변경 없음 — `cleared` 유지. UNKNOWN 1건 종결.

---

# 4차 — 2차 검증자(`pr488-reverify`) 종결 노트

2차 패스에서 N1 을 올린 검증자가, 3차 `cleared` 판정과 그 근거를 확인하고 남기는 마지막 기록이다.
**판정은 `cleared` 유지에 동의한다** — 새 blocker 없음. 다만 **정정 1건과 보완 2건**이 있다.

## 정정 — shadow yml 안전 근거가 gateway-api 에서 사실과 다르다

"gateway-api·board-api 는 **main·test 양쪽 모두** `flyway.enabled: false` 라 V29 에 도달할 수 없다"는
근거가 오갔는데, **gateway-api 는 그렇지 않다**:

| 파일 | `flyway.enabled` |
| --- | --- |
| `app/gateway-api/src/main/resources/application.yml:13` | **`true`** |
| `app/gateway-api/src/test/resources/application.yml:12` | `false` |
| `app/board-api/src/main/resources/application.yml:13` | `false` |
| `app/board-api/src/test/resources/application.yml:12` | `false` |

gateway-api 는 `build.gradle.kts:26` 으로 `:infra` 를 물고 있어 V29 가 클래스패스에 있고,
**운영에서 실제로 Flyway 를 돌린다.**

**결론은 그래도 "차단 아님"이다 — 이유가 다를 뿐이다.**
`app/gateway-api/src/main/resources/application.yml:21` 이 `transactional-lock: false` 를 갖고 있고
(`:18-20` 주석이 V29 CONCURRENTLY 요건을 명시), flyway 를 켜는 세 모듈 전부가 이 키를 갖는다
(gateway-api `:21`, game-api `:21`, game-engine `:21`). **운영 데드락 위험은 없다.**
테스트가 안 매달리는 이유도 "main 이 꺼져서"가 아니라 **test shadow 가 꺼서**다.

즉 gateway-api 의 shadow yml 은 **#487 과 정확히 같은 모양**이다 — main 의 flyway 설정 전체
(`transactional-lock` 포함)를 파일 단위로 가린다. 지금 안 터지는 건 그 shadow 가 flyway 자체를
꺼두기 때문이고, 누군가 그 한 줄을 `true` 로 바꾸는 순간 #487 이 두 번째 모듈에서 재현된다.
**"양쪽 다 꺼져 있어서 안전"으로 기록하면 다음 사람이 정확히 그 한 줄을 밟는다.**

**최종 입장(3번 질문): 차단 아님, 별도 티켓으로 충분.** 오늘 실패도 운영 위험도 없고 PR #488 범위
밖이다. 다만 티켓에는 위 표와 "gateway-api main 은 flyway ON" 을 근거로 적어라. 처방은
`TestResourceShadowingTest` 를 두 모듈에 복제하고 오버라이드를 `application-test.yml` +
`@ActiveProfiles("test")` 로 옮기는 것이다(game-api 가 이미 그 형태다).

## 보완 1 — CI 런 인용은 잡 하나가 아니라 런 전체로

`32449185250`(head `9709c799`)의 `jvm` 은 `success`(05:04:07→05:13:40)가 맞다. 모듈별 XML 7모듈
전부 0건 아님도 로그에서 확인했다. 이걸로 2차의 `NoClassDefFoundError` 가 **환경 아티팩트로 확정**
되고 F3 불필요하다는 결론에 동의한다.

다만 **그 런의 전체 결론은 `failure`** 다. 원인은 `agent-system` 잡의
`Check provider-agnostic agent working system` 스텝:

```
- **ERROR cross-agent-critique**: Unresolved Verdict: fix-required blocks completion:
  docs/superpowers/reviews/2026-08-21-opensam-221-220-hang-fix-review.md
```

즉 **그 시점 아티팩트가 아직 `fix-required` 였기 때문**이며, `49d16b76` 이 `cleared` 로 바꿨으니
자기해소된다. 코드 결함이 아니고, 오히려 `CLAUDE.md` 의 cross-agent-critique 게이트가 설계대로
동작한 증거다. 다만 "CI 가 답을 냈다"를 잡 하나만 인용해 적으면 런이 빨간 사실이 기록에서 빠진다.
**후속 확인 필요:** `49d16b76` 런(`32450039800`)은 이 노트 작성 시점 `in_progress` —
green 전환 여부는 **UNKNOWN**, 머지 전 확인할 것.

## 보완 2 — 3차 UNKNOWN(`gate.sh backend` 풀런)의 현재 상태

3차 요약표의 `UNKNOWN — gate.sh backend 전체 7모듈 green 여부` 는 다음과 같이 갈라 적어야 정확하다:

- **7모듈 테스트 green 여부 → 닫혔다.** CI `jvm` 이 같은 트리·같은 커밋에서 `./gradlew build` 로
  7모듈 전부 실행·green. 충돌 없는 단독 러너라 로컬 풀런보다 강한 증거다.
- **`gate.sh` 자체의 XML 평가 경로 → 닫혔다(2차 증거).** CI 는 `gate.sh` 를 타지 않으므로 이건
  별개다. 2차에서 스크립트 끝 python 블록을 잘라내 직접 돌렸다: 빈 루트 → `No Gradle test XML
  files found for selected module roots: app/board-api` **exit 1**(조용한 통과 아님, fail-closed),
  실제 저장소 루트 → `XML gate green: 8 suites, 52 tests`. `app/board-api` 가 실제로 읽힌다.
- **`gate.sh backend` 를 끝까지 돌린 단일 실행 → 여전히 없다.** 내 풀런(`backend-20260821141208`)은
  팀리드 지시로 중단했다. 위 두 조각으로 판정 근거는 충분하지만, "풀런 1회 관측"은 **UNKNOWN** 으로
  남는다. 추측으로 채우지 않는다.

## 로그 귀속 최종 정리 (2차에서 두 번 틀렸던 항목)

3차 검증자의 동시성 고지로 확정됐다. 내 초기 귀속은 틀렸고, 정정 기록을 남긴다.

| 로그 | 주체 | 결과 |
| --- | --- | --- |
| `board-20260821140615` | 2차 검증자(나) | `BUILD FAILED` — XML write 충돌 |
| `board-20260821140728` | 팀리드 | `BUILD SUCCESSFUL` / `8 suites, 52 tests` |
| `backend-20260821140859` | **3차 검증자** (내가 팀리드로 오귀속 → 정정) | task graph 확인 후 kill |
| `backend-20260821141208` | 2차 검증자(나) | 팀리드 지시로 중단 |

`:common:test` 의 `NoClassDefFoundError` 는 이 겹침에서 나왔고 CI 단독 실행이 green 이므로
**회귀 아님으로 확정**한다.

## 4차 요약

| 심각도 | 항목 | 상태 |
| --- | --- | --- |
| — | N1 (`gate.sh` 정렬) | `9709c799` 로 닫힘 — 동의 |
| — | F1 (초상 화이트리스트) | `6229ce59` 로 닫힘 — 2차에서 실증 |
| **정정** | shadow yml 안전 근거: gateway-api main 은 `flyway.enabled: **true**` | 결론(차단 아님)은 유지, 근거 교체 |
| should-fix | gateway-api·board-api shadow yml — 별도 티켓 | 열림 |
| should-fix | `ci.yml` `web`·`agent-system` 에 `timeout-minutes` 없음 | 열림 |
| 관측 | `onPortraitError` 두 앱 동작 상이(UX 한정) | 열림 |
| UNKNOWN | `49d16b76` CI(`32450039800`) green 전환 | 머지 전 확인 |
| UNKNOWN | `gate.sh backend` 풀런 1회 관측 | 미실행 (중단) |

**2차 검증자 최종: `cleared` 에 동의. 잔여 fix-required 0건.**
