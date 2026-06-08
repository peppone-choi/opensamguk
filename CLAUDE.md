# CLAUDE.md — opensamguk

Load-bearing rules. They encode the parity discipline; violating them silently breaks the golden gates.

## What this repo is

**opensamguk** — faithful migration of the PHP game **devsam/core** (삼국지 모의전투 HiDCHe / 삼모) to **Kotlin/Spring + Next.js + PostgreSQL + Redis + nginx**, on a **memory-centric CQRS** architecture.

- **`legacy/devsam-core` (PHP) = GRAND TRUTH.** Every behavior (RNG draws, rounding, log strings, side-effect order) matches byte-for-byte. The oracle, never "improved."
- **`legacy/devsam-core2026` (TypeScript)** = a *second*, structural oracle only. **PHP wins every divergence** (collapse experience, findNextCapital BFS-vs-Euclidean, arsort-vs-V8 sort, append-additive order, `Math.round`-vs-half-away).
- `legacy/` is **git-ignored**, never committed. Design + roadmap: `docs/superpowers/specs/2026-05-29-devsam-opensamguk-kotlin-migration-design.md`.
- Repo stays **PRIVATE** until a Koei-IP review clears it. No Koei-owned assets/IP, secrets, or credentials in commits.

## Architecture (memory-centric CQRS)

```
api ──Redis(XADD)──▶ game-engine daemon ──JDBC batch flush──▶ PostgreSQL
 ▲                   (InMemoryTurnWorld = source of truth)         │
 └──────────── turnCompleted SSE ◀── ChangeRecorder dirty/created/deleted
```

Modules (`settings.gradle`):
- **`common`** — RNG/log kernel: `rng/LiteHashDrbg` (byte-exact SHA-512 DRBG) + `rng/RandUtil` + `rng/SeedSerializer`, `util/PhpRound`, `log/*` (Josa/ConvertLog/tokens), `constants/GameConst`.
- **`logic`** — pure game logic (no Spring/DB): `stats/ActionPipeline` (multi-source stat fold + `getStatValue` + calc-cache), `actions/*` + `CommandRegistry`, `war/*` battle engine, `ai/*` GeneralAI, `event/*` DSL, `tick/*`, domain.
- **`infra`** — `JdbcFlushExecutor` (**JDBC-only** flush + delete/tombstone delta + row mappers), Flyway `db/migration/V*.sql`, Redis.
- **`app/gateway-api`** (:8080) auth/profile · **`app/game-api`** (:8081) read+precheck+intake+SSE · **`app/game-engine`** (:8082) turn daemon (`InMemoryTurnWorld`+`ChangeRecorder`+`MonthlyPipeline`+`TurnRunService`).
- **`web/gateway`** (:3000) · **`web/game`** (:3001) — Next.js.

**The ONE daemon-write rule (architecture-test-enforced):** the game-engine daemon NEVER uses a JPA `EntityManager` for writes. JPA = read/precheck only (game-api). Daemon writes go **only** through `ChangeRecorder` → `JdbcFlushExecutor` JDBC batch. (Two competing dirty-truths — JPA dirty-checking + change-recorder — would silently diverge.)

## Parity discipline (NON-NEGOTIABLE)

1. **RNG draw-for-draw.** All randomness is `RandUtil(LiteHashDrbg(seed))`. The draw **order, count, and method args** are parity targets, not just the result. In battle, the WHOLE fight runs on **ONE** `RandUtil(warSeed)` built once in `processWar()` and threaded by reference — never re-seeded mid-stream. One extra/missing/reordered draw desyncs everything downstream.
2. **Rounding.** `Util::round`/`setRound` = **half-AWAY-from-zero** → use `PhpRound` (negative-scale `phpRound(v,-2)`, NEVER `phpRound(v/100)*100`). NEVER `Math.round` (half-up) or `kotlin.math.round` (half-to-even). `Util::toInt`/`intdiv` = truncate-toward-zero. Damage-loop clamp = `ceil()` (distinct from round).
3. **Korean log byte-parity.** Log strings (`Josa` 조사, color/tag markup, prefixes, `<Y1>【name】</> <C>HP (-dead)</>`, 진격·퇴각·패퇴·전멸·분쟁·정복 …) must match exactly. **Log order = execution order** → execution drift breaks the log gate.
4. **Flush delta, not inline writes.** Mutations are recorded as `created`/`dirty`/`deleted` (tombstone) on `ChangeRecorder` and flushed in bulk. Resolvers write **only** delta — no inline DB write.
5. **Faithful port, never fabricate.** Golden numbers/logs/seeds come **only** from a real PHP capture (`tools/php-golden`, Docker). If a value can't be captured faithfully, **quarantine it with proof** (sibling-code-path byte-match) + log to the phase backlog — do **not** invent it, do **not** weaken a test or edit a golden. On a mismatch: fix the Kotlin impl, not the golden.
6. **Insertion order matters.** jsonb / conflict-map / trigger-caller keys preserve insertion order (`LinkedHashMap`), never re-keyed by id. PHP 8.0+ sorts are stable — never add a non-stable secondary comparator.

