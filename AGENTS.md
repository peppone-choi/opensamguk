# AGENTS.md — opensamguk

> **2026-08-24 — 에이전트 하네스는 이 저장소에서 제거됐다.**
> 이 문서에 남아 있는 `.claude/agents`·`.claude/commands`·`.claude/skills`(단 `historical-sources` 는 살아 있다)·`.claude/workflows`·
> `.codex/`·`.agents/skills`·`docs/agent/`·`scripts/agent/`(단 `protect-sensitive-files.sh` 는 살아 있다)·
> `tools/agent-system/check.py`·`skills-lock.json` 경로 언급은 **역사 기록이다. 지금 그 파일은 없다.**
> 살아 있는 층은 셋뿐이다 — 이 문서들의 제품·아키텍처 규칙, `.ai/decisions.md`(ADR-LITE),
> `.claudeignore` + `scripts/agent/protect-sensitive-files.sh`(시크릿·골든·legacy 차단 훅).
> 자세한 경위는 `.ai/decisions.md` ADR-LITE-047.

AI 코딩 에이전트 · 기여자용 온보딩 가이드. 코드를 건드리기 전에 이 문서를 읽으세요. **제품 기준과 아키텍처 규칙은 조용히 어기기 쉽습니다.** 상세·정본 규칙은 [`CLAUDE.md`](CLAUDE.md)에 있으며 이 문서는 그 요약 + 빠른 참조입니다.

---

## 프로젝트 한 줄 요약

PHP 게임 **devsam/core**(삼국지 모의전투 HiDCHe / 삼모) 이식에서 출발해 **메모리 중심 CQRS** 스택(**Kotlin/Spring Boot + Next.js + PostgreSQL + Redis + nginx**)으로 발전한 독립 제품.

- **제품 정본 = 최신 승인 ADR·spec·현재 구현.** ADR-LITE-042 이후 PHP 패러티는 신규 설계 제약이 아님.
- **`legacy/devsam-core` (PHP)**와 **`legacy/devsam-core2026` (TS)**는 역사·구조·회귀 분석용 참고 자료.
- 기존 `hwe/ts/` Vue는 흐름 참고 자료이며, 신규 프론트 기준은 현재 구현과 승인된 디자인 시스템.
- `legacy/`는 **git-ignore**, 커밋 금지. 저장소는 코에이 IP 검토 전까지 **비공개** — 자산/IP·비밀키·자격증명 커밋 금지.
- 사용자·관리자·기획 문서 진입점: [`docs/README.md`](docs/README.md).

---

## 모듈 구조

`settings.gradle.kts`에 선언:

| 모듈 | 종류 | 포트 | 책임 |
|------|------|------|------|
| `common` | 라이브러리 | — | RNG 커널(`rng/LiteHashDrbg`+`RandUtil`+`SeedSerializer`), `util/PhpRound`, `log/*`(조사·ConvertLog·토큰), `constants/GameConst` |
| `logic` | 라이브러리 | — | 순수 게임 로직(Spring/DB 없음): `stats/ActionPipeline`, `actions/*`+`CommandRegistry`, `war/*` 전투, `ai/*` GeneralAI, `event/*` DSL, `tick/*`, 베팅·경매·유산·메시지 |
| `infra` | 라이브러리 | — | `JdbcFlushExecutor`(JDBC 전용 flush + 델타/툼스톤 + row mapper), Flyway `db/migration/V*.sql`, Redis, JPA read repository, `seed/ScenarioImporter` |
| `app:gateway-api` | Boot 앱 | `:8080` | 인증(JWT/BCrypt) · 프로필 · 어드민(`AdminSeeder`) |
| `app:board-api` | Boot 앱 | `:8083` | 게시판 read/write · gateway 발급 access JWT 검증 · 공유 users DB 조회 |
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

# 동결 회귀 백엔드 표준 게이트(XML 검증 포함, 명령명은 역사적으로 parity 유지)
tools/parity/gate.sh backend

# 단일 모듈
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test

# 커밋 전 권장 풀 체크
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew \
  :common:test :logic:test :infra:test :app:game-engine:test :app:game-api:test :app:board-api:test

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

## 제품·회귀 규율 (NON-NEGOTIABLE)

상세는 [`CLAUDE.md`](CLAUDE.md)와 `.ai/decisions.md` ADR-LITE-042. 요약 6조:

