# Agent Handoff

다음 세션/에이전트가 **대화 기록 없이** 이 파일만으로 재개할 수 있어야 한다. 갱신 시 이전 내용은 교체한다(장기 이력은 `docs/superpowers/SESSION_HANDOFF.md`).

- Updated at: 2026-07-18
- From: Claude Code (`batch3-closeout` — batch-3 마무리 세션, 사용자 승인 하 진행)

## Goal

batch-3(OPENSAM-92·93·94·97·103 + §13 지도)의 **closeout**: 원장 정합화 → 최종 검증(Phase B) → 티켓별 커밋 분할안 제시 → **A4(커밋/push/PR) 사람 승인 대기에서 정지**. 이후 다음 5티켓 선정(standing directive "티켓 5개씩").

## Current result

- 전 레인 구현 + 독립 리뷰 **cleared** (7건: `docs/loops/opensam-batch3-2026-07-17/reviews/`). 94 리뷰는 2026-07-17 22:22 CLEARED가 최종.
- 산출물 전량 미커밋: 수정 33파일 + 신규 ~25파일, 브랜치 `codex/full-frame-portrait-resize`(push됨, last commit `759f00b4` = OPENSAM-97 전체 프레임 축소).
- 원장 정합화(Phase A) 완료: ownership에 `batch3-closeout` 등록 + 94 두 레인 completed 전환, current-state 갱신.

## Decisions already made

`.ai/decisions.md` ADR-LITE-001~012. 최근: 010(v2 콘텐츠 RTK 대체), 011(에셋 AI 생성 + UI 현대화), 012(코에이 IP 게이트 전면 해제 + 에셋 별도 공개 repo/CDN — 메인 repo 바이너리 미커밋 유지).

## Verification plan (Phase B — 커밋 전 필수)

- `tools/parity/gate.sh backend` — **XML `failures="0" errors="0"` + BUILD SUCCESSFUL로 판정, exit code 불신**.
- `cd web/gateway && pnpm typecheck && pnpm test` · `cd web/game && pnpm typecheck && pnpm test`.
- `tools/agent-system/check.py --strict --base origin/main`.

## Known failures / cautions

- Testcontainers 다중 스위트 동시 실행 시 컨테이너 기동 flake — 단독 재실행으로 분별(`known-issues.md`). 94 리뷰의 V30 "경합 오탐"도 동일 클래스.
- **EC2 prod 요금 미납 정지** — A5(배포) 전 해제 확인 필수.
- 93 라이브 반영 시 repo-밖 compose에 `:ro` 볼륨 라인 수동 추가 필요.
- 문서-실상 불일치: 직전 원장 "branch=main·외부 frozen" vs 실제 codex 브랜치 push됨 — push 경위 사용자 확인 대기(current-state Open question ③).

## Do not repeat

- 골든/테스트 완화·위조, `.env*` 읽기, **승인 없는 커밋/푸시/머지/배포** (하드 룰 — A4/A5는 명시 승인만).
- gradle 판정을 exit code로 하지 말 것 — 출력 tail + 테스트 XML.
- `.ai/*`는 single writer(`batch3-closeout`)만 수정.

## Remaining work

1. Phase B 검증 실행 → 결과 보고.
2. A4 승인 대기: 커밋 분할안 6건 — ①91a(gateway-api profile icon + V30 + infra User*) ②92(web/gateway account UI + proxy + tests) ③93(nginx + compose + 웹 2앱 portrait helper) ④94(wire/dispatcher/ChangeRecorder/flush/controller/handler + IT) ⑤§13 헥스맵(tools/rtk14) ⑥docs/원장. base=main PR까지, 머지(=자동 배포)는 A5 별도.
3. 미결: `.codex/config.toml` `max_threads` 제거 diff 포함 여부(사용자 지시 대기) · lane-97-fullrun(1000명 크롭, FP 2차 필터 중단점) 인수 여부 · EC2 해제.
4. closeout 후 다음 5티켓 선정(마일스톤 M1/M2 High 우선, `ownership.md` Batch fences의 라벨·priority 규칙).

## Files to read first

`.ai/current-state.md` → `.ai/ownership.md` → `docs/superpowers/plans/2026-07-17-opensam-92-93-94-97-103-execution-contract.md` → `docs/loops/opensam-batch3-2026-07-17/reviews/`