## Build & test

- **Java 21 LTS required** (Gradle 8.12 fails to parse Java 25). Always run from the **repo root**:
  ```bash
  JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test
  ```
- **Verify by OUTPUT TAIL + test XML, not exit code** (the host routes gradle through a context-mode wrapper; `task-notification` exit 0 is unreliable). Pipe `... 2>&1 | tail -40`, grep `BUILD SUCCESSFUL` + counts, or read `**/build/test-results/test/*.xml`. Use `--rerun-tasks` (UP-TO-DATE false-greens).
- **Testcontainers on macOS** needs `api.version=1.44` + `DOCKER_CONTEXT=default` + Ryuk disabled (wired in `tasks.test`). Docker-unavailable ⇒ IT **skipped**, not failed.
- Full check: `./gradlew :common:test :logic:test :infra:test :app:game-engine:test :app:game-api:test`. Docker smoke: `./tools/smoke.sh`. Frontend: `cd web/gateway && corepack pnpm dev`.

## How phases are built

Each phase = one cycle **spec → plan → adversarial review → execute → gate**. Plans in `docs/superpowers/plans/`, research in `docs/superpowers/research/`.
- **Foundation-first.** Every *shared extension point* (registry, base class, stat-key enum, pipeline hook) is built in a **Tier-0 foundation wave** that later families only **consume**. Parallel worktree families must be **disjoint** — never co-widen the same file (⇒ merge conflict; cross-area shared artifacts build sequentially, creator-then-consumer).
- The phase **gate** is a real PHP golden replayed draw-for-draw. Not "done" until green (or gaps quarantined with proof + logged to the backlog).
- One logical commit per task. **Every commit message ends with:**
  ```
  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  ```

**Golden capture harness — `tools/php-golden/`:** the PROVEN Docker capture (MariaDB 11.4 + `php:8.3-cli`, scenario `1010` = 174 generals, NOT empty scenario_0). Quirks: `_boot.php` binds via `DB::db()`; `j_install.php` is called twice; install is **not** idempotent (fresh DB per run); dumps must be byte-identical across two runs.

## Repo conventions

- **Branch stack**, one per phase: `p0a-foundation-scaffold → p0b-parity-kernel → p1-vertical-slice → p2-commands-constraints → p3-monthly-tick → p4-battle-engine → p5 (NPC AI) → …`. Each branches off the previous; PRs stacked (base = parent) for clean diffs.
- Git-ignored: `legacy/`, `build/`, `node_modules/`, `.env*`, `.workflow-*.mjs`, `.claude-tmp/`, `.agents/`, `.bkit/`, `.codex/`. `tools/php-golden/probe_*.php` = throwaway, never committed.

## Skills (`Skill` tool or `/<name>`; use the **process** skill first, then implementation)

- **working system** — start non-trivial work from `docs/superpowers/WORKING_SYSTEM.md`. It fixes skill routing, PHP oracle analysis, parity comparison, hardcoding policy, and the standard gate command. `skills-lock.json` records the skills.sh project skills; `.agents/skills/` is local and git-ignored.
- **skills.sh project skills** — restored with `DISABLE_TELEMETRY=1 npx --yes skills experimental_install`. Use `next-best-practices` for Next.js, `webapp-testing` for browser flows, `redesign-existing-projects` for legacy UI modernization, `kotlin-spring-boot`/`java-spring-boot` for backend work, and `supabase-postgres-best-practices` for DB work. `java-testing` is reference-only because its skills.sh Gen audit was High Risk.
- **provider-agnostic guard** — `tools/agent-system/check.py` is the shared local/CI check for every model/provider. Use `--format json` for machine-readable agent integration and `--strict --base origin/main` in PR/CI.
- **cross-agent critique** — non-trivial work must be attacked by an independent agent/provider before ship. Kimi-backed Claude Code, Codex, Gemini, or another peer should check PHP evidence, tests, docs, hardcoding, and production invariants; unresolved `fix-required` blocks merge/deploy.
- **backend gate** — use `tools/parity/gate.sh backend` for the standard backend proof. It runs Java 21 Gradle tasks and verifies XML failures/errors, not exit code alone.
- **gstack** — all web browsing via **`/browse`** (never `mcp__claude-in-chrome__*`). Plan/review/ship/QA/deploy commands — full list in `~/.claude/CLAUDE.md`.
- **compound engineering** — loop brainstorm→plan→work→review→compound (~80% plan/review). `/ce-*` commands + `ce-*` review subagents (spawn via `Agent`: always-on correctness/maintainability/testing/project-standards; conditional security/performance/reliability/data-migrations + language reviewers).
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
- ⬜ **P6 P8-coupled remainder** — P6-specific PHP golden capture scripts (diplomacy, message, auction, betting, inheritance, worldcmd), parity harness integration, restart-rehydrate lossless gate.
- 🔄 **P7** read API ✅ + frontend ⬜ — dedicated REST controllers for auction/betting/message/mailbox/diplomacy 완료, `GetDiplomacy.php` neutral-map masking 완료, frontend pages (`web/game/app/game/`) 진행 예정.
- ⬜ **P8** parity harness (PHP 93-command compare, 23 missing ported/backlogged) + gateway orchestration + AWS EC2 t3.large deploy (LLM-free, 0 external API deps). Infra scaffold present: `.github/workflows/deploy.yml`, `docker-compose.prod.yml`, `infra/nginx/`, `scripts/deploy.sh`, `HealthCheckController`.

