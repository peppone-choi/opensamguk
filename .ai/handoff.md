# Agent Handoff

다음 세션/에이전트가 **대화 기록 없이** 이 파일만으로 재개할 수 있어야 한다. 갱신 시 이전 내용은 교체한다(장기 이력은 `docs/superpowers/SESSION_HANDOFF.md`).

- Updated at: 2026-07-16
- From: Claude Code (Agent OS 활성화 세션 — Wave 0 완료)

## Goal

승인된 합의 계획 `.omc/plans/2026-07-16-agent-os-activation-plan.md`의 실행: Agent OS 활성화 (Wave 0 기반 정비 → Wave 1 도구 배선 → Wave 2 검증·커밋 → Wave 3 시연 2건). 딥 인터뷰 스펙 정본: `.omc/specs/deep-interview-opensamguk-ai-work-system.md`.

## Current result

**Wave 0 완료 + Wave 1 저작·검증 완료(외부 스모크만 채점대기) + Wave 2 W2-1/W2-2 완료.**
- Wave 0: W0-1 `/os-*` 개명(게이트 PASS, `.omc/artifacts/w0-1-rename-gate.md`) · W0-3 `.claudeignore` · W0-4 건초더미 3전략 매핑 · W0-5 프롬프트 팩 10종 · W0-6 헌법 포인터 · W0-2 훅 실활성화(ADR-LITE-005, 실사격 5케이스).
- Wave 1 (원장: `.omc/artifacts/w1-tool-wiring-gate.md`): W1-1 `.mcp.json` un-ignore+4서버+토큰스캔 가드레일(ADR-LITE-007, 실사격 6케이스) · W1-2 `/os-plan-tickets` · W1-3 `/os-e2e`+**Playwright 스모크 PASS** · W1-4 `claude_review.yml`(파리티 한국어 프롬프트)+`.coderabbit.yaml` · W1-5 Sentry SDK 양 앱(typecheck 0·vitest 3/3·148/148·next build 양 앱 통과).
- Wave 2: W2-1 검증 루프 3종 표(`docs/agent/verification.md`) · W2-2 리뷰 아티팩트(`docs/superpowers/reviews/2026-07-16-agent-os-activation.md`, Verdict: cleared) + `check.py --strict --base origin/main` **그린**.

## Decisions already made

`.ai/decisions.md` ADR-LITE-001~006. 005(훅 실활성) · 006(/os-* 개명)이 이번 세션 추가, 003은 superseded. 사용자가 합의 계획 **전체 승인** ("전체 승인. 인간 체크리스트는 하나하나 물어봐.", 2026-07-16).

## Files changed

`git status --short`로 확인 (커밋은 Wave 2-3에서 단일 PR 커밋 계획으로 인간 승인 후 — 이 세션은 커밋하지 않음).

## Commands executed

훅 프로토콜(stdin JSON) 실사격 5케이스 — 전부 기대 일치: `.env.hooktest` Write→exit 2 차단 · 골든 Edit→exit 2 차단 · legacy Read→exit 0 허용 · 일반 Write→exit 0 · verify-changes.sh 훅 모드→변경 매트릭스 출력.

## Verification result

- Wave 0 종료 게이트: `.claude/settings.json` VALID JSON · os-* 커맨드 7종 존재 · 구 커맨드명 잔재 0건 · "example 전용" stale 문구 3곳 정리(CLAUDE.md·AGENTS.md·이 파일) · 실사격 잔여물 없음.
- **실행하지 않은 검증**: 훅 세션-등록 end-to-end (훅은 세션 시작 시 스냅샷 → **다음 세션 시작 시** `.env` Read 시도가 차단되는지 확인할 것). gradle/pnpm (제품 코드 무변경).

## Known failures

- omx state machine: `notify-fallback-watcher`가 상태 파일을 재덮어씀 → deep-interview→ralplan 전환 불가. 상태 머신 우회로 해결, 계획 헤더에 deviation 기록. OMC 버그 리포트 후속 대상.
- Atlassian 기존 사이트 suspended-inactivity(403) → 사용자가 신규 사이트 생성 중.

## Do not repeat

- 골든/테스트 완화·위조, `.env*` 읽기, 승인 없는 커밋 (하드 룰).
- `state_write(mode="ralplan")` 재시도 (watcher가 계속 덮어씀 — 우회 확정).
- parity-ship SKILL.md의 `/review` 참조는 개명 대상 아님 (버킷② 보존, 모호성 1건은 게이트 원장에 기록됨).

## Remaining work

- **외부 스모크 4건** (인간 체크리스트 완료 시 해제, `.omc/artifacts/w1-tool-wiring-gate.md` 해제 조건 표): atlassian OAuth+티켓 스모크 · Sentry DSN+대시보드 실증 · Claude GHA PR 코멘트 · CodeRabbit PR 코멘트(또는 fallback ADR).
- **W2-3**: 단일 브랜치/PR 커밋 5건 — **인간 승인 대기** (분할안은 handoff 하단): (1) docs/agent/+팩 (2) 커맨드+훅+.claudeignore (3) .gitignore+.mcp.json+CI 워크플로 (4) Sentry SDK (5) .ai/+리뷰 아티팩트. 단일 PR 원칙 — strict 게이트가 리뷰 아티팩트를 같은 변경 집합에서 봐야 함.
- **Wave 3**: W3-1 F4 소형 갭 풀 파이프라인(핸드오프 포맷 드라이런 선행 — Jira·PR 리뷰 스모크 해제 후) → W3-2 DB 인덱스(EXPLAIN ANALYZE 정본, Flyway V29+, CREATE INDEX CONCURRENTLY 비트랜잭션).

## Required human decisions (Wave 1 차단 항목)

① Atlassian 신규 사이트 생성 완료 여부 · ② Sentry 계정 + 프로젝트 2개(DSN) + AUTH_TOKEN · ③ CodeRabbit 무료 체험 설치 · ④ ANTHROPIC_API_KEY 발급 + GitHub secret 등록. **키 값은 채팅에 붙이지 말고 완료 여부만 알릴 것.**

## Files to read first

`.omc/plans/2026-07-16-agent-os-activation-plan.md` → `.ai/decisions.md` → `.ai/current-state.md` → `.omc/artifacts/w0-1-rename-gate.md`
