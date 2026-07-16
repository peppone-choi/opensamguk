# Lifecycle Runbook: Testing (검증)

## Status

**ACTIVE** — gradle 게이트(`tools/parity/gate.sh`), 골든 리플레이 테스트, pnpm typecheck/test, Playwright 브라우저 검증이 전부 실동작 중.

## Read This When

코드를 변경했을 때, 완료를 선언하기 전, 테스트가 실패했을 때.

## Preconditions

JDK 21 (`JAVA_HOME=$(/usr/libexec/java_home -v 21)`), repo root에서 실행. 통합 테스트는 Docker 필요(없으면 skip — fail 아님).

## Inputs

현재 diff (`git diff --stat`), 변경 유형 분류.

## Procedure

```text
변경 분류 (verification.md 행렬)
→ 가장 작은 관련 테스트 (타깃 GoldenTest / 모듈 테스트)
→ 정적 검사 (pnpm typecheck / agent-system check.py)
→ 통합 테스트 (infra/engine/api — Testcontainers)
→ 필요 시 광역 게이트 (tools/parity/gate.sh backend)
→ UI 변경 시 브라우저 검증 (webapp-testing / Playwright — 불가하면 '채점대기')
→ 결과 기록 (실행/미실행 구분, XML 인용)
```

## Tools / Commands

`verification.md`의 행렬이 정본. 대표:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --rerun-tasks 2>&1 | tail -40
tools/parity/gate.sh backend   # logic|engine|api 협대역 변형 있음
cd web/game && pnpm typecheck && pnpm test
scripts/agent/verify-changes.sh   # diff 분류 → 최소 검증 안내/실행
```

## E2E — `/os-e2e` 절차 정본 (Playwright MCP)

UI 흐름을 실브라우저로 검증한다. `.mcp.json`의 `playwright`(stdio) 사용 — npx 자동 설치, OAuth 불필요.

1. **시나리오 정의** — 검증할 사용자 흐름 1개를 Given-When-Then으로 적는다 (예: "로그인된 유저가 커맨드 예약 → 예약 목록에 표시"). 티켓 작업이면 Story의 AC를 그대로 시나리오로 쓴다.
2. **환경 확인** — 대상 서비스 기동 여부(`docker compose ps` 또는 dev 서버 :3000/:3001). 미기동이면 기동하거나 `채점대기`.
3. **실행** — `browser_navigate` → `browser_snapshot`(관측) → `browser_click`/`browser_type`/`browser_fill_form`(행동) → `browser_snapshot`/`browser_network_requests`(판정). 판정은 DOM/네트워크 관측 기반 — 스크린샷 육안 단독 판정 금지(`context-strategy.md` 유형 3).
4. **기록** — 시나리오·단계·관측 증거(스냅샷 요약, 요청/응답 코드)를 결과에 인용. 실행 못 한 시나리오는 통과가 아니라 `채점대기`.

**역할 경계**: `webapp-testing` 스킬 = 탐색적 UI 재현·패러티 비교(스크린샷 중심). `/os-e2e` = 시나리오 고정 회귀 검증(AC 판정 중심). 재현이 목적이면 webapp-testing, 판정이 목적이면 /os-e2e.

**거버넌스 주석**: CLAUDE.md의 "모든 웹 브라우징은 `/browse`" 조항은 `mcp__claude-in-chrome__*`(리서치 브라우징)을 겨냥한다. `/os-e2e`의 Playwright MCP는 승인된 **테스트 자동화**로 별개다 — 이 조항 위반이 아니다.

## Human Approval Gates

없음(검증 자체는 자유) — 단 **테스트 삭제/약화는 승인 대상이 아니라 금지**다.

## Verification (판정 규칙)

exit code 금지 — `BUILD SUCCESSFUL` + 테스트 XML `failures="0" errors="0"`. UP-TO-DATE 의심 시 `--rerun-tasks`.

## 금지 목록

- 실패 테스트 삭제 · assertion 약화 · skip으로 CI 통과 · 골든 수정으로 통과.
- 실행하지 않은 검증(특히 E2E)을 통과로 기록.
- 환경 오류(Docker 미가동 skip, Testcontainers flake)를 제품 성공/실패로 오해 — flake는 단독 재실행으로 분별.

## Failure Handling

1. 실패를 숨기지 않고 원문 인용. 2. `failure-cases.md` 대조. 3. 골든 불일치면 Kotlin 구현 수정(골든 불변). 4. 원인 불명이면 `lifecycle-review.md`가 아니라 디버깅 절차(`/os-debug` 커맨드, 가설 3개)로.

## Completion Criteria

행렬상 최소 검증 전부 green + 증거 인용 가능.

## State Files to Update

`.ai/current-state.md`(Verification run/result).

## Handoff Requirements

실행한/안 한 검증 목록과 결과를 handoff에 구분 기재.
