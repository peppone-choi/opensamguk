# Work Ownership

병렬 에이전트(Claude Code, Codex, Gemini 등)의 파일 소유권 등록부. 규칙은 `docs/agent/collaboration-protocol.md`가 정본.

| Agent | Task | Branch/worktree | Owned files | Status | Updated at |
|---|---|---|---|---|---|
| `batch2-contract-writer` | OPENSAM-92/93/94/97/103 execution contract | shared workspace (disjoint) | `docs/superpowers/plans/2026-07-17-opensam-92-93-94-97-103-execution-contract.md` | completed/released — planning-only PROPOSED contract reviewed/cleared; A0 pending, implementation/external writes blocked | 2026-07-17 |
| `lane-90-web-gateway` | OPENSAM-90 frontend | shared workspace (disjoint) | `web/gateway/**`; `docs/loops/opensam-90-gateway-portrait/**` | completed/released — implementation/review PASS; A4/A5 blocked | 2026-07-17 |
| `lane-91a-gateway-api` | OPENSAM-91a API/storage | shared workspace (disjoint) | `app/gateway-api/**`; `infra/src/main/resources/db/migration/**`; `infra/src/test/**`; `docs/loops/opensam-91-profile-icon/**` | completed/released — SPEC/SECURITY/TESTS PASS, fix-required=0; LICENSE/A4/A5 blocked | 2026-07-17 |
| `lane-91b-catalog-research` | OPENSAM-91b source/catalog research | shared workspace (disjoint) | `docs/superpowers/research/2026-07-17-opensam-91b-*` | completed/released — research done; rights/activation blocked | 2026-07-17 |
| `lane-102-map-research` | OPENSAM-102 map source/coordinate coverage | shared workspace (disjoint) | `docs/superpowers/research/2026-07-17-opensam-102-rtk14-rtk8r-map-source-coverage.md`; `docs/superpowers/research/2026-07-17-opensam-102-map-coordinate-ledger.csv` | completed/released — 101 coordinates cleared; non-repo quarantine + RIGHTS WARN | 2026-07-17 |
| `lane-109-system-research` | OPENSAM-109 system candidate catalog | shared workspace (disjoint) | `docs/superpowers/research/2026-07-17-rtk-system-candidate-catalog.md` | completed/released — catalog research done; HOLDs remain | 2026-07-17 |
| `lane-113-ui-concepts` | OPENSAM-113 UI diagnosis/concepts | shared workspace (disjoint) | `docs/superpowers/research/2026-07-17-opensam-113-ui-diagnosis-and-concepts.md`; preview artifacts는 repo 밖 user-data only | completed/released — docs/board done; live A2 and user A3 selection pending | 2026-07-17 |

## Batch 3 lanes (2026-07-17, OPENSAM-92·93·94·97·103 A0 승인)

