# Current State

## OPENSAM-34 predeploy Go conditions — local grader ready; external observation blocked (2026-07-31)

- D4-31~35 local grader, manual-only workflow, runbook, and final independent
  review are complete. The re-review is `cleared`; this is a local contract
  conclusion, not a production Go decision.
- Jira remains `할 일`. The actual `ec2-prod` runner was observed offline in
  both this repository and sibling `opensamguk-docker`, so D4-31~35 actual
  observations remain blocked/incomplete and are not promoted from simulation.
- Local evidence: both scripts `bash -n` PASS; hermetic contract PASS; YAML
  parse PASS; scoped untracked-file whitespace PASS; fresh Gradle V29
  `2/0/0/0` and V32 `9/0/0/0`, `BUILD SUCCESSFUL in 2m 2s`.
- The first independent review's ref-ordering, integer/health, read-only SQL,
  canonical `serverId`, `df`, and exact V29-index findings were remediated;
  final re-review confirmed them intact.
- `scripts/agent/verify-changes.sh` classification ran, but `--run` was not
  rerun for OPENSAM-34. Production/EC2 access, workflow dispatch, `.env*` or
  secret access, commit, push, PR, merge, deploy, Jira mutation, data deletion,
  legacy/golden writes, and test weakening did not occur.
- Tooling baseline: repeated generic Fablize tool-failure notices during
  successful read-only discovery are isolated external-tool observations, not
  grader or production evidence. Direct scoped command evidence is authoritative
  for this closeout.
- Local OPENSAM-34 file ownership is released. The next action is only an EC2
  resume followed by explicit user approval and manual workflow inputs; it is
  not authorized by this closeout. ADR-LITE-026 still requires three separate
  PR-conversation review-agent rounds plus explicit human merge approval.

## OPENSAM-33 B2 운영 스모크 — 로컬 완료/released (2026-07-31)

- Jira: `할 일`, D4-14~17. 외부 Jira 전이는 실행하지 않았으며, 다음 순서의
  OPENSAM-34 local grader closeout is recorded above; its production observation
  remains separately blocked.
- Final isolated artifact:
  `/var/folders/34/jlnbkc0j6fj0nkcp7fj0f9h00000gn/T/opensamguk-op33-remediation.A4KNsK/live-gate-marker-fixed`.
  `che_요양`은 `202` intake 뒤 동일 request ID로 reservation/execution `200`을
  받았고, durable marker·XRANGE·XINFO·XACK·XPENDING=0가 같은 Redis entry를
  증명했다.
- 60초 cadence의 세 snapshot은 successful ticks `2 → 3 → 4`, 각각
  failures/consecutive failures `0`이었다. Authoritative read는
  injury/experience/dedication `0/0/0 → 0/10/7`; `turnCompleted` 뒤
  front-info refresh와 DOM `명성=전무 (10)`, `계급=30품관 (7)`를 관측했다.
- Focused fresh evidence: `ScenarioImporterIT` 14/0/0/0,
  `RedisCommandStreamIT` 3/0/0/0, `IntakeResultChannelTest` 4/0/0/0,
  `RealtimeRelayIT` 1/0/0/0; marker lane unit 4/4 + Testcontainers IT 1/1
  skip 0; typecheck and shell contracts PASS. Fresh rerun: shell syntax+timeout
  contract PASS, web typecheck PASS, `ScenarioMapSeedIT` 8/0/0/0 (`BUILD
  SUCCESSFUL` in 2m), and `CommandReserveServiceTest` 4/0/0/0 plus IT 1/0/0/0
  (`BUILD SUCCESSFUL` in 1m 22s).
- Initial `fix-required` findings (polling-only wake proof, leftover isolated
  resources, missing phase correlation, insufficient timeout) were remediated.
  Final independent review is cleared.
- QUESTION (non-blocking for stale-refresh): 9 EventSource opens / 8
  `turnCompleted`; reconnect/remount versus duplicate subscription is UNKNOWN.
