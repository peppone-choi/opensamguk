# CLAUDE.md — opensamguk

> **2026-08-24 — 에이전트 하네스는 이 저장소에서 제거됐다.**
> 이 문서에 남아 있는 `.claude/agents`·`.claude/commands`·`.claude/skills`(단 `historical-sources` 는 살아 있다)·`.claude/workflows`·
> `.codex/`·`.agents/skills`·`docs/agent/`·`scripts/agent/`(단 `protect-sensitive-files.sh` 는 살아 있다)·
> `tools/agent-system/check.py`·`skills-lock.json` 경로 언급은 **역사 기록이다. 지금 그 파일은 없다.**
> 살아 있는 층은 셋뿐이다 — 이 문서들의 제품·아키텍처 규칙, `.ai/decisions.md`(ADR-LITE),
> `.claudeignore` + `scripts/agent/protect-sensitive-files.sh`(시크릿·골든·legacy 차단 훅).
> 자세한 경위는 `.ai/decisions.md` ADR-LITE-047.

Load-bearing product and architecture rules. Violating them silently breaks deterministic regression and operational integrity.

## What this repo is

**opensamguk** — PHP 게임 **devsam/core**(삼국지 모의전투 HiDCHe / 삼모)에서 출발한 **Kotlin/Spring + Next.js + PostgreSQL + Redis + nginx** 턴제 시뮬레이션. **memory-centric CQRS** 아키텍처.

v1 코어는 devsam/core 를 바이트 단위로 이식해 세웠지만, **2026-08-20(ADR-LITE-042) 부로 패러티 이식을 종료했다.** 이제 오픈삼국은 자기 설계를 따른다.

- **`legacy/devsam-core` (PHP) = 참고 자료.** ~~GRAND TRUTH~~ — **2026-08-20 (ADR-LITE-042) 부로 오라클 지위가 해제됐다.** 체섭은 체섭이고 오픈삼국은 오픈삼국이다. PHP 동작은 설계를 참고할 때 읽는 자료이지, 맞춰야 할 정답이 아니다.
- **`legacy/devsam-core2026` (TypeScript)** = 또 하나의 참고 자료. 예전 PHP 우선 규칙도 ADR-LITE-042 로 해제됐다 — 두 레거시 모두 이제 우리 설계를 정할 때 읽는 자료다.
- `legacy/` is **git-ignored**, never committed. Design + roadmap: `docs/superpowers/specs/2026-05-29-devsam-opensamguk-kotlin-migration-design.md`.
- Repo stays **PRIVATE** until a Koei-IP review clears it. No Koei-owned assets/IP, secrets, or credentials in commits.

## Architecture (memory-centric CQRS)

```
api ──Redis(XADD)──▶ game-engine daemon ──JDBC batch flush──▶ PostgreSQL
 ▲                   (InMemoryTurnWorld = source of truth)         │
 └──────────── turnCompleted SSE ◀── ChangeRecorder dirty/created/deleted
```

Modules (`settings.gradle.kts`):
- **`common`** — RNG/log kernel: `rng/LiteHashDrbg` (byte-exact SHA-512 DRBG) + `rng/RandUtil` + `rng/SeedSerializer`, `util/PhpRound`, `log/*` (Josa/ConvertLog/tokens), `constants/GameConst`.
- **`logic`** — pure game logic (no Spring/DB): `stats/ActionPipeline` (multi-source stat fold + `getStatValue` + calc-cache), `actions/*` + `CommandRegistry`, `war/*` battle engine, `ai/*` GeneralAI, `event/*` DSL, `tick/*`, domain.
- **`infra`** — `JdbcFlushExecutor` (**JDBC-only** flush + delete/tombstone delta + row mappers), Flyway `db/migration/V*.sql`, Redis.
- **`app/gateway-api`** (:8080) auth/profile · **`app/game-api`** (:8081) read+precheck+intake+SSE · **`app/game-engine`** (:8082) turn daemon (`InMemoryTurnWorld`+`ChangeRecorder`+`MonthlyPipeline`+`TurnRunService`) · **`app/board-api`** (:8083) board read/write + verify-only access JWT.
- **`web/gateway`** (:3000) · **`web/game`** (:3001) — Next.js.

