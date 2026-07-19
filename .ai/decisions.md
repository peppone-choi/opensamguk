# Decisions (ADR-LITE)

인간이 승인한 결정만 `approved`가 된다. 에이전트는 `proposed`까지만 기록할 수 있다.

## ADR-LITE-001 기존 CLAUDE.md/AGENTS.md 보존

- Date: 2026-07-16
- Status: approved
- Decision: `CLAUDE.md`(패러티 정본)·`AGENTS.md`(요약)는 본문을 보존하고, 상단에 Agent OS 부트스트랩 섹션(읽기 순서 + `.ai/` + `docs/agent/` 라우터 링크)만 추가한다.
- Context: Agent OS 프롬프트는 "짧은 부트스트랩 문서"를 요구했으나 기존 문서는 load-bearing 정본이라 재구성 리스크가 큼.
- Alternatives: 짧게 재구성 / 완전 무수정.
- Consequences: 부트스트랩은 다소 길지만 기존 세션·에이전트 정의(`.claude/agents/*`)가 참조하는 내용이 깨지지 않음.
- Approved by: 사용자 (AskUserQuestion 인터뷰)

## ADR-LITE-002 신규 운영 문서는 docs/agent/ 집약

- Date: 2026-07-16
- Status: approved
- Decision: `workflow-before-after.md`, `failure-cases.md`, `lifecycle-*.md`를 루트가 아닌 `docs/agent/` 아래에 둔다. 필수 파일명은 유지.
- Context: 루트가 이미 혼잡(빌드 파일·compose·workflow mjs 등).
- Approved by: 사용자 (AskUserQuestion 인터뷰)

## ADR-LITE-003 Hooks는 스크립트 + example 설정만

- Date: 2026-07-16
- Status: superseded (→ ADR-LITE-005)
- Decision: 훅 로직은 `scripts/agent/`의 실행 가능한 셸 스크립트로 두고, `.claude/settings.example.json`만 생성한다. 실제 활성화(`settings.json`/`settings.local.json` 반영)는 사람이 검토 후 수동으로 한다.
- Context: 전역 OMC가 이미 자체 훅을 주입 중이라 충돌 위험이 있고, 활성 설정은 검증된 스키마가 필요.
- Consequences: 훅이 자동으로 동작하지 않음 — Codex 등 타 에이전트는 같은 스크립트를 수동/자체 훅으로 호출.
- Approved by: 사용자 (AskUserQuestion 인터뷰)

## ADR-LITE-004 .ai/task.md는 현재 루프로 시드

- Date: 2026-07-16
- Status: approved
- Decision: `.ai/task.md`를 빈 템플릿이 아니라 live-gap-closure + v2 준비의 실제 상태로 시드한다. 이후 갱신은 사람이 한다.
- Approved by: 사용자 (AskUserQuestion 인터뷰)

## ADR-LITE-005 훅 실활성화 (.claude/settings.json)

- Date: 2026-07-16
- Status: approved
- Decision: `.claude/settings.json`을 생성해 PreToolUse(`Read|Write|Edit` → `protect-sensitive-files.sh`)·PostToolUse(`Write|Edit` → `verify-changes.sh`) 훅을 실활성화한다. ADR-LITE-003(example 전용)을 대체한다.
- Context: ADR-LITE-003의 우려였던 "전역 OMC 훅과의 충돌"을 실측으로 해소 — ① 전역 `~/.claude/settings.json`에 hooks 키 없음, ② OMC 플러그인 훅 11종은 오케스트레이션 계층(keyword-detector/skill-injector/persistent-mode 등)이라 이 레포 가드(시크릿·골든·legacy 차단, 검증 매트릭스)와 기능 중복 없음(체인 누적만). ③ `verify-changes.sh`는 plain stdout + exit 0이라 모델 컨텍스트 미주입, docs-only diff는 한 줄로 종료 → matcher 협소화 불필요 판정.
- Evidence: 훅 프로토콜(stdin JSON) 실사격 5케이스 — `.env.hooktest` Write→exit 2 차단, 골든 Edit→exit 2 차단, legacy Read→exit 0 허용, 일반 Write→exit 0, verify-changes.sh 훅 모드→변경 매트릭스 출력. @멘션 첨부는 PreToolUse 우회 → `.claudeignore`가 담당.
- Consequences: 훅은 세션 시작 시 스냅샷되므로 **다음 세션부터** 적용. Codex 등 타 에이전트는 종전대로 같은 스크립트를 수동 호출(듀얼 모드 유지).
- Approved by: 사용자 (합의 계획 `.omc/plans/2026-07-16-agent-os-activation-plan.md` 전체 승인, 2026-07-16)