- One `scripts/agent/verify-changes.sh --run` result: five-module Gradle
  `BUILD SUCCESSFUL in 12m 55s`, 552 suites / 4,763 tests / failures 0 /
  errors 0 / skipped 1; `web/game` typecheck PASS and Vitest 46 files / 232
  tests PASS; `git diff --check` PASS. Wrapper exit 1 comes only from strict
  checker baselines: user-owned `.codex/config.toml` personal model pin and the
  historical 2026-07-27 review lacking one anchored Scope/Verdict under the
  current rule.
- Explicitly unexecuted: `tools/parity/gate.sh backend`, production deploy or
  EC2, commit/push/PR/merge, and Jira transition. Generic Fablize read-command
  notices are the documented external tooling baseline, not product evidence.

## OPENSAM-32 외교 상태 전이 6종 — 로컬 완료 (2026-07-30)

- Jira: Highest / `할 일`, D4-08~13.
- PHP oracle과 fresh baseline 뒤 D4-10 shortcut form 누락, proposal
  destination color 불일치, 분리 seam의 lifecycle 과장을 RED로 확인해
  최소 수정했다.
- `che_불가침제의` pinned modal은 server catalog form을 사용하고 lookup,
  matching row, matching form 누락 시 fail-closed한다.
- 세 proposal resolver는 preload된 상대국 color를 message payload에
  사용한다.
- Testcontainers IT가 실제 proposal → DB message → accept → 양방향 flush
  → 동일 월드 declaration을 연결한다.
- Fresh focused evidence: logic 72/72, engine 34/34, infra 2/2, frontend
  16/16, typecheck PASS; backend failure/error/skip 0.
- live browser는 compose 필수 runtime 설정 부재로 `채점대기`; accept
  다중 로그/event 전체 PHP 패러티는 Jira 상태 전이 밖 후속 항목이다.
- 독립 코드 재검토 finding은 모두 해소됐고 문서 정합화 후 최종 판정은
  `cleared`다. Jira 전이와 git/배포/production 작업은 수행하지 않았다.
- OPENSAM-32 소유권은 released. OPENSAM-33 write scope는 새
  `$os-start-task` 계약 전까지 닫혀 있다.

## OPENSAM-31 v1 안정화 체크리스트 — 로컬 완료 (2026-07-30)

- 사용자 승인 순서: `31 → 32 → 33 → 34 → 149 → 35`.
- active-plan에 D4-01~07의 정확한 repo-root 명령, 객관적 PASS/FAIL,
  Docker/Testcontainers skip 처리, production 승인 경계를 기록했다.
- 독립 검토의 D4-03/05/06/07 과장 주장을 모두 제거했고 최종 판정은
  `cleared`다. browser-facing SSE 전달과 열거한 7개 명령의 fresh 개별
  실행은 증거로 승격하지 않았다.
- whole-worktree `verify-changes --run` 1회 결과: fresh backend XML
  552 suites / 4,758 tests / failure·error 0 / skip 1; `web/game`
  typecheck + 46 files / 227 tests; Agent OS contract와 diff check PASS.
  임시 Gradle 로그가 스크립트 종료 때 삭제돼 `BUILD SUCCESSFUL` 문구는
  별도 보존되지 않았다.
- strict checker의 두 error는 현 작업 밖 baseline이다:
  user-owned `.codex/config.toml` model pin과 historical 2026-07-27 review의
  uppercase `Verdict: CLEARED`/현재 lowercase anchor 규칙 불일치.
- EC2/prod, commit/push/merge/deploy, Jira 상태 변경은 수행하지 않는다.
- Jira OPENSAM-31은 외부 전이 권한이 없어 계속 `할 일`이다.
- OPENSAM-31 소유권은 released. 다음 write scope는 새 `$os-start-task`
  계약 뒤 OPENSAM-32로만 연다.

## v1 비운영 미완성 폐쇄 — 완료 (2026-07-29)

사용자 지시 `"미완성 중 운영전환 제외하고 나머지를 완성시켜. 검증은 로컬 도커로 해."`의
비운영 범위가 완료됐다. 감사 §6.1–§6.8과 §8의 비운영 차단은 구현·재측정했고,
v1은 **상순·중순·하순, 연 36순**을 유지한다(ADR-LITE-024).