**The ONE daemon-write rule (architecture-test-enforced):** the game-engine daemon NEVER uses a JPA `EntityManager` for writes. JPA = read/precheck only (game-api). Daemon writes go **only** through `ChangeRecorder` → `JdbcFlushExecutor` JDBC batch. (Two competing dirty-truths — JPA dirty-checking + change-recorder — would silently diverge.)

## Product and regression discipline (ADR-LITE-042, 2026-08-20)

<!-- ADR-LITE-042-RULES replay_determinism,numerical_change_record,stable_logs_and_order,flush_delta,no_fabrication_or_weakening,insertion_order -->

**Status:** PHP parity porting and its `PHP wins`/golden-first gates are retired. The six numbered rules below remain active as product regression and architecture rules, not legacy-equality rules.

The product authority is the latest approved ADR/spec plus the current implementation. PHP and hwe are optional historical/reference inputs. New work does not require PHP draw-for-draw, byte-log parity, or an oracle capture.

1. **Deterministic replay.** The same seed, input, and ordering must reproduce the same result. This is a product debugging and dispute-resolution property, not PHP parity.
2. **Intentional numerical changes.** Existing `PhpRound` and truncation/clamp behavior remains protected by frozen regressions. Any change must state the intended numerical rule and regression impact; PHP behavior alone is not a reason to keep or change it.
3. **Stable logs and ordering.** Korean logs are UX output. Preserve execution order and record intentional copy/order changes; new copy need not byte-match PHP.
4. **Flush delta, not inline writes.** Mutations are recorded as `created`/`dirty`/`deleted` on `ChangeRecorder` and flushed in bulk. Resolvers write only delta.
5. **Never fabricate or weaken evidence.** Existing goldens and tests are frozen baselines. Do not delete or edit them merely to make a change pass. A justified product change may update an affected expectation only with explicit intent and regression evidence.
6. **Insertion order matters.** Preserve result-affecting map/event insertion and execution order explicitly.

Historical parity workflows (`tools/php-golden/`, `parity-close`, `parity-ship`, and `tools/parity/gate.sh`) remain available only for explicitly requested frozen-regression maintenance. Their historical names do not restore PHP as product authority. Full decision and reversal procedure: `.ai/decisions.md` ADR-LITE-042.

**Five-stat product extension — politics/charm.** `politics`(정치)/`charm`(매력)은 오픈삼국 독자 스탯이다. RTK14 원본과 생성 시나리오는 **git-ignore, 미커밋**하고 `tools/rtk14/build_rtk14_stats.py`만 버전 관리한다. 빌더는 모든 `scenario_*.json` 장수 tuple의 인덱스 14/15를 원수치로 덮어쓰며, 통무지·생몰년·별칭으로 동명이인을 1:1 배정하고 미매칭만 50/50으로 둔다. 유저 생성은 통무지정매 5개 입력과 총합 275 상한을 사용한다. 스펙: `docs/superpowers/specs/2026-06-13-five-stat-rtk14-divergence.md`.

**Sanctioned divergence — han 건국·3축 등급 밸런스 (ADR-LITE-043).** han 맵에서만 공백지 수비병 돌파비율을 `FOUND_ASSAULT_RATIO=2.0`(`ceil(defence * 2.0)`)으로 두고, 건국 가능 등급을 중/소(5/6) + 영현/장현(`level >= 10`)으로 둔다. 군치 수에 따른 도적·황건 spine 문턱은 `1/13/28郡治 -> nation.level 2/3/4`다. CHE/miniche 건국 돌파비용은 0이고 기존 회귀 픽스처는 frozen-baseline으로 보존한다. 정본·뒤집기 경로는 `.ai/decisions.md` ADR-LITE-043이다.

