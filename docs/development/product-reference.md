# OpenSamguk 제품·운영 상세 참고

기존 CLAUDE.md의 상세 본문을 보존한 조건부 참고다. 현재 상시 진입점은 저장소 루트의 CLAUDE.md/AGENTS.md다. 아래 경로·명령은 저장소 루트 기준이며, 작업에 해당하는 절만 읽는다. Roadmap 및 F0–F5 상태 기록은 현재 완료 판정으로 사용하지 않고 최신 승인 ADR·spec·구현과 대조한다. 이 문서는 자동 가져오기 대상이 아니다.

> 에이전트 하네스 제거 경위는 `.ai/decisions.md` ADR-LITE-047에 보존한다. 현재 진입점은 `AGENTS.md`, 필요한 작업 절차는 `docs/superpowers/WORKING_SYSTEM.md`다. 아래 제품·아키텍처 규칙은 Claude와 Codex가 공유한다.

Load-bearing product and architecture rules. Violating them silently breaks deterministic regression and operational integrity.

## What this repo is

**opensamguk** — PHP 게임 **devsam/core**(삼국지 모의전투 HiDCHe / 삼모)에서 출발한 **Kotlin/Spring + Next.js + PostgreSQL + Redis + nginx** 턴제 시뮬레이션. **memory-centric CQRS** 아키텍처.

v1 코어는 devsam/core 를 바이트 단위로 이식해 세웠지만, **2026-08-20(ADR-LITE-042) 부로 패러티 이식을 종료했다.** 이제 오픈삼국은 자기 설계를 따른다.

- **`legacy/devsam-core` (PHP) = 참고 자료.** ~~GRAND TRUTH~~ — **2026-08-20 (ADR-LITE-042) 부로 오라클 지위가 해제됐다.** 체섭은 체섭이고 오픈삼국은 오픈삼국이다. PHP 동작은 설계를 참고할 때 읽는 자료이지, 맞춰야 할 정답이 아니다.
- **`legacy/devsam-core2026` (TypeScript)** = 또 하나의 참고 자료. 예전 PHP 우선 규칙도 ADR-LITE-042 로 해제됐다 — 두 레거시 모두 이제 우리 설계를 정할 때 읽는 자료다.
- `legacy/` is **git-ignored**, never committed. Design + roadmap: `docs/superpowers/specs/2026-05-29-devsam-opensamguk-kotlin-migration-design.md`.
- Repo stays **PRIVATE** until a Koei-IP review clears it. No secrets or credentials in commits. **RTK14 장수 초상은 예외다 (ADR-LITE-048, 2026-09-06):** 소유자가 책임을 지고 제품에서 쓰기로 결정했다. `opensamguk-images` 의 `portraits/rtk14/serving/{original,portrait,icon}` 을 CDN 으로 참조하며, 이 저장소에는 여전히 초상 파일을 커밋하지 않는다. 그 외 Koei 소유 자산·IP 는 계속 커밋 금지.

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

Historical PHP capture (`tools/php-golden/`) is for explicitly requested frozen-regression maintenance. The removed `parity-close`/`parity-ship` skills are not callable; `tools/parity/gate.sh` remains a backend test helper. Their historical names do not restore PHP as product authority. Full decision and reversal procedure: `.ai/decisions.md` ADR-LITE-042.

**Five-stat product extension — politics/charm.** `politics`(정치)/`charm`(매력)은 오픈삼국 독자 스탯이다. RTK14 원본과 생성 시나리오는 **git-ignore, 미커밋**하고 `tools/rtk14/build_rtk14_stats.py`만 버전 관리한다. 빌더는 모든 `scenario_*.json` 장수 tuple의 인덱스 5·6·7·14·15를 원수치로 덮어쓰며, 통무지·생몰년·별칭으로 동명이인을 1:1 배정한다. 검토된 override(`rtk14_unmatched_overrides.json`)가 없는 미매칭 행에서는 빌드가 실패하고, override 에 50/50 쌍은 금지된다. 50/50 은 생성본이 없을 때 importer 기본값(`ScenarioJson.kt`)일 뿐이다. 유저 생성은 통무지정매 5개 입력과 총합 275 상한을 사용한다. 스펙: `docs/superpowers/specs/2026-06-13-five-stat-rtk14-divergence.md`.

**Sanctioned divergence — han 건국·3축 등급 밸런스 (ADR-LITE-043).** han 맵에서만 공백지 수비병 돌파비율을 `FOUND_ASSAULT_RATIO=2.0`(`ceil(defence * 2.0)`)으로 두고, 건국 가능 등급을 중/소(5/6) + 영현/장현(`level >= 10`)으로 둔다. 군치 수에 따른 도적·황건 spine 문턱은 `1/13/28郡治 -> nation.level 2/3/4`다. CHE/miniche 건국 돌파비용은 0이고 기존 회귀 픽스처는 frozen-baseline으로 보존한다. 정본·뒤집기 경로는 `.ai/decisions.md` ADR-LITE-043이다.

## Build & test

- **Java 21 LTS required** (Gradle 8.12 fails to parse Java 25). Always run from the **repo root**:
  ```bash
  JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test
  ```
