# Current State

## CQRS runtime safety active implementation (2026-07-18)

- 2026-07-19 foundation-unblock amendment approved: canonical identity was split from broad/G0-dependent `OPENSAM-43` into `OPENSAM-148` / GitHub `#298`, linked as a blocker of both `OPENSAM-43` and `OPENSAM-126`. Build-only sequence is identity → S2 → S3 → S4; live-capacity and W3 binding remain activation/cutover gates.
- OPENSAM-148 landed through commit `16f8add2`, PR `#299`, merge `53a31eb9`; Jira OPENSAM-148 and GitHub #298 are complete. Active branch is `codex/op-126-scoped-schema` at that merge.
- OPENSAM-126 is now active. First implementation unit is exhaustive world-owned/global table inventory followed by V31 exactly-one-world fail-closed expand/backfill and negative migration tests. No second-world admission or production migration is authorized.
- The start inventory is materialized at `docs/superpowers/research/2026-07-19-opensam-126-world-table-inventory.md`: raw CREATE set 44/44, final physical tables 42 after V7 replacement, C1 world-owned 33 / C2 global candidates 8 / C3 classification-blocked 3. V31's first TDD cohort is fixed to `nation`, `city`, `general`, `general_turn`, and `nation_turn`; SQL/tests are the next active step and are not yet implemented.
- `OPENSAM-148` canonical `world_id` contract/type is implemented locally and independently reviewed **cleared**. The review first caught quoted numeric JSON (`"42"`) acceptance; the custom integer-scalar serializer remediation is now 9/9 focused tests green. Fresh Java 21 no-cache regression is common 37 suites/217 tests and logic 270 suites/3,110 tests, all failures/errors 0.
- The OPENSAM-148 slice is independently cleared and landed. OPENSAM-126 planning/ownership has started; no schema migration, second-world admission, W3 activation, or deploy has occurred yet.
- User approved implementation start and explicit `.ai/task.md` contract / `.ai/*` ownership transfer.
- Active Epic: `OPENSAM-116`; plan SoT: `docs/superpowers/plans/2026-07-18-cqrs-memory-consistency-hardening-plan.md`.
- Branch: `codex/op-126-scoped-schema`, based on `origin/main` merge `53a31eb9`.
- Current wave: W0. `OPENSAM-123` now has both a fail-closed sanitized production-manifest **validation-only** boundary and an independently reviewed deterministic local-only materializer. Production-shaped capture remains blocked on the stopped production host and a complete approved live aggregate manifest/restore; no live 3×2 capture or capacity threshold was claimed.
- Corrected seed-proxy evidence `w0-harness-20260718d`: JDK 21, exact 2 GiB cgroup, G1/60% heap flags, 3× `current` + 3× `cold10x`, raw/JFR 12-source SHA preservation, and operational-vs-forced-GC split. Cold history 10× raised mean retained heap from `18,631,298.67` to `73,771,205.33` bytes (`+55,139,906.67`, `+295.95%`); operational JFR pause p95 rose from `17.48` to `37.94` ms.
- Earlier validation-only boundary checkpoint: checked-in 19-input `WorldSnapshotLoader` inventory; exact executable binding for the active-server `ng_games` row plus scalar server count; canonical manifest/projection/raw binding; selected-field payload semantics; Kotlin overflow guard; Python suite **40 tests, 3 opt-in skips**; packaged two-row composition/inventory-mutation/capture-block regressions green; runner rejects production-shape capture before resolve/build/Docker. After local materializer and provenance regressions, the fresh consolidated suite is **45 tests, 3 opt-in skips**, all green. The earlier disposable PostgreSQL current/cold run is retained only as historical fixed-seed probe evidence, not production-shape or capacity evidence.
- Local surrogate evidence `op123-local-20260719b`: six fresh PostgreSQL/Docker probes (`current`×3 + `cold10x`×3), JDK `21.0.11+10-LTS`, exact 2 GiB cgroup, G1/60% heap, six raw + six JFR, explicit reanalysis, production `WorldSnapshotLoader`, and exact 7 table / 8 snapshot / 19 loader-input observations. Mean retained heap was `14,908,445.33` bytes vs `66,375,930.67` bytes (`+51,467,485.33`, `+345.22%`). This is checked-in local policy evidence only; no threshold or live-capacity decision.
- `OPENSAM-124` PHP unknown and lifecycle-choice blocker are resolved: two independent fresh `scenario_1010` installs produced byte-identical GA-079 evidence SHA-256 `a8918979ab2d532d85a4b4604c55944d76d5a70b4ee9bb726ba8161e3ff22418` (8,731 bytes). The reviewed daemon seam implements expected-`stageVersion` child transitions, prefix-stop semantics, Stage-B-only recovery, recorder-backed `killturn`, and `nationbulk` architecture-guard coverage; focused tests are 8/8 green. Durable production activation remains blocked on landing `OPENSAM-148`, then S2/S3 and W3 world-scoped CAS/fenced flush. API general-row write and ring-only activation remain forbidden.
- Read policy is fixed in the draft contract: authoritative/read-your-write/min-version reads use the PostgreSQL primary; a replica, if ever introduced by a later ADR, is eventual-only. No read replica is being created in W0.
- Progress evidence is synchronized to Jira `OPENSAM-123`/`OPENSAM-124`/`OPENSAM-126`/`OPENSAM-148` and GitHub `#269`/`#270`/`#272`/`#298`. `OPENSAM-148` is in review; no ticket was transitioned to complete.
- Root owner: `cqrs-hardening-root`. OPENSAM-123/124 implementation and review file ownership is released; root retains `.ai/*` orchestration state.
- User authorized commit/push/merge for OPENSAM-148 and then OPENSAM-126 start. Deploy, production migration, dependency addition, read replica, and second-world admission remain unauthorized.