## Build & test

- **Java 21 LTS required** (Gradle 8.12 fails to parse Java 25). Always run from the **repo root**:
  ```bash
  JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test
  ```
- **Verify by OUTPUT TAIL + test XML, not exit code** (the host routes gradle through a context-mode wrapper; `task-notification` exit 0 is unreliable). Pipe `... 2>&1 | tail -40`, grep `BUILD SUCCESSFUL` + counts, or read `**/build/test-results/test/*.xml`. Use `--rerun-tasks` (UP-TO-DATE false-greens).
- **Testcontainers on macOS** needs `api.version=1.44` + `DOCKER_CONTEXT=default` + Ryuk disabled (wired in `tasks.test`). Docker-unavailable ⇒ IT **skipped**, not failed.
- Full check: `./gradlew :common:test :logic:test :infra:test :app:game-engine:test :app:game-api:test :app:board-api:test`. Docker smoke: `./tools/smoke.sh`. Frontend: `cd web/gateway && corepack pnpm dev`.

## How phases are built

Each phase = one cycle **spec → plan → adversarial review → execute → gate**. Plans in `docs/superpowers/plans/`, research in `docs/superpowers/research/`.
- **Foundation-first.** Every *shared extension point* (registry, base class, stat-key enum, pipeline hook) is built in a **Tier-0 foundation wave** that later families only **consume**. Parallel worktree families must be **disjoint** — never co-widen the same file (⇒ merge conflict; cross-area shared artifacts build sequentially, creator-then-consumer).
- A phase **gate** must match its current spec and risk: preserve affected frozen-baseline tests, add reproducible evidence for new rules, and log any unverified gap as `UNKNOWN` or blocked.
- One logical commit per task. **Every commit message ends with:**
  ```
  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  ```

### Living documentation is part of implementation

Documentation is not a tail phase. Every task must assess documentation impact before completion
and update the affected source of truth in the same issue and PR.

- `README.md` is the public front door. Keep it understandable without private conversation or
  internal planning context; describe only verified current behavior and clearly labeled direction.
- `V1` and `V2` are internal code, migration, and regression identifiers, not player-facing product
  editions. Public titles and user documentation must say existing, current, or new behavior instead.
- `docs/user/**` owns player rules, tutorials, help, and recovery guidance.
- `docs/admin/**` owns operations, access controls, reset, backup, restore, and incident procedures.
- `docs/design/**` and approved specs own product direction, domain language, and release gates.
- `CLAUDE.md` changes only when a durable product, architecture, or verification invariant changes.
- `AGENTS.md` changes only when repository structure, workflow, required verification, or document
  ownership changes.
- Module and tool `README.md` files change with their inputs, outputs, commands, or ownership.

Do not churn unrelated documents. If no documentation is affected, record `docs-impact: none` with
the reason in the task report. A task is incomplete when its implementation and documentation
disagree, or when planned behavior is presented as already shipped.

**Golden capture harness — `tools/php-golden/`:** the PROVEN Docker capture (MariaDB 11.4 + `php:8.3-cli`, scenario `1010` = 174 generals, NOT empty scenario_0). Quirks: `_boot.php` binds via `DB::db()`; `j_install.php` is called twice; install is **not** idempotent (fresh DB per run); dumps must be byte-identical across two runs.

## Repo conventions

- **Branch stack**, one per phase: `p0a-foundation-scaffold → p0b-parity-kernel → p1-vertical-slice → p2-commands-constraints → p3-monthly-tick → p4-battle-engine → p5 (NPC AI) → …`. Each branches off the previous; PRs stacked (base = parent) for clean diffs.
- Git-ignored: `legacy/`, `build/`, `node_modules/`, `.env*`, `.workflow-*.mjs`, `.claude-tmp/`, downloaded external `.agents/skills/*`, `.bkit/`, and unselected local `.codex/*`. The Codex project surface (`.codex/config.toml`, `.codex/hooks.json`, `.codex/agents/*.toml`) and selected repository skills under `.agents/skills/` are tracked. `tools/php-golden/probe_*.php` = throwaway, never committed.