| Agent | Task | Branch/worktree | Owned files | Status | Updated at |
|---|---|---|---|---|---|
| `root-batch3-orchestrator` | batch 3 orchestration | shared workspace | — (해제) | released — 세션 종료, closeout은 `batch3-closeout`이 인수 | 2026-07-18 |
| `batch3-closeout` | batch-3 closeout (원장 정합화 → 최종 검증 → A4 승인 대기) | `codex/full-frame-portrait-resize` | — (해제) | completed/released — A4/A5 complete; user-approved `.ai/*` ownership transferred to CQRS runtime safety | 2026-07-19 |
| `lane-93-dpic-serving` | OPENSAM-93 nginx `/d_pic/` + 양 앱 portrait helper | shared workspace (disjoint) | — (해제) | completed/released — 리뷰 cleared + disable_symlinks 하드닝 검증 완료 | 2026-07-17 |
| `lane-97-rtk-faces` | OPENSAM-97 RTK14 local-only face pipeline (1차 골격) | shared workspace (disjoint) | — (해제) | released — 29/29 tests green, acceptance #8 미충족 상태로 종료; 소유권 97b로 승계 | 2026-07-17 |
| `lane-97b-portrait-qa` | OPENSAM-97 QA (사망 오판 정정) | shared workspace (disjoint) | — (해제) | completed/released — shipped(c74c9e27) 대조로 QA 전이 검증(diff는 로스터 1000 + opt-in mfr뿐, 기본 off); mfr 0.12는 黄忠을 '교정'이 아닌 정직 NO_DETECT로 전환함을 실측 정정 | 2026-07-17 |
| `lane-97c-portrait-finish` | OPENSAM-97 확장 마무리: 상태 평가 + QA 20건 완결 | shared workspace (disjoint) | — (해제) | completed/released — 리뷰 cleared; 단 릴레이 "20/20 PASS"·manifest sha 주장은 부정확(실물 18 PASS/1 PARTIAL/1 FAIL) — 아티팩트 우선 검증이 잡음 | 2026-07-17 |
| `reviewer-97-faces` | OPENSAM-97 독립 리뷰 | shared workspace (disjoint) | `docs/loops/opensam-batch3-2026-07-17/reviews/opensam-97-faces-review.md` | completed — **cleared** (fix-required 0, note 6, 3중 보고 시간선 재구성 완료); 조작·체리피킹 없음(manifest 실물 18/1/1 유지), 97c "20/20"은 산문 과장뿐; lane-97 해제 후 편집은 절차 note N5; mfr 0.12 실측(黄忠·丁原 오검출→NO_DETECT, 실얼굴 손실 0) | 2026-07-17 |
| `lane-103-cutover-spec` | OPENSAM-103 sanctioned-divergence spec | shared workspace (disjoint) | — (해제) | completed/released — R2 재검증 cleared, status PROPOSED(사용자만 APPROVED 전환) | 2026-07-17 |
| `lane-92-account-ui` | OPENSAM-92 account multipart UI + Next proxy | shared workspace (disjoint) | — (해제) | completed/released — 독립 리뷰 **cleared** (fix-required 0, note 3) | 2026-07-17 |
| `reviewer-92-account` | OPENSAM-92 독립 리뷰 | shared workspace (disjoint) | `docs/loops/opensam-batch3-2026-07-17/reviews/opensam-92-account-review.md` | completed — cleared; 53/53·typecheck·build 직접 재실행, 보안 단언 실증, 브라우저 live QA는 후속 verifier로 deferred | 2026-07-17 |
| `lane-94-icon-sync` | OPENSAM-94 typed sync + dirty/flush (실저자 — 사망 오판 정정) | shared workspace (disjoint) | — (해제) | completed/released — 구현 6/6, 독립 리뷰 **CLEARED**(fix-required 0, note 3; V30 경합 오탐은 격리 재실행 1/0/0로 해소); 전-모듈 회귀는 closeout Phase B가 재실행 | 2026-07-18 |
| `lane-94b-icon-sync` | OPENSAM-94 중복 스폰 (no-op) | shared workspace (disjoint) | — (해제) | released — 디스크 구현은 lane-94 저작으로 판명, 94b는 무기록 종료 | 2026-07-17 |
| `reviewer-94-sync` | OPENSAM-94 독립 리뷰 (implementer 생존 확인 → read-only 선행, gradle은 94 완료 후) | shared workspace (disjoint) | `docs/loops/opensam-batch3-2026-07-17/reviews/opensam-94-sync-review.md` | completed — **CLEARED**(2026-07-17 22:22, fix-required 0, note 3; 직접 재실행 XML 판독) | 2026-07-18 |
| `lane-map-rtk-series` | 전 RTK 시리즈 지도 비교·보충 리서치 (계약 §13) | shared workspace (disjoint) | — (해제) | completed/released — 리뷰 cleared + note 3건 반영(corroborated 4→2 정정) | 2026-07-17 |
| `lane-map-datafy` | RTK14 헥스맵 데이터화 (사망 오판 정정) | shared workspace (disjoint) | `tools/rtk14/build_rtk14_hexmap.py`; `tools/rtk14/test_build_rtk14_hexmap.py`; `docs/superpowers/research/2026-07-17-rtk14-hexmap-datafication.md` | completed/released — 독립 검증 CLEARED(이중 실행 재현·18/22 byte-exact·회귀 3건 수치 재검) | 2026-07-17 |
| `lane-map-datafy-b` (재배정) | RTK14 헥스맵 독립 검증자 | shared workspace (disjoint) | `docs/loops/opensam-batch3-2026-07-17/reviews/rtk14-hexmap-review.md` | completed/released — 리뷰 문서 영속화 완료, FINAL VERDICT cleared(fix-required 0, note 2) | 2026-07-17 |
| `reviewer-103-spec` | OPENSAM-103 spec 독립 리뷰 | shared workspace (disjoint) | `docs/loops/opensam-batch3-2026-07-17/reviews/opensam-103-spec-review.md` | completed — R1 fix-required → R2 cleared | 2026-07-17 |
| `reviewer-93-dpic` | OPENSAM-93 독립 리뷰 | shared workspace (disjoint) | — (해제) | completed/released — cleared (note 3) | 2026-07-17 |
| `reviewer-103-spec` (재배정) | RTK 시리즈 지도 리서치 독립 검증 | shared workspace (disjoint) | `docs/loops/opensam-batch3-2026-07-17/reviews/rtk-series-map-research-review.md` | completed — cleared (note 3, 반영 완료) | 2026-07-17 |
| `lane-97-fullrun` | ADR-012 전량 초상 크롭 생산(1000명, mfr 0.12) | scratchpad only (repo 무접촉) | repo 파일 소유 없음 — `{SP}/assets-staging/**` 산출 | active — 156×210 HOI4 합성 743/743 완료, 오케스트레이터 아티팩트 검증 일치(치수 0오차·sha·육안 3건, 曹洪 FP FAIL 확인); **u2net-onnx 직결 도구 스왑 승인**(rembg py3.13 설치 불가, 동일 모델 md5 검증); FP 2차 필터 진행 중 | 2026-07-17 |
| `codex-portrait-resize` | 사용자 지시: 얼굴 크롭 제거 + 전체 초상 비율 축소, GitHub/Jira 상태·priority 동기화 | shared workspace (disjoint from lane-94 and scratchpad fullrun) | — (해제) | completed/released — full-frame resize + `contain`, GitHub/Jira sync, 자동·브라우저 QA, 독립 리뷰 CLEAR; branch/commit/push/deploy 없음 | 2026-07-17 |

