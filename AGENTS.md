# AGENTS.md — opensamguk

AI 코딩 에이전트 · 기여자용 온보딩 가이드. 코드를 건드리기 전에 이 문서를 읽으세요. **패러티/아키텍처 규칙은 조용히 어기기 쉽습니다.** 상세·정본 규칙은 [`CLAUDE.md`](CLAUDE.md)에 있으며 이 문서는 그 요약 + 빠른 참조입니다.

---

## 프로젝트 한 줄 요약

PHP 게임 **devsam/core**(삼국지 모의전투 HiDCHe / 삼모)를 **메모리 중심 CQRS** 스택(**Kotlin/Spring Boot + Next.js + PostgreSQL + Redis + nginx**)으로 byte-단위 충실 이식.

- **`legacy/devsam-core` (PHP) = GRAND TRUTH.** 모든 동작(RNG 추출 순서·반올림·한글 로그·부수효과 순서)을 byte 단위로 일치. 원작을 절대 "개선"하지 않음.
- **`legacy/devsam-core2026` (TS)** = 구조 힌트용 2차 오라클. **PHP가 모든 divergence에서 이김.**
- 프론트 grand truth = `hwe/ts/` Vue (`hwe/*.php`는 dist mount 셸).
- `legacy/`는 **git-ignore**, 커밋 금지. 저장소는 코에이 IP 검토 전까지 **비공개** — 자산/IP·비밀키·자격증명 커밋 금지.

---

## 모듈 구조

`settings.gradle.kts`에 선언:

| 모듈 | 종류 | 포트 | 책임 |
|------|------|------|------|
| `common` | 라이브러리 | — | RNG 커널(`rng/LiteHashDrbg`+`RandUtil`+`SeedSerializer`), `util/PhpRound`, `log/*`(조사·ConvertLog·토큰), `constants/GameConst` |
| `logic` | 라이브러리 | — | 순수 게임 로직(Spring/DB 없음): `stats/ActionPipeline`, `actions/*`+`CommandRegistry`, `war/*` 전투, `ai/*` GeneralAI, `event/*` DSL, `tick/*`, 베팅·경매·유산·메시지 |
| `infra` | 라이브러리 | — | `JdbcFlushExecutor`(JDBC 전용 flush + 델타/툼스톤 + row mapper), Flyway `db/migration/V*.sql`, Redis, JPA read repository, `seed/ScenarioImporter` |
| `app:gateway-api` | Boot 앱 | `:8080` | 인증(JWT/BCrypt) · 프로필 · 어드민(`AdminSeeder`) |
| `app:game-api` | Boot 앱 | `:8081` | read + precheck + 명령 intake + SSE 릴레이 |
| `app:game-engine` | Boot 앱 | `:8082` | 턴 데몬: `InMemoryTurnWorld`·`ChangeRecorder`·`MonthlyPipeline`·`TurnRunService`, `boot/ScenarioSeedRunner`+`WorldSnapshotLoader` |
| `web/gateway` | Next.js | `:3000` | 게이트웨이 프론트(로그인/로비/어드민) |
| `web/game` | Next.js | `:3001` | 게임 프론트(인게임 UI) |

아키텍처:

```
api ──Redis(XADD)──▶ game-engine daemon ──JDBC batch flush──▶ PostgreSQL
 ▲                   (InMemoryTurnWorld = source of truth)         │
 └──────────── turnCompleted SSE ◀── ChangeRecorder dirty/created/deleted
```

---

## The ONE 데몬-write 규칙 (아키텍처 테스트로 강제)

game-engine 데몬은 **절대** JPA `EntityManager`로 write하지 않습니다. JPA = read/precheck 전용(game-api). 데몬 write는 **오직** `ChangeRecorder` → `JdbcFlushExecutor` JDBC 배치만. 두 dirty-truth가 공존하면 조용히 발산합니다.

- 강제 테스트: `DaemonNoEntityManagerTest` / `InfraNoEntityManagerTest`.
- 예외(비위반): `boot/ScenarioSeedRunner`·`AdminSeeder`는 `JdbcTemplate`만 사용(Flyway 동급), write-path scan(`opensamguk.engine.{flush,turn,run}`) 밖인 `opensamguk.engine.boot`에 위치.
- precheck 합의 테스트: `PrecheckFullCrossCallSiteTest` — game-api precheck와 game-engine reserved-turn 평가가 Allow/Deny + reason 문자열에서 일치함을 증명.