## Skills (`Skill` tool or `/<name>`; use the **process** skill first, then implementation)

- **working system** — start non-trivial work from `docs/superpowers/WORKING_SYSTEM.md`. It fixes skill routing, historical PHP-oracle analysis when a frozen-baseline regression needs it, hardcoding policy, and the standard gate command. `skills-lock.json` records downloaded skills.sh project skills; those external skill directories remain local, while repository-owned operating skills are tracked under `.agents/skills/`.
- **skills.sh project skills** — restore the locked set with `scripts/agent/project-skills.sh restore`; Codex also runs this from `SessionStart`. Use `vercel-react-best-practices` for Next.js/React, `webapp-testing` for browser flows, `redesign-existing-projects` for legacy UI modernization, `kotlin-spring-boot`/`java-spring-boot` for backend work, and `supabase-postgres-best-practices` for DB work. `java-testing` is reference-only because its skills.sh Gen audit was High Risk. If a task needs missing expertise, use `$find-project-skill` to search and review candidates before a project-only install; never install it globally by default.
- **provider-agnostic guard** — `tools/agent-system/check.py` is the shared local/CI check for every model/provider. Use `--format json` for machine-readable agent integration and `--strict --base origin/main` in PR/CI.
- **cross-agent critique** — non-trivial work must be attacked by an independent agent/provider before ship. A peer checks current spec/ADR evidence, tests, docs, hardcoding, and production invariants. PHP evidence is required only for a historical frozen-baseline claim. Unresolved `fix-required` blocks merge/deploy.
- **backend gate** — use `tools/parity/gate.sh backend` for the standard backend proof. It runs Java 21 Gradle tasks and verifies XML failures/errors, not exit code alone.
- **gstack** — all web browsing via **`/browse`** (never `mcp__claude-in-chrome__*`). Plan/review/ship/QA/deploy commands — full list in `~/.claude/CLAUDE.md`.
- **loop engineering / compound engineering** — shared source of truth: `docs/superpowers/LOOP_ENGINEERING.md`. Claude/Codex adapters must stay thin and follow the same measure → one hypothesis → remeasure → adopt/revert loop. Claude `/ce-*` commands and review agents are provider surfaces, not separate rules.
- **defect chain** — for a historical frozen-baseline gap, use `opensamguk-php-oracle` to record PHP/hwe path+line evidence before comparison. For current UI or production bugs, start from `webapp-testing`, converge the root cause with `systematic-debugging`, and wrap the pass with `loop-engineering` baseline/hypothesis/grader/adopt-or-revert evidence. New product defects do not require PHP evidence. If a required current-scope link is unavailable, record `채점대기`/`blocked`; do not silently ship/merge.
- **superpowers** — `superpowers:subagent-driven-development` (TDD red→green, one commit/task) is the rigid execution skill; follow exactly.
- **code-review-graph (MCP)** — live structural graph of this repo (565 files / 6936 nodes / 67k edges). Use `detect_changes` / `query_graph` (callers/callees/imports/tests) / `get_impact_radius` / `get_affected_flows` / `semantic_search_nodes` / `get_review_context` **BEFORE** Grep/Glob; rebuild via `build_or_update_graph_tool`.
- **harness** ("하네스 구성해줘") · **graphify** (`/graphify`).

## Roadmap status

`P0→P1→P2→P3→P4→P5→P6→P7→P8` · `P7 ← {P2,P6}` (parallel w/ P3/P4/P5) · `P6 ← {P4,P5}`.