## Historical batch-3 closeout snapshot

- Updated at: 2026-07-18 (batch-3 closeout 착수 — 사용자 승인)
- Active agent: Claude `batch3-closeout` (원장 정합화 → 최종 검증 → A4 승인 대기). 이전 `root-batch3-orchestrator`는 released.
- Current branch: **`codex/full-frame-portrait-resize`** (origin에 push됨; last commit `759f00b4` OPENSAM-97 전체 프레임 비율 축소, 2026-07-17 23:47)
- Current phase: **batch-3 REVIEW-COMPLETE / CLOSEOUT IN-PROGRESS** — 92·93·94·97·103 + §13(지도 리서치·헥스맵) **전 레인 독립 리뷰 cleared**. 산출물은 전량 **미커밋**(수정 33파일 + 신규 ~25파일). A4(커밋/push/PR)는 커밋 분할안까지 준비 후 **사람 승인 대기**.
- Batch-3 최종 스냅샷 (2026-07-17~18):
  - **OPENSAM-103** cutover spec: R2 **cleared**, status `PROPOSED`(APPROVED 전환은 사용자만).
  - **OPENSAM-93** /d_pic/ 서빙: nginx 허용목록 + `disable_symlinks on;` + compose `:ro` + 양 앱 portrait helper(웹 2앱 `MANAGED_ICON` 정규식 + `/d_pic/` 해석). 리뷰 **cleared**. 주의: 라이브 박스 repo-밖 compose에 A5 전 `:ro` 볼륨 수동 반영 필요.
  - **OPENSAM-97**: 전체 프레임 비율 축소 전환 커밋됨(`759f00b4`). 얼굴 파이프라인 리뷰 cleared(18 PASS/1 PARTIAL/1 FAIL 정직 기록). **lane-97-fullrun(1000명 크롭 생산)은 별도 세션 scratchpad에서 "FP 2차 필터 진행 중" 상태로 남음 — 인수 여부 사용자 결정 대기.**
  - **OPENSAM-92** account UI: 리뷰 **cleared**(53/53·typecheck·build 재실행). 라이브 브라우저 QA는 후속 verifier로 deferred.
  - **OPENSAM-94** typed sync: 구현 6/6 + 독립 리뷰 **CLEARED**(2026-07-17 22:22, fix-required 0, note 3; V30 경합 오탐은 무경합 격리 재실행 1/0/0로 해소). wire variant·dispatcher·ChangeRecorder/DirtyState·JdbcFlushExecutor·game-api controller·engine handler + V30 마이그레이션/IT.
  - §13 지도: RTK 시리즈 비교·adjacency ledger·RTK14 헥스맵 데이터화 모두 검증 CLEARED.
  - 리뷰 산출물 7건: `docs/loops/opensam-batch3-2026-07-17/reviews/` (103·93·94·97·92·map·hexmap) 전부 cleared.