**미래 마일스톤(로드맵 외, 조건 충족 시):** `docs/superpowers/MILESTONES.md` — **M-config**(post-parity 상수 외부화: 풀 패러티 close + 운영 안정 후 `GameConst` 등 패러티값을 JSON으로, 패러티 골든을 frozen-baseline 회귀 게이트로 교체).

## 프론트엔드/배포 (F0–F5)

P7 프론트 + P8 시드/배포를 점진적으로 닫는 F-시리즈. 계획: `docs/superpowers/plans/2026-06-02-frontend-parity-and-scenario-seed-plan.md`. 원칙: `hwe/ts/` Vue가 프론트 grand truth(`hwe/*.php`는 dist mount 셸), PHP가 이긴다. 사용법·서비스 표·빠른 시작은 `README.md`(한글), 모듈/명령은 `AGENTS.md` 참조.

- ✅ **F0 게이트웨이 인증** — gateway-api 자체 JWT/BCrypt 로컬 인증(Kakao OAuth에서 의도적 divergence). `web/gateway` 엔트런스/로그인/회원가입/로비/어드민. 토큰은 Next route handler가 gateway-api로 프록시(동일출처 → CORS 불필요)하며 **httpOnly 쿠키**(`sam_access`/`sam_refresh`)에만 보관 — 브라우저 JS에 토큰 미노출. `AdminSeeder`가 `ADMIN_USERNAME`/`ADMIN_PASSWORD` env로 관리자(peppone, role=ADMIN) 멱등 생성(둘 다 설정돼야 시드).
- ✅ **F1 시나리오 시드** — `infra/seed/ScenarioImporter`(+`ScenarioJson`)가 커밋된 리소스(`scenario/scenario_1010.json` + `scenario/cities_1010.json`)를 opensamguk 스키마 행으로 매핑해 JDBC INSERT. `engine/boot/ScenarioSeedRunner`(`SeedBootstrap.ensureSeeded`)가 부팅 시 `world_state` 비어 있을 때만 1회 시드(멱등). `WorldSnapshotLoader`가 DB→`InMemoryTurnWorld` 부팅 배선. env fence: `SCENARIO_SEED_ENABLED`(로컬 기본 true 가능, production compose 기본 false), `SCENARIO_CODE`(기본 `scenario_1010`). **JDBC-only — one-daemon-write-rule 비위반**(`engine.boot` 패키지, write-path scan 밖).
- ✅ **F2 메인화면 + 메뉴 척추** — `web/game` 메인(`GameChrome` = GameInfo 헤더 + GlobalMenu + MainControlBar 20버튼+게이팅).
- ✅ **F3 read API + 랭킹/내정보** — game-api read 컨트롤러 + `web/game` 랭킹(`a_*`)·내정보(`b_*`) 페이지. 모두 game-api로 **read-only 렌더**.
- ✅ **F4 액션 페이지(read)** — chief-center/battle/troop/auction/board/vote/diplomacy/inherit/npc-control/simulator 등 read 렌더. **mutation(명령 제출) 경로는 아직 미완**(read 우선).
- 🔄 **F5 turnkey + docs** — 정본 `docker-compose.yml`(로컬 8서비스) + `docker-compose.production.yml`(EC2, GHCR 이미지) + `.env.example` + 한글 `README/AGENTS/CLAUDE`. `git pull && docker compose up`로 자동 설치·시드.

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