- ✅ **P0–P4** gate-closed: scaffold · parity kernel · vertical slice · ~35 commands+constraints · monthly tick · battle engine (`processWar_NG` + triggers + WarUnit + city-conflict + ConquerCity + battle items/specialties; G1 battle/conquest draw-for-draw).
- ✅ **P5** NPC AI — `ai/*` GeneralAI port + 4-layer autorun policy + F-BRIDGE candidate gate (`candidateAllowed` = the exact execution gate) + per-general module stat stack + engine seam (`AiTurnAdapter`, nation-pass-before-general). Gate-closed: live-selection **174/174** turns (667/667 draws) + **8 crafted families 11/11**. ~1968 tests (common 169 / logic 1661 / engine 138). **Backlog (documented, not fabricated):** long-sim multi-turn (gate dim c), G12 nation reserved-fail deny-log. **Quarantines (proven):** genfound-방랑군 (needs 거병→건국 mini-sim), `chooseInstantNationTurn` (zero PHP callers), Q1 ORDER BY RAND (do선양/오랑캐임관 — unreachable in 1010, deterministic substitute).
- ✅ **P3** monthly pipeline — `MonthlyPipeline.runMonth()` + `PostUpdateMonthly` Q1-Q17 settlement + `TurnDaemonLifecycle` + `EventActionFactory` + `EventDispatcher` + all 9 world event leaves (`UpdateNationLevel`, `AssignGeneralSpeciality`, `ProcessIncome`, `ProcessWarIncome`, `RaiseDisaster`, `RandomizeCityTradeRate`, `UpdateCitySupply`, `ProcessSemiAnnual`, `MergeInheritPointRank`). Zero stubs.
- ✅ **P6 pure-logic** gate-closed (~2195 tests): inheritance enum keyName parity + buff fold slot #7 + `TurnDaemonCommandDispatcher` + `BettingActions` registrar + missing diplomacy proposals (`종전제의`, `불가침파기제의`) + `BuyHiddenBuff` cumulative-diff cost + `AuctionBidHandler`/`AuctionFinalizeHandler` + messaging sink unified (`GeneralActionResolveContext` + `NationActionResolveContext`) + `BettingEngine` calcReward/giveReward + `BettingInfo` structural realignment.
- ✅ **P6 P7-coupled** 완료 — `PlaceBetHandler` (gold deduction + ng_betting INSERT), `AuctionExpiryDaemon` (turn lifecycle wired), `DiplomaticMessageController` (accept/decline API), `ChangeRecorder` betting channel + `JdbcFlushExecutor` flush step.
- ✅ **P6 restart-rehydrate bounded gate** — `OPENSAM-149`/`#324`의 bounded restart gate는 머지·클리어됐다(`docs/superpowers/reviews/2026-08-14-opensam-149-closeout-review.md` = cleared, `RehydrateLosslessGateIT`·`FullRehydrateTurnGateIT`·`RehydrateRoundTripIT`). closure matrix의 Q 셀과 all-channel lossless는 여전히 격리된 운영 범위다. P6별 PHP 캡처·93-command 비교는 신규 기능 선행 조건이 아니며 기존 frozen-baseline을 조사할 때만 사용한다.
- ✅ **P7 read API + frontend 기본 표면** — auction/betting/message/mailbox/diplomacy를 포함한 전용 REST controller와 `web/game/app/game/` 페이지가 존재하고 F4 live intake/daemon 경로에 연결돼 있다. 개별 기능의 완료 범위는 아래 F4와 `docs/design/roadmap.md`의 현재/진행 표기를 따른다.
- 🔄 **P8 운영 전환** — 로컬/호환 표면은 `.github/workflows/deploy.yml`, `docker-compose.production.yml`, `infra/nginx/`, `scripts/deploy.sh`, `HealthCheckController`에 있다. 프로덕션 제어면은 별도 `opensamguk-docker` shared/server/deployer 모델이며, 서버별 승격·S6 cutover는 명시적 운영 승인과 검증 전까지 미완료다. 런타임은 LLM-free이고 외부 API 의존이 없다.