## Batch 4 lane (2026-07-19, OPENSAM-143…147 사용자 실행 지시)

| Agent | Task | Branch/worktree | Owned files | Status | Updated at |
|---|---|---|---|---|---|
| `codex-batch4-orchestrator` | OPENSAM-143…147 시나리오 정제·매니페스트·생성·파일럿·시드 E2E | `codex/opensam-143-batch4` / `.claude/worktrees/codex-batch4` | `tools/scenario/**`; `.gitignore`; `docs/superpowers/plans/2026-07-19-opensam-143-batch4-implementation-plan.md`; `docs/superpowers/reviews/2026-07-19-opensam-143-batch4-review.md`; `app/game-engine/src/main/kotlin/opensamguk/engine/boot/ScenarioSeedRunner.kt`; `app/game-engine/src/test/kotlin/opensamguk/engine/boot/ScenarioMapSeedIT.kt`; `app/game-engine/src/test/resources/scenario/scenario_3190_test.json` | completed/released — commits `1e7145f2` / `d4623061` locally merged and in integration; Batch 4 commits not pushed to `origin/main`, no PR/deploy/external-tracker mutation; root CQRS branch and user `.codex/config.toml` untouched. | 2026-07-19 |

## CQRS runtime safety lanes (2026-07-18, OPENSAM-116 구현 개시 승인)

| Agent | Task | Branch/worktree | Owned files | Status | Updated at |
|---|---|---|---|---|---|
| `cqrs-hardening-root` | OPENSAM-116 W0→W5 orchestration; current W0 OPENSAM-123/124 | `codex/op-123-cqrs-runtime-baseline` | `.ai/*`; `docs/superpowers/plans/2026-07-18-cqrs-memory-consistency-hardening-plan.md` | active — user approved contract and ownership transfer; source ownership is assigned per ticket after discovery | 2026-07-18 |
| `cqrs-w0-baseline-implementer` | OPENSAM-123 reproducible heap/snapshot/latency harness | shared workspace (disjoint) | — (해제) | completed/released — corrected 3×/3× seed-proxy capture, symlink-safe analyzer, 20/20 tests; production-shape acceptance remains pending | 2026-07-18 |
| `cqrs-w0-contract-implementer` | OPENSAM-124 consistency/failure architecture contract | shared workspace (disjoint) | `docs/superpowers/specs/2026-07-18-cqrs-consistency-failure-contract.md` | completed/released — safe-but-blocked contract; GA-079 requires PHP capture and approved daemon-owned lifecycle | 2026-07-18 |
| `cqrs-w0-baseline-reviewer` | OPENSAM-123 independent implementation/artifact review | shared workspace (disjoint) | — (해제) | completed/released — final PASS/WATCH/APPROVE; strict Scope/Verdict anchors green | 2026-07-18 |
| `cqrs-w0-security-reviewer` | OPENSAM-123 analyze-only confinement adversarial review | shared workspace (disjoint) | — (해제) | completed/released — former HIGH closed; final PASS/WATCH/APPROVE | 2026-07-18 |
| `cqrs-ga079-capture-implementer` | OPENSAM-124 GA-079 PHP bulk reservation capture | shared workspace (disjoint) | — (해제) | completed/released — capture harness handed off; root owns the two-run evidence capture and contract status update | 2026-07-18 |
| `ga079_capture` | OPENSAM-124 GA-079 PHP two-run oracle evidence | `codex/op-123-cqrs-runtime-baseline` | — (해제) | completed/released — two fresh installs byte-identical, cleanup fail-closed, independent review cleared | 2026-07-18 |
| `op123_production_shape` | OPENSAM-123 sanitized manifest validation-only boundary | `codex/op-123-cqrs-runtime-baseline` | — (해제) | completed/released — exact ngGames binding + packaged validation/capture-block regressions cleared; live 3×2 blocked on stopped EC2 evidence and deterministic materializer/approved sanitized restore | 2026-07-18 |
| `op123_local_materializer` | OPENSAM-123 deterministic local surrogate materializer + Docker 3x2 | `codex/op-123-cqrs-runtime-baseline` | — (해제) | completed/released — local 3×2 + provenance fix independently reviewed cleared; no EC2/live/prod claim | 2026-07-19 |
| `op124_lifecycle_model` | OPENSAM-124 approved two-commit child lifecycle model + daemon seam tests | `codex/op-123-cqrs-runtime-baseline` | — (해제) | completed/released — focused lifecycle/guard independently reviewed cleared; activation waits for OPENSAM-43/W3 | 2026-07-19 |