1. **결정론적 재현** — 같은 seed·입력·순서는 같은 결과를 만든다. 신규 기능은 PHP draw-for-draw 일치를 요구하지 않는다.
2. **수치 변경 기록** — 반올림·절삭·clamp는 의도와 회귀 영향을 기록한다. 기존 `PhpRound` 사용처를 이유 없이 바꾸지 않는다.
3. **로그 품질과 순서** — 한글 로그는 UX 산출물이며 실행 순서를 안정적으로 보존한다. 신규 문구에 PHP byte 일치를 요구하지 않는다.
4. **델타 flush, 인라인 write 금지** — `ChangeRecorder` `created`/`dirty`/`deleted` → 일괄 flush. 리졸버는 델타만 쓴다.
5. **날조·테스트 약화 금지** — 기존 골든·테스트는 동결 회귀 기준선으로 보존한다. 기대값 변경에는 명시적 기획 근거가 필요하다.
6. **삽입 순서 보존** — 결과에 영향을 주는 map·이벤트의 삽입·실행 순서를 명시적으로 유지한다.

## 살아 있는 문서 규칙 (NON-NEGOTIABLE)

문서 갱신은 출시 직전 정리 단계가 아니라 구현 작업의 일부다. 코드·데이터·운영 방식이 바뀌면 같은 이슈와
PR에서 영향을 받는 정본을 함께 수정한다.

- `README.md`: 처음 방문한 사람을 위한 공개 소개, 현재 제공 범위, 실행 방법이 바뀔 때 수정한다. 내부
  대화, 임시 판단, 에이전트 전용 용어, 비공개 운영 정보를 넣지 않는다.
- `V1`·`V2`는 코드·마이그레이션·회귀 기준선에 남아 있는 내부 식별자일 뿐 사용자용 제품명이 아니다.
  공개 제목과 사용자 문서에서는 `기존 구현`, `현재 구현`, `신규 기능`처럼 실제 의미를 쓴다.
- `docs/user/**`: 플레이어가 보는 규칙, 화면, 튜토리얼, 도움말, 성공·실패 조건이 바뀔 때 수정한다.
- `docs/admin/**`: 서버 운영, 초기화, 백업, 복원, 권한, 장애 대응이 바뀔 때 수정한다.
- `docs/design/**` 및 승인 spec: 제품 방향, 도메인 의미, 출시 관문이 바뀔 때 수정한다.
- `CLAUDE.md`: 장기적인 제품·아키텍처 불변식과 검증 규칙이 바뀔 때만 수정한다.
- `AGENTS.md`: 저장소 구조, 작업 절차, 필수 검증, 문서 책임이 바뀔 때만 수정한다.
- 하위 `README.md`: 해당 모듈·도구·자산의 입력, 출력, 실행법, 소유권이 바뀔 때 수정한다.

파일을 매 작업마다 의미 없이 건드리지 않는다. 영향이 없으면 report에 `docs-impact: none`과 근거를 남긴다.
영향이 있는데 문서를 수정하지 않았거나, 문서가 아직 구현되지 않은 기능을 현재 기능처럼 설명하면 작업은
완료가 아니다. 구현 검증과 함께 링크·명령·화면 문구의 정확성도 확인한다.

---

## 코드 스타일

- Kotlin official(`kotlin.code.style=official`). 들여쓰기: `.kt`/`.kts` 4칸, `.ts`/`.tsx`/`.json`/`.yml` 2칸.
- UTF-8, LF, 끝 개행 필수(`.editorconfig`). 패키지 소문자 `opensamguk.<module>`. 클래스 PascalCase, 함수/변수 camelCase.
- 주석은 영어, 게임 콘텐츠 문자열은 한글. 테스트명은 backtick 서술형.

---

## 골든 픽스처

기존 골든 수치/로그/시드는 `tools/php-golden/` 실제 PHP 캡처에서 만들어졌고, 지금은 **동결 회귀 기준선**이다. 신규 기능의 선행 조건은 아니지만 삭제·위조·근거 없는 기대값 변경은 금지한다. 골든은 `logic/.../resources/golden/`·`common/.../resources/golden/`에 read-only 소비 대상으로 둔다.

---

## 작업 운영 체계 / skills.sh