**CQRS 정합성 하드닝 트랙 (ARCH-S1–S6, OPENSAM-127~139) — 전부 build-only, 라이브 동작·골든 불변.** ✅ 월드 스코프(127~129) · flush 무결성 `DeltaGenerationSession`/`world_version` CAS+`writer_epoch`/`FlushRecoveryGate`(130~132) · S4 durable 명령 경로(command_inbox 선기록·durable result/outbox·consumer-group wake+post-commit ACK·크래시/리플레이, 133~136, PR #312, 리뷰 cleared) · S5 읽기·부팅 경계(hot/cold 카탈로그·bounded boot reads·minVersion read barrier→409 `VERSION_NOT_VISIBLE`, 137~139, PR #314/#315) 모두 main 머지. ⬜ **S6 롤아웃**(canary/expand-backfill/replica ADR, #268) 잔여 — 프로덕션 cutover/activation 미수행. 트리아지: `docs/superpowers/research/2026-07-23-ticket-triage-next.md`.

**v2 출처·확실성 계약 (OPENSAM-37, G0-A②).** `logic/src/main/kotlin/opensamguk/logic/v2/evidence/`가
v2 역사 데이터의 출처·확실성 계약을 소유한다 — `EvidenceContracts.kt`(SourceProximity 7값 / EvidenceClass 5값 /
WorldContentProfile 3값 / SourceLicense·LicenseBundling / EvidenceRef / HistoricalClaim / WorldContentOverlay·Snapshot)와
`EvidenceContractValidator.kt`(시기 역투영 차단 · 등급 혼합 차단 · overlay 격리 · 엄격 고증 · 번들 게이트).
in-memory 순수 계약이며 v1 역사 동결 회귀 코어(RNG·로그·골든·DB)를 전혀 참조하지 않는다. 등급 값 추가·혼합 등급 신설 금지.
**CHGIS = 사용 허용, 게임 타일맵은 서빙 허용 (ADR-LITE-039 + 040, 2026-08-18 사용자 지시).** 역사 지도 트랙에서 CHGIS V6 /
TGAZ 를 **사용한다**. 조건은 RTK14 와 동일한 격리다: 원본 shapefile·다운로드물과 그로부터 만든 좌표 데이터는
**git-ignore·미커밋**이고, 커밋 대상은 추출 스크립트뿐이다(`tools/map/*.py`). 저장소 번들·CDN·배포 이미지·
런타임 allowlist 로 올리지 않는다. **잔여 위험은 소멸하지 않았다** — V6 README 원문은 `License: free for
academic research, no commercial use, resale, or redistribution permitted.` 인데 같은 Dataverse 데이터셋
메타데이터는 `CC0 1.0`(`termsOfUse: None`)이라 두 표기가 충돌하고 CC0 표기의 출처는 여전히 **UNKNOWN**이다.
**ADR-LITE-040(2026-08-18)에서 사용자가 위험을 인수하고 공개 서버 서빙을 승인했다** —
게임이 먹는 `data/map/han-tiles.json` 만 커밋·이미지 동봉하고, 원본 shapefile·`han-places.json`·
`terrain-grid.json` 은 계속 미커밋이다. 서면 계약은 여전히 **미이행**이며 상업화는 승인 밖이다.
철거 경로는 파일 한 개 삭제(→ `/api/map/terrain` 404 → 기존 맵 폴백), 복구 경로는 續漢書 郡國志 +
Wikidata(CC0) 로 좌표를 다시 세우는 것이다. 판정 근거: `docs/loops/opensam-37-evidence-contracts-2026-08-16/
chgis-license-review.md`, 비평: `docs/superpowers/reviews/2026-08-16-opensam-37-evidence-contracts-review.md`,
결정: `.ai/decisions.md` ADR-LITE-039.

**미래 마일스톤(로드맵 외, 조건 충족 시):** `docs/superpowers/MILESTONES.md` — **M-config**(운영 안정과 현재 제품 spec 승인 뒤 `GameConst` 등 설계 상수를 외부화하고, 기존 골든은 frozen-baseline 회귀 게이트로 유지).

## 프론트엔드/배포 (F0–F5)

P7 프론트 + P8 시드/배포를 점진적으로 닫는 F-시리즈. 계획: `docs/superpowers/plans/2026-06-02-frontend-parity-and-scenario-seed-plan.md`. `hwe/ts/` Vue는 기존 흐름 참고 자료이고, 신규 UI는 현재 구현과 승인된 디자인 방향을 따른다. 사용법·서비스 표·빠른 시작은 `README.md`와 `docs/README.md`, 모듈/명령은 `AGENTS.md` 참조.

- ✅ **F0 게이트웨이 인증** — gateway-api 자체 JWT/BCrypt 로컬 인증(Kakao OAuth에서 의도적 divergence). `web/gateway` 엔트런스/로그인/회원가입/로비/어드민. 토큰은 Next route handler가 gateway-api로 프록시(동일출처 → CORS 불필요)하며 **httpOnly 쿠키**(`sam_access`/`sam_refresh`)에만 보관 — 브라우저 JS에 토큰 미노출. `AdminSeeder`가 `ADMIN_USERNAME`/`ADMIN_PASSWORD` env로 관리자(peppone, role=ADMIN) 멱등 생성(둘 다 설정돼야 시드). 액세스 토큰은 신원(`sub`=userId)과 인가(`role`)만 담으며 표시 클레임(username/nickname/grade/picture/imgsvr)은 발급하지 않는다(OPENSAM-220/#483, 코드 반영 완료 — 배포 실측 미확인). 게임 서버·게시판은 표시 정보를 `users` 행에서 읽는다. 절차는 `docs/operations/jwt-key-rollout.md`.
- ✅ **F1 시나리오 시드** — `ScenarioSeedRunner`가 `SCENARIO_DIR`의 동일 파일명을 classpath보다 우선하고 `ScenarioImporter`가 선택된 모든 시나리오를 JDBC INSERT한다. fresh DB에서만 멱등 시드하며, RTK14 생성본은 tuple 14/15 원수치를 포함한 gitignored JSON이다. env fence: `SCENARIO_SEED_ENABLED`, `SCENARIO_CODE`, `SCENARIO_DIR`. **JDBC-only — one-daemon-write-rule 비위반**.
- ✅ **F2 메인화면 + 메뉴 척추** — `web/game` 메인(`GameChrome` = GameInfo 헤더 + GlobalMenu + MainControlBar 20버튼+게이팅).
- ✅ **F3 read API + 랭킹/내정보** — game-api read 컨트롤러 + `web/game` 랭킹(`a_*`)·내정보(`b_*`) 페이지. 모두 game-api로 **read-only 렌더**.
- 🔄 **F4 액션 페이지 + mutation** — 예약·서신·베팅·경매·외교·게시판·투표·유산·NPC 정책·토너먼트·장수 선택 풀을 실제 intake/daemon 경로에 연결했다. 남은 하드 스텁·상수 빈 응답·현재 spec/API 불일치는 라이브 루프에서 계속 폐쇄한다. 역사적 회귀 결함만 frozen baseline과 PHP 참고 자료로 비교한다. **result-poll 규약(OPENSAM-13/135):** 인테이크 202는 성공이 아니다 — FE는 `pollCommandResult(requestId)`로 `RESOLVED`까지 폴링해 `ok`/`reason`을 분기하고, 엔진 핸들러는 성공·deny 모두 `TurnDaemonCommandResult`(`ok`/`reason`)를 반환한다(202만 보고 성공 토스트 = 성공 위조 금지).
- 🔄 **F5 turnkey + docs** — 정본 `docker-compose.yml`(로컬 9서비스) + 호환용 `docker-compose.production.yml`(GCP Compute Engine e2-standard-2, GHCR 이미지) + `.env.example` + 한글 `README/AGENTS/CLAUDE`. `git pull && docker compose up`로 자동 설치·시드.

**브랜드 에셋.** 마스터 `assets/brand/logo-master.png`(AI 자체제작, 제3자 파생 아님) 하나에서
`python3 tools/assets/build_brand_assets.py`가 두 프런트엔드의 파비콘·앱아이콘·워드마크를 전량
재생성한다. 산출물(`web/*/app/{icon,apple-icon}.png`, `favicon.ico`, `web/*/public/logo-wordmark*.png`)을
손으로 고치지 말고 빌더를 다시 돌려라. Next App Router가 `app/` 아래 파일명만 보고 자동 배선하므로
`layout.tsx`의 `metadata.icons`는 쓰지 않는다. 출처·파생 규약은 `assets/brand/README.md`.
`opensamguk-images`(제3자 파생 에셋)와는 무관한 별도 계보다.

## Skill routing

When the user's request matches an available skill, invoke it via the Skill tool. When in doubt, invoke the skill.

Key routing rules:
- Product ideas/brainstorming → invoke /office-hours
- Strategy/scope → invoke /plan-ceo-review
- Architecture → invoke /plan-eng-review
- Design system/plan review → invoke /design-consultation or /plan-design-review
- Full review pipeline → invoke /autoplan
- Bugs/errors → invoke /investigate
- QA/testing site behavior → invoke /qa or /qa-only
- Code review/diff check → invoke /review
- Visual polish → invoke /design-review
- Ship/deploy/PR → invoke /ship or /land-and-deploy
- Save progress → invoke /context-save
- Resume context → invoke /context-restore

## Agent Operating System (docs/agent/ + .ai/)

Operational layer for AI agents (Claude Code, Codex, others). **This file and `docs/superpowers/WORKING_SYSTEM.md` remain 정본** — on any conflict, this file wins; record the conflict in `.ai/decisions.md`.

- **Route by task**: `docs/agent/README.md` (Always Read: `.ai/task.md` → `.ai/decisions.md` → `docs/agent/project-overview.md`; then only the docs your task type needs — Progressive Disclosure, don't load everything).
- **Session state**: `.ai/` (task contract, current-state, decisions ADR-LITE, known-issues, ownership single-writer registry, handoff). Update via `/os-checkpoint` before reset/agent switch.
- **Runbooks**: `.claude/commands/` (`/os-start-task` `/os-analyze` `/os-implement` `/os-debug` `/os-verify` `/os-review` `/os-checkpoint` `/os-plan-tickets` `/os-e2e`) — thin entry points into `docs/agent/` procedures; the `os-` prefix avoids collision with global OMC skills (`/verify` `/review` `/analyze`). Codex uses the tracked project-skill equivalents (`$os-start-task` through `$os-e2e`) and therefore follows the same docs and gates.
- **Prompt packs**: `docs/agent/prompt-pack.md` — 공통 5종 + 작업군 5종(파리티포팅·PHP오라클·프론트배선·인프라배포·기획티켓분해), each 페르소나/목표/형식/제약 + 발동조건/중단 조건. Consumed by `/os-*` commands and repo agents (parity-porter, fe-submit-wirer, deployer, …).
- **Codex surface**: `.codex/config.toml`, `.codex/hooks.json`, and `.codex/agents/*.toml` are tracked project configuration. Codex restores locked external skills on `SessionStart`; project openers must trust the repository hooks and reload/restart Codex after hook or config changes.
- **Guards**: `scripts/agent/protect-sensitive-files.sh` (secrets/golden/legacy write-block) · `scripts/agent/codex-bash-guard.sh` (best-effort checks for the simple Bash calls Codex hooks currently intercept) · `scripts/agent/verify-changes.sh` (base diff → minimal verification matrix). Hooks are active for Claude via `.claude/settings.json` and Codex via `.codex/hooks.json` (ADR-LITE-005/009). Codex hooks are not a complete shell-security boundary; trust/reload and the hard rules still apply. `@`-mention attachments bypass Claude PreToolUse — `.claudeignore` covers that hole.
- **Hard rules** (agents may not weaken them): no commit/push/merge/deploy/data-delete without human approval · never read/print `.env*`/keys/tokens · never fabricate goldens/tests/commands · unverified = UNKNOWN, not guessed.
