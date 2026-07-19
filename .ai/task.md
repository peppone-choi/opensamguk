# Current Task

- Status: active
- Updated at: 2026-07-19
- Seeded by: 사용자 인터뷰(2026-07-16) — "현재 루프 반영" 선택. 내용 변경은 사람이 한다.

## Goal

세 갈래가 병행 중이다. **현재 선택된 active implementation은 3번 CQRS runtime safety**다.

1. **live-gap-closure 루프** (`docs/loops/live-gap-closure-2026-07-10/LEDGER.md` = 정본 원장)
   - 라이브 서버(sam.peppone.dev)에서 무동작·PHP 불일치인 명령/UI/월간 훅을 바퀴 단위로 폐쇄.
   - 바퀴 10까지 채택 완료. 백로그 최상단: `ProcessTournament.resolveMatch()`의 PHP `fight()`(에너지 기반 RNG 전투 심) 풀 포트 + 골든 캡처.
2. **v2 구현 준비** (`docs/loops/v2-planning-2026-07-12/LEDGER.md`, round-2 adopted)
   - v2 기획 수렴 완료(작전·회의·원군·replay 우선, 실시간 대형 전장). V2-0A 격리 후 G0→0B, C0→C1..C5 순서 고정.
   - 최근 커밋 `ab69a7f6`이 "reviewed v2 foundation" 보존 — 구현(V2-1)은 아직 시작 전.
3. **CQRS runtime safety** (`OPENSAM-116`, 계획 정본 `docs/superpowers/plans/2026-07-18-cqrs-memory-consistency-hardening-plan.md`)
   - bounded hot/cold memory, end-to-end `world_id`, generation-safe flush, writer fencing/CAS, durable command inbox/outbox, versioned primary reads를 W0→W5 순서로 구현한다.
   - 현재 시작점: 승인된 foundation-unblock 순서의 B0 `OPENSAM-148` canonical world identity는 로컬 구현·독립 리뷰까지 완료했다. 다음 B1 `OPENSAM-126` scoped schema는 이 foundation을 land한 뒤 시작하며, W0 live-capacity/W3 증거는 activation/cutover gate로 유지한다.

## User value

- v1: 라이브 유저가 누르는 버튼이 전부 실동작 + PHP 원작과 동일 결과.
- v2: 커맨드 나열이 아닌 "작전 한 장면"이 성립하는 새 콘텐츠.
- CQRS: 데이터가 늘어도 OOM 위험이 bounded되고, 접수된 명령을 잃지 않으며, 정합성이 필요한 read가 committed version을 보장한다.

## In scope

- live-gap LEDGER 백로그 항목(가설 1개 = 바퀴 1개), v2 채택안의 승인된 구현 순서, OPENSAM-116의 승인된 W0→W5 순서.

## Out of scope

- 패러티 골든/테스트 약화, `legacy/` 커밋, 운영 DB 파괴적 변경(별도 승인), v2 보류 콘텐츠(일일퀘스트·무작위 전리품·과금 능력치·장식 3D·runtime LLM).

## Acceptance criteria

- 각 바퀴: 가설 → 채점기(게이트/PHP oracle/prod 관측) → 채택/원복이 LEDGER에 기록.
- 백엔드: `tools/parity/gate.sh backend` green(XML `failures="0" errors="0"` 확인).
- 프론트: 해당 앱 `pnpm typecheck`(+ web/game은 `pnpm test`) green.
- 비자명 작업: 독립 에이전트의 cross-agent critique가 `cleared`.
- CQRS build-only: identity → S2 → S3 → S4 순서와 각 Jira GWT/completion predicate를 충족한다. W0 production-shaped baseline과 W3 binding은 second-world admission·activation·cutover 전 별도 gate로 충족한다.

## Constraints

- `CLAUDE.md` 패러티 규율 6조 + one-daemon-write rule. PHP가 모든 divergence에서 이긴다.

## Allowed files

- 루프 범위 내 소스/테스트/문서. 공유 확장점(`CommandWireMapper.kt`, `TurnDaemonCommand.kt` 등)은 병렬 작업 시 sequential(creator-then-consumer).

## Protected files

- `logic/**/resources/golden/**`, `common/**/resources/golden/**`(골든 수정 금지 — 캡처로만 갱신), `.env*`, `legacy/**`.

## Required verification

- `docs/agent/verification.md`의 변경 유형별 최소 검증 행렬.

## Human approval checkpoints

- main push(=자동 배포), prod 재시드/DB 파괴적 변경, 의존성 추가, 골든 격리(quarantine) 판정, 정책 문서(CLAUDE/AGENTS/이 파일) 변경, read replica 생성, 두 번째 world admission/cutover point-of-no-return.
