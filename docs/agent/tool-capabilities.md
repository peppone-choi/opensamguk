# Tool Capabilities — 실제 사용 가능 여부 (2026-07-16 검증)

"일반적으로 가능"과 "이 프로젝트에 설정됨"을 구분한다. `command -v`로 검증한 결과 기반.

| Capability | Claude Code | Codex | Project configured | Notes |
|---|---|---|---|---|
| File read/write | ✅ | ✅ | ✅ | 공통 |
| Shell | ✅ | ✅ | ✅ | gradle exit code 신뢰 금지(`verification.md`) |
| Git | ✅ | ✅ | ✅ | main push = 자동 배포 — 승인 게이트 대상 |
| GitHub CLI (`gh`) | ✅ | ✅ | ✅ 설치됨 | PR/이슈. deployer 에이전트가 사용 |
| Docker | ✅ | ✅ | ✅ 설치됨 | compose 9서비스, php-golden 캡처, Testcontainers |
| PHP (호스트) | — | — | ❌ NOT_INSTALLED | PHP는 **오직 Docker**(`tools/php-golden/`, php:8.3-cli) |
| JDK 21 | — | — | ✅ temurin-21 | `JAVA_HOME=$(/usr/libexec/java_home -v 21)` 필수 |
| Node/pnpm | ✅ | ✅ | ✅ | web/* 빌드·테스트 |
| Python3 | ✅ | ✅ | ✅ | `tools/agent-system/check.py`, `tools/rtk14/` |
| AWS CLI | ✅ | ✅ | ⚠️ 설치됨, 배포는 미사용 | 배포는 GH Actions/SSH+compose. terraform ❌ NOT_INSTALLED |
| Browser/E2E | ✅ Playwright MCP + claude-in-chrome | ⚠️ Playwright MCP 선언, 실호출 전 미검증 | ⚠️ MCP는 세션 의존 | Codex는 `.codex/config.toml`에 등록됐지만 실제 기동·호출 성공을 따로 확인한다. UI 검증 불가 시 `채점대기` 보고 |
| code-review-graph MCP | ✅ enabled (`settings.local.json`) | ❌ | ✅ | Grep 전에 graph 사용 (`~/CLAUDE.md` 정책) |
| headroom MCP | ⚠️ `.mcp.json` 등록 | ⚠️ `.codex/config.toml` 등록 | NEEDS_HUMAN_CONFIRMATION | localhost:8787 — 선언과 별개로 서버 기동 여부를 세션마다 확인 |
| Jira/Confluence | ✅ `.mcp.json` `atlassian`(http `/v1/mcp`) | ⚠️ `.codex/config.toml` 선언, Codex 인증 미확인 | Claude 실호출 ✅ / Codex NEEDS_HUMAN_CONFIRMATION | Claude에서는 사이트 pepponechoi-jira 인증 실호출 + 티켓 스모크 PASS(SCRUM-5 생성→완료 전환). **실운영 프로젝트 = `OPENSAM`("오픈삼국", id 10001)** — `/os-plan-tickets` 대상, create 권한·이슈타입(에픽/스토리/작업/기능/버그/하위 작업) MCP 실확인(2026-07-16). SCRUM은 스모크용 잔재. Codex는 별도 OAuth 및 실호출이 필요하며 Claude 인증을 재사용한다고 간주하지 않는다. 구 SSE 엔드포인트는 지원 종료 예고로 마이그레이션 완료 |
| Sentry | ✅ REST API(사용자 토큰, 로컬 `.env`) + `.mcp.json` `sentry`(http) 선언 | ⚠️ `.codex/config.toml` 선언, Codex 인증 미확인 | Claude REST 실증 ✅ / Codex NEEDS_HUMAN_CONFIRMATION | org `tekken-75`, 프로젝트 5개를 서비스명으로 rename(`gateway-api`/`game-api`/`game-engine`/`web-gateway`/`web-game`). DSN은 로컬 `.env`(백엔드 3, compose 매핑) + 각 앱 `.env.local`(프론트 2)에 주입되며 전부 git-ignored. 기존 스모크는 5/5 이벤트 전송→API 회수 CONFIRMED. 에이전트는 `.env*`를 읽거나 가드를 우회해 수정하지 않으며, 로컬 비밀 설정은 사람이 관리한다. Codex MCP OAuth·REST 인증은 별도로 확인한다 |
| Terraform | ❌ | ❌ | ❌ NOT_INSTALLED | IaC 없음 — compose + GH Actions가 배포 정본 |
| Provider 에이전트/스킬 | ✅ `.claude/agents,skills,workflows` | ✅ `.codex/agents/*.toml` 7개 + tracked `.agents/skills/os-*` | ✅ | Codex의 `$os-*`는 Claude `/os-*`와 같은 `docs/agent/` Runbook을 소비한다 |
| Codex 전용: `.codex/config.toml` | ❌ | ✅ | ✅ | 7개 Codex 에이전트, `headroom`/`playwright`/`atlassian`/`sentry` MCP, hooks·multi-agent 활성 선언 |
| Hooks | ✅ 활성 (`.claude/settings.json`) | ⚠️ `.codex/hooks.json` 선언 | ✅ 양쪽 설정 존재 | Codex는 `SessionStart`(무결성 검사+스킬 복원), `PreToolUse`(apply_patch + 지원되는 단순 Bash의 best-effort 가드), `PostToolUse`(검증 매트릭스)를 등록한다. 모든 shell 호출을 가로채는 보안 경계는 아니다. 프로젝트 trust 후 검토·승인하고 reload한 뒤 활성으로 판정한다. Claude의 `@`멘션 우회는 `.claudeignore` 담당 |
| Playwright MCP | ✅ `.mcp.json` `playwright`(stdio) | ⚠️ `.codex/config.toml` `playwright`(stdio) | Claude 실호출 ✅ / Codex 미검증 | `/os-e2e`/`$os-e2e` 테스트 자동화용. OAuth는 불필요하지만 Codex에서 `npx` 설치·기동·실호출을 확인하기 전에는 사용 가능으로 단정하지 않는다. `/browse`(리서치 브라우징)와 별개 |

## MCP 온보딩 (fresh clone / 신규 사용자)

`.mcp.json`(Claude)/`.codex/config.toml`(Codex)은 커밋된 **선언**이다(토큰 무포함, ADR-LITE-007). 재현성 주장은 선언 수준까지이며, 등록은 기동·인증·실호출 성공을 의미하지 않는다. 동일 서버라도 Claude의 인증 상태는 Codex 인증을 대신하지 않는다.

1. **playwright** (stdio) — 첫 사용 시 `npx -y @playwright/mcp@latest`가 설치·기동하므로 네트워크가 필요하다. Claude의 기존 실증과 별개로 Codex는 첫 실호출 전까지 미검증으로 다룬다.
2. **atlassian** (http, `https://mcp.atlassian.com/v1/mcp` — 구 SSE `/v1/sse`는 2026-06-30 이후 지원 종료 예고, 마이그레이션 완료) — 각 provider 세션의 첫 호출 시(또는 각 provider의 MCP 메뉴) 브라우저 OAuth 창이 뜬다. 본인 Atlassian 계정으로 승인 → **사이트 선택 드롭다운에서 대상 사이트를 정확히 고른다**. 다른 사이트로 승인돼 있으면 호출이 "isn't explicitly granted"로 거부 — 재동의로 해결(2026-07-16 실사례). 기존 사이트가 `suspended-inactivity`(403)이면 신규 사이트를 만들고 그쪽으로 승인한다. 인증 확인 = `getAccessibleAtlassianResources` 실호출 1회(목록에 뜨는 것만으로는 false-green). Claude에서 통과해도 Codex는 따로 확인한다.
3. **sentry** (http, `https://mcp.sentry.dev/mcp`) — 계정·프로젝트 5개(org tekken-75) 준비 완료(2026-07-16). Claude에서는 REST API 경로로 프로젝트 조회·이벤트 회수를 실증했지만 Codex MCP/OAuth는 별도 검증 대상이다. DSN·토큰은 사람이 로컬 비밀 설정으로 관리하며, 에이전트는 `.env*`를 읽거나 출력하거나 가드를 우회해 생성·수정하지 않는다.
4. **headroom** (http, localhost:8787) — Claude와 Codex에 모두 선언되어 있지만 로컬 서버 기동 시에만 응답한다. 세션마다 확인.

## 사용 규칙

- 여기 ❌/NOT_CONFIGURED인 도구를 "사용 가능"으로 전제한 계획을 세우지 않는다. 필요하면 `NEEDS_HUMAN_CONFIRMATION`으로 사람에게 올린다.
- MCP는 "등록됨 ≠ 기동됨 ≠ 자동 실행 허용됨" — 사용 전 실제 응답으로 확인한다. 원격 MCP는 **선언 확인이 아니라 인증 완료(실호출 1회)** 기준으로 판정한다.
- MCP 인증·동의 상태는 provider별로 검증한다. Claude에서의 성공은 Codex에서의 성공 증거가 아니다.
- Codex hooks는 프로젝트를 trust하고 hooks 설정을 검토·승인한 뒤 세션을 reload해야 활성으로 간주한다.
- 이 표의 검증일이 오래되면(도구 추가/제거 후) 재검증하고 날짜를 갱신한다.
