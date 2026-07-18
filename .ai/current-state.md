# Current State

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
- Next action: **A4 승인 대기 중** — 커밋 분할안 제시 완료, 사람 승인 시 티켓별 커밋 + PR 오픈(머지·배포는 별도 A5). `.codex/config.toml` 모델 핀 제거는 승인 시 커밋에 포함(거부 시 원복 가능).
- Must-read files for next action: `.ai/ownership.md`, `docs/superpowers/plans/2026-07-17-opensam-92-93-94-97-103-execution-contract.md`, `docs/loops/opensam-batch3-2026-07-17/reviews/`, `docs/agent/verification.md`

> 이 파일은 마지막 갱신 시점의 스냅샷이다. 오래됐으면 `git log --oneline -10`과 `docs/loops/*/LEDGER.md`로 교차 검증하라.