- 감사/종결: `docs/superpowers/research/2026-07-26-v1-legacy-equivalence-audit.md`
  2026-07-29 최종 부록, `docs/superpowers/reviews/2026-07-27-v1-nonoperational-completion-review.md`
- PHP: schema 4 fresh A/B 12개월·36순 capture byte-identical; Kotlin authoritative
  replay 1/0/0/0. 경로: `.omo/evidence/v1-ai-production/`.
- gates: backend 550 suites / 4,753 tests / failure·error 0; 영향 backend 185 suites /
  1,172 tests / failure·error 0; `web/game` 46 files / 227 tests + typecheck.
- local Docker: runtime9이 가입/로그인, join `202 → RESOLVED`, 후속 예약/거절,
  14 DOM route, engine restart 뒤 command/general/repository persistence를 관측했다.
- 독립 review: CLEAR / APPROVE / blockers none.
- **제외·미수행:** CQRS S6, production canary/expand/backfill/capacity, live EC2
  cutover, commit/push/merge/deploy/data delete/secret access.
- checker의 cleared/quarantined disjoint Scope union 수정 뒤 최종 Agent OS
  `scripts/agent/verify-changes.sh --run`을 정확히 한 번 실행했다. Gradle 5개
  모듈은 `BUILD SUCCESSFUL in 13m 27s` / 29 tasks, `web/game` typecheck + 46
  files / 227 tests, Agent OS contract와 diff/whitespace는 PASS다.
- strict checker는 error 1 / warning 0이며 exit 1의 유일한 원인은 수정하지 않은
  사용자 소유 `.codex/config.toml` 최상위 personal model pin이다. cross-agent
  finding은 scope-union 독립 review의 `cleared`로 제거됐다. 이는 비운영 v1
  완료와 별개의 whole-worktree strict baseline이며 strict green·ship/merge
  ready를 뜻하지 않는다. 증거:
  `.omo/evidence/v1-final/verify-changes-final2/{verify-changes.log,exit-code.txt}`.

## 버전 1 레거시 동등성 감사 — 2026-07-26 (historical snapshot)

판정은 **미완성 / release-blocked**다. `docs/` 동결 입력 388개를 전수
참조하고 PHP `devsam/core` 및 `hwe/ts`와 현 Kotlin/Next 실경로를 대조했다.
정본 보고서는
`docs/superpowers/research/2026-07-26-v1-legacy-equivalence-audit.md`,
문서 원장은
`docs/loops/v1-legacy-equivalence-audit-2026-07-26/DOCS_MANIFEST.md`다.

이번 diff에서 확정 버그 6개를 bounded 수정했다.

- cold boot world scope와 troop 재적재
- `ProfileIconSync` durable inbox terminal result
- `che_천도` 거리/비용/턴/trial/유산/로그/static-event 순서
- AI 요양 기본 임계값 30→10
- event cold-load world scope
- board secret / unique auction deep link

최종 증거: backend 521 suites / 4,585 tests / 실패 0 / skip 205,
`web/game` 42 files / 216 tests, 독립 리뷰 `cleared`. Docker 2-world IT,
PHP 재캡처, live browser는 환경 부재로 `채점대기`다. 명령·월 틱·전투·AI
정책·부가 시스템·JPA read·프런트·S6 운영의 잔여 차단 항목은 감사 보고서
§6에 남아 있다. commit/push/merge/deploy는 수행하지 않았다.

## 현재 상태 요약 — 2026-07-25

CQRS 정합성 하드닝 트랙과 F4 프론트 액션 배선이 함께 main에 반영됐다. 아래는 정본 최신 상태이며, 그 뒤 히스토리 절은 압축된 기록(증거는 PR/리뷰 아티팩트가 정본)이다.

### CQRS 하드닝 (ARCH-S1–S6) — S5까지 build-only 완료, main 머지

전부 **build-only**(프로덕션 cutover/activation 미수행), 라이브 게임 동작·패러티 골든 불변.