## ADR-LITE-006 런북 커맨드 /os-* 개명

- Date: 2026-07-16
- Status: approved
- Decision: `.claude/commands/`의 7종 런북을 `/os-` 접두로 개명한다 (`/os-start-task` `/os-analyze` `/os-implement` `/os-debug` `/os-verify` `/os-review` `/os-checkpoint`). 참조 갱신은 3버킷 게이트로 분류 — ①개명 대상(런북 상호참조·CLAUDE/AGENTS/docs/agent), ②보존(전역 라우팅 `CLAUDE.md:121` `invoke /review` = gstack 스킬, parity-ship SKILL.md의 `/review`), ③무시(스크립트 경로·산문 슬래시·stale worktrees). 게이트 원장: `.omc/artifacts/w0-1-rename-gate.md`.
- Context: 무접두 `/verify`·`/review`·`/analyze`가 전역 OMC 스킬·gstack 커맨드와 이름 충돌 — 라우팅이 비결정적이 됨 (current-state Open question ①).
- Consequences: 이 레포 런북은 항상 `/os-*`로 호출. 전역 스킬(`/review` 등)의 라우팅 문구는 건드리지 않았으므로 기존 파이프라인 무영향. parity-ship SKILL.md 참조의 모호성 1건은 게이트 원장에 기록(후속 판단 대상).
- Approved by: 사용자 (합의 계획 전체 승인, 2026-07-16)

## ADR-LITE-007 .mcp.json un-ignore + 토큰 스캔 가드레일