- **Verify by OUTPUT TAIL + test XML, not exit code alone** (when a host uses a context-mode wrapper, `task-notification` exit 0 can be unreliable). Pipe `... 2>&1 | tail -40`, grep `BUILD SUCCESSFUL` + counts, or read `**/build/test-results/test/*.xml`. Use `--rerun-tasks` (UP-TO-DATE false-greens).
- **Testcontainers on macOS** needs `api.version=1.44` + `DOCKER_CONTEXT=default` + Ryuk disabled (wired in `tasks.test`). Docker-unavailable ⇒ IT **skipped**, not failed.
- Full check: `./gradlew :common:test :logic:test :infra:test :app:game-engine:test :app:game-api:test :app:board-api:test`. Docker smoke: `./tools/smoke.sh`. Frontend: `cd web/gateway && corepack pnpm dev`.

## Implementation and review

Use the current task's scope and acceptance criteria. Phase-sized architecture work needs an approved spec and executable plan; routine approved local edits do not need an extra approval cycle. Plans live in `docs/superpowers/plans/`, research in `docs/superpowers/research/`.

- **Foundation-first.** Build shared extension points before their consumers. Concurrent writers own disjoint files; shared artifacts are updated sequentially.
- Preserve affected frozen baselines, verify new rules with reproducible evidence, and report unverified gaps as `UNKNOWN`.
- Before merging or deploying changes to replay, flush, data integrity, or production contracts, obtain independent review. Unresolved `fix-required` blocks that action.
- Commit/push/merge/deploy/data deletion or re-seeding require user authorization for the action and target. Continue authorized local implementation and verification without asking again at each step. When commits are authorized, keep them logical and attribute only actual contributors; do not hardcode another model as co-author.

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

## Repo conventions and skills

- `legacy/`, `build/`, `node_modules/`, `.env*`, `.workflow-*.mjs`, `.claude-tmp/`, `.bkit/`, and PHP `probe_*.php` outputs are local/ignored surfaces; never commit secrets or downloaded legacy/IP sources.
- Project `.codex/` configuration, skill locks, restoration scripts and `$os-*` adapters were removed. Do not assume a SessionStart restore or a Codex hook exists.
- For historical claims, use `.claude/skills/historical-sources/SKILL.md`; keep its Claude path. Other skills are environment-provided: choose only ones actually available whose task scope matches. Missing optional tools do not prevent safe local work.
- Claude-specific tools and user-level routing follow that host's active instructions; this shared file does not require Codex to have Claude's `Skill` tool or `/browse` command.
- Workflow, historical PHP comparison, targeted gates and production boundaries: `docs/superpowers/WORKING_SYSTEM.md`, only the relevant section.

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

**UI 정본(ADR-LITE-049, 2026-09-06).** 두 프런트의 시각·정보구조 정본은 야전 사령부(Concept A) 캔버스이며 소스 사본은 `docs/design/ui-redesign-2026-09/`, 구현 계획은 `docs/superpowers/plans/2026-09-06-ui-redesign-implementation-plan.md`다. 메인=작전실(지도 중앙·명령 목록 12순 우측 고정), 커뮤니티/회의실/기밀실은 별개 화면, 초상 3종, 현행 라벨·게이팅 불변, 비활성은 점선+사유.

**브랜드 에셋.** 마스터 `assets/brand/logo-master.png`(AI 자체제작, 제3자 파생 아님) 하나에서
`python3 tools/assets/build_brand_assets.py`가 두 프런트엔드의 파비콘·앱아이콘·워드마크를 전량
재생성한다. 산출물(`web/*/app/{icon,apple-icon}.png`, `favicon.ico`, `web/*/public/logo-wordmark*.png`)을
손으로 고치지 말고 빌더를 다시 돌려라. Next App Router가 `app/` 아래 파일명만 보고 자동 배선하므로
`layout.tsx`의 `metadata.icons`는 쓰지 않는다. 출처·파생 규약은 `assets/brand/README.md`.
`opensamguk-images`(제3자 파생 에셋)와는 무관한 별도 계보다.

## Claude-specific skill routing

When running in Claude with the named skills actually available, retain these task routes: product ideas → `/office-hours`; strategy/scope → `/plan-ceo-review`; architecture → `/plan-eng-review`; design consultation/review → `/design-consultation` or `/plan-design-review`; full review pipeline → `/autoplan`; bugs → `/investigate`; browser QA → `/qa` or `/qa-only`; code review → `/review`; visual polish → `/design-review`; authorized shipping → `/ship` or `/land-and-deploy`; checkpoint/resume → `/context-save` or `/context-restore`. Use the active Claude user's `/browse` routing for browsing where configured. These are conditional Claude tool routes, not Codex command requirements or permission to ship.

Structural graph tools can help with callers, dependencies and impact analysis when available. Verify their freshness; do not require a missing graph tool before ordinary file search.

## Agent operation and safety

`AGENTS.md` is the repository entry point; use its task-specific links. `.ai/` stores task state and decisions, not a mandatory reading list for every edit. When resuming work, validate its dates and claims against the current checkout and request. Do not mark proposed decisions approved without human approval.

Keep `.claudeignore` and `scripts/agent/protect-sensitive-files.sh` intact. Inspect actual host configuration before claiming a hook is active; those Claude protections do not establish a Codex sandbox boundary.

Never read or print secret values from `.env*`, keys or tokens; use `.env.example` for the public contract. Never fabricate goldens, tests, commands or successful verification. Unverified means `UNKNOWN`. Report concrete changes, checks and remaining risks, and continue until the authorized implementation and relevant verification are complete.