- **문서-실상 불일치 (기록, Open question ③)**: 직전 원장은 "branch=main, 외부 상태 frozen(A4 미승인)"이었으나 실제로는 `codex/full-frame-portrait-resize` 브랜치가 생성·push되고 97 커밋이 올라감(`codex-portrait-resize` 레인의 ownership 행은 "branch/commit/push 없음"으로 기록되어 있어 모순). 사용자 지시로 push됐는지 확인 필요 — 이후 A4는 명시 승인 하에만.
- Closeout 계획 (2026-07-18 사용자 "승인."):
  - **A 원장 정합화** ✅ 완료 — ownership(94 두 레인 completed, closeout writer 등록)·current-state(이 파일)·handoff 갱신.
  - **B 최종 검증** ✅ 완료 (2026-07-18 13:04 기준, 전량 OUTPUT/XML 판정):
    - 백엔드 게이트: `BUILD SUCCESSFUL in 9m 58s` + XML green **486 suites / 4423 tests** (failures·errors 0).
    - web/gateway: typecheck 무오류 + **53/53** (4파일). web/game: typecheck 무오류 + **186/186** (39파일; 12:56 실행의 `PartialReservedCommand` 1건 실패는 단독 2/2·전체 재실행 green — known-issues의 부하 플레이크 패턴, 테스트 자체 레이스 근인 기록).
    - `check.py --strict --base origin/main`: **No findings** — 해소 조치 3건: ⓐ `.codex/config.toml` 개인 모델 핀 4줄 제거(main에도 있던 pre-existing 위반; codex-surface 규칙이 강제) ⓑ 91 최종 리뷰를 `docs/loops/opensam-91-profile-icon/final-review.md`로 이동(전 영역 Scope 요구는 PR 단위 통합 크리틱만 충족 가능) ⓒ 통합 크리틱 신설 `docs/superpowers/reviews/2026-07-18-opensam-batch3-consolidated-critique.md`(Scope 전 영역 + Verdict: cleared, 7건 독립 리뷰 집계).
  - **C A4 커밋/PR** — **사람 승인 지점에서 정지.** 티켓별 6커밋 분할안(91a → 92 → 93 → 94 → 헥스맵 → docs/원장), base=main PR. main 머지=자동 배포(A5)라 별도 승인 + EC2 요금 정지 해제 여부 확인. `.codex/config.toml` `max_threads` 원복 diff 포함 여부는 사용자 지시 대기.
  - **D lane-97-fullrun 인수** — 사용자 확인 대기(진행 세션 생존 여부·scratchpad 경로).
