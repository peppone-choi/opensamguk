# Lifecycle Runbook: Planning (기획)

## Status

**ACTIVE** — 이 저장소의 기획은 로컬 Markdown으로 실동작 중: 페이즈별 `spec → plan → adversarial review → execute → gate` 사이클(`docs/superpowers/specs|plans|research/`), 루프 원장(`docs/loops/*/LEDGER.md`), v2 기획 수렴 루프. **Jira는 `.mcp.json` `atlassian` MCP로 인증·스모크 완료**(사이트 pepponechoi-jira, 2026-07-16 — 온보딩·재동의 절차는 `tool-capabilities.md`), **티켓 대상 프로젝트는 `OPENSAM`**("오픈삼국" — SCRUM은 스모크용 잔재) — 티켓 분해는 아래 `/os-plan-tickets` 절차를 쓴다. 로컬 Markdown(plans/LEDGER)이 여전히 계획 **정본**이고, Jira는 추적·AC 체크리스트 표면이다.

## Read This When

새 기능/페이즈/루프를 시작할 때, 요구사항이 모호할 때, v2 콘텐츠 제안을 다룰 때.

## Preconditions

- `.ai/task.md` 확인 — 이미 활성 계약이 있으면 새 기획은 별도 승인 대상.
- v2 관련이면 `docs/loops/v2-planning-2026-07-12/LEDGER.md`의 채택/보류 목록 준수.

## Inputs

문제 정의(사용자 보고, prod 관측, LEDGER 백로그), 승인 ADR/spec와 현재 구현 근거. PHP path+line은 명시적인 역사/동결 회귀 유지보수일 때만 필수.

## Procedure

1. **문제 정의** → 사용자 가치 한 줄.
2. **범위/비범위 명시** — "만들지 않을 것"과 의도적 제외 사유를 반드시 적는다(정렬 앵커).
3. **수용 기준** — 테스트 가능하게(Given-When-Then 권장; 명시적 동결 회귀 유지보수는 "골든 draw-for-draw green"이 AC).
4. **위험/제약** — 결정론·동결 회귀·수치/로그 변경 의도·one-daemon-write·배포 영향 검토.
5. **Task 분해** — 구현 가능한 단위(가설 1개 = 바퀴 1개 원칙). 공유 확장점은 foundation-first로 선행 태스크 분리.
6. **사람 승인** — 계획을 `docs/superpowers/plans/`(페이즈급) 또는 루프 LEDGER(바퀴급)에 기록하고 승인받는다. 승인 결과는 `.ai/decisions.md`(중대 결정) 또는 `.ai/task.md` 갱신.
7. 비자명 계획은 **adversarial review**(독립 에이전트 비판)를 실행 전에 받는다.

## Ticket Decomposition — `/os-plan-tickets` 절차 정본

PRD/스펙/계획 문서를 Jira Epic/Story/Sub-task로 변환한다. 프롬프트 정본: `docs/agent/prompt-pack.md` "기획 티켓 분해" 팩.

1. **입력 확정** — 대상 문서(스펙/계획/LEDGER 백로그 항목) 경로를 받는다. 문서 없이 구두 요구만 있으면 먼저 위 Procedure 1~4(문제 정의→AC)를 수행해 문서화한다.
2. **분해 규칙** —
   - **Epic** = 페이즈/기능군 1개 (예: "F4 액션 페이지 mutation").
   - **Story** = 사용자 가치 단위, **Given-When-Then AC 필수** (명시적 동결 회귀 유지보수는 "골든 draw-for-draw green"이 AC).
   - **Sub-task** = 구현 단위(가설 1개 = 바퀴 1개 원칙과 정렬). 공유 확장점은 foundation-first 선행 Sub-task로 분리.
3. **AC 6단계 추적** — 각 Story의 AC를 ①정의(Given-When-Then) ②티켓 기입 ③구현 ④검증(행렬 실행) ⑤PR 연동(본문에 `Closes <티켓키>` 또는 티켓 URL) ⑥종결(검증 증거 코멘트 후 Done 전이)로 추적한다. ④의 증거 없이 ⑥으로 건너뛰지 않는다.
4. **MCP 호출** — `getVisibleJiraProjects`로 프로젝트 확인 → `createJiraIssue`(Epic→Story→Sub-task 순, 부모 링크) → 생성된 키 목록을 계획 문서/LEDGER에 역기입.
5. **스모크(최초 1회)** — 스로어웨이 티켓 1건 생성·삭제로 인증·권한을 실증. 실패( 403 suspended 등)면 `채점대기`로 보고하고 로컬 Markdown 단독으로 계속한다.

## Tools / Commands

로컬 Markdown + git (계획 정본) · `atlassian` MCP (`.mcp.json` 선언, OAuth 후 사용 — 티켓 표면).

## Human Approval Gates

계획 채택, 범위 변경, 의존성 추가, 비가역 아키텍처 결정, v2 보류 콘텐츠 재개.

## Verification

계획 문서에 AC·검증 명령(`verification.md` 행렬 참조)과 승인 ADR/spec·현재 구현 근거가 명시됐는지 확인한다. 명시적 역사 비교라면 PHP path+line도 확인한다.

## Failure Handling

요구사항이 확정 불가하면 구현하지 않고 질문 목록과 함께 사람에게 반환.

## Completion Criteria

승인된 계획 문서 + `.ai/task.md` 갱신.

## State Files to Update

`.ai/task.md`(사람), `.ai/current-state.md`, 해당 LEDGER.

## Handoff Requirements

계획 문서 경로와 승인 상태를 `.ai/handoff.md`에 기록.