---

## 빌드 · 테스트 명령

**전제**: JDK 21 LTS(Gradle 8.12는 Java 25+ 파싱 실패), Docker, Node 20 + pnpm(corepack). **항상 repo root에서, `JAVA_HOME`을 21로 고정.**

```bash
# 전체 빌드 + 테스트
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew build

# 패러티 백엔드 표준 게이트(XML 검증 포함)
tools/parity/gate.sh backend

# 단일 모듈
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test

# 커밋 전 권장 풀 체크
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew \
  :common:test :logic:test :infra:test :app:game-engine:test :app:game-api:test

# Docker 스모크 (이미지 빌드 + 전체 스택 + health 단언)
./tools/smoke.sh

# 프론트 dev
cd web/gateway && corepack pnpm dev   # :3000
cd web/game    && corepack pnpm dev   # :3001
```

### ⚠️ gradle context-mode 주의

호스트가 gradle을 **context-mode 래퍼**로 라우팅 → `task-notification` **exit 0이 부정확**합니다. 빌드 성공 여부는 반드시:

- 출력 tail로 검증: `... 2>&1 | tail -40` 후 `BUILD SUCCESSFUL` + 테스트 카운트 grep
- 또는 테스트 결과 XML: `**/build/test-results/test/*.xml`
- UP-TO-DATE false-green 의심 시 `--rerun-tasks`

### Testcontainers (macOS)

`infra`·`app:game-api`·`app:game-engine`의 `tasks.test`에 배선: `api.version=1.44`, `DOCKER_CONTEXT=default`, `TESTCONTAINERS_RYUK_DISABLED=true`. **Docker 미사용 시 통합 테스트는 fail이 아니라 skip.**

---

## 패러티 규율 (NON-NEGOTIABLE)

상세는 [`CLAUDE.md`](CLAUDE.md). 요약 6조:

1. **RNG draw-for-draw** — `RandUtil(LiteHashDrbg(seed))`. 추출 **순서·횟수·인자**가 타깃. 전투는 단일 `RandUtil(warSeed)` 참조 전달, 중간 재시드 금지.
2. **반올림** — `Util::round` = half-AWAY → `PhpRound`(음수 스케일 `phpRound(v,-2)`). `Math.round`/`kotlin.math.round` 금지. `toInt`/`intdiv` = 0 방향 절삭. 데미지 클램프 = `ceil()`.
3. **한글 로그 byte-패러티** — 조사·색/태그·접두어 byte 일치. 로그 순서 = 실행 순서.
4. **델타 flush, 인라인 write 금지** — `ChangeRecorder` `created`/`dirty`/`deleted` → 일괄 flush. 리졸버는 델타만.
5. **충실 이식, 날조 금지** — 골든은 실제 PHP 캡처에서만. 불일치 시 Kotlin 구현 수정, 골든·테스트 약화 금지. 캡처 불가 시 증거와 함께 격리 + 백로그.
6. **삽입 순서 보존** — `LinkedHashMap`. PHP 8.0+ 정렬 stable — 비-stable 2차 비교자 금지.

---

## 코드 스타일

- Kotlin official(`kotlin.code.style=official`). 들여쓰기: `.kt`/`.kts` 4칸, `.ts`/`.tsx`/`.json`/`.yml` 2칸.
- UTF-8, LF, 끝 개행 필수(`.editorconfig`). 패키지 소문자 `opensamguk.<module>`. 클래스 PascalCase, 함수/변수 camelCase.
- 주석은 영어, 게임 콘텐츠 문자열은 한글. 테스트명은 backtick 서술형.

---

## 골든 픽스처

골든 수치/로그/시드는 **오직** `tools/php-golden/` 실제 PHP 캡처(Docker: MariaDB 11.4 + `php:8.3-cli`, 시나리오 `1010`)에서. quirk: `j_install.php` 두 번 호출, install 비멱등(매 실행 fresh DB), 덤프 byte-identical. 골든은 `logic/.../resources/golden/`·`common/.../resources/golden/`에 read-only 소비 대상으로 둠 — 다른 모듈로 복사 금지.