- Standing directive: batch-3 closeout 후 다음 5티켓 선정·구현 계속("앞으로 티켓 5개씩").
- Previous baseline (2026-07-16, 유지): PR #154 머지 완료(Agent OS 활성화 + 백엔드 Sentry 3앱 + Jira 연결). Claude GHA armed(다음 PR부터). CodeRabbit 실리뷰는 소형 PR 대기. **EC2 prod 요금 미납 정지** — prod 작업 보류(`known-issues.md`).
- Verification run (batch-3 리뷰 시점): 각 리뷰어 직접 재실행 — 92 53/53·typecheck 0·build OK, 94 suite XML failures=0 errors=0(격리 재실행 포함), 91a gateway 132/132·infra 140/140. closeout Phase B가 전량 재실행 예정.
- Verification NOT run: 라이브 브라우저 QA(92·93 — 후속 verifier), Sentry SDK 실행 경로, Claude GHA/CodeRabbit 실리뷰(다음 PR).
- Open questions (사람 결정 필요): ① Sentry 사용자 토큰 회전(채팅 노출 이력) ② lane-97-fullrun 인수 여부 + scratchpad 산출물 위치 ③ `codex/full-frame-portrait-resize` push 경위 확인(위 불일치) ④ `.codex/config.toml` `max_threads=1000` 제거 diff 커밋 포함 여부 ⑤ EC2 정지 해제 여부(A5 전제).
- **A4 완료 (2026-07-18, 사용자 승인)**: 티켓별 6커밋(`14a02afd` 91 → `a8fbcc99` 93 → `7e06fd46` 92 → `12792b94` 94 → `fe2d23c5` 헥스맵 → `9831cc45` docs/원장/설정) + CI 픽스 `413cc8e6` push.
- **A5 완료 (2026-07-18, 사용자 승인)**: **PR #260 머지됨**(2026-07-18T05:00:57Z, main `064d5e1a`). 자동 배포 런은 EC2 정지(요금 미납)로 **취소** — 사용자 지시: "나중에 요금 납부하면 다시 배포만 하면 됨". Jira 동기화 완료(90·91·92·93·94·102·103 완료 전이, 97 진행중 유지, 98·100·95·104·105 batch-4 코멘트) + GitHub #232~236·#245·#246 close, #240 유지.
- Next action: lane-97 초상 전수 크롤 완주 → 얼굴 크롭 전량 적용 → opensamguk-images 2차 푸시 · 시나리오 체계 스펙 정식화(scratchpad 설계 노트 → docs/superpowers/specs/) · batch-4 선정 확정 · EC2 요금 납부 후 최신 main 재배포.
- Must-read files for next action: `.ai/ownership.md`, `docs/superpowers/plans/2026-07-17-opensam-92-93-94-97-103-execution-contract.md`, `docs/loops/opensam-batch3-2026-07-17/reviews/`, `docs/agent/verification.md`

> 이 파일은 마지막 갱신 시점의 스냅샷이다. 오래됐으면 `git log --oneline -10`과 `docs/loops/*/LEDGER.md`로 교차 검증하라.

## Batch 4 closeout (2026-07-19)

- Source branch/worktree: `codex/opensam-143-batch4` / `.claude/worktrees/codex-batch4`; OPENSAM-143 through OPENSAM-147 were committed as `1e7145f2` and locally fast-forward merged into `main` on 2026-07-19.
- Remote/external state: Batch 4 commits are not pushed to `origin/main`; no pull request, deploy, Jira/GitHub tracker mutation, legacy write, golden write, or environment-file access was performed. The original root CQRS branch and user `.codex/config.toml` were untouched.
- Completed scope: deterministic 1,000-officer v1 seed projection; 3190/3200/3219 manifests and ignored pilots; deterministic verifier; canonical scenario-number seed wiring; synthetic and actual fresh-database identity/idempotency/integrity coverage. Rank/officer-city redesign and PHP post-build promotion remain v2 work.
- Final verification: forced five-task backend `--rerun-tasks` sweep `BUILD SUCCESSFUL in 7m 20s` with 486 fresh XML reports / 4,426 tests / one known LongSim skip / zero failures or errors; exact parity gate `BUILD SUCCESSFUL in 2m 44s` with XML green; `verify-changes --run` exit 0 with game-engine 84 XML reports / 568 tests / one skip / zero failures or errors and strict `Errors=0 Warnings=0`.
- Actual ignored 3190 override: the first final attempt was isolated as stale Gradle configuration-cache environment selecting the synthetic fixture; `--no-daemon --no-configuration-cache` recovered `input=actual-override`, `BUILD SUCCESSFUL in 1m 32s`, XML 4/0/0/0, and passed identity/idempotency/integrity with nation 21, city 94, general 264, general_turn 7,920, nation_turn 1,464, diplomacy 420, rank_data 9,768, ng_games 1, event 13.
- Pilot state: 3190 and 3219 are ready under their reports; 3200 remains quarantined because the source filters 손책 at `200.1`, leaving nation 6 without a ruler until v2 PHP postBuild promotion parity exists.
- Next action: Batch 4's approved local commit and merge are complete. Any remote push/PR, deploy, external-tracker update, or branch cleanup remains separately approval-gated.
