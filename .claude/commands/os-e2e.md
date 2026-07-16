# /os-e2e — Playwright E2E 시나리오 생성·실행

$ARGUMENTS: 검증할 사용자 흐름(또는 티켓 키/AC)

전제: 대상 서비스 기동(:3000 gateway / :3001 game 또는 docker compose). 미기동·캡처 불가면 통과 처리 금지 — `채점대기` 보고.

절차 정본: `docs/agent/lifecycle-testing.md` §E2E —
- 시나리오는 Given-When-Then 1개 단위. 티켓 작업이면 Story AC를 그대로 사용.
- Playwright MCP(`browser_navigate`/`browser_snapshot`/`browser_click`/`browser_fill_form`/`browser_network_requests`)로 실행. 판정은 DOM/네트워크 관측 기반.
- `webapp-testing` 스킬과 경계: 재현·패러티 비교는 webapp-testing, AC 판정 회귀는 /os-e2e.
- Playwright MCP는 승인된 테스트 자동화 — CLAUDE.md `/browse` 조항(claude-in-chrome 리서치 브라우징)과 별개.

중단 조건: 서비스 기동 실패 반복(→ 환경 문제로 보고), 시나리오가 UI 밖 로직 검증(→ gradle/골든 게이트로), 같은 실패 3회(→ `/os-debug`).