- 정본 운영 문서: `docs/superpowers/WORKING_SYSTEM.md`.
- 루프 엔지니어링 정본: `docs/superpowers/LOOP_ENGINEERING.md`. Claude/Codex 모두 같은 문서를 기준으로 측정 → 가설 1개 → 재측정 → 채택/원복 루프를 돈다.
- 기존 회귀 갭은 필요할 때 `opensamguk-php-oracle`로 역사적 근거를 확인한다. 실서버/UI 버그는 `webapp-testing` → `systematic-debugging` → `loop-engineering`으로 재현·원인·재측정 증거를 남긴다.
- skills.sh 설치 목록은 `skills-lock.json`에 고정. 다운로드한 외부 `.agents/skills/*`는 로컬 실행 표면이며 git-ignore이고, 새 환경에서는 `scripts/agent/project-skills.sh restore`로 복원한다. Codex는 같은 복원을 `SessionStart`에서 자동 실행한다. 반면 `$os-*`, `$find-project-skill` 등 저장소 운영 스킬은 추적한다.
- 설치된 외부 스킬: `vercel-react-best-practices`, `webapp-testing`, `redesign-existing-projects`, `java-spring-boot`, `java-testing`, `kotlin-spring-boot`, `supabase-postgres-best-practices`.
- 필요한 전문 스킬이 없으면 Codex의 `$find-project-skill`로 skills.sh 후보를 검색·검토한 뒤 프로젝트에만 설치한다. 전역 설치는 기본값이 아니다.
- `java-testing`은 skills.sh Gen 감사상 High Risk로 표시됨. 참고로만 사용하고, 실제 합격 판정은 repo 테스트와 `tools/parity/gate.sh`가 담당.
- PHP 레거시 분석이 필요한 경우 `legacy/devsam-core` source path + line range와 비교 목적을 기록한다. 신규 기능에 PHP 캡처를 선행 조건으로 두지 않는다.
- 프론트 현대화는 승인된 디자인 방향과 현재 Next.js 구현을 기준으로 하고, `hwe/ts/` Vue는 흐름 참고에만 사용한다. 하드코딩 placeholder 대신 실제 API 상태를 렌더한다.
- provider/model 공통 개발도구는 `tools/agent-system/check.py`. 로컬은 `tools/agent-system/check.py`, CI/PR은 `tools/agent-system/check.py --strict --base origin/main`, 기계 판독은 `--format json`.
- 비자명 작업은 구현자와 별개 agent/provider의 비판적 검증을 거친다. 병렬 agent는 구현 증거·테스트·문서·운영 불변식을 공격적으로 검토하고, `fix-required`가 남아 있으면 ship/merge 금지.

---

## 페이즈 / 브랜치 / 커밋