| 그룹 | 티켓 | PR / 커밋 | 상태 |
|------|------|-----------|------|
| 월드 스코프 (B1) | OPENSAM-127~129 | #302~#305 | main 머지 — 로더/쿼리/예약/Redis 키/flush를 `world_id`로 스코프, 동일 local-ID 2월드 격리 게이트 통과 |
| flush 무결성 (B2) | OPENSAM-130~132 | #307~#309, #311 | main 머지 — `DeltaGenerationSession`, `world_version` CAS + `writer_epoch`, `FlushRecoveryGate` |
| S4 durable 명령 경로 | OPENSAM-133~136 | **#312** | main 머지 · GH #279/#280/#281/#282 **CLOSED** · 독립 리뷰 `Verdict: cleared` — command_inbox 선기록, durable result/outbox, consumer-group wake + post-commit ACK, 크래시/리플레이 매트릭스 |
| S5-T1 hot/cold 카탈로그 | OPENSAM-137 | 커밋 `4e7095df` | main 머지 (build-only) |
| S5-T2 bounded boot reads | OPENSAM-138 | **#314** | main 머지 — 부팅 아카이브 읽기 bounded/on-demand화 |
| S5-T3 minVersion read barrier | OPENSAM-139 | **#315** | main 머지 — game-api `minVersion` read barrier, stale read → 409 `VERSION_NOT_VISIBLE` |
| S6 롤아웃 | OPENSAM-122 (#268) | — | **잔여** — canary/expand-backfill/replica ADR, S2–S5 완료 후 착수 |

- 에픽 #266(ARCH-S4)은 자식 T1–T4가 닫혔지만 **activation/operational 잔여** 때문에 OPEN 유지.
- ARCH-S1-T3(OPENSAM-125 / #271) 용량 임계값·admission policy는 OPEN(병렬 capacity work).

### F4 프론트 액션 배선 — main 머지

| 티켓 | PR | 내용 |
|------|-----|------|
| OPENSAM-13 | #316 | 엔진 deny 결과를 web/game에 표면화 |
| OPENSAM-6 | #317 | 외교 서신 승인/거부 응답 배선 |
| OPENSAM-8 | #318 | 내정보(my-page) 즉시 액션 |
| OPENSAM-7 | #319 | 인사부 roster read model + 장수 임면 배선 |

메일함 서신 삭제(`web/game/app/game/mailbox/page.tsx` → `deleteMessage` intake)도 배선 완료.

### 오늘(2026-07-25) Jira Done 처리

OPENSAM-6 / 7 / 8 / 13 / 97 / 123 / 124 를 완료 처리함. (97 = 초상 수집 page 모드 승격, 123 = CQRS 로컬 집계 기준선 재현, 124 = 국가 벌크 증거 + 데몬 라이프사이클 고정 — 커밋 `11bb0322`/`e013e47c`/`b6cb77f0`.)

### 다음 착수 후보

OPENSAM-137은 완료됐으므로 S5 잔여(hot/cold 활성화 follow-up) 또는 S6 착수 판단은 활성화 정책(#271, #268) 게이트를 따른다. 프로덕션 deploy/cutover·골든 위조·force-merge 금지.

---

## 히스토리 (압축) — 증거 정본은 PR/리뷰 아티팩트

### CQRS B1 — OPENSAM-127~129
process-world reads + flush scope + two-world isolation. main 머지 (#302~#305).

### CQRS B2 — OPENSAM-130~132 (build-only, 2026-07-21)
- OPENSAM-130 (#307): DeltaGenerationSession prepare/commit/abort.
- OPENSAM-131 (#308): world_version CAS + writer_epoch fence on flush.
- OPENSAM-132 (#309/#311): FlushRecoveryGate + intake/tick stop; FLUSH_RETRY resume.
- 리뷰: `docs/superpowers/reviews/2026-07-21-opensam-13{0,1,2}-*.md` — Verdict cleared.

### S4 durable 명령 경로 — OPENSAM-133~136 (build-only, PR #312 머지 2026-07-22)
- **OPENSAM-133 / #279 (ARCH-S4-T1)**: `command_inbox` 선기록(202 이전), DB-before-Redis intake, reserved ring + inbox 트랜잭션, 안정 intent fingerprint, 중복 request-id 처리. GH CLOSED 2026-07-23 (build-only).
- **OPENSAM-134 / #280 (ARCH-S4-T2)**: durable inbox claim/reclaim(lease), Redis consumer-group wake + PEL 인계 + post-commit ACK. GH CLOSED 2026-07-23 (build-only).
- **OPENSAM-135 / #281 (ARCH-S4-T3)**: durable `command_result`/`command_outbox`(V35), 같은 flush TX 커밋, `CommandOutboxRelay` 재시도, 예약/큐 terminal 상관. GH CLOSED. 리뷰 최종 `Verdict: cleared` — `docs/superpowers/reviews/2026-07-22-opensam-135-durable-result-outbox-review.md`.
- **OPENSAM-136 / #282 (ARCH-S4-T4)**: 크래시/리플레이 매트릭스. GH CLOSED.
- 잔여(비-차단): 프로덕션 deploy/cutover 미수행, 에픽 #266 activation 잔여, `reservationRevision` 계약 설계 잔여, 광역 `verify-changes.sh --run` Gradle stall(툴링 baseline). 상세 트리아지: `docs/superpowers/research/2026-07-23-ticket-triage-next.md`.

### S5-T1 hot/cold 카탈로그 — OPENSAM-137 (build-only, 커밋 `4e7095df`)
`logic/.../memory/HotColdCatalog.kt`(ALWAYS_HOT/PHASE_HOT/QUERY_ONLY_COLD) + `HotColdWorldCatalogGuardTest`(스냅샷 로더/런타임 read-seam/직접 SQL 스캔). 독립 리뷰 반복 후 method-agnostic reader 탐지로 수렴. S5 런타임 prefetch 활성화는 미수행.

### S5-T2 bounded boot reads — OPENSAM-138 (build-only, PR #314)
`WorldSnapshotLoader`가 statistic/history/global-log full-scan 제거, `ArchiveHistoryReader`/`StatisticSnapshotReader` on-demand 시seam. 아카이브 flush 복구는 `DatabaseHooks`가 pending 마커만 싣고 `JdbcFlushExecutor`가 재시도 TX 내부에서 읽도록 교정. 리뷰 5회차 cleared — `docs/superpowers/reviews/2026-07-23-opensam-138-bounded-boot-review.md`. 리터럴 JFR/heap 비교(#284)와 광역 `verify-changes.sh --run`은 미실행.

### S5-T3 minVersion read barrier — OPENSAM-139 (build-only, PR #315 머지 2026-07-24)
- `TurnDaemonEventEnvelope`가 nullable `committedWorldVersion` 운반(레거시 envelope 디코드 보존), 데몬/API terminal result 행이 커밋 버전 인코드.
- `GET /api/command/result/{requestId}`가 `committedWorldVersion` 최상위 노출.
- game-api `minVersion` 인터셉터: command-result read = read-your-writes, ranks/history/world-log/admin = eventual, 그 외 = authoritative.
- `ReadConsistencyBarrier`가 전용 `game-api-read-barrier` Hikari 풀로 `world_state.world_version` 폴링, stale read → 409 `VERSION_NOT_VISIBLE`(worldId/currentVersion/requiredVersion/retryAfterMs).
- 독립 리뷰 cleared — `docs/superpowers/reviews/2026-07-24-opensam-139-minversion-read-barrier-review.md`.
- **상태: PR #315로 main 머지 완료** (이전 "commit/push/PR pending"은 해소됨).

### 미해결 툴링 baseline (제품 결함 아님)
광역 `scripts/agent/verify-changes.sh --run`가 `--rerun-tasks` Gradle 매트릭스에서 반복 stall(exit 143)하는 현상은 여러 워커에서 관측됨. 집중 모듈 테스트·strict `check.py`·독립 리뷰는 green. 개인 `.codex/config.toml` overlay(`max_threads`/`max_depth`)는 이 트랙에서 편집/스테이징하지 않음.