## Batch fences (2026-07-17)

- `completed/released` 행은 batch closeout 이력이며 현재 write ownership을 보유하지 않는다. 재개 시 새 single writer를 등록한다.
- 표에 없는 공유 파일(특히 compose/nginx, 공용 catalog/runtime schema, `.ai/*`)은 사전 조정 후 foundation owner 한 명을 정하기 전까지 read-only로 취급한다.
- GitHub branch/commit/push/PR/deploy는 A4/A5 별도 승인 전에는 실행하지 않는다. 단, **이슈 라벨·마일스톤 쓰기는 사용자 지시(2026-07-17)로 승인** — 코드 외부 상태(브랜치/커밋)는 여전히 unchanged.
- **Jira는 사용자 지시(2026-07-17)로 상태 동기화 승인** — 배치3 티켓 전이 완료(92·93·97·103 → 진행 중, 94 → 검토 중, 증거 코멘트 부착). 완료 전환은 커밋/머지(A4) 후에만. **스프린트는 사용자 결정으로 사용 안 함** — 상태 전이 + 증거 코멘트만으로 보드 운영.
- **배치 그룹핑은 깃헙 이슈 라벨로** (사용자 지시 2026-07-17): jira-mirror 이슈에 `batch-N` 라벨 부착 — `batch-2`(90·91·102·109·113), `batch-3`(92·93·94·97·103) 적용 완료. 새 배치 선정 시 라벨 생성+부착이 표준 절차.
- **로드맵 관리 = 깃헙 마일스톤 + Jira priority** (사용자 지시 2026-07-17, 101개 미러 이슈 전부 배정 완료): M1 v1 안정화·라이브 갭(15,31-34) / M2 게이트웨이·인게임 UX(78-95 트랙) / M3 RTK 콘텐츠 대체(96-106) / M4 비주얼 현대화(112-115) / M5 v2 구현(16-77) / M6 시스템·엔진 진화(107-111). Jira priority: High = v1 라이브 갭 트랙(1-15,31-34) + 전콘 활성 트랙(89-95), Low = 티켓 요약에 [LOW] 표기된 것(99,100,106,110), 나머지 Medium. 새 티켓 생성 시 이 규칙으로 마일스톤·priority를 함께 배정한다.
- OPENSAM-91/91b·102 legal/rights gate와 OPENSAM-113 live A2/A3 gate는 pending이지만, 그 자체로 파일 소유권을 유지하지 않는다.

## 사용 규칙 (요약)

1. **single-writer-per-file**: 한 파일은 한 시점에 한 에이전트만 수정한다. 다른 에이전트 소유 파일은 읽기만.
2. 작업 시작 전 이 표에 행을 추가하고, 종료/중단 시 Status를 갱신·해제한다.
3. 공유 확장점(`CommandWireMapper.kt`, `common/wire/TurnDaemonCommand.kt`, `ChangeRecorder` 채널, `JdbcFlushExecutor`)은 **병렬 소유 금지** — foundation-first, creator-then-consumer 순차.
4. 병렬화는 독립 조사·테스트·리뷰·문서화·disjoint 파일 구현에만.
5. stale 판정: `Updated at`이 오래됐고 해당 branch/worktree에 활동이 없으면(커밋/파일 mtime), 사람 확인 후 행을 해제한다. 죽은 에이전트 워크트리 회수 절차는 `docs/superpowers/SESSION_HANDOFF.md` 이력 참조.
6. 표에 없는 공유 파일 수정은 반드시 사전 조정하고, ownership 행을 갱신한 뒤 시작한다.