- 흐름: `P0 → … → P8`, 각 페이즈 = **spec → plan → adversarial review → execute → gate**. 플랜 `docs/superpowers/plans/`, 리서치 `docs/superpowers/research/`.
- **Foundation-first**: 공유 확장점은 Tier-0 wave에서 먼저, 이후 family는 소비만. 병렬 worktree family는 **disjoint** — 같은 파일 co-widen 금지.
- 브랜치 스택(페이즈당 1, 부모 분기): `p0a-foundation-scaffold → p0b-parity-kernel → p1-vertical-slice → p2-commands-constraints → p3-monthly-tick → p4-battle-engine → p5-npc-ai → …`. PR도 스택(base = 부모).
- 프론트/시드/배포는 **F0–F5** 시리즈(아래) — 계획서 `docs/superpowers/plans/2026-06-02-frontend-parity-and-scenario-seed-plan.md`.
- **CQRS 정합성 하드닝(ARCH-S1–S6, OPENSAM-127~139)** — 전부 build-only(라이브 동작·골든 불변). ✅ 월드 스코프(127~129) · flush 무결성(130~132) · S4 durable 명령 경로(133~136, PR #312) · S5 읽기·부팅 경계(hot/cold 카탈로그·bounded boot·minVersion read barrier, 137~139, PR #314/#315) main 머지. ⬜ S6 롤아웃(#268) 잔여, 프로덕션 cutover 미수행. 트리아지: `docs/superpowers/research/2026-07-23-ticket-triage-next.md`.
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
| **F5 turnkey + docs** | 정본 `docker-compose.yml`(로컬) + 호환용 `docker-compose.production.yml`(GCP Compute Engine e2-standard-2/GHCR) + `.env.example` + 한글 `README/AGENTS/CLAUDE`. `git pull && docker compose up`로 자동 설치·시드. | 🔄 |

> 프론트는 read-only 단계를 지났습니다. 버튼이 보이면 실제 API/daemon 결과까지 검증해야 하며, 하드 스텁·상수 빈 응답·disabled dead control은 완료로 세지 않습니다.

---

## 배포

```bash
# 로컬 전체 스택 (9서비스: postgres·redis·gateway-api·board-api·game-api·game-engine·web-gateway·web-game·nginx)
docker compose up -d --build

# 프로덕션 호환 표면 (GCP Compute Engine e2-standard-2, GHCR 이미지 풀, POSTGRES_PASSWORD 필수)
docker compose -f docker-compose.production.yml up -d
```

- 백엔드 이미지 멀티스테이지: `gradle:8.12-jdk21` 빌드 → `eclipse-temurin:21-jre` 런타임. 프론트: `node:22-alpine` 빌드(`next build`) → `node:22-alpine` standalone 런타임.
- nginx(`infra/nginx/nginx.conf`) 라우팅: `/api/gateway/`→gateway-api · `/api/board/`→web-gateway Next 프록시→board-api · `/api/game/`→web-gateway Next 프록시(httpOnly 쿠키→Bearer, 서버 선택) · `/api/game/realtime/`→game-api(SSE, 버퍼링 off) · `/game/`→web-game · `/`→web-gateway · `/health`.
- CI/CD: `.github/workflows/deploy.yml`(빌드 → GHCR push → GCP VM의 `gcp-prod` self-hosted runner에서 공유 스택 동기화), 수동 `scripts/deploy.sh`(헬스 체크 루프). 런타임 외부 API 의존 0, LLM-free.

---

## 보안

- 저장소 비공개 — 노출/자격증명 커밋 금지. `.env*`는 git-ignore, `.env.example`을 템플릿으로 사용.
- 관리자 비밀번호는 env(`ADMIN_PASSWORD`)로만 — 코드/리포 하드코딩 금지.
- 런타임 외부 API 의존 0, 완전 자체 호스팅(LLM-free). 배포 타깃 GCP Compute Engine e2-standard-2(클라우드 시크릿 매니저 비전제).

---

## 참고

- 정본 규율·load-bearing 규칙: [`CLAUDE.md`](CLAUDE.md)
- 사용자/빠른 시작/서비스 표/시드: [`README.md`](README.md)
- 마이그레이션 설계 + 로드맵: `docs/superpowers/specs/2026-05-29-devsam-opensamguk-kotlin-migration-design.md`
- 프론트 패러티 + 시드 계획(F0–F5): `docs/superpowers/plans/2026-06-02-frontend-parity-and-scenario-seed-plan.md`
- PHP 골든 캡처 하네스: `tools/php-golden/` · 스모크: `tools/smoke.sh` · 버전 카탈로그: `gradle/libs.versions.toml`

---

## Agent Operating System (에이전트 공통 운영 계층)

Claude Code·Codex 등 모든 에이전트가 공유하는 운영 계층. **정본은 [`CLAUDE.md`](CLAUDE.md)와 `docs/superpowers/WORKING_SYSTEM.md`** — 충돌 시 정본이 이기고, 충돌 사실은 `.ai/decisions.md`에 기록한다.

- **작업 라우터**: `docs/agent/README.md` — 필수 3개(`.ai/task.md` → `.ai/decisions.md` → `docs/agent/project-overview.md`)를 먼저 읽고, 작업 유형별 문서만 추가 로드 (전부 읽지 말 것).
- **세션 상태**: `.ai/` — 작업 계약(task) · 현재 상태(current-state) · 결정 기록(decisions, ADR-LITE) · 알려진 이슈(known-issues) · 파일 소유권(ownership, single-writer) · 인수인계(handoff). 리셋/에이전트 전환 전 갱신 필수.
- **런북**: `.claude/commands/`의 `/os-start-task` `/os-analyze` `/os-implement` `/os-debug` `/os-verify` `/os-review` `/os-checkpoint` `/os-plan-tickets` `/os-e2e` — `docs/agent/` 절차의 얇은 진입점. `os-` 접두사는 전역 OMC 스킬(`/verify` `/review` `/analyze`)과의 충돌 회피용. Codex는 추적된 프로젝트 스킬 `$os-start-task` … `$os-e2e`로 동일한 문서와 게이트를 따른다.
- **프롬프트 팩**: `docs/agent/prompt-pack.md` — 공통 5종 + 작업군 5종(파리티포팅·PHP오라클·프론트배선·인프라배포·기획티켓분해). 각 팩 = 페르소나/목표/형식/제약 + 발동조건/중단 조건. `/os-*` 커맨드와 레포 에이전트가 정본으로 참조.
- **Codex 프로젝트 표면**: `.codex/config.toml` · `.codex/hooks.json` · `.codex/agents/*.toml`과 선택된 `.agents/skills/` 운영 스킬은 추적한다. 다운로드한 외부 스킬만 로컬에 둔다.
- **가드 스크립트**: `scripts/agent/protect-sensitive-files.sh`(시크릿 읽기/쓰기·골든/legacy 쓰기 차단, 훅+수동 겸용) · `scripts/agent/codex-bash-guard.sh`(Codex가 지원하는 단순 Bash 호출의 best-effort 차단) · `scripts/agent/verify-changes.sh`(base diff → 최소 검증 명령). Claude는 `.claude/settings.json`, Codex는 `.codex/hooks.json`으로 실활성이다(ADR-LITE-005/009). Codex에서 처음 열 때 저장소 훅을 신뢰하고 설정 변경 뒤 reload/restart한다. Codex 공식 훅은 모든 shell 경로를 가로채지 못하므로 완전한 보안 경계로 간주하지 않는다. `@`멘션 첨부는 `.claudeignore`가 담당.
- **불변 규칙(에이전트가 스스로 완화 금지)**: 승인 없는 commit/push/merge/deploy/데이터 삭제 금지 · `.env*`/키/토큰 읽기·출력 금지 · 골든/테스트/명령 위조 금지 · 미확인 사항은 UNKNOWN으로 보고.
