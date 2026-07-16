# Agent Handoff

다음 세션/에이전트가 **대화 기록 없이** 이 파일만으로 재개할 수 있어야 한다. 갱신 시 이전 내용은 교체한다(장기 이력은 `docs/superpowers/SESSION_HANDOFF.md`).

- Updated at: 2026-07-16
- From: Claude Code (Agent OS 활성화 세션 — W2-3 커밋/PR + 백엔드 Sentry + Jira 연결)

## Goal

승인된 합의 계획 `.omc/plans/2026-07-16-agent-os-activation-plan.md`의 실행: Agent OS 활성화 (Wave 0 → 1 → 2 → 3). 딥 인터뷰 스펙 정본: `.omc/specs/deep-interview-opensamguk-ai-work-system.md`.

## Current result

**Wave 0 ✅ · Wave 1 ✅(Claude GHA 실리뷰만 W3-1에서 판정) · Wave 2 ✅ — PR #154 MERGED(2026-07-16 14:31 UTC, 커밋 8건). EC2 prod는 요금 미납 정지로 prod 작업 전면 보류.**
- 커밋: (1) docs/agent+팩 (2) 커맨드+훅 (3) .mcp.json+CI 워크플로 (4) 프론트 Sentry (5) .ai/+리뷰 아티팩트 (6) **백엔드 Sentry 3앱**(ADR-LITE-008) (7) **Jira 연결+atlassian MCP http 마이그레이션**.
- 스모크 원장: `.omc/artifacts/w1-tool-wiring-gate.md` — playwright ✅ · atlassian 인증+티켓(SCRUM-5) ✅ · CodeRabbit 설치 ✅(실리뷰는 W3-1 소형 PR — 이 PR은 57파일>50 스킵) · Claude GHA 채점대기(**main 병합 후** 실행 + 시크릿 ✅ 등록됨) · **Sentry 실증 ✅**(org tekken-75, 서비스명 프로젝트 5개, DSN 로컬 주입, 5/5 전송·회수).

## Decisions already made

`.ai/decisions.md` ADR-LITE-001~008. 008 = 백엔드 Sentry 조기 인출(사용자 "지금 이 PR에 추가" 승인, 에러 캡처 전용·트레이싱 0).

## Verification result

- 3앱 gradle test XML green: 73/0/0 · 394(flake 1건 단독 재실행 green — known-issues에 기록) · 557/0/0(skip 1=기왕 백로그).
- 프론트(커밋 4 시점): tsc 0 error · vitest 3/3·148/148 · next build 양 앱 통과.
- `check.py --strict --base origin/main` → No findings.
- **실행하지 않은 검증**: Sentry 대시보드 실증(DSN 대기) · Claude GHA/CodeRabbit 실리뷰(병합/소형 PR 대기).

## Known failures

- Atlassian OAuth는 **사이트 단위 부여** — 다른 사이트로 승인돼 있으면 "isn't explicitly granted" 거부, `/mcp` 재동의로 해결(온보딩: `docs/agent/tool-capabilities.md`).
- 사용자 URL의 PEPPO-2는 Atlassian **Home** 프로젝트(Jira 아님) — Jira MCP로 접근 불가. Jira 실운영 프로젝트는 사용자가 생성한 **`OPENSAM`**(SCRUM은 스모크용 잔재).
- Testcontainers 컨테이너 기동 flake(다중 스위트 동시 실행 시) — 단독 재실행으로 분별(`known-issues.md`).

## Do not repeat

- 골든/테스트 완화·위조, `.env*` 읽기, 승인 없는 커밋/푸시/머지 (하드 룰).
- 키/토큰/DSN 값을 채팅에 받지 말 것 — 등록 완료 여부만 확인(시크릿은 이름 목록으로 검증).
- gradle 판정을 exit code로 하지 말 것 — 출력 tail + 테스트 XML.

## Remaining work

- ~~PR #154 머지~~ ✅ 완료(2026-07-16) — Claude GHA 워크플로 main 반영(armed, 다음 PR부터 실행). 머지 발화 배포 런은 EC2 정지로 취소(정상 — `known-issues.md`).
- **Wave 3 착수는 사용자 재개 신호 대기** ("요금 납부 후 정지 풀리면 더 하자"). W3-1/W3-2 자체는 EC2 불필요 — 사용자가 원하면 즉시 가능.
- **사용자 액션**: Sentry 사용자 토큰 회전 권장(채팅 노출 이력 — 회전 후 로컬 `.env`의 `SENTRY_AUTH_TOKEN`만 교체). ~~Jira 프로젝트 생성~~ ✅ `OPENSAM` · ~~Sentry 계정+DSN~~ ✅ 배선·실증 완료(2026-07-16).
- **Sentry 잔여 백로그**(`known-issues.md`): prod 클라이언트 빌드 아그 · EC2 prod `.env` DSN 반영 · CI 소스맵 토큰 주입.
- **Wave 3**: W3-1 F4 소형 갭 풀 파이프라인(Jira 티켓 → 구현 → 소형 PR로 이중 리뷰 실판정 → 게이트) → W3-2 DB 인덱스(EXPLAIN ANALYZE 정본, Flyway V29+, CREATE INDEX CONCURRENTLY 비트랜잭션).

## Files to read first

`.ai/current-state.md` → `.ai/decisions.md` → `.omc/artifacts/w1-tool-wiring-gate.md` → `.omc/plans/2026-07-16-agent-os-activation-plan.md`
