# OPENSAM-221 / OPENSAM-220 — game-api 테스트 매달림 + 슬림 JWT 후속 리뷰 (PR #488)

Scope: app/game-api/ app/gateway-api/ web/game/ .github/workflows/ docs/ — 브랜치 `opensam-220-slim-jwt-issuance`, base `origin/main`, 5커밋
Verdict: fix-required

작성 레인과 분리된 컨텍스트에서 독립 리뷰어(opus)가 read-only로 공격했다. 근본원인 주장 자체는
**증거로 확인됐고** 매달림 수정도 게임 API 안에서는 빈틈이 없다. 다만 `web/gateway` 쪽 초상 헬퍼가
같은 구멍을 그대로 남긴 채 남아 있어 `fix-required` 1건이다.

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

## 요약

| 심각도 | 항목 |
| --- | --- |
| **fix-required** | F1 — `web/gateway/lib/portrait.ts` imgsvr=0 화이트리스트 미적용 + 회귀 테스트 부재. 두 사본의 trim 계약도 갈라짐 |
| should-fix | `tools/parity/gate.sh` backend 태스크에 `:app:board-api:test` 누락 — 문서와 여전히 불일치 |
| should-fix | gateway-api·board-api 의 `src/test/resources/application.yml` shadow 패턴 잔존(현재 무해, 함정은 동일) |
| should-fix | `@ActiveProfiles("test")` 규칙 강제 수단이 YAML 주석뿐 — 아키텍처 테스트 1줄 권장 |
| 관측 | 근본원인 기술이 "flyway 키 소실"로 좁다. 실제로는 main yml **전체** 치환 |
| 관측 | 클레임 키 잠금은 RS256×`includeProfileClaims=false` 경로만 커버 |
| 관측 | `#468` "테스트 8개" = 파일 8개 / `@Test` 42개 |
| 관측 | deploy.yml XML 카운트는 진단 출력이며 실패를 만들지 않는다 |
| UNKNOWN | core2026 감사의 원본 commit `2a73f80b`, "1234 커밋 뒤짐", URL 변경 — 외부 Gitea·git-ignore `legacy/` 라 저장소 내 검증 불가 |