---

## 작업 운영 체계 / skills.sh

- 정본 운영 문서: `docs/superpowers/WORKING_SYSTEM.md`.
- 루프 엔지니어링 정본: `docs/superpowers/LOOP_ENGINEERING.md`. Claude/Codex 모두 같은 문서를 기준으로 측정 → 가설 1개 → 재측정 → 채택/원복 루프를 돈다.
- 레거시 갭·UI 패러티·실서버 버그는 반드시 `opensamguk-php-oracle`(PHP/hwe path+line 증거) → `webapp-testing`(UI 재현/브라우저 관측) → `systematic-debugging`(원인 수렴, 수정 전 root cause) → `loop-engineering`(베이스라인/가설/채점/채택) 순서로 묶는다. 하나라도 못 쓰면 `채점대기`/`blocked`를 기록하고 조용히 ship/merge하지 않는다.
- skills.sh 설치 목록은 `skills-lock.json`에 고정. `.agents/skills/`는 로컬 실행 표면이며 git-ignore이므로 새 환경에서는 `DISABLE_TELEMETRY=1 npx --yes skills experimental_install`로 복원.
- 설치된 외부 스킬: `next-best-practices`, `webapp-testing`, `redesign-existing-projects`, `java-spring-boot`, `java-testing`, `kotlin-spring-boot`, `supabase-postgres-best-practices`.
- `java-testing`은 skills.sh Gen 감사상 High Risk로 표시됨. 참고로만 사용하고, 실제 합격 판정은 repo 테스트와 `tools/parity/gate.sh`가 담당.
- PHP 레거시 분석은 항상 `legacy/devsam-core` source path + line range → parity dimensions → `tools/php-golden/` capture/compare → Kotlin/Next implementation 순서.
- 프론트 현대화는 `hwe/ts/` Vue 디자인/흐름을 grand truth로 삼고, 하드코딩 placeholder 대신 실제 API 상태를 렌더.
- provider/model 공통 개발도구는 `tools/agent-system/check.py`. 로컬은 `tools/agent-system/check.py`, CI/PR은 `tools/agent-system/check.py --strict --base origin/main`, 기계 판독은 `--format json`.
- 비자명 작업은 구현자와 별개 agent/provider의 비판적 검증을 거친다. Kimi-backed Claude Code, Codex, Gemini 등 병렬 agent는 서로 PHP 증거·테스트·문서·운영 불변식을 공격적으로 검토하고, `fix-required`가 남아 있으면 ship/merge 금지.

---

## 페이즈 / 브랜치 / 커밋

- 흐름: `P0 → … → P8`, 각 페이즈 = **spec → plan → adversarial review → execute → gate**. 플랜 `docs/superpowers/plans/`, 리서치 `docs/superpowers/research/`.
- **Foundation-first**: 공유 확장점은 Tier-0 wave에서 먼저, 이후 family는 소비만. 병렬 worktree family는 **disjoint** — 같은 파일 co-widen 금지.
- 브랜치 스택(페이즈당 1, 부모 분기): `p0a-foundation-scaffold → p0b-parity-kernel → p1-vertical-slice → p2-commands-constraints → p3-monthly-tick → p4-battle-engine → p5-npc-ai → …`. PR도 스택(base = 부모).
- 프론트/시드/배포는 **F0–F5** 시리즈(아래) — 계획서 `docs/superpowers/plans/2026-06-02-frontend-parity-and-scenario-seed-plan.md`.
- **한 작업 = 한 논리 커밋. 모든 커밋 메시지 끝에:**
  ```
  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  ```

---

## 프론트엔드 / 배포 (F0–F5)