- Date: 2026-07-16
- Status: approved
- Decision: `.gitignore`에서 `.mcp.json`을 제거해 MCP 선언(playwright stdio · atlassian sse · sentry http · headroom)을 커밋 가능하게 한다. 보상 가드레일로 `protect-sensitive-files.sh` §3에 `.mcp.json` 쓰기 시 토큰 패턴 스캔(sk-ant/sk-/ghp_/github_pat_/xox*/AKIA/glpat-/sntrys_/JWT + 비-env token/apiKey/password/secret 필드)을 추가한다. `.mcp.example.json` 템플릿 방식은 기각(사본 드리프트).
- Context: git-ignored 상태로는 W1 도구 배선 커밋이 no-op(Architect F1). un-ignore는 시크릿-커밋 방어선 하나를 제거하므로 보상 장치 필수(Critic #3). 재현성은 선언 수준 — 원격 2종은 사용자별 대화형 OAuth(온보딩: `docs/agent/tool-capabilities.md`).
- Evidence: 가드레일 실사격 6케이스 — sk-ant 토큰 Write→exit 2, token 필드 Edit→exit 2, env 참조 선언→exit 0, 수동 모드 디스크 스캔→exit 0, `.env` 차단 회귀 무손상, 일반 파일 허용 무손상.
- Approved by: 사용자 (합의 계획 W1-1 전체 승인, 2026-07-16)

---

## ADR-LITE-008 백엔드 3앱 Sentry 배선 (백로그 조기 인출)

- Date: 2026-07-16
- Status: approved
- Decision: known-issues 백로그였던 Spring 백엔드 Sentry SDK를 PR #154(`agent-os-activation`) 6번째 커밋으로 조기 배선한다. `sentry-spring-boot-starter-jakarta` 8.49.0을 3앱(gateway-api·game-api·game-engine)에 추가하되 **에러 캡처 전용**으로 고정: `traces-sample-rate: 0.0`(game-engine 턴 데몬 핫루프에 트레이싱 계측 금지, 3앱 일관), `send-default-pii: false`, DSN은 env `SENTRY_DSN` — 빈 값이면 SDK 전체 no-op(프론트 `enabled: !!dsn`과 동일 설계, 기존 동작 무변경이 기본값). compose는 서비스별 변수(`SENTRY_DSN_GATEWAY_API`/`_GAME_API`/`_GAME_ENGINE`)를 각 컨테이너의 `SENTRY_DSN`으로 매핑한다.
- Context: 프론트 2앱 배선(W1-5) 직후 사용자 제안("센트리는 아예 백엔드에도 달아버릴까") → AskUserQuestion으로 배치 확인, **"지금 이 PR에 추가"** 선택. RNG/로그/ChangeRecorder/JPA 경로 비접촉 — 파리티 게이트·one-daemon-write 무영향.
- Alternatives: 별도 PR(기각 — 사용자 선택), Sentry OTel agent 방식(기각 — 에이전트 계측이 핫루프에 오버헤드 리스크).
- Consequences: DSN 발급 전에는 동작 무변경. 대시보드 실증은 w1 게이트 원장 Sentry 항목과 동일 해제 조건으로 채점대기.
- Approved by: 사용자 (2026-07-16, AskUserQuestion "지금 이 PR에 추가")

## ADR-LITE-009 Codex Agent OS 프로젝트 표면 활성화

- Date: 2026-07-17
- Status: approved
- Decision: `.codex/config.toml`·`.codex/hooks.json`·7개 custom agent와 추적형 `.agents/skills/$os-*`를 프로젝트 표면으로 둔다. SessionStart는 `skills-lock.json`과 로컬 무결성 스탬프를 검사해 외부 skills.sh 스킬을 프로젝트 범위로 복원하고, 작업 중 누락된 전문성은 `$find-project-skill`의 search→inspect→project-only add 절차로 가져온다. ADR-LITE-005의 "Codex는 수동 호출" 결론만 이 결정으로 대체한다.
- Context: Claude 전용 `/os-*`·agents·hooks를 Codex에서도 fresh clone부터 재현해야 하며, 외부 스킬 본문을 커밋하면 upstream 드리프트와 공급망 검토가 어려워진다.
- Consequences: Codex 프로젝트 trust/reload가 필요하다. `apply_patch`와 단순 Bash 호출은 훅으로 검사하지만 공식 Codex 문서가 명시하듯 모든 shell 경로를 가로채는 완전한 보안 경계는 아니므로 비밀 접근·legacy/golden 쓰기 금지 규칙은 계속 하드 룰이다.
- Approved by: 사용자 (2026-07-16~17, Codex 호환 및 skills.sh 자동 복원 요청)

## ADR-LITE-010 v2 콘텐츠 정체성 — RTK 종합으로 devsam 콘텐츠 대체

- Date: 2026-07-17
- Status: approved
- Decision: v2의 콘텐츠 정체성은 RTK 시리즈 종합 데이터(맵·시나리오·세력·장수 스탯·초상)로 devsam(체섭) 콘텐츠를 **대체**하는 것이다 — 신규 시나리오 병행이 아니다. devsam 시나리오는 프로덕트 콘텐츠에서 은퇴하고, 패러티 골든 게이트의 **동결 회귀 픽스처**로 강등·보존한다(M-config의 frozen-baseline 메커니즘과 연계). 엔진 시맨틱스(RNG·반올림·로그·전투·AI)와 골든 게이트 자체는 불변.
- Context: 패러티 P0–P6 폐쇄로 엔진 확보 완료. 소스 실증(2026-07-17): wikiwiki sangokushi14/8r 전 무장 얼굴(633×900)·스탯, san14db Wayback 958/1000 무장 페이지의 시나리오별 세력·소속거점. 사용자 방향 선언: "슬슬 기존 devsam(체섭)의 그늘에서 벗어나야".
- Alternatives: RTK 콘텐츠를 신규 시나리오로 병행 추가 (기각 — 사용자: "신규 시나리오보단 대체").
- Consequences: v2 로드맵 티켓화의 맵/시나리오 갈래는 "대체 트랙"으로 재프레임(에픽 OPENSAM-101). OPENSAM-96(초상 소싱)이 선발대. Koei-IP 우려는 사용자 결정으로 현 시점 보류.
- Approved by: 사용자 (2026-07-17, 채팅 직접 지시 "등록해둬. 그리고 우려는 일단 접어둬.")

## ADR-LITE-011 에셋 AI 생성 정책 + 비주얼 현대화 방향

- Date: 2026-07-17
- Status: approved
- Decision: 이미지·모델링 등 그래픽 에셋이 필요하면 **AI 생성**으로 조달한다. 아울러 현재의 칙칙하고 답답한 화면에서 벗어나는 **UI 비주얼 현대화**를 트랙으로 진행한다(에픽 OPENSAM-112).
- Context: 초상은 RTK 소싱(OPENSAM-96)이 1차이고, AI 생성은 미매칭·신규 캐릭터·배경·아이콘 등 보충/신규 수요를 채운다. 스타일 일관성(택일된 화풍 기준)은 유지한다.
- Alternatives: 외주/구매 에셋 (기각 — 비용·속도), 기존 화면 유지 (기각 — 사용자: "칙칙하고 답답한 화면에서 벗어나야지").
- Consequences: AI 에셋 생성 파이프라인(프롬프트·스타일 가이드·후처리·CDN 배포) 티켓화. UI 리디자인은 시안 → 사용자 선택 → 공통 척추 적용 순서.
- Approved by: 사용자 (2026-07-17, 채팅 직접 지시)

## ADR-LITE-012 코에이 IP 게이트 전면 해제 + 에셋 공개 CDN 배포 경로

- Date: 2026-07-17
- Status: approved
- Decision: RTK 소싱 에셋(초상 크롭, 지도 파생물)에 걸려 있던 코에이 저작권 우려 게이트(LEGAL/RIGHTS WARN — OPENSAM-91/91b/97/102)를 사용자 지시로 **전면 해제**한다. 배포 경로는 "로컬 가공 → **별도 공개 GitHub 에셋 repo** → CDN(jsDelivr류)"로 한다. 메인 repo에는 여전히 이미지 바이너리를 커밋하지 않는다(repo 위생·용량 목적 — IP 사유 아님). 런타임 위키 핫링크는 계속 금지(안정성·politeness).
- Context: 사용자 원문 "코에이 저작물? 무시해! 어차피 devsam도 코에이 저작물을 썼어." + 직전 제안 "받아서 가공해서 깃헙에 올리고 cdn 쓰는게 맞지 않나?" (2026-07-17). devsam/core 원작도 코에이 초상(d_pic)을 사용한 전례. ADR-LITE-010의 "Koei-IP 우려 보류"를 확정 해제로 승격.
- Alternatives: 자체 /d_pic/ 서빙만 사용(권리 보수적) — 사용자 결정으로 기각(단 서버 자체 서빙 인프라는 OPENSAM-93 산출물로 유효·병행 가능); AI 생성 대체(ADR-011) — 미매칭/미검출 보충 수요용으로 유지.
- Consequences: OPENSAM-91/97 활성화 경로 unblock. 전량(1000명) 크롭 생산 즉시 착수(mfr 0.12 프로덕션 설정 — 리뷰 실측: 오검출 제거·실얼굴 손실 0). 에셋 repo 생성·푸시·CDN 배선 + 위키명(한자)↔시나리오 장수 매핑은 다음 batch 티켓으로 편성. 메인 repo 공개 여부는 별도 결정(현행 private 유지).
- Approved by: 사용자 (2026-07-17, 채팅 직접 지시)

## ADR-LITE-013 CQRS 정합성 read는 primary, read replica는 보류

- Date: 2026-07-18
- Status: approved
- Decision: command/query의 코드·모델 책임은 분리하되, read-your-write·권한/중복검사·예약 precheck·`minVersion` 등 정합성이 필요한 read는 PostgreSQL primary를 사용한다. 물리 read replica는 지금 만들지 않으며, 후속 GO/NO-GO ADR이 승인될 때에만 eventual-only 조회를 대상으로 도입한다. 메모리 source of truth는 전체 이력을 무제한 적재하지 않고 bounded hot/cold + deterministic prefetch 방향으로 전환한다.
- Context: 사용자는 write/read 분리와 데이터 정합성을 모두 요구했고, 정합성 read가 write connection을 타야 하는지 및 별도 read DB의 타당성을 질문했다. 승인된 CQRS hardening 계획은 committed version barrier, primary read routing, bounded state, replica 보류를 W0→W5 순서로 고정했다. W0 seed-proxy 측정에서 cold history 10×가 mean retained heap을 `+295.95%` 증가시켜 전체 적재 위험을 정량 확인했다.
- Alternatives: 모든 read를 replica로 전송(기각 — replica lag에서 RYW/precheck 정합성 파괴), 모든 read를 영구적으로 primary만 사용(기각 — 향후 eventual workload의 독립 확장 여지를 불필요하게 닫음), 현재 즉시 replica 구축(기각 — 관측·용량 근거와 lag/fallback 계약 없음).
- Consequences: API 라우팅은 read 의미별로 authoritative/RYW와 eventual을 구분한다. replica 장애·지연이 command correctness에 영향을 주어서는 안 되며, OPENSAM-141의 별도 ADR 전까지 인프라 증설은 없다. `OPENSAM-124` 계약은 GA-079 PHP `killturn` 부수효과의 daemon-owned lifecycle이 캡처·승인될 때까지 DRAFT/approval-blocked를 유지한다.
- Approved by: 사용자 (2026-07-18, CQRS 계획 승인 및 구현 개시 지시)

---

## 템플릿

## ADR-LITE-014 W0 로컬 Docker 대체 측정과 GA-079 2단계 lifecycle

- Date: 2026-07-19
- Status: approved
- Decision: 정지된 EC2/EBS는 시작하지 않는다. OPENSAM-123은 완전 로컬 Docker에서 deterministic sanitized aggregate materializer를 사용해 current 3회와 cold10x 3회를 fresh DB·2 GiB·JDK 21 조건으로 실행하되, 결과를 local surrogate로만 표기하고 production/live capacity 근거로 승격하지 않는다. GA-079는 child별 `PENDING -> RING_COMMITTED -> APPLIED|NOOP|FAILED_AFTER_RING`(또는 ring 전 `REJECTED_BEFORE_RING`) 2-commit lifecycle을 선택한다. 각 전이는 expected `stage_version` CAS로 fence하며, stage A는 ring, stage B는 daemon의 `ChangeRecorder -> JdbcFlushExecutor` general effect를 소유한다.
- Context: 사용자는 두 보류 결정을 모두 승인했지만 정지 해제는 현재 불가하여 로컬 Docker 실행을 지시했다. PHP 증거는 ring commit 뒤 old killturn이 남는 crash/failure 경계를 확정했다.
- Constraints: OPENSAM-123 결과는 EC2/live/prod capacity가 아니다. GA-079는 API `general` write, ring+general 단일 transaction, ring-only parity claim을 금지한다. durable schema/activation은 canonical `world_id`(OPENSAM-43)와 W3 predecessor 뒤에 진행하며 임시 singleton identity를 만들지 않는다.
- Consequences: 이번 W0 작업은 local materializer/3x2 artifact와 lifecycle model/daemon seam/focused tests를 만든다. GA-079 production activation은 predecessor가 충족될 때 동일 상태기계를 durable CAS로 연결한다.
- Approved by: 사용자 (2026-07-19, "둘 다 승인. 다만 정지를 지금은 풀 수 없고 대신 로컬에서 도커로 실행해.")

---

```md
## ADR-LITE-NNN 제목

- Date:
- Status: proposed / approved / superseded
- Decision:
- Context:
- Alternatives:
- Consequences:
- Approved by:
```
