# /os-plan-tickets — 스펙 → Jira Epic/Story/Sub-task 분해

$ARGUMENTS: 대상 문서 경로(스펙/계획/LEDGER 백로그) [+ Jira 프로젝트 키]

전제: `atlassian` MCP 인증 완료(`docs/agent/tool-capabilities.md` 온보딩). 미인증/403이면 `채점대기` 보고 후 로컬 Markdown 단독 진행.

`docs/agent/prompt-pack.md`의 "기획 티켓 분해" 프롬프트를 수행한다:
- 절차 정본: `docs/agent/lifecycle-planning.md` §Ticket Decomposition — Epic(페이즈) / Story(사용자 가치 + **Given-When-Then AC 필수**) / Sub-task(구현 단위, foundation-first 선행 분리).
- AC 6단계 추적: 정의 → 티켓 기입 → 구현 → 검증 → PR `Closes <키>` → 검증 증거 코멘트 후 종결.
- 생성된 티켓 키를 계획 문서/LEDGER에 역기입. 로컬 Markdown이 계획 정본, Jira는 추적 표면.

중단 조건: 대상 문서에 AC가 없음(→ 먼저 lifecycle-planning Procedure 1~4로 문서화), 티켓 대량 재작성 필요(→ 사람 승인), MCP 인증 실패(→ `채점대기`).