| 단계 | 핵심 | 상태 |
|------|------|------|
| **F0 게이트웨이 인증** | gateway-api 자체 JWT/BCrypt(Kakao 제거 divergence). `web/gateway` 로그인/회원가입/로비/어드민. 토큰은 Next route handler 프록시 + **httpOnly 쿠키**(`sam_access`/`sam_refresh`), 브라우저 JS 미노출, 동일출처(CORS 불필요). `AdminSeeder`가 `ADMIN_USERNAME`/`ADMIN_PASSWORD` env로 peppone(role=ADMIN) 멱등 시드. | ✅ |
| **F1 시나리오 시드** | `ScenarioImporter` + `ScenarioSeedRunner`가 외부 `SCENARIO_DIR`의 동일 파일명을 classpath보다 우선해 모든 시나리오를 시드. 정치·매력은 tuple 14/15 원수치, 생성물은 gitignored. env: `SCENARIO_SEED_ENABLED`/`SCENARIO_CODE`/`SCENARIO_DIR`. | ✅ |
| **F2 메인화면 + 메뉴 척추** | `web/game` 메인(`GameChrome` = GameInfo 헤더 + GlobalMenu + MainControlBar 20버튼 + 게이팅). | ✅ |
| **F3 read API + 랭킹/내정보** | game-api read 컨트롤러 + `web/game` 랭킹(`a_*`)·내정보(`b_*`) 페이지, **read-only 렌더**. | ✅ |
| **F4 액션 페이지 + mutation** | 예약·서신·베팅·경매·외교·게시판·투표·유산·NPC 정책·토너먼트·장수 선택 풀을 실제 intake/daemon 경로에 연결. 잔여 명령은 라이브 루프로 폐쇄 중. | 🔄 |
| **F5 turnkey + docs** | 정본 `docker-compose.yml`(로컬) + `docker-compose.production.yml`(EC2/GHCR) + `.env.example` + 한글 `README/AGENTS/CLAUDE`. `git pull && docker compose up`로 자동 설치·시드. | 🔄 |

> 프론트는 read-only 단계를 지났습니다. 버튼이 보이면 실제 API/daemon 결과까지 검증해야 하며, 하드 스텁·상수 빈 응답·disabled dead control은 완료로 세지 않습니다.

---

## 배포

```bash
# 로컬 전체 스택 (8서비스: postgres·redis·gateway-api·game-api·game-engine·web-gateway·web-game·nginx)
docker compose up -d --build

# 프로덕션 (EC2 t3.large, GHCR 이미지 풀, POSTGRES_PASSWORD 필수)
docker compose -f docker-compose.production.yml up -d
```

- 백엔드 이미지 멀티스테이지: `gradle:8.12-jdk21` 빌드 → `eclipse-temurin:21-jre` 런타임. 프론트: `node:22-alpine` 빌드(`next build`) → `node:22-alpine` standalone 런타임.
- nginx(`infra/nginx/nginx.conf`) 라우팅: `/api/gateway/`→gateway-api · `/api/game/`→web-gateway Next 프록시(httpOnly 쿠키→Bearer, 서버 선택) · `/api/game/realtime/`→game-api(SSE, 버퍼링 off) · `/game/`→web-game · `/`→web-gateway · `/health`.
- CI/CD: `.github/workflows/deploy.yml`(빌드 → GHCR push → SSH → 롤링 재시작), 수동 `scripts/deploy.sh`(헬스 체크 루프). 런타임 외부 API 의존 0, LLM-free.

---

## 보안

- 저장소 비공개 — 노출/자격증명 커밋 금지. `.env*`는 git-ignore, `.env.example`을 템플릿으로 사용.
- 관리자 비밀번호는 env(`ADMIN_PASSWORD`)로만 — 코드/리포 하드코딩 금지.
- 런타임 외부 API 의존 0, 완전 자체 호스팅(LLM-free). 배포 타깃 EC2 t3.large(클라우드 시크릿 매니저 비전제).

---

## 참고

- 정본 규율·load-bearing 규칙: [`CLAUDE.md`](CLAUDE.md)
- 사용자/빠른 시작/서비스 표/시드: [`README.md`](README.md)
- 마이그레이션 설계 + 로드맵: `docs/superpowers/specs/2026-05-29-devsam-opensamguk-kotlin-migration-design.md`
- 프론트 패러티 + 시드 계획(F0–F5): `docs/superpowers/plans/2026-06-02-frontend-parity-and-scenario-seed-plan.md`
- PHP 골든 캡처 하네스: `tools/php-golden/` · 스모크: `tools/smoke.sh` · 버전 카탈로그: `gradle/libs.versions.toml`
