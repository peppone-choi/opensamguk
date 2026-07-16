# Tool Capabilities — 실제 사용 가능 여부 (2026-07-16 검증)

"일반적으로 가능"과 "이 프로젝트에 설정됨"을 구분한다. `command -v`로 검증한 결과 기반.

| Capability | Claude Code | Codex | Project configured | Notes |
|---|---|---|---|---|
| File read/write | ✅ | ✅ | ✅ | 공통 |
| Shell | ✅ | ✅ | ✅ | gradle exit code 신뢰 금지(`verification.md`) |
| Git | ✅ | ✅ | ✅ | main push = 자동 배포 — 승인 게이트 대상 |
| GitHub CLI (`gh`) | ✅ | ✅ | ✅ 설치됨 | PR/이슈. deployer 에이전트가 사용 |
| Docker | ✅ | ✅ | ✅ 설치됨 | compose 8서비스, php-golden 캡처, Testcontainers |
| PHP (호스트) | — | — | ❌ NOT_INSTALLED | PHP는 **오직 Docker**(`tools/php-golden/`, php:8.3-cli) |
| JDK 21 | — | — | ✅ temurin-21 | `JAVA_HOME=$(/usr/libexec/java_home -v 21)` 필수 |
| Node/pnpm | ✅ | ✅ | ✅ | web/* 빌드·테스트 |
| Python3 | ✅ | ✅ | ✅ | `tools/agent-system/check.py`, `tools/rtk14/` |
| AWS CLI | ✅ | ✅ | ⚠️ 설치됨, 배포는 미사용 | 배포는 GH Actions/SSH+compose. terraform ❌ NOT_INSTALLED |
| Browser/E2E | ✅ Playwright MCP + claude-in-chrome | ⚠️ 자체 확인 필요 | ⚠️ MCP는 세션 의존 | UI 검증 불가 시 `채점대기` 보고 |
| code-review-graph MCP | ✅ enabled (`settings.local.json`) | ❌ | ✅ | Grep 전에 graph 사용 (`~/CLAUDE.md` 정책) |
| headroom MCP | ⚠️ `.mcp.json` 등록 | ❌ | NEEDS_HUMAN_CONFIRMATION | localhost:8787 — 서버 기동 여부는 세션마다 확인 |
| Jira/Confluence | ⚠️ `.mcp.json` `atlassian`(sse) 선언 | ❌ | ⚠️ 선언됨 — 사용자별 OAuth 필요 | 신규 Atlassian 사이트 생성 후 인증(아래 온보딩). 스모크(티켓 1건 생성·삭제) 전까지 `채점대기` |
| Sentry | ⚠️ `.mcp.json` `sentry`(http) 선언 | ❌ | ⚠️ 선언됨 — 계정·DSN·OAuth 필요 | W1-5에서 `@sentry/nextjs` 배선 예정. 그 전 관측은 docker logs + prod DB 쿼리 + health |
| Terraform | ❌ | ❌ | ❌ NOT_INSTALLED | IaC 없음 — compose + GH Actions가 배포 정본 |
| Claude 전용: subagents/skills/workflows | ✅ `.claude/agents,skills,workflows` | ❌ | ✅ | Codex는 `.codex/agents/*.toml` + `docs/agent/` Runbook으로 동일 절차 수행 |
| Codex 전용: `.codex/config.toml` | ❌ | ✅ | ✅ | 6개 패러티 에이전트 toml 존재 |
| Hooks | ✅ 활성 (`.claude/settings.json`) | 수동 실행 | ✅ 활성 (ADR-LITE-005) | 세션 시작 시 스냅샷 — 설정 변경은 다음 세션부터. `@`멘션 우회는 `.claudeignore` 담당 |
| Playwright MCP | ✅ `.mcp.json` `playwright`(stdio) | ❌ | ✅ 완전 재현 (npx, OAuth 불필요) | `/os-e2e` 테스트 자동화용. `/browse`(리서치 브라우징)와 별개 |

## MCP 온보딩 (fresh clone / 신규 사용자)

`.mcp.json`은 커밋된 **선언**이다(토큰 무포함, ADR-LITE-007). 재현성 주장은 선언 수준까지 — 원격 2종은 사용자별 대화형 인증이 추가로 필요하다.

1. **playwright** (stdio) — 추가 절차 없음. 첫 사용 시 `npx -y @playwright/mcp@latest`가 자동 설치·기동. 완전 재현.
2. **atlassian** (sse, `https://mcp.atlassian.com/v1/sse`) — 세션에서 첫 호출 시 브라우저 OAuth 창이 뜬다. 본인 Atlassian 계정으로 승인 → 사이트 선택. 기존 사이트가 `suspended-inactivity`(403)이면 신규 사이트를 만들고 그쪽으로 승인한다. 인증 확인 = `getAccessibleAtlassianResources` 실호출 1회(목록에 뜨는 것만으로는 false-green).
3. **sentry** (http, `https://mcp.sentry.dev/mcp`) — Sentry 계정 + 프로젝트(web/gateway·web/game) 선행. 첫 호출 시 OAuth 승인. DSN은 앱 env로만 주입(`.env*` — 절대 커밋·채팅 금지).
4. **headroom** (http, localhost:8787) — 로컬 서버 기동 시에만 응답. 세션마다 확인.

## 사용 규칙

- 여기 ❌/NOT_CONFIGURED인 도구를 "사용 가능"으로 전제한 계획을 세우지 않는다. 필요하면 `NEEDS_HUMAN_CONFIRMATION`으로 사람에게 올린다.
- MCP는 "등록됨 ≠ 기동됨 ≠ 자동 실행 허용됨" — 사용 전 실제 응답으로 확인한다. 원격 MCP는 **선언 확인이 아니라 인증 완료(실호출 1회)** 기준으로 판정한다.
- 이 표의 검증일이 오래되면(도구 추가/제거 후) 재검증하고 날짜를 갱신한다.
