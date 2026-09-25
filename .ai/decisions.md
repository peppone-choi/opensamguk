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
- Status: approved; parity-authority clauses superseded by ADR-LITE-042
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

## ADR-LITE-016 Strict V31 and runtime scoping land as one unmerged stack

- Date: 2026-07-20
- Status: approved
- Decision: V31의 `world_id NOT NULL`, scoped unique/FK, legacy turn unique 제거를 완화하지 않는다. V31이 요구하는 OPENSAM-127 loader/query/reservation scoping과 OPENSAM-128 `JdbcFlushExecutor` create/update/delete scoping을 같은 미머지 브랜치 스택에서 먼저 완성하고, 해당 런타임·동시성 게이트가 green이 되기 전에는 V31을 commit/push/merge/deploy하지 않는다.
- Context: V31 집중 migration/importer 테스트 17건은 green이었지만 독립 검토의 실제 PostgreSQL 실행에서 기존 flush writer와 reserved-turn upsert가 strict schema와 호환되지 않아 실패했다. nullable 호환 expand는 이 문제를 뒤로 미루는 대안이지만 사용자는 권장안인 architecture-honest stacked completion을 선택했다.
- Constraints: 두 번째 world admission, production migration/cutover, main push/deploy는 여전히 금지한다. 이번 스택은 V31의 현재 scoped cohort와 그 실제 runtime 경로를 먼저 일관되게 만들며, 전체 S2-T2/T3 완료 주장은 각 티켓의 전 world-owned SQL/Redis AC가 별도로 충족된 뒤에만 가능하다.
- Consequences: 공유 flush substrate는 OPENSAM-128 구현자가 단일 소유하고, OPENSAM-127의 key contract를 선행 또는 동일 순차 레인에서 소비한다. 독립 리뷰의 V31 relation-lock, importer admission/transaction, non-1 propagation, post-DDL rollback 테스트 지적도 같은 스택에서 해소한다.
- Approved by: 사용자 (2026-07-20, "권장 방향대로 계속.")

## ADR-LITE-014 W0 로컬 Docker 대체 측정과 GA-079 2단계 lifecycle

- Date: 2026-07-19
- Status: approved
- Decision: 정지된 EC2/EBS는 시작하지 않는다. OPENSAM-123은 완전 로컬 Docker에서 deterministic sanitized aggregate materializer를 사용해 current 3회와 cold10x 3회를 fresh DB·2 GiB·JDK 21 조건으로 실행하되, 결과를 local surrogate로만 표기하고 production/live capacity 근거로 승격하지 않는다. GA-079는 child별 `PENDING -> RING_COMMITTED -> APPLIED|NOOP|FAILED_AFTER_RING`(또는 ring 전 `REJECTED_BEFORE_RING`) 2-commit lifecycle을 선택한다. 각 전이는 expected `stage_version` CAS로 fence하며, stage A는 ring, stage B는 daemon의 `ChangeRecorder -> JdbcFlushExecutor` general effect를 소유한다.
- Context: 사용자는 두 보류 결정을 모두 승인했지만 정지 해제는 현재 불가하여 로컬 Docker 실행을 지시했다. PHP 증거는 ring commit 뒤 old killturn이 남는 crash/failure 경계를 확정했다.
- Constraints: OPENSAM-123 결과는 EC2/live/prod capacity가 아니다. GA-079는 API `general` write, ring+general 단일 transaction, ring-only parity claim을 금지한다. durable schema/activation은 canonical `world_id`(OPENSAM-43)와 W3 predecessor 뒤에 진행하며 임시 singleton identity를 만들지 않는다.
- Consequences: 이번 W0 작업은 local materializer/3x2 artifact와 lifecycle model/daemon seam/focused tests를 만든다. GA-079 production activation은 predecessor가 충족될 때 동일 상태기계를 durable CAS로 연결한다.
- Approved by: 사용자 (2026-07-19, "둘 다 승인. 다만 정지를 지금은 풀 수 없고 대신 로컬에서 도커로 실행해.")

---

## ADR-LITE-015 CQRS foundation-unblock 의존성 재분해

- Date: 2026-07-19
- Status: approved
- Decision: canonical `world_id` 계약을 broad V2-0B `OPENSAM-43`에서 분리한 전용 CQRS foundation(`OPENSAM-148`, GitHub `#298`)으로 먼저 확정한다. 이 foundation은 `OPENSAM-43`과 scoped schema `OPENSAM-126`을 모두 block한다. `OPENSAM-43`의 G0 선행·11항목 범위는 축소하거나 완료 처리하지 않는다. Build-only 순서는 identity foundation → S2 world scope → S3 generation/fence/CAS/recovery → S4 durable inbox/outbox이며, 그 뒤에만 GA-079를 활성화한다.
- Context: 기존 계획은 W1에서 `OPENSAM-43 Done`을 요구하지만 `OPENSAM-43` 자체가 G0 뒤의 broad V2-0B 티켓이고, W0 계약 승인은 W3 binding을 기다리면서 W3는 W1/W2를 기다리는 순환 의존성이 있었다. 정지된 EC2 때문에 OPENSAM-123 live-capacity 증거도 지금 만들 수 없다.
- Constraints: OPENSAM-123 live-capacity 증거와 OPENSAM-124의 W3 durable binding은 production activation/cutover gate로 유지한다. Local surrogate를 live 근거로 승격하지 않고, 임시 singleton identity·API general write·ring-only activation·두 번째 world admission을 금지한다.
- Consequences: 계약 승인과 build-only foundation은 순환에서 해제되지만, production activation은 live-capacity, two-world isolation, scoped flush, writer epoch/world-version CAS, recovery, durable inbox/result/outbox gate가 모두 green일 때까지 금지된다.
- Approved by: 사용자 (2026-07-19, foundation-unblock 개정안에 "승인.")

---

## ADR-LITE-017 v2 부하 2트랙을 가신 1트랙으로 병합

- Date: 2026-07-25
- Status: approved
- Decision: v2의 부하 2트랙(추종 Follower / 가신 Retainer)을 **가신 1트랙**으로 병합한다. 구 추종은 가신의 속성으로 흡수한다 — `origin(EXISTING|RECRUITED)`, `hasOwnBugok`(독립 병력 보유, EXISTING 기본 true / RECRUITED 기본 false), `role(참모|호위|군수관|정찰|사신|NONE)`, `releasePolicy(MUTUAL|MASTER_ONLY)`, `upkeep`(RECRUITED만 월별 금·쌀 소모). `subjectType`은 `GENERAL|FOLLOWER|RETAINER|BUGOK|SUBFACTION`에서 `GENERAL|RETAINER|BUGOK|SUBFACTION`으로 줄인다. 커맨드는 `추종서약`+`가신채용` → `가신서약`, `추종해제`+`가신해고` → `가신해제`, `가신임무부여` → `가신임무`로 통합한다. 광역 명령 `동시침공`·`집결명령`·`광역이동` 3종은 유지하고 대상만 `hasOwnBugok=true`인 가신으로 재정의한다.
- Context: 추종과 가신의 실제 차이는 출신(기존 장수 풀 vs 신규 생성)과 병력 보유 여부뿐이며, 이는 주체 타입이 아니라 속성이다. 두 트랙을 별도 주체로 두면 스키마·커맨드·광역 명령 대상 해석·UI 패널이 전부 이중화되면서 얻는 것은 없다. 구현 전이라 지금이 변경 시점이다 — 스키마(`general_retainers`)도 커맨드도 아직 코드로 존재하지 않는다.
- Alternatives: 2트랙 유지(기각 — 이중화 비용만 남고 게임적 차이는 속성으로 표현 가능), 추종만 남기고 가신을 흡수(기각 — 채용·유지비·임무 축이 가신 쪽에 있어 명명이 역행), 구현 후 리팩터링(기각 — 스키마·커맨드 코드·프론트 패널이 생긴 뒤에는 비용이 배가).
- Consequences: `general_followers` 테이블이 불필요해지고 `general_retainers` 하나로 수렴한다. Redis 키 `world:{id}:followers:{masterId}`는 `world:{id}:retainers:{masterId}`로 바뀐다. 병합 대상 커맨드는 6종에서 3종으로 줄고(광역 3종은 별개로 유지), `subjectType`은 5값에서 4값이 된다. 부곡은 사람이 아니라 병력 집단이므로 이 병합과 무관하며 `BUGOK` subjectType은 그대로 둔다. `docs/wiki/raw/**`의 PRD·ROADMAP은 원본 소스라 수정하지 않고, `docs/wiki/pages/game/opensamguk-v2-direction.md`에 모순 플래그로 추적한다.
- Approved by: 사용자 (2026-07-25, "추종과 가신을 합치는거야. 좋아, 그렇게 해.")

---

## ADR-LITE-018 v1을 오리지널로 동결하고 v2 뉴버전을 상시 운영으로 삼는다

- Date: 2026-07-25
- Status: approved; parity-authority clauses superseded by ADR-LITE-042
- Decision: 현재 opensamguk(PHP `devsam/core` 패러티 산물)을 **오리지널**로 동결하고, v2를 **뉴버전**으로 구현해 상시 운영 월드로 삼는다. 오리지널은 상시 가동하지 않고 필요할 때 여는 on-demand 월드로 둔다. 동결은 기능 추가 중단을 뜻하며 유지보수 중단이 아니다 — CLAUDE.md 패러티 규율 6항(RNG draw-for-draw, `PhpRound`, 한글 로그 바이트 패러티, flush delta, 골든 날조 금지, 삽입 순서)은 오리지널에 그대로 계속 적용된다. 두 버전은 V2-0A 격리 게이트대로 별도 DB(`opensamguk_v2`)·별도 route/bean/migration으로 분리하며, 한 코드베이스에서 플래그로 공존시키지 않는다.
- Context: v2는 v1의 확장이 아니라 별도 제품이다(`CommandSubject.subjectType`, Operation, BattleReplay, 3D 지도, 실시간 대형 부대 전장, 200ms tick, 별도 스키마 8종). v1은 PHP 골든이라는 대체 불가능한 정본 오라클을 가진 완성된 패러티 자산이므로 폐기하면 회귀 게이트 자체가 사라진다. 반대로 v1을 계속 주 운영으로 두면 v2 구현 대역이 나오지 않는다. 두 버전을 나누는 시점은 v2 구현 착수 전인 지금이 가장 싸다.
- Alternatives: v1을 주 운영으로 유지하고 v2를 부가 콘텐츠로(기각 — v2는 전투·주체·tick 모델이 달라 부가로 얹을 수 없고 v1 패러티를 깬다), v1 폐기(기각 — PHP 골든 회귀 게이트와 패러티 자산 소실), 단일 코드베이스에서 런타임 플래그 공존(기각 — V2-0A 격리 게이트 "production profile의 v2 route·bean·migration·catalog loader 수 0" 위반).
- Consequences: **M-config 마일스톤의 전제가 뒤집힌다.** `docs/superpowers/MILESTONES.md`의 M-config(post-parity 상수 외부화 — `GameConst` 패러티값을 JSON으로 빼고 패러티 골든을 frozen-baseline 회귀 게이트로 교체)는 v1을 대상으로 했으나, v1이 오리지널로 동결되므로 **v1은 상수 외부화 대상에서 제외**한다. `GameConst`의 패러티값과 PHP 골든은 오리지널의 정본 게이트로 그대로 남는다. 상수 외부화가 필요한 쪽은 뉴버전이고, v2에는 애초에 PHP 골든이 없어 "골든을 frozen-baseline으로 교체" 논거 자체가 성립하지 않는다. 또한 오리지널 on-demand 운영의 선결 조건은 **restart-rehydrate lossless gate**다 — 이것이 닫히지 않으면 월드를 여는 순간 턴이 되감긴다(`gap/LOGIC_GAP.md` §15, `SESSION_HANDOFF.md` H "매 main 배포 = 턴 되감김"). 게이트웨이 계정을 두 버전이 공유할지는 미결로 남긴다.
- Approved by: 사용자 (2026-07-25, "버전 1을 따로 저장하되 버전 2를 구현해서 오리지널, 뉴버전으로 나눌거야. 운영은 뉴버전으로 하고. 오리지널은 필요할때 여는걸로." / "둘다 지금 해.")

---

## ADR-LITE-019 v2 오픈 경로에서 G0·C-track을 오픈 후로 미루고 OPENSAM-149를 선행으로 올린다

- Date: 2026-07-25
- Status: approved
- Decision: v2 오픈 경로를 정본 phase 순서에서 재배열한다. (1) `OPENSAM-149`(restart-rehydrate lossless gate)를 v2 착수 **전** 선행으로 올린다. (2) `V2-G0` 역사 지리·3D(`OPENSAM-36`~`42`)와 `C-track` 콘텐츠 exact-count(`OPENSAM-51`~`55`)를 오픈 경로에서 빼고 **오픈 후 콘텐츠 확장**으로 미룬다. `V2-0B` sandbox 적재는 G0 카탈로그 대신 기존 도시 세트 또는 RTK 빌더(`OPENSAM-104`/`105`) 산출물을 쓴다. 확정 오픈 경로는 `[v1 선행 31·32·33·34] → 149 → 0A(35) → 0B(43·44) → V2-1(45·46·47) → V2-2(48) → V2-3(56) → V2-5(61)` = 14 티켓이다. `V2-4A` replay spine · `V2-4B` 실시간 formation 전투 · `I0`/`V2-6` 어전회의 · `O0`/`V2-7` 황실·관직·봉신 · `V2-8` hardening은 전부 오픈 후로 둔다.
- Context: 열린 티켓 104개를 전수 확인한 결과 v2 오픈에 실제로 걸리는 것은 소수였고, 정본 phase 순서(`0A→G0→0B→1→2→B0→C0→C1..C5→3→…`)의 2번째가 G0라는 점이 최대 병목이었다. G0은 명세상 "in-memory, DB write 없음"이라 게임플레이 기여가 0인데 2,000 거점 전사 + Three.js LOD + FPS 게이트로 7티켓을 소모한다. 사용자 목표는 "버전 2를 빨리 여는 것"이다. 한편 `OPENSAM-149`는 v1/v2 공용 데몬 경로(`ChangeRecorder → JdbcFlushExecutor → WorldSnapshotLoader`)의 결함이라 v2 포크 후에 고치면 두 번 고치고, 그때까지 v2 sandbox도 배포마다 턴이 되감긴다. ADR-LITE-015가 걸었던 선행 `OPENSAM-148`은 완료되어 `OPENSAM-43`(V2-0B)은 이미 해금 상태다.
- Alternatives: 정본 순서 유지(기각 — 오픈까지 20+티켓이고 3D 증명이 게임플레이보다 먼저 온다), G0-A 행정 계약만 선행하고 G0-B/C만 연기(기각 — 사용자가 전면 연기를 택함. 데이터 모델을 오픈 후 교정하는 비용은 감수한다), `OPENSAM-149`를 v2와 병행(기각 — 개발 기간 내내 배포마다 sandbox 되감김을 감수해야 함), `OPENSAM-149`를 오리지널 여는 시점까지 보류(기각 — v2가 같은 결함을 안고 포크되어 두 번 고치게 됨).
- Consequences: **GOLDENSET 개정이 따른다.** `docs/loops/v2-planning-2026-07-12/GOLDENSET.md` 4번(모든 현·읍·도·후국 치소의 지도 참여, 대표 도시 축약 금지)과 8번(120/380/1,500 3D LOD)은 **적용 시점을 "v2 오픈 시점"에서 "오픈 후 G0 착수 시점"으로 옮긴다.** 항목 자체는 폐기하지 않고 v2 오픈 판정 기준에서만 제외한다. 시험지 개정은 별도 사용자 승인 사항이며 이 ADR이 그 승인 기록이다. 오픈 직후 v2는 3D 지도도, 2,000 거점도, exact-count 콘텐츠도 없는 상태로 뜨고 v1 대비 차별점은 조작 대상 패널·부곡·작전·가신 4개뿐이다 — 오픈 커뮤니케이션이 이를 사실대로 밝혀야 하며, "개선 폭이 작다"는 것이 이 결정이 감수하는 리스크다. `docs/superpowers/plans/2026-07-17-v2-ticket-backlog/README.md` §착수 순서도 이 재배열로 갱신한다. ADR-LITE-018의 재분류와 합쳐 `OPENSAM-112`~`115`(v1 비주얼 현대화)는 우선순위가 내려가고 `OPENSAM-101`·`104`·`105`·`106`(RTK 맵·시나리오 빌더)은 0B의 도시 데이터 공급원으로 오픈 경로에 진입한다.
- Approved by: 사용자 (2026-07-25, "내가 원하는건 버전 2를 빨리 여는거긴 해." + G0 "오픈 뒤로 미룬다" / OPENSAM-149 "v2 착수 전에 먼저" 선택)

---

## ADR-LITE-020 모든 작업은 무조건 문서화한다 — 대화에만 남은 결과는 산출물이 아니다

- Date: 2026-07-25
- Status: approved
- Decision: 에이전트가 수행한 모든 비자명 작업은 **끝나는 시점에 리포지토리 파일로 남긴다.** 대화창·에이전트 보고·터미널 출력에만 존재하는 결과는 산출물로 인정하지 않는다. 최소 대상: (1) 결정 → `.ai/decisions.md` ADR-LITE, (2) 루프 라운드의 기준선·가설·채점기·**채점 결과**·결정 → `docs/loops/<루프>/`(LEDGER 결과 칸 포함), (3) 조사·오라클 증거 → `docs/superpowers/research/` 또는 해당 spec, (4) 세션 상태·인계 → `.ai/task.md`·`.ai/current-state.md`·`.ai/handoff.md`, (5) 계획·티켓 분해 → `docs/superpowers/plans/`, **(6) 대화 자체 → `docs/superpowers/SESSION_HANDOFF.md` 최상단에 세션 항목 prepend**(`.ai/README.md:18`대로 `.ai/`에는 장황한 로그를 쌓지 않는다). 리뷰·채점·비평 결과는 **판정이 `cleared`든 `fix-required`든 똑같이** 파일로 남긴다. 문서화가 끝나지 않은 작업은 완료로 보고하지 않는다.

  **대화 기록에 반드시 들어가는 것**: ① 사용자 지시 **원문**(요약·의역 금지, 발화 순서대로) ② 지시에서 나왔으나 아직 티켓·정본이 없는 것 ③ 물었는데 **답을 못 받은 질문** ④ 확인 불가로 남은 것과 그것이 무엇을 떠받치고 있는지 ⑤ 뒤집힌 정본(어느 문서 몇 번째 줄이 언제 왜 뒤집혔는지). 결정의 *결과*만 남기고 *대화의 맥락*을 버리면 다음 세션이 같은 논의를 다시 한다.
- Context: 이 리포지토리의 작업 상당량이 서브에이전트 위임으로 수행되고 컨텍스트는 요약·리셋된다. 서브에이전트 보고는 부모 컨텍스트에만 존재하므로 요약 한 번이면 사라지고, 다음 세션·다른 provider(Codex 등)·사람 리뷰어는 그것을 볼 방법이 없다. 특히 **부정적 판정**(채점 N, `fix-required`, 기각된 대안, 확인 불가 항목)은 남기지 않으면 같은 논의를 다시 하거나 이미 무너진 전제 위에 다시 설계하게 된다 — round-3 독립 채점이 저자의 자기채점 10/10을 5/10으로 뒤집은 사례가 정확히 그 유형이다. `.ai/`·`docs/agent/`·loop 원장은 이미 그 목적으로 존재하지만 "언제 반드시 쓰는가"가 규칙으로 명시돼 있지 않았다.
- Alternatives: 중요한 것만 선별 문서화(기각 — 무엇이 중요한지는 나중에 결정되고, 선별 판단 자체가 누락의 주된 원인이다), `/os-checkpoint` 시점에만 일괄 기록(기각 — 체크포인트 전에 컨텍스트가 리셋되면 소실되고, 사후 재구성은 날조 위험이 있다), 커밋 메시지로 대체(기각 — 기각된 대안·확인 불가 항목·채점 근거가 담기지 않는다).
- Consequences: 작업당 문서 쓰기 오버헤드가 상시 발생한다. 이것은 비용이 아니라 계약으로 취급한다. 문서는 **작업이 끝난 뒤가 아니라 결과가 나온 즉시** 쓰며, 서브에이전트 보고를 받은 부모는 그 내용을 파일로 옮기기 전에는 다음 작업으로 넘어가지 않는다. 날조 금지 규율이 그대로 적용된다 — 확인하지 못한 것은 UNKNOWN으로 쓰고, 실패·미검증·기각 사유를 삭제하거나 완화하지 않는다. CLAUDE.md 하드 룰("unverified = UNKNOWN, not guessed")과 `docs/superpowers/LOOP_ENGINEERING.md`의 measure → hypothesis → remeasure 기록 의무를 이 ADR이 전 작업으로 확장한다.
- Approved by: 사용자 (2026-07-25, "무조건 문서화를 할 것.")

---

## ADR-LITE-021 도시 중심·인맥(꽌시)을 하나의 시스템으로 채택하고 오픈 경로를 20 티켓으로 확정한다

- Date: 2026-07-25
- Status: approved
- Decision: round-3 설계안(`docs/loops/v2-planning-2026-07-12/round3-proposal-city-guanxi.md`, 독립 채점 6바퀴 끝에 **10/10 `cleared`**)을 채택한다. (1) **도시 중심 = 자원 소유 주체의 위치**(국가 → 도시)이며 3D·거점 수가 아니다. 묘섭이 "도시 중심"이라 부르는 것의 실체가 이것이다. (2) **인맥은 도시 중심과 별개 시스템이 아니라 같은 시스템의 다른 면**이다 — 인사권·배치효과·감시·자원분배 4축이 전부 기존 `officer_level` 위에 얹히고 평행 축을 신설하지 않는다. (3) 오픈 경로를 ADR-LITE-019의 **14 → 20 티켓**으로 개정한다(+R1 원장기반 · R2 수입·봉록 · R3 공백지화 · R4 병사보충 · R5 수송 · R6 원장열람). **조건부 항목 없는 단일값**이다. (4) **장수↔장수 관계망 전체(P0~P6, 7티켓)는 오픈 후**로 둔다. (5) 관계는 **능력치에도 보정을 준다** — `product-spec.md:388`의 "능력치 버프가 아니라"를 이 결정으로 뒤집었다. PHP 골든 오라클이 없는 v2 전용 divergence이므로 v2 world profile 한정 tail-append stat 모듈로만 주입한다. (6) v2 배포 토폴로지는 **한 프로세스 = 한 월드 = 한 DB**이며, 이는 선언이 아니라 `infra/.../seed/ScenarioSeedCoordinator.kt:37-49`가 `error(...)`로 강제하는 코드 불변식이다(시드 활성 부팅 한정 — ADR-LITE-018의 별도 DB 결정과 정합).
- Context: 사용자 1순위는 "버전 2를 빨리 여는 것"이고, 도시 중심 참조는 묘섭(37 도움말 페이지), opensamguk이 더하는 차별점은 장수↔장수 관계다. 관계망을 오픈 경로에 넣지 않는 근거는 **순서**다 — 관계를 낳는 emergent 소스 5종 중 4종이 오픈 경로 **마지막 두 티켓**(`OPENSAM-56`·`61`)에 붙으므로, 넣는다는 것은 오픈 직전에 7티켓을 더 얹는다는 뜻이다. 이 판정은 기록 소급 가능 여부와 무관하게 성립한다. 설계 자체는 축소·희석되지 않았고 착수 시점만 이동했다.
- Alternatives: 관계망을 오픈 경로에 포함(기각 — 1순위와 충돌, 위 순서 논거), `nation.gold`를 도시 원장의 미러로 두기(기각 — 직접 산술 35파일·배관 포함 42파일에 choke point 0. 국고를 **병존하는 별개 실계정**으로 재정의하고 총합 불변식을 폐기하는 네 번째 길을 택했다), v2 원장을 `InMemoryTurnWorld`/`WorldSnapshotLoader` 경유로 적재(기각 — `HotColdWorldCatalogGuardTest`가 로더 메서드 집합을 T1 카탈로그와 `assertEquals`로 봉인해 **물리적으로 불가능**), v2 원장을 별도 flush 싱크·별도 커넥션 풀로 분리(기각 — 토폴로지 확정 결과 "v1 템플릿에 묶여 있다"는 전제 자체가 거짓이었고, 분리하면 v2 쓰기가 `DaemonWriteGuard` 사각지대에 놓인다).
- Consequences: **ADR-LITE-019의 오픈 경로 표(14)가 이 ADR의 20으로 대체된다.** ADR-019의 나머지(G0·C-track 오픈 후 연기, `OPENSAM-149` 선행)는 그대로 유효하다. `product-spec.md:388`이 뒤집혔다(위 (5)). 국고가 정기 수입원을 잃고 전쟁수입·레벨업 일시금만 받게 되어 국가 지출이 **이전 전용 경제**로 바뀐다 — 이는 이 결정이 감수하는 미열거 비용이며 오픈 전 관측 3종(국고 월간 추이 / 병종연구 최초 완료 시점 / `maxResourceActionAmount` 분포)으로 감시한다. R2가 최대 티켓이 되어 반나절 규율로 분해하면 20 → 21이 될 수 있으나 이는 동일 산출물의 분해이지 범위 추가가 아니다. v2 스택을 별도 compose 서비스로 띄우고 `GAME_DATABASE_URL`·`OPENSAMGUK_WORLD_ID`·`SCENARIO_CODE`/`SCENARIO_DIR`·`V2_ENABLED`를 v1과 다른 값으로 주입하는 것은 **`OPENSAM-35`(0A)의 DoD**가 되며 오늘 compose 파일에는 v2 스택이 없다. 잔여 UNKNOWN 3건(U9 `@Serializable` sealed 서브클래스 파일 분리 · U10 v2 시드·마이그레이션 부팅 순서 · U12 `SPRING_FLYWAY_LOCATIONS` env 오버라이드)은 전부 착수 첫 작업의 컴파일·실측으로 닫히며 **어느 것도 티켓 수량 20의 전제가 아니다.**
- Approved by: 사용자 (2026-07-25, "내가 원하는건 버전 2를 빨리 여는거긴 해." / "그리고 도시/인맥(꽌시) 중심의 플레이를 유도할 수 있을까?" / "돌려. 그럼 우리가 더 채울건 여기서 장수장수 관계겠군." / "관계는 능력치 버프에도 영향을 줘야지. 예를 들면 유비 관우 장비 의형제제라던지.")

---

## ADR-LITE-022 UI는 "이 주체가 지금 할 수 있는 것만" 보여준다

- Date: 2026-07-25
- Status: approved
- Decision: v2 UI의 기본 표시 규칙을 **"20버튼 전부 노출"에서 "이 주체가 지금 할 수 있는 것만"으로 바꾼다.** 메인 컨트롤바는 현재 주체(장수 신분 / 국가 직위 `officer_level` / 도시 상태 / 자원·병력 조건)에서 **실제로 실행 가능한 것만** 보여주고, 불가능한 것은 숨기거나 **사유와 함께** 비활성한다. 이는 새 게이팅 로직의 신설이 아니라 **기존 게이팅(F2 `MainControlBar` 20버튼 + 게이팅) 위에 얹는 표시 규칙**이다. `OPENSAM-113`(UI 비주얼 현대화)의 요구사항으로 편입한다.
- Context: v1은 20버튼을 전부 노출하고 누른 뒤에야 거절 사유를 보여준다. 신규 유저에게는 무엇을 할 수 있는지가 화면에서 읽히지 않고, 숙련 유저에게는 매번 불가능한 선택지를 걸러내는 비용이 든다. 사용자 지시("버전 2를 열면서 UI를 현대화 하고 유저 맞춤으로 갈거야")의 "유저 맞춤"이 무엇인지 이 세션에서 물었고 미결로 남아 있었다. 판정 데이터는 이미 서버에 있다 — precheck 제약(`constraints/Presets.kt`)이 거절 사유 문자열까지 갖고 있으므로 프론트가 그것을 사후가 아니라 **사전에** 소비하면 된다.
- Alternatives: 전부 노출 유지(기각 — 유저 맞춤의 실질이 없어진다), 불가능한 것을 완전히 숨기기만(기각 — 무엇이 있는지 자체를 배울 수 없고 "왜 안 보이지"가 문의로 돌아온다. 사유 표기가 있는 비활성이 기본, 숨김은 신분상 애초에 무관한 것에만), 별도 신규 티켓 발행(기각 — 113이 이미 UI 현대화 티켓이고 같은 화면을 건드린다).
- Consequences: 프론트가 precheck 결과를 렌더 시점에 알아야 하므로 **read API가 "가능/불가 + 사유"를 함께 내려주는 형태**로 늘어날 수 있다. 이때 판정 정본은 서버이며 프론트가 조건을 복제 구현하면 안 된다(이중 진실 금지). v1 패러티 표면은 건드리지 않는다 — 표시 규칙이지 판정 규칙 변경이 아니다. OPENSAM-113의 범위가 "비주얼 현대화"에서 "비주얼 + 표시 규칙"으로 넓어진다.
- Approved by: 사용자 (2026-07-25, "유저 맞춤 원칙을 OPENSAM-113에 넣는다" 선택 / 원 지시 "단, 버전 2를 열면서 UI를 현대화 하고 유저 맞춤으로 갈거야")

---

## ADR-LITE-023 게이트웨이 계정을 오리지널·뉴버전이 공유한다

- Date: 2026-07-25
- Status: approved
- Decision: 게이트웨이 계정(gateway-api 자체 JWT/BCrypt 인증, F0)을 **오리지널(v1)과 뉴버전(v2)이 공유한다.** 한 번 가입한 유저는 같은 계정으로 두 버전에 모두 로그인한다. ADR-LITE-018이 미결로 남긴 항목을 이 결정으로 닫는다.
- Context: ADR-LITE-018은 v1/v2를 **별도 DB·별도 route/bean/migration**으로 분리했으나 계정 공유 여부는 미결이었다. 분리 대상은 *게임 월드 상태*이고 계정은 게임 월드가 아니라 게이트웨이 자산이다. 오리지널이 on-demand로 열리는 월드인 이상, 열 때마다 별도 회원가입을 요구하면 그 월드를 여는 의미 자체가 줄어든다.
- Alternatives: 버전별 별도 계정(기각 — on-demand 오리지널을 여는 마찰이 커지고, 같은 사람의 두 계정을 운영자가 연결할 방법이 없어 어드민·제재·문의 대응이 이원화된다), 계정은 공유하되 프로필·닉네임까지 공유(**보류** — 결정하지 않았다. 아래 Consequences 참조).
- Consequences: 게이트웨이 DB는 **두 버전의 공용 자산**이 되므로 ADR-LITE-018의 "별도 DB" 분리선은 **게임 월드 DB에만** 적용된다는 점이 명확해진다. 계정 하나가 두 월드에 장수를 갖게 되므로 **로비가 "어느 월드에 들어갈지"를 먼저 고르는 화면이 된다.** 미결로 남는 것: 닉네임·프로필·유산 포인트 같은 **계정 부속 데이터를 어디까지 공유하는가** — 유산 포인트는 게임 밸런스에 직결되므로 월드별 분리가 기본값으로 보이나 확정하지 않았다. 어드민 권한(`ADMIN_USERNAME`/`ADMIN_PASSWORD` 시드, role=ADMIN)은 공유 계정이므로 자동으로 두 버전 모두에 적용된다 — 이것이 의도인지는 어드민 화면 착수 시 재확인한다.
- Approved by: 사용자 (2026-07-25, "게이트웨이 계정을 오리지널/뉴버전이 공유한다" 선택)

---

## ADR-LITE-024 v1 날짜도 상순·중순·하순의 36순을 사용한다

- Date: 2026-07-27
- Status: approved
- Decision: 오리지널(v1)의 게임 날짜도 월마다 상순·중순·하순을 두며 **1년 36순**을 사용한다. `GameConst.phasesPerMonth=3`·`turnsPerYear=36`과 v1 기본 `ServerClock`은 유지한다. PHP의 월별 12회 장기 캡처를 Kotlin v1에서 재생할 때는 한 PHP 월을 Kotlin 3순으로 확장해 비교하며, v1을 12턴/년으로 되돌리지 않는다.
- Context: 12개월 exact 재생 디버깅 중 PHP가 1경계=1개월이라는 정적 근거만으로 v1 프로덕션을 12턴/년으로 바꾸는 안이 제시됐으나, 사용자가 v1의 제품 정본을 직접 확정했다. 실제 잔여 결함은 36순 자체가 아니라 여러 순을 한 번에 catch-up할 때 메모리 날짜를 마지막에 한 번만 갱신해 중간 AI가 오래된 날짜를 읽을 수 있는 경계 처리다.
- Alternatives: PHP와 동일한 12턴/년으로 회귀(기각 — 사용자 정본과 현재 v1 제품 규칙 위반), 36순은 유지하되 월간 파이프라인을 매 순 실행(기각 — 월간 처리는 phase 1에서만 실행), 36순 유지 + 각 순 경계의 live date 전진 + phase 1 월간 실행(채택).
- Consequences: 12턴/년을 전제로 한 실험 패치는 원복한다. long-sim materializer는 PHP 월별 상태를 v1의 3순 cadence로 변환해야 한다. catch-up 회귀 테스트는 모든 phase 경계에서 live date가 전진하는지와 월간 파이프라인이 phase 1에서만 실행되는지를 동시에 증명해야 한다.
- Approved by: 사용자 (2026-07-27, "v1도 36순을 써.")

---

## ADR-LITE-025 V2 출시에 전용 battle-engine 기반 야전·공성·수전을 필수화한다

- Date: 2026-07-30
- Status: approved
- Decision: V2 출시에 실시간+제한 전술 정지 방식의 야전·공성·수전을 모두 포함한다. 런타임은 battle별 authoritative fixed-tick session actor를 가진 전용 `battle-engine`으로 분리한다. 총지휘관은 본대 편제 1개와 전역 권한을 가지고 장교는 배정 편제 1개를 맡으며, 권한 변경·명령·조작 모드 전환은 지휘망 지연을 거친다. 출시 기준은 진영당 16편제(총 32), 기본 12분·최대 15분이다. 실시간 성능·동기화·재접속·렌더 게이트가 실패하되 세 전장 어댑터의 headless G6가 통과하면 같은 BattleTicket/명령/replay 계약의 사전 전술+자동전투로 fallback한다.
- Context: 사용자 요청은 기존 일괄 전투를 전략·전술이 있는 2D/2.5D 전투로 바꾸는 것이었고, 2026-07-29~30 `superpowers:brainstorming` 인터뷰에서 세션 아키텍처, 지휘권, WebSocket·저장, 복구·보안, 출시 게이트를 순서대로 승인했다. 정본 스펙은 `docs/superpowers/specs/2026-07-30-v2-realtime-battle-session-command-replay-design.md`이며 독립 재검토 최종 판정은 `CLEAR — blockers none`이다.
- Alternatives: V2 오픈 후 추가(기각 — 사용자 직접 선택), 기존 game-engine scheduler+HTTP/SSE 내장(기각 — 장기 세션·재접속·epoch fence·부하 격리가 약함), client lockstep(기각 — 안개·권한·부정 명령·결과 정본을 클라이언트에 분산), full 3D 우선(기각 — 초기 자산·렌더 비용이 전술 기반을 압도).
- Consequences: ADR-LITE-019/021의 “V2-4A/4B 오픈 후”와 “오픈 경로 20 단일값”은 이 결정으로 해당 부분만 supersede된다. 기존 20은 전투 프로그램 추가 전 부분합이다. `V2-G0`·`C-track`·관계망의 오픈 후 분류, 도시·인맥 설계, v1 격리는 계속 유효하다. 이전 2.5D 문서의 game-engine scheduler·HTTP/SSE·8편제·오픈 후 rollout은 역사 초안으로 강등하고, Three.js 정사영 2.5D·formation 판정·에셋 계약은 유지한다. `battle-engine`은 한 WorldId/DB에만 바인딩하고 `battle_*`만 쓰며, game-engine만 캠페인 결과를 `ChangeRecorder -> JdbcFlushExecutor`로 반영한다.
- Approved by: 사용자 (2026-07-29~30 설계 보드 섹션별 승인, 2026-07-30 작성 스펙 최종 승인 “승인.”)

---

## ADR-LITE-026 PR에서 리뷰 에이전트를 3회 멘션하고 수정·재검증 후에만 머지한다

- Date: 2026-07-31
- Status: approved
- Decision: 앞으로 모든 PR은 원격에 올린 뒤 **PR 대화에서 리뷰 에이전트를 멘션해 리뷰를 3회 요청**한다. 각 라운드의 지적을 코드·테스트·문서에 반영하고 관련 검증을 다시 통과한 뒤에만 머지 후보로 인정한다. 세 PR 리뷰의 판정과 수정 근거는 PR 대화와 리포지토리 리뷰 산출물에 남긴다. 실제 merge는 기존 안전 규칙대로 사용자의 명시적 승인 후에만 수행한다.
- Context: 사용자가 말한 "자체 리뷰"는 로컬 서브에이전트 검토가 아니라 PR에서 멘션해 호출하는 리뷰 기능이다. 구현자 1회의 자기 확인이나 PR 전 리뷰만으로는 원격 PR 상태에서 드러나는 통합·문서·운영 결함을 충분히 막지 못한다.
- Alternatives: 리뷰 1회(기각 — 사용자 지정 횟수 미달), 3회 리뷰만 받고 수정 없이 머지(기각 — 지적 반영 의무가 없음), merge 후 사후 리뷰(기각 — 결함이 main에 먼저 들어감).
- Consequences: PR마다 최소 세 개의 독립 리뷰 기록, 지적별 수정 추적, 수정 후 관련 검증이 필요하다. 열린 `fix-required`가 하나라도 있으면 merge 금지다. 리뷰 3회 완료는 merge 권한을 자동 부여하지 않으며 사용자 승인 절차를 대체하지 않는다.
- Approved by: 사용자 (2026-07-31, "그리고 앞으론 PR 올린 후에 자체 리뷰를 3번 받고 수정 한 다음에 머지하도록 해." / "PR에서 멘션하면 리뷰 가능하잖아. 그걸 이야기 하는거야.")

---

## ADR-LITE-027 이미 릴리스된 마이그레이션을 확장하지 않고 새 전진 마이그레이션으로 수리한다

- Date: 2026-08-04
- Status: approved
- Decision: 이미 배포되어 `flyway_schema_history`에 기록된 마이그레이션(V26)은 확장하지 않는다. 수리가 필요하면 아직 어떤 월드도 기록하지 않은 **새 전진 마이그레이션**에 수리 로직 전체를 넣는다. `codex/fix-possession-five-stats`에서 V26과 그 테스트는 `origin/main` 기준으로 바이트 단위 원복했고, RTK14 NPC 수명주기 수리는 world-scoped `V38__rtk14_npc_lifecycle_repair.kt` 하나로 통합했다. 또한 `origin/main`이 이미 `V36__diplomacy_casualties.sql`을 싣고 있으므로 이 PR의 claim-request 마이그레이션은 `V37__general_owner_claim_request.sql`로 리넘버했다(같은 버전 2개 = Flyway duplicate version 실패).
- Context: 리뷰가 P1으로 지적한 대로, V26을 이미 기록한 DB는 확장된 V26 로직을 절대 재실행하지 않아 업그레이드된 월드가 조기 활성화·오유예 장수를 그대로 안고 간다. 부팅 순서를 추적해 확인한 추가 사실: Flyway는 `ScenarioSeedRunner`(ApplicationRunner)보다 먼저 실행되고 `JdbcOperations` 빈이 `flywayInitializer`에 의존하므로, 신규 DB에서는 V26 실행 시점에 `world_state`가 비어 있어 V26이 즉시 반환한다. 즉 V26 확장은 기존 월드에는 정의상 도달 불가, 신규 월드에는 목적상 도달 불가였다.
- Alternatives: 확장된 V26 유지 + 보완용 신규 마이그레이션 추가(기각 — 수리 로직이 두 곳으로 갈라져 신규 마이그레이션이 확장 V26의 정확한 여집합이어야만 수렴하고, 총 diff도 더 크다), V26 확장만 유지(기각 — 리뷰 P1 그대로), Flyway repeatable/baseline 재설정(기각 — 운영 DB 이력 조작).
- Consequences: 수리 로직이 한 곳에만 존재하고 모든 월드가 V38을 정확히 한 번 실행하므로, 이미 마이그레이션된 월드와 새로 시드된 월드가 구조적으로 같은 최종 상태로 수렴한다. V26 원복으로 사라진 테스트 커버리지(external-only 해석, external-over-classpath 우선순위, nation별 deferred identity, 중복 future-appearance fail-closed, 시나리오 누락 fail-closed)는 `V38Rtk14NpcLifecycleRepairMigrationTest`로 옮겼고 malformed external override 롤백 케이스를 추가했다. 대가: V25 이하에서 처음 올라오는 아주 오래된 DB는 원복된 V26의 엄격한 `(name, nationId, bornYear)` 매칭을 그대로 만나며, 이는 `origin/main`의 기존 동작이다.
- Approved by: 리뷰 지적(P1, chatgpt-codex-connector) 및 팀 지시. 사용자 merge 승인은 별도이며 이 ADR은 merge·배포를 승인하지 않는다.

---

## ADR-LITE-028 origin/main 머지 충돌은 골든이 이긴 쪽으로 해소한다

- Date: 2026-08-04
- Status: approved
- Decision: `codex/fix-possession-five-stats` ← `origin/main` 머지의 코드 충돌 5건은 "양쪽 의도 보존, 단 골든/패러티가 이긴다" 원칙으로 해소했다. (1) `GeneralBuilder.npcText`는 이 PR의 `String = ""` 대신 main의 `String? = null`을 채택하고 PR의 RTK14 필드(`politics`/`charm`/`appearanceYear`/`rtkMetadata`)는 유지한다. (2) `BuiltGeneralMapper`는 PR의 meta 맵 호이스팅 구조를 유지하되 main이 추가한 11개 키를 main의 삽입 순서 그대로 병합해 43개 항목을 만든다. (3) `ScenarioSeedRunner`는 PR의 `scenarioResolver`와 main의 `turnTerm`을 모두 선언한다. (4) `web/game/lib/types.ts`는 main의 `settings` 블록을 PR의 2-space 인덴트로 흡수한다. (5) `my-boss-route.test.tsx`는 main의 신규 테스트를 모두 살리고 `genlist` 기대값은 `[10, 42]`로 둔다.
- Context: `npcText`는 스타일이 아니라 의미 차이다. `GeneralBuilderGoldenTest`가 "명시적 빈 문자열(`""`)"과 "미설정(`null`)"을 구분하고 `GeneralAI`가 npcmsg truthiness로 RNG draw 하나를 게이팅하므로, `text ?: ""`는 골든을 깨뜨린다. meta 키 삽입 순서는 CLAUDE.md 규칙 6의 패러티 대상이다. `genlist`는 컴포넌트 차이가 아니라 픽스처 차이였다 — 이 PR의 픽스처가 순욱(id 10)을 `ambassador`로 표시해 피커가 미리 선택하고, 허저(42) 클릭은 `toggleSelection`이 append하므로 `[10, 42]`가 맞다.
- Alternatives: 각 충돌에서 한쪽을 통째로 채택(기각 — 양쪽 모두 실제 작업이라 한쪽을 버리면 기능이나 골든이 사라진다), `npcText`를 PR 쪽 비-null로 되돌리기(기각 — 골든 게이트 위반).
- Consequences: 병합 결과는 양쪽 기능을 모두 보존하며 골든/패러티 계약을 깨지 않는다. 미해소 리스크로 기록: `BuiltGeneralMapper`는 `"npcmsg": null` 키를 항상 쓰지만 `ScenarioImporter`는 미설정 시 키를 생략하므로 두 경로의 jsonb 형태가 다르다. 둘 다 이 머지 이전부터 존재했고 현재 게이트되지 않는다 — 두 경로를 함께 바이트 비교하는 게이트가 생기면 충돌한다.
- Approved by: 팀 지시(충돌 해소 시 한쪽을 통째로 버리지 말 것). 사용자 merge 승인은 별도다.

---

## ADR-LITE-029 OPENSAM-35는 격리 probe로 닫고 실제 v2 leaf는 OPENSAM-150에서 증명한다

- Date: 2026-08-08
- Status: approved
- Decision: OPENSAM-35 S5의 DB 수용 기준을 **v2 스택 전용 probe 이벤트 행 존재 + v1 기본 이벤트 12행 미적재**로 확정한다. 실제 v2 leaf 행 존재는 OPENSAM-150의 필수 수용 기준으로 이관하며, OPENSAM-150은 같은 격리 DB에서 실제 v2 schema/content leaf와 v1 기본 12행 0을 함께 재측정해야 한다.
- Context: OPENSAM-35는 v2 런타임 코드가 0건인 상태에서 production 격리 게이트를 선설치하는 티켓이고, OPENSAM-150 `v2_city_ledger` 스키마는 명시적 비범위다. 기존 계약은 0A에서 실제 v2 leaf를 요구하면서 동시에 그 leaf를 만드는 티켓을 비범위로 두어 모순이었다. S5 실측은 v2 전용 DB/world와 probe 이벤트 2행, v1 기본 이벤트 12행 0을 이미 증명했다.
- Alternatives: OPENSAM-35에 가짜 v2 leaf를 추가(기각 — 콘텐츠·스키마 날조이자 OPENSAM-150 범위 침범), OPENSAM-150 완료까지 OPENSAM-35 병합 보류(기각 — consumer가 foundation 격리 게이트를 소비해야 하므로 의존 순서 역전), leaf 기준 삭제(기각 — 실제 consumer 티켓에서 반드시 증명해야 한다).
- Consequences: 0A는 격리 능력만으로 종결할 수 있고 OPENSAM-150은 실제 v2 leaf를 반드시 추가·실측해야 한다. probe는 제품 콘텐츠로 커밋하지 않으며, v1 기본 12행 미적재 불변식은 두 티켓 모두에서 유지한다.
- Approved by: 사용자 (2026-08-08, "승인.")

---

## ADR-LITE-030 OPENSAM-43는 고정된 기존 도시 입력으로 열고 G0·1,180 선행을 해제한다

- Date: 2026-08-09
- Status: approved
- Decision: OPENSAM-43 V2-0B의 유일한 도시 입력은 추적 중인
  `infra/src/main/resources/scenario/cities_1010.json`이다. 이 파일을 복제하지 않고
  `content/v2/cities_1010.json`의 7필드 **메타데이터 참조**만으로 가리킨다. 고정 SHA-256은
  `6759a68255cae1a6b9c05cbbaf5736ed8fc9fcb50c6623be44d7e3dfe0b4d393`, 총 도시는 94,
  `nation_id != 0`인 시나리오 소유 도시는 24다. 같은 입력을 두 번 typed snapshot으로 적재한
  in-memory diff는 0이어야 한다. 이 승인으로 구 OP43의 G0 통과·counter 1,180·gameplay
  `CountyParticipationFixture` 선행은 **정식으로 supersede**한다. 단 V2-G0 자체와 1,180
  콘텐츠/fixture는 폐기하지 않고 v2 오픈 후 작업으로 보존한다.
- Context: 이전 backlog micro의 V2-0B header와 0B-g가 G0·1,180을 OP43 acceptance로
  기록해, 승인된 runtime-contract plan의 실제 입력·94/24·repeat-diff 계약과 충돌했다.
  `content/v2/cities_1010.json`은 도시 행 사본이 아니라 source path/hash/count를 담는
  메타데이터이며, source payload는 계속 `scenario/cities_1010.json`이다.
- Alternatives: G0/counter 1,180을 OP43 선행으로 유지(기각 — 승인된 빠른 v2 오픈 경로와
  충돌), 도시 행을 `content/v2`에 재복사(기각 — 입력 이중 진실·drift 위험), G0를 삭제
  처리(기각 — 오픈 후 역사 지리·CountyParticipationFixture 목표를 소실시킴).
- Consequences: 0B-a는 새 v1 완료 주장을 복사하지 않고 canonical v1 completion ledger를
  참조한다. 0B-g는 metadata → 기존 tracked source → typed snapshot 검증까지만 맡는다.
  0B-j는 실제 world/profile/catalog/wire/Flyway read·write seam을 inventory로 남기되
  persistence를 완료로 주장하지 않는다. OPENSAM-44/150의 실제 v2 schema·leaf, OPENSAM-104/105
  RTK builder, G0, production deploy/cutover는 이 승인 범위가 아니다.
- Approved by: 사용자 (2026-08-09, OPENSAM-43 V2-0B runtime contract `"승인."`)

---

## ADR-LITE-031 OPENSAM-113의 A3 선택 gate와 PHP parity evidence gate는 별개다

- Date: 2026-08-13
- Status: proposed
- Decision (pending human approval): 2026-07-17 승인 실행 계약을 그대로 따른다. A3는 사용자가 concept 1개를 선택하는 hard gate이며,
  PHP-golden draw-for-draw parity는 패러티 대상 산출물에 적용되는 A2/출시 전 evidence gate다. 이 lane의
  parity는 실행 전까지 `채점대기`이며 A3를 재정의하지 않는다.
- Context: PR #398 remediation 중 연구 문서와 ownership ledger가 parity를 A3 blocker로 합쳤지만, 정본 실행
  계약은 A3를 concept 선택으로만 정의하고 parity evidence를 A2에 둔다. 계약을 조용히 바꾸면 downstream
  agent가 서로 다른 gate를 따르게 된다.
- Alternatives: parity를 A3 prerequisite로 승격(기각 — 사용자 승인된 계약의 무단 변경), parity 요구를 삭제(기각 —
  프로젝트 패러티 규율 위반), A2/출시 전 별도 gate로 유지(채택).
- Consequences: human approval 전에는 이 ADR을 새 authorization으로 사용할 수 없고 기존 실행 계약이 정본이다.
  Concept 선택 전 OPENSAM-114/115 implementation은 계속 금지된다. parity/live/independent visual evidence가
  없으면 해당 gate는 `채점대기`이며 A3 선택이나 synthetic evidence가 이를 통과시키지 않는다.
- Approved by: NONE — human approval required. Existing execution-contract approval remains canonical; user concept
  selection, parity replay, and implementation approvals are separate.

---

## ADR-LITE-032 P-4 작전 replay 계약과 BattleTicket 전투 세션 계약은 두 계층으로 병존한다

- Date: 2026-08-16
- Status: approved
- Decision: **P-4(`ReplayEnvelope`/`DeterministicReplayBody`/`deterministicReplayHash`)와 ADR-LITE-025의 `BattleTicket` 세션 모델은 병존한다.** 계층 경계는 스코프 키로 긋는다 — **P-4 = 작전(Operation) 단위 사후 리플레이 계약**(키 `operationId`), **07-30 세션 스펙 = 전투 인스턴스 단위 실시간 세션 계약**(키 `battle_id`). 어느 쪽도 다른 쪽을 폐기하지 않는다. 함께 **P-13 전술 엔진 7종은 07-30 계열에 (a) 포함으로 판정**하며 생존 형태는 **동명 생존 4종**(`BattleState`·`BattleClock`·`BattleEvent`·`BattleReplay`) / **개명 생존 2종**(`OrderIntent` → 07-30 §8 명령 상태기계, `BattleServerAuthority` → `BattleAuthoritySnapshot` + §5 소유권 불변식) / **어댑터 이연 1종**(`BattleTopology`)이다. 동결값 `ContinuousTopology + REALTIME_FIXED_TICK`은 유지한다. 계약 동결 문서(`docs/superpowers/specs/2026-08-16-v2-contract-freeze-p1-p15.md`, 브랜치 `op-73-75-contract-freeze` / PR #405) §OPEN QUESTION **Q1·Q3은 이 ADR로 닫힌다**(그 문서 자체는 이 ADR이 수정하지 않는다).
- Context: 판정 근거 원본은 `docs/superpowers/research/2026-08-16-v2-battle-canon-reconcile-p4-p13.md`(레인 G, 297줄)다. 결정적 근거 4개: (1) `.ai/decisions.md:271` — ADR-LITE-025가 supersede 대상을 **명시 열거**(ADR-019/021의 일정 분류 + 07-28 2.5D 문서 4개 절)하면서 product-spec §6(P-4)·§10(P-13)을 그 목록에 **넣지 않았다**. 열거형 supersede에서의 누락은 침묵이 아니라 비-supersede의 증거다. (2) `docs/superpowers/plans/2026-07-28-v2-2_5d-tactical-battle-and-sprite-design.md:70` — "기존 제품 spec의 … replay 계약을 개정하지 않는다". 동기화 커밋 `3f4d2f2a`가 **같은 문장의 뒷절만 재작성**하고 이 앞절은 보존했다 = 침묵이 아니라 **선택적 보존**. 같은 커밋은 product-spec을 아예 건드리지 않았다. (3) `docs/superpowers/plans/2026-07-30-v2-realtime-battle-foundation-implementation-plan.md:80,82,92,143` — `deterministic/BattleClock.kt`·`deterministic/BattleState.kt`·`replay/BattleReplayReducer.kt`·`persistence/BattleEventRepository.kt`가 P-13 7종 중 4종을 **파일명 그대로** 되살린다("07-30이 7종을 한 번도 언급하지 않는다"는 스펙 파일에 한해 참). (4) Jira에서 **아무도 닫지 않았다** — OPENSAM-24(P-4 소유, V2-4A replay spine)·OPENSAM-21(P-13 소유, Spike B0) 둘 다 여전히 "할 일"이다.
- Alternatives: **(b) 대체 — ADR-LITE-025가 P-4/P-13을 폐기했다: 기각.** 위 근거 1·2·3·4가 각각 독립적으로 (b)를 무너뜨린다. 백로그 `README.md:77`의 "V2-4A 대체·재분해"는 **티켓 층위** 서술이며, 같은 README:28의 라벨 분리 규칙(스펙 티켓=계약 동결 / 계획 티켓=구현)상 구현 티켓 교체가 동결된 계약을 자동 폐기하지 않는다. **(a) 포함 — 어휘만 다르다: P-13에는 성립, P-4에는 기각**(`operationId`·`normalizedLogEntries` 대응물 0건, `phases[]` 7값 미열거, 단일 다이제스트 ↔ checkpoint 해시 체인은 다른 산출물). **(d) 판정 불가: Q3에는 부적용**(동명 부활이 직접 증거), Q1에는 형식 등급으로 성립했으나 이 비준으로 해소한다.
- Consequences: **남는 경계 문제** — `operationId`와 `normalizedLogEntries`는 07-30 세션 모델에 대응물이 **0건**이다(작전층 전용 필드로 남는다). `phases[]`(APPROACH/SCOUT/INTERCEPT/FIELD/SIEGE/URBAN/AFTERMATH)는 **한 작전 안의 순차 단계 축**이고 07-30 어댑터는 **전투 종류 축**(야전/공성/수전)이라 두 목록은 같은 축이 아니다 — 특히 ADR-LITE-025가 출시 필수로 넣은 **수전은 `phases[]`에 자리가 없다**. 이 경계는 이 ADR이 닫지 않고 **H2로 이연**해 별도 티켓에서 결정한다(마감선 = BATTLE-F2 착수 전). **BATTLE 트랙 영향** — F0(OPENSAM-156)·F1(157)은 P-4/P-13 어휘를 소비하지 않으므로 **착수 안전**이다. 위험 시작점은 **F2(158)** — `BattleTicketV1`과 버전/아티팩트 레지스트리 이름이 여기서 동결되고 F3(159)에서 해시 형태가 굳는다. **F12(168)가 만드는 G1 checkpoint state hash 게이트는 P-15d(`DeterministicReplayBody` hash diff 0)와 다른 산출물**이므로 현행대로면 P-15d는 미측정으로 남는다 — 소유자 공백은 실재 결함이며 별도 티켓으로 등록한다. 잔여 리스크: P-13b 불변식("사각형/육각형 grid와 연속 좌표 지형을 같은 위치·이동·충돌 계약으로")과 P-13e("부곡도 같은 부대 인터페이스로")를 07-30 계열 문서 어디도 재진술하지 않아, 어댑터 3종이 각자 좌표·부대 모델을 만들면 조용히 깨진다. **수정 금지**: 07-28 2.5D 문서 `:70`은 (b) 기각의 핵심 증거이므로 변경하지 않는다.
- Approved by: 사용자 (2026-08-16, H1 = (c) 병존 비준 · H8 = Q3 종결 두 건 승인). 판정 근거 원본: `docs/superpowers/research/2026-08-16-v2-battle-canon-reconcile-p4-p13.md`(커밋 `8608a90f`). 이 ADR은 product-spec 개정·Jira 상태 전이·merge·배포를 승인하지 않는다.

---

## ADR-LITE-033 `BattleTopology`는 BATTLE-F2의 어댑터 SPI에 선치한다 (H7)

- Date: 2026-08-17
- Status: proposed
- Decision (pending human approval): H7 세 선택지 중 **(1) BATTLE-F2(OPENSAM-158)의 `BattleRulesAdapter` SPI에 선치**를 채택한다. `BattleTopology`(위치·이동·충돌 계약)와 그 계약이 다루는 **부대 핸들 인터페이스**를 F2의 범위·수용 기준에 이름으로 명시하고, 야전/공성/수전 어댑터 에픽(OPENSAM-170/171/172)은 그 SPI를 **소비만** 한다. 동결 초기 구현값은 ADR-LITE-032가 유지한 `ContinuousTopology`다. OPENSAM-21(Spike B0)은 `BattleTopology`를 범위로 갖지 않는다 — 명명 티켓은 **OPENSAM-158 하나뿐**이다.
- Context: ADR-LITE-032 Consequences가 남긴 잔여 리스크 — P-13b 불변식("사각형/육각형 grid와 연속 좌표 지형을 **같은** 위치·이동·충돌 계약으로", `docs/superpowers/specs/2026-07-12-opensamguk-v2-product-spec.md:423`)과 P-13e("삼국지 부곡도 **같은** 부대 인터페이스로", `:426`)를 07-30 계열 문서가 어디에서도 재진술하지 않는다. 판정 원본 `docs/superpowers/research/2026-08-16-v2-battle-canon-reconcile-p4-p13.md:211`은 `BattleTopology`를 7종 중 유일하게 **어느 티켓에도 없는 결함**으로 기록했고, 같은 문서 `:240`(R7)·`:280`(H7)이 마감선을 "어댑터 에픽 발행 전"으로 잡았다. F2는 아직 `할 일`이고 현재 범위가 `BattleTopology`를 언급하지 않으므로 (1)안이 여전히 가능하다 — F2가 착수되면 SPI 시그니처가 굳어 선치 창이 닫힌다.
- Alternatives: **(2) 어댑터 3종 자율 + 사후 게이트: 기각.** 좌표·부대 모델을 세 에픽이 각자 만든 뒤 사후에 맞추는 것은 P-13b/P-13e를 "조용히 깨진 다음 발견"하는 순서이고, 이미 굳은 세 구현을 되돌리는 비용이 SPI 한 줄을 미리 못박는 비용보다 크다. **(3) OPENSAM-21에 흡수: 기각.** ADR-LITE-032가 7종 중 4종을 F2/F3 구현으로 판정해 B0의 계약 범위는 이미 축소됐고(연구문서 `:238` R5), 어댑터가 실제로 소비하는 지점은 F2의 SPI다 — 계약을 소비처와 다른 티켓에 두면 F2가 SPI를 먼저 동결해 B0 결정이 사후 추인이 된다.
- Consequences: **범위 증가는 F2에 국한**된다 — F2 수용 기준에 "grid 지형과 연속 좌표 지형이 동일한 위치·이동·충돌 인터페이스를 통과하고, 부곡 부대가 동일 부대 인터페이스를 쓴다"를 **테스트로** 요구하는 항목이 추가되므로 F2 공수가 늘어난다. 그 테스트는 실제 규칙이 아니라 **두 개의 최소 지형 구현(grid 1 + 연속 1)이 같은 SPI를 통과함**만 고정한다(전투 규칙은 F2 비범위 유지). `FormationModel`(P-13e) 전체 모델은 여전히 어댑터 이연이며, 이 ADR이 F2로 끌어오는 것은 **부대 핸들 인터페이스**뿐이다. 미해결로 남는 인접 공백: H2(`phases[]` 축 대 어댑터 축, 수전 자리 없음)와 P-15d 미측정은 이 ADR이 닫지 않는다. v1 패러티(logic/war·PHP golden·RNG·로그)는 무관·무변경이며 이 ADR은 코드를 만들지 않는다.
- Approved by: NONE — human approval required. 이 ADR은 product-spec 개정·구현 착수·merge·배포를 승인하지 않는다. 티켓 범위 편집(OPENSAM-158)은 되돌릴 수 있는 기록 행위로서 선반영하되, F2 착수 승인은 별건이다.

---

## ADR-LITE-034 OPENSAM-21(Spike B0)은 개명 2종 이름 대응만 남기고 축소한다 (H5)

- Date: 2026-08-17
- Status: proposed
- Decision (pending human approval): H5 세 선택지 중 **(1) 잔여 범위 축소**를 채택한다. OPENSAM-21의 B0 절은 동명 생존 4종(`BattleState`·`BattleClock`·`BattleEvent`·`BattleReplay`)을 BATTLE-F2/F3/F5(OPENSAM-158/159/161)에 **위임**하고, `BattleTopology`는 ADR-LITE-033에 따라 OPENSAM-158 단독 소유이므로 B0 범위에서 뺀다. 따라서 **B0의 실제 잔여는 개명 2종의 이름 대응 기록 하나**다. C0(콘텐츠 lifecycle)는 무변경이며 에픽을 종료하지 않는다(옵션 2 기각) — C0가 살아 있기 때문이다.

  **개명 대응표(이 ADR이 그 기록이다):**

  | 구 이름 (P-13, product-spec §10) | 신 계약 (07-30 계열) |
  |---|---|
  | `OrderIntent` (P-13d) | 07-30 실시간 전투 세션·명령·리플레이 설계 **§8 명령 상태기계** |
  | `BattleServerAuthority` (P-13g) | `BattleAuthoritySnapshot` + 같은 스펙 **§5 소유권 불변식** |

- Context: ADR-LITE-032가 P-13 7종을 07-30 계열에 (a) 포함으로 판정했고(근거 원본 `docs/superpowers/research/2026-08-16-v2-battle-canon-reconcile-p4-p13.md` §2.4·§4·R5), 07-30 구현 계획 `:80,82,92,143`이 4종을 파일명 그대로 되살린다. OPENSAM-21 본문은 그 판정 전에 쓰인 채 "grid/연속좌표 두 topology 공통 BattleState·OrderIntent·BattleEvent/Replay 직렬화 계약"을 여전히 자기 범위로 선언하고 있었다 — 손대지 않으면 B0와 BATTLE-F2/F3/F5가 같은 계약을 **이중 착수**한다.
- Alternatives: **(2) 종료: 기각.** OPENSAM-21은 B0+C0 합본 에픽이고 C0(FormationTemplate/Facility/… + CatalogBudget lifecycle + dangling fixture 4종)는 어느 판정도 건드리지 않았다. B0만의 사유로 에픽을 닫으면 C0가 소유자를 잃는다. **(3) 현행 유지: 기각.** 이중 착수 위험이 실재하고 마감선(F2 착수 전)이 임박했다.
- Consequences: OPENSAM-21 본문에 위임 표와 "이 티켓이 동명 4종·`BattleTopology`에 어떤 계약도 새로 정의하지 않는다"는 잔여 수용 기준 2번을 넣었다. **마이크로 티켓 B0-a~g는 축소를 아직 반영하지 않았다** — 백로그 문서(`docs/superpowers/plans/2026-07-17-v2-ticket-backlog/01-backbone-micro.md:61` 등) 갱신은 이 ADR이 하지 않고 남긴다. 상태·라벨·담당자 변경은 하지 않았다(OPENSAM-181 비범위 준수). v1 패러티 무관·코드 무변경.
- Approved by: NONE — human approval required. 이 ADR은 OPENSAM-21의 상태 전이·종료·구현 착수를 승인하지 않는다.

---

## ADR-LITE-035 P-15d 게이트는 OPENSAM-57이 소유하고 V2-4A는 작전층 경계만 갖는다 (H3+H4)

- Date: 2026-08-17
- Status: proposed
- Decision (pending human approval): **H3 = (1)안, H4 = (1)안**을 함께 채택한다. 두 결정은 같은 레인의 앞뒤라 하나로 기록한다.
  1. **P-15d 소유** — `P-15d`(동일 입력 재실행 시 `DeterministicReplayBody` hash diff 0, `docs/superpowers/specs/2026-07-12-opensamguk-v2-product-spec.md:449`)를 이름으로 명시한 수용 기준은 **OPENSAM-57 하나**가 갖는다. BATTLE-F3(OPENSAM-159)에는 **작전층 단일 다이제스트 파생 훅**을 범위·수용 기준 3번으로 넣되 `P-15d` 토큰은 넣지 않는다 — "정확히 1개 티켓" 조건을 지키기 위해서다.
  2. **V2-4A 처분** — OPENSAM-24(에픽)는 `operationId` 작전층 ↔ `battle_id` 세션층 **경계·스코프만** 소유하고 07-30 세션 계약을 재구현하지 않는다. 중복이던 4A-a~j 구현 목록은 본문에서 걷어내고 **자식 OPENSAM-57 단독 소유**로 남긴다(같은 산출물 소유 티켓 = 1개).
- Context: H3은 실재 결함이다 — F12(OPENSAM-168)의 G1 checkpoint state hash 게이트는 P-15d와 다른 산출물이고(ADR-LITE-032 Consequences), 07-30 구현 계획은 product-spec을 0회 참조하므로 현행대로면 P-15d가 미측정으로 남는다. H4는 `README.md:77`("V2-4A 대체·재분해")와 Jira 현황(OPENSAM-24 `할 일`, BATTLE-F0~F13 부모가 전부 OPENSAM-25)의 불일치다. ADR-LITE-032가 (c) 병존을 비준했으므로 작전층 replay 계약은 살아 있고, 살아 있는 계약에 소유 티켓이 붙어 있어야 한다. 마감선은 F3 착수 전(해시 형태가 F3에서 굳는다)이며 F3은 아직 `할 일`이다.
- Alternatives: **H3 (2) P-15d를 checkpoint-hash 기준으로 개정해 F12 귀속: 기각.** 그것은 product-spec §6/§15 **본문 개정**이고, 개정 권한 규칙 자체가 아직 없다(H6 = OPENSAM-182 미결). 권한이 정의되기 전에 정본을 고치는 것이 이번 사고의 재발이다. **H3 (3) 신규 게이트 티켓: 기각** — 이미 4A-g(replay body hash)·4A-i(replay gate)를 가진 OPENSAM-57과 산출물이 겹쳐 소유 중복을 새로 만든다. **H4 (2) 종료: 기각** — 병존 비준으로 작전층 계약이 살아 있는데 소유 티켓을 없애면 P-15d가 다시 고아가 된다. **H4 (3) 현행 유지: 기각** — 24와 57이 같은 목록을 중복 선언한 상태가 AC2 위반이다.
- Consequences: F3의 공수가 파생 훅 + 결정성 테스트만큼 늘어난다(전투 규칙은 여전히 비범위). 훅의 **구체 알고리즘**(체인 → 단일 다이제스트 축약 방식)은 아직 정의돼 있지 않다 — F3 구현 시 결정하며 이 ADR은 "결정적일 것"과 "wall-clock 제외"만 요구한다. `phases[]` 축과 수전 문제(H2/OPENSAM-178)는 이 ADR이 닫지 않는다. product-spec 본문은 **한 글자도 고치지 않았다**(H6 미결 존중). 상태·라벨·담당자 변경 없음. v1 패러티 무관·코드 무변경.
- Approved by: NONE — human approval required. 이 ADR은 product-spec 개정·구현 착수·티켓 종료·merge·배포를 승인하지 않는다.

---

## ADR-LITE-036 product-spec 개정 권한 규칙을 v2 백로그 README에 둔다 (H6)

- Date: 2026-08-17
- Status: proposed
- Decision (pending human approval): product-spec(정본) 개정 절차·권한을 **v2 백로그 `README.md`의 새 절 "정본 개정 규칙"**으로 고정한다. 네 조항 — (1) 값·키·불변식·산출물이 달라지면 개정 시점이다, (2) 본문 수정은 **사람 승인** 필수이며 ADR-LITE 기록이 의무이고 수정 커밋은 그 번호를 인용한다, (3) 새 스펙이 product-spec 절을 대체하면 **절 번호를 명시 열거**해야 하고 열거되지 않은 절은 살아 있는 것으로 간주한다("언급하지 않음"은 유효한 상태가 아니다), (4) 후속 구현 계획의 Spec-to-Task 표는 자기가 구현·대체하는 product-spec 항목 행을 포함해야 한다.
- Context: 규칙 부재가 이번 Q1/Q3 충돌의 구조적 원인이다. ADR-LITE-025는 supersede 대상을 열거하면서 product-spec §6·§10을 넣지도 빼지도 않았고, 동기화 커밋 `3f4d2f2a`는 5개 파일을 고치면서 product-spec을 건드리지 않았으며, 07-30 계획의 Spec-to-Task 표는 product-spec을 0회 참조한다(연구문서 §1 E1/E2·§3 (b) 반대 3·R8·H6). 규칙을 정본 판정 절 바로 뒤에 둔 것은 "무엇이 이기는가"와 "그것을 어떻게 고치는가"가 붙어 있어야 읽히기 때문이다.
- Alternatives: 별도 규칙 문서 신설(기각 — 정본 판정 규칙과 떨어지면 읽히지 않고, 규칙 문서 자체의 정본성이 또 문제가 된다), CLAUDE.md에 편입(기각 — CLAUDE.md는 저장소 전역 규율이고 이 규칙은 v2 스펙 계열에 한정된다), 규칙 없이 사례별 판단(기각 — 그것이 현 상태이며 사고를 만들었다).
- Consequences: 앞으로 product-spec 절과 충돌하는 스펙·계획은 **개정 또는 비개정을 한 줄로 명시**해야 하고, traceability 표에 product-spec 행이 없는 계획은 리뷰에서 막힌다 — 계획 문서 작성 비용이 조금 늘어난다. 소급 적용은 하지 않는다(기존 문서를 일괄 재감사하지 않는다). **product-spec 본문은 이 결정으로 한 글자도 바뀌지 않는다.** 이 규칙이 서면서 H2(OPENSAM-178, `phases[]` 수전 축)가 product-spec §6을 실제로 고치려면 사람 승인 + ADR-LITE 경로를 밟게 된다.
- Approved by: NONE — human approval required. 이 ADR은 product-spec 개정 자체를 승인하지 않으며, 규칙의 발효 역시 사람 비준을 전제로 한다.

---

## ADR-LITE-037 `phases[]`는 작전층 순차 단계 축, 전투 종류는 별도 축이다 (H2)

- Date: 2026-08-18
- Status: approved
- Decision: H2 세 선택지 중 **(3) 축 분리**를 채택한다. `phases[]`(APPROACH/SCOUT/INTERCEPT/FIELD/SIEGE/URBAN/AFTERMATH)는 **한 작전 안의 순차 단계 축**으로 한정하고, 야전·공성·수전은 **전투 종류 축(battle type)**으로 분리한다. 두 축의 관계는 "한 phase 안에서 0..N개의 전투가 열리고 각 전투가 battle type 하나를 갖는다"이며, 이 문장을 `docs/superpowers/specs/2026-07-12-opensamguk-v2-product-spec.md` §6 `BattleReplay` 절에 넣었다. **수전은 phase 값이 아니다** — `phases[]`에 `NAVAL`을 추가하지 않는다.
- Context: ADR-LITE-032가 이연한 유일한 경계 문제다. `phases[]` 7값은 순차 단계 축인데 07-30 어댑터 3종은 전투 종류 축이라 두 목록이 같은 축이 아니고, ADR-LITE-025가 출시 필수로 넣은 수전에는 `phases[]` 자리가 없다(연구문서 §2.5·R2·H2). 마감선은 BATTLE-F2(OPENSAM-158) 착수 전 — F2에서 `BattleTicketV1`과 레지스트리 이름이 동결된다.
- Alternatives: **(1) `phases[]`에 `NAVAL` 추가: 기각.** 가장 작은 수정이지만 순차 단계 축에 전투 종류를 섞는다 — `NAVAL`은 "순서상 어디"가 아니라 "무엇으로 싸우나"이고, 수전 뒤에 URBAN이 오는 작전을 표현할 수 없다. FIELD/SIEGE의 이중 의미도 그대로 남는다. **(2) battle type 선언만 하고 `phases[]`는 무언급: 기각.** 결과는 (3)과 비슷하나 두 축의 관계를 명시하지 않아 티켓 AC2("한 문장으로 명시")를 절반만 만족한다.
- Consequences: FIELD/SIEGE가 "단계이자 종류"로 이중 해석되던 것이 해소된다 — 같은 이름이 두 축에 있으므로 **F2가 battle type 열거를 동결할 때 phase 이름과 구분되는 표기를 쓸 것**(예: `FIELD_BATTLE`)을 권고한다. `battle type` 열거의 정식 값·이름은 이 ADR이 동결하지 않는다(F2 소유, OPENSAM-158). 작전층 `phases[]` 7값 자체는 **변경 없음** — 값 추가·삭제 없이 의미만 한정했다. 이 개정은 ADR-LITE-036(H6) 개정 규칙의 첫 적용 사례다: 사람 승인 → ADR 기록 → 본문 수정 커밋이 ADR 번호 인용.
- Approved by: 사용자 (2026-08-18, H2 = (3) 축 분리 선택). 이 ADR은 §6의 위 한 문장 외 product-spec 개정, F2 착수 승인, merge·배포를 승인하지 않는다.

---

## ADR-LITE-038 치소와 비치소 촌락은 구분해서 넣는다 — 촌락 등급은 미결정

- Date: 2026-08-18
- Status: proposed
- Decision: 신규 맵 데이터/임포터는 **치소(州治·郡治·縣治)와 비치소 촌락(鄕·亭)을 구분**해서 넣는다. 縣治를 `city.level` 3("관" = 관문)으로 보내지 않는다. **촌락의 표현 방식은 미결정이며, 확정 전까지 촌락을 기존 lv1~4에 밀어넣는 것을 금지한다** — lv1 수(항구)·2 진(목책)·3 관(관문)·4 이(이민족)는 의미가 이미 차 있고 도시 아이콘(`tools/assets/build_city_icons.py`)이 그 의미대로 그려져 있다.
- Context: 사용자 요구(2026-08-18). 원본 지명 데이터는 이 구분을 이미 갖고 있다 — 260년 지점 데이터셋 `tier_label`이 首都/王都 27 · 州治 31 · 郡治 148(치소)과 縣 1229 · 鄕/亭/舊縣 1359(하급 취락)를 별도 라벨로 구분한다. 구분이 사라지는 지점은 게임 매핑이다: 縣(1229)이 lv3 "관"으로 매핑돼 행정 취락이 군사 관문으로 둔갑하고, 鄕/亭(1359)은 "옵션"으로 남아 실제로 들어오지 않는다. 현행 lv1~8 사다리에는 촌락 슬롯 자체가 없다(5~8이 전부 치소).
- Alternatives: **(a) 촌락에 새 level 값 부여** — 사다리 한 칸 추가로 끝나지만 level이 "규모 축"과 "종류 축"을 계속 겸하게 된다. **(b) level과 직교하는 `kind`/`isSeat` 필드 추가** — 데이터 원형에 가깝지만 `city.level` 소비처(command 가용성·NPC 행동·아이콘·아우라 크기)를 전부 훑어야 한다. **둘 다 이 ADR에서 채택하지 않는다(UNKNOWN)** — 실제 신규 맵 작업이 착수될 때 결정한다. 지금 고르면 소비처 실측 없이 고르는 것이다.
- Consequences: 신규 맵 임포터 작업이 착수되기 전까지 코드 변경은 **없다**. 기존 che 맵과 시드 데이터는 불변이며 v1 패러티(RNG·로그·골든)에 무관하다. 이 ADR은 금지 규칙만 발효시킨다 — 촌락을 lv1~4로 밀어넣는 PR은 이 번호를 근거로 막힌다. 긴 설명·데이터 통계는 로컬 위키 `docs/wiki/pages/data/city-level-convention.md`(gitignored)에 있고, 구속력 있는 규칙은 이 항목이 정본이다.
- Approved by: NONE — human approval required.

---

## ADR-LITE-040 CHGIS 파생 타일맵의 공개 서버 서빙을 승인한다 — 사용자 위험 인수

- Date: 2026-08-18
- Status: approved
- Decision: 후한 군현 타일맵(`data/map/han-tiles.json`, 256×256 지형·소유·도로 격자)을 **공개 게임 서버에서 브라우저로 서빙한다**. ADR-LITE-039 가 남겨 둔 「공개 전환 시점의 조치」를 **서면 계약 없이 통과**시키는 것이며, 그 결정을 사용자가 명시적으로 내렸다. 게임이 소비하는 파생물(`han-tiles.json`)만 커밋 대상으로 올리고, **원본 shapefile·`han-places.json`·`terrain-grid.json` 은 계속 git-ignore·미커밋**으로 둔다.
- Context: 사용자 지시(2026-08-18, "공개 서버긴 한데 누가 이걸 보겠냐. 그냥 올려버려. 내가 책임질테니까"). 직전에 위험을 명시적으로 고지했고 — CHGIS V6 README 의 `no commercial use, resale, or redistribution` 조항상 브라우저로 격자를 내려보내는 것이 재배포에 해당한다는 점, 같은 Dataverse 메타데이터의 `CC0 1.0` 표기와 정면 충돌하며 CC0 출처가 여전히 UNKNOWN 이라는 점 — 사용자가 그 위에서 위험을 인수했다.
- Alternatives: **(a) 좌표 재구축(기각 아님, 보류)** — 續漢書 郡國志(後漢書 卷109~113, 저작권 소멸)에서 군현 목록을 뽑고 좌표를 Wikidata(CC0)/OSM 으로 붙이면 배포물에서 CHGIS 가 사라진다. 재료는 이미 사료 인덱스에 있고 郡治 127 개 커버리지를 재보면 실현성이 판정된다. 위험이 현실화하면 이 경로로 갈아탄다. **(b) 서면 허락 확보** — 표기 충돌을 근거로 문의할 명분이 있으나 회신 시점을 통제할 수 없다. **(c) 공개 연기: 기각** — 사용자가 거부했다.
- Consequences: **위험은 소멸하지 않고 인수됐다.** 권리자 문제 제기 시 (1) 엔드포인트가 404 를 돌려주도록 파일만 내리면 프런트가 기존 맵으로 폴백하므로 **철거는 파일 삭제 한 번**이고, (2) 그 다음 복구 경로가 대안 (a) 다. 상업화는 이 ADR 이 승인하지 않는다 — 유료화·수익화 시점에 다시 판단해야 한다. 원본 데이터셋 재배포(shapefile·전체 좌표 테이블)도 여전히 승인 밖이다.
- Approved by: 사용자 (2026-08-18, 위험 고지 후 명시적 인수). 이 결정의 책임 주체는 사용자다.

---

## ADR-LITE-039 CHGIS/TGAZ 사용을 허용한다 — 사용자 지시, 미커밋 조건

- Date: 2026-08-18
- Status: proposed
- Decision: v2 역사 지도 작업에 **CHGIS V6 / TGAZ 를 사용한다**. ADR-LITE 이전의 차단 판정(`CLAUDE.md` "CHGIS = 번들 금지")을 이 결정이 대체한다. 단 **RTK14 와 동일한 격리 조건**을 건다: 원본 shapefile·DBF·다운로드물과 그로부터 생성한 좌표 데이터는 **git-ignore, 미커밋**이고, 버전 관리 대상은 **추출 스크립트뿐**이다(`tools/map/*.py`). 저장소 번들·CDN·배포 이미지·런타임 allowlist 에 CHGIS 파생물을 올리지 않는다.
- Context: 사용자 지시(2026-08-18, "CHGIS/TGAZ를 써. 이건 명령이야"). 배경은 새 역사 지도 트랙 — 기존 devsam 추상 맵을 버리고 실제 고대 중국 도시 좌표 위에 아이소메트릭 타일 맵을 세우기로 했다. 실측 확인 사항: (a) CHGIS V6 `v6_time_cnty_pts` 는 서기 220년 시점 활성 현 치소 **982개**를 좌표·`PRES_LOC` 현대 비정·`BEG_YR`/`END_YR` 존속 기간과 함께 제공한다 — v2 설계(`docs/superpowers/specs/2026-07-13-v2-historical-city-army-terrain-design.md:299`)가 목표한 후한서 1,180현과 같은 자릿수이고, 그 문서는 이미 CHGIS 를 출처로 열거한다(`:403-416`). (b) V6 의 route 레이어(`v6_Ming_Routes_2016`, `TeaHorse`)는 **명대·차마고도이며 한대 도로가 아니다** — 한대 도로망 공개 데이터셋은 확인되지 않았다.
- Alternatives: **(a) 차단 유지(기존 판정): 기각** — 사용자가 명시적으로 지시했고, 대체 출처가 같은 것을 주지 못한다. Wikidata(CC0)는 현대 대도시 좌표만 있어 한대 현 치소 982개를 못 준다. Natural Earth(퍼블릭 도메인)는 지형·해안선만 준다. **(b) 무조건 사용(원본까지 커밋): 기각** — 재배포가 EULA 가 금지하는 바로 그 행위다. **(c) 채택: 사용하되 미커밋** — RTK14 선례(`CLAUDE.md` 5스탯 divergence 절)와 같은 격리로, 지시를 이행하면서 금지 행위 자체는 피한다.
- Consequences: **잔여 위험은 소멸하지 않는다.** V6 README 원문은 `License: free for academic research, no commercial use, resale, or redistribution permitted.` 이고, 같은 Dataverse 데이터셋의 메타데이터는 `CC0 1.0` 으로 표기한다(`doi:10.7910/DVN/Q9VOF5`, `termsOfUse: None`). 두 표기가 정면 충돌하며 CC0 표기의 출처는 여전히 **UNKNOWN** 이다. 따라서 (1) 이 저장소가 **공개로 전환되거나** (2) 게임이 **상업화되면** 파생 좌표 데이터의 배포가 EULA 조항에 걸린다 — 그 시점에 CHGIS Management Committee 서면 계약을 받거나 파생물을 걷어내야 한다. 이 ADR 은 그 두 시점의 조치를 **미이행 상태로 남긴다**. 학술·비공개 연구 이용은 EULA 가 명시적으로 허용하는 범위다.
- Approved by: 사용자 (2026-08-18, 명시적 지시). 이 ADR 은 CHGIS 파생물의 **공개 배포·상업 이용을 승인하지 않는다** — 미커밋·비공개 조건에서의 사용만 승인한다.

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

## ADR-LITE-041 — 후한 군현 맵(han) 세계 규격 확정 (2026-08-19)

**결정.** v1 게임 세계를 che 94성에서 후한 군현 맵으로 갈아끼운다. 패러티는 이 범위에
한해 사용자가 면제했다(맵·시나리오 배치). 규격:

- **세계** 175郡 · 780城(郡治 175 + 續漢書 郡國志에 이름이 실린 縣 605). 郡國志에 없는
  CHGIS 縣 226개는 소속 郡도 戶 근거도 없어 거점으로 올리지 않는다(지도에도 안 찍는다).
  郡國志 추출이 보강되면 `zhi` 표시만으로 저절로 는다.
- **지역** 14 = 後漢 13州 + `동이`(『三國志』魏書 東夷傳 권역: 부여·고구려·옥저·예·삼한·
  주호·왜 22곳). 이민족 거점(오환·선비·흉노·강·저·애뢰·산월)에는 따로 지역을 두지 않고
  가까운 州에 흡수시킨다. 夷洲·流求는 사료를 따라 揚州.
- **등급** 경(9, 낙양·장안 = 兩京) / 특·대·중·소(郡治, 郡國志 戶 백분위를 che 등급 분포
  모양으로 자름) / 영현(10)·장현(11)(縣, 續漢書 百官志 「萬戶以上為令，不滿為長」,
  縣 개별 戶가 없어 郡戶÷縣수로 파생) / 이(4, 이민족 = 東夷傳의 '道' 성격).
  패러티 상수 `CityConst.levelMap` 은 안 건드리고 `generateCities(levelMap=)` 로 han
  변형만 자기 표를 쓴다.
- **이동로** 縣 영역 인접(郡 안·郡 경계 모두) + 縣↔자기 郡 治所 직결(인접만으로 治所에
  못 닿는 縣 224개 때문에 필수). 郡 경계는 국경 縣을 밟고 넘는다 — 治所끼리 직결은
  縣 통로가 없거나 4홉을 넘는 41/427 쌍에만 둔다. 도로·수로·해로는 사용자가 직접 놓는다.
- **시나리오** `han_ownership.json`(사료 지배표, 15시나리오 116/175郡)으로 세력 배치를
  다시 깐다. 郡을 가지면 그 郡의 縣도 함께 갖는다. 사료상 領有 郡이 없는 세력은 城을
  지어내지 않고 `scale=0`(방랑군)으로 내린다. 1010의 공백지 70성 설계는 깨져도 좋다는
  사용자 결정에 따라 사료대로 채운다.

**근거.** 사용자 지시(2026-08-19). 사료 근거는 續漢書 郡國志·百官志, 『三國志』魏書 東夷傳/
韓傳, 『삼국사기』·『삼국유사』, 華陽國志, 晉書 지리지. 좌표는 CHGIS V6(ADR-LITE-039/040 격리).

**뒤집는 법.** 생성기(`tools/scenario/build_han_world.py`)와 시나리오 재작성기
(`tools/scenario/apply_han_world.py`)만 되돌리면 che 세계로 돌아온다. 런타임은
`CityConstRegistry.of("che")` 가 기본값이라 맵 선택만 바꾸면 된다.

## ADR-LITE-042 — PHP 패러티를 설계 제약에서 해제한다 (2026-08-20)

<!-- ADR-LITE-042-CONTRACT retired=php_grand_truth,php_wins,draw_for_draw,byte_log,golden_first,frozen_baseline; retained=truthfulness,replay_determinism,one_daemon_write,flush_delta,insertion_order -->

- Date: 2026-08-20
- Status: approved
- Approved by: 사용자 (직접 지시, 2026-08-20)

**결정.** v2 로 올라가면서 devsam/core(체섭) PHP 를 **똑같이 구현하는 것을 그만둔다**.
사용자 원문: *"패러티는 이제 신경쓰지 마"*, *"이제부터 패러티에 관한 것은 제외할 것.
체섭은 체섭이고, 오픈삼국은 오픈삼국이야."*

이 저장소의 최상위 규칙이 뒤집힌 것이므로 ADR 로 못박는다. `CLAUDE.md` 의
"Parity discipline (NON-NEGOTIABLE)" 절은 이 ADR 로 **대체된다**.

**무엇이 풀리나.**

- PHP 가 그랜드 트루스라는 지위. 이제 오픈삼국의 게임 설계가 스스로 정본이다.
  예전의 **`PHP wins`** 우선 규칙은 은퇴했고, PHP 와 다르게 만드는 것이 더 이상
  divergence(예외 승인 대상)가 아니다.
- RNG draw-for-draw 일치. 드로우 개수·순서·인자를 PHP 에 맞출 의무가 없다.
  (OPENSAM-216 이 지적한 `GenFoundFamily.kt:410` dist-3 nextBit 소모 등은
  더 이상 블로커가 아니라 설계 자유도다.)
- 한국어 로그 바이트 일치. 로그는 이제 UX 산출물이지 게이트가 아니다.
- `phpRound` 반올림·`intdiv` 절삭을 "PHP 가 그러니까" 유지할 의무.
  수치 규약은 우리가 정하되, **바꿀 때는 의도적으로 바꾸고 기록한다.**
- 새 기능을 만들 때 PHP 오라클(`tools/php-golden`) 캡처를 선행 조건으로 두는 것.
  즉 **golden-first** 작업 순서는 신규 제품 작업의 기본 게이트가 아니다.

**무엇이 남나.** (해제된 것은 "PHP 와 같아야 한다"이지 "품질 기준"이 아니다.)

- **거짓 완료 금지.** 골든·테스트·명령 결과를 지어내지 않는다. 미확인은
  UNKNOWN 이지 추측이 아니다. 테스트를 통과시키려고 테스트를 약화하지 않는다.
- **제품 회귀는 유지한다.** 휘하의 같은 입력 재실행·결정론 결과를 비교해 의도치 않은
  동작 변경을 잡는다. 기대값을 바꿀 때는 **왜 바꾸는지**를 커밋에 남긴다. 삼모 전용
  골든·회귀는 2026-09-25 ADR-LITE-066에 따라 삭제한다.
- **리플레이 결정론.** 같은 시드 → 같은 결과는 디버깅·재현·분쟁조정에 필요하므로
  유지한다. 이는 PHP 와의 일치와 무관한 별개 속성이다.
- **one-daemon-write-rule**(데몬은 ChangeRecorder→JdbcFlushExecutor 로만 쓴다),
  삽입 순서 보존, flush 델타 규약 — 전부 아키텍처 무결성 규칙이지 패러티 규칙이
  아니므로 그대로 간다.
- `legacy/` 는 계속 git-ignore·미커밋. 이제 **참고 자료**이지 오라클이 아니다.

**정정 (2026-09-25, ADR-LITE-066).** 삼모 전용 골든·회귀·도구의 보존 의무를 폐기했다.
휘하가 쓰는 공유 동작은 의존 감사 후 이전한다. 거짓 완료 금지, 휘하 리플레이 결정론,
단일 쓰기·flush 무결성 규칙은 유지한다.

**근거.** 후한 군현 맵(ADR-LITE-041, 780성)으로 세계 규격 자체가 갈리면서
94성 전제 위에 세운 PHP 동작을 그대로 재현하는 것이 의미를 잃었다. OPENSAM-216 이
보여주듯 맵을 갈면 AI 의 드로우 개수부터 달라져 패러티는 어차피 유지 불가능하다.
남은 선택은 "패러티를 붙들고 맵을 포기"하거나 "패러티를 놓고 게임을 만들기"였고,
사용자가 후자를 택했다.

**뒤집는 법 (2026-09-25 정정).** PHP 패러티 정책을 다시 채택하려면 새 제품 결정과 구현이
필요하다. 삼모 전용 골든·`tools/php-golden` 하네스를 롤백 수단으로 보존하지 않는다.
운영 컷오버의 복원은 ADR-LITE-066의 이미지 태그와 백업을 따른다.

**이전 미결의 처리.** 기존 순수 PHP 패러티 테스트 중 삼모 전용 항목은 의존 감사 후 삭제한다.
휘하의 제품 회귀로 필요한 항목은 현재 동작을 설명하는 테스트로 옮긴다.

## ADR-LITE-043 — CHE 계열 시나리오를 런타임 목록에서 은퇴하고 han 밸런스 상수를 승인한다

- Date: 2026-08-20
- Status: approved
- Decision: 운영자가 선택하는 시나리오 카탈로그는 han 제품 시나리오 15개(`1010`,
  `1020`, `1021`, `1030`, `1031`, `1040`, `1041`, `1050`~`1120`)만 노출한다. 공백지
  `0`, `1`, `2`, `900`, `901`, `902`, `903`, `905`, `906`, `908`, `910`, `911`, `912`,
  `913`, `914`와 v2 시험장 `9200`은 지원 런타임 목록에서 은퇴한다. 클래스패스
  JSON과 `scenario_1010_che.json`, `CheScenarioBootIT`, `ScenarioImporterIT`, 골든, CHE/
  miniche 테스트 코드는 frozen-baseline 회귀 표면으로 삭제하지 않는다. 명시적
  `SCENARIO_CODE`로 회귀 픽스처를 부트하는 테스트 경로는 보존하되, 제품 지원 표면은
  `ScenarioCatalogService`의 명시적 allowlist다.
- Decision: han 전용 건국 밸런스를 `FOUND_ASSAULT_RATIO = 2.0`으로 고정한다. 필요 돌파
  병력은 `ceil(city.defence * 2.0)`이며 han 이외 맵은 0이다. 건국 가능 등급은 기존
  중/소(5/6)에 han 영현/장현(`level >= 10`)을 추가하고 경(9)·대(7)·특(8)은 막는다.
  군치 수로 정하는 도적·황건 spine은 `1/13/28郡治 -> nation.level 2/3/4`로 고정한다.
- Context: OPENSAM-214 전수 감사에서 위 15개 공백지는 장수 0·세력 0이고 map이
  `che`/`miniche`/`miniche_b`/`miniche_clean`/`cr`이었다. `9200`은 장수 2·세력 2의
  `miniche_b` v2 시험장으로 이미 OPENSAM-151에서 카탈로그 비노출이었다. 사용자는
  2026-08-19에 `che/miniche/miniche_b` 은퇴와 테스트 오라클 보존을 직접 지시했다.
- Context: OPENSAM-204의 `161郡` 전제는 stale-premise다. ADR-LITE-041이 제품 세계를
  `175郡治 / 780城`으로 이미 확정했고, 현재 `data/map/han-tiles.json` 메타는 `seats=175`,
  `infra/src/main/resources/map/han.json`은 도시 780개다. 타일맵의 `cities=1,144`는 렌더·소유격자
  입력이지 플레이 가능한 세계 노드 수가 아니다.
- Alternatives: 시나리오 JSON/맵/테스트를 물리 삭제(기각 — frozen-baseline과 되돌리기
  경로를 파괴), 숫자 대역으로 자동 노출(기각 — 새 샌드박스가 묵시적으로 제품 카탈로그에
  유입), 기존 30개 모두 노출(기각 — 은퇴 지시 위반).
- Consequences: 시나리오 리소스 추가는 자동 출시가 아니다. 제품 노출은 allowlist 리뷰를
  필요로 한다. 은퇴한 CHE 경로의 테스트 코드와 직접 픽스처 부트는 계속 가능하지만
  운영자 선택지로는 지원하지 않는다. han 상수 변경은 수치 변경 기록과 회귀 테스트를
  필수로 한다.
- Amendment (2026-09-25, ADR-LITE-066): 위 CHE 시나리오·골든·테스트 픽스처의 보존 결정은 폐기한다. 삼모 전용 시나리오와 직접 부트·회귀 코드는 의존 감사 뒤 제거한다. 제품 시나리오의 명시적 노출 규칙과 실제 휘하가 쓰는 han 데이터는 유지한다.
- Approved by: 사용자(2026-08-19 CHE 계열 은퇴 지시) + OPENSAM-214 승인 티켓

## ADR-LITE-044 개정 — 아이소 타일 격자를 지도 렌더링 정본으로 삼는다

- Date: 2026-08-25 (2026-08-22 결정 개정)
- Status: approved; supersedes the 2026-08-22 text of ADR-LITE-044
- Decision: 로비 `MapPreview`, 인게임 `MapViewer`, 지도 페이지는 모두
  shared `HanMapCanvas`를 범용화한 **아이소 타일 렌더러**를 쓴다. 아이소
  격자와 `data/map/<mapCode>-tiles.json` 계약이 제품 지도의 투영·렌더링
  정본이다. `han` 월드는 `han-tiles.json`을 직접 쓰며 `che` CDN 배경으로
  폴백하지 않는다.
- Rendering: 지형·도로·국가색·도시·깃발·수도·사건 표시는 외부 맵 아트
  없이 캔버스 팔레트로 그린다. 레거시 맵 아트는 자산 정본이 아니며,
  비-`han` 호환성은 같은 타일 스키마와 결정적 테스트 픽스처로 검증한다.
- Province deployment: 커밋된 역사·지리 행정 정체성 소스는 계속
  `data/map/han-tiles.json` 하나다. `han-provinces.png`와
  `han-provinces.meta.json`은 그 소스에서 Docker 빌드 때 한 번 결정적으로
  생성되어 런타임 이미지에 함께 패키징되는 배포 파생물이며, 역사·지리
  소스나 커밋 대상이 아니다. game-api의 `/api/map/provinces` 엔드포인트는
  PNG만 서빙하고, 메타데이터는 HTTP로 서빙하지 않는 패키지 검증 sidecar다.
  따라서 `han-tiles.json`이 유일한 커밋 정본이라는 말은 런타임 파일이
  하나뿐이라는 뜻이 아니다. RGB 정수 `0`은 미소속, 그 밖의 값은
  `((commanderyIndex + 1) << 12) | (provinceIndex + 1)`로 해석한다.
- Province asset contract: ID 이미지는 `768×669` lossless PNG 그대로 운반하고,
  리사이즈·팔레트화·JPEG/WebP 변환·색 보정 등 픽셀 값을 바꾸는 변환을
  금지한다. 브라우저는 이 이미지를 정치색 원본으로 쓰지 않고, 픽셀 ID를
  현재 도시 소유국 색에 결합해 정치 레이어를 만든다. 이미지 로드·디코드가
  실패하면 지형과 도시 오버레이만 렌더링하며, 과거 정치 경계나 다른 맵
  자산으로 조용히 대체하지 않는다.
- Coordinates: `HanCityConst` 의 `(x,y)`를 타일 `(col,row)`로 돌릴 때 축별
  역변환 `col=x*cols/width`, `row=y*rows/height`를 쓴다. 단일 배율로 두
  축을 섞지 않는다(`tools/scenario/build_han_world.py` 산출식의 역).
- Preserved: ADR-LITE-039/040의 CHGIS 격리는 그대로다. 커밋된 역사·지리
  행정 정체성 소스는 `data/map/han-tiles.json` 하나이고,
  `han-provinces.png`와 `han-provinces.meta.json`은 소스가 아닌 런타임 배포
  파생물이다. 원본 shapefile·`han-places.json`·`terrain-grid.json`은 계속
  미커밋이다. ADR-LITE-042 규칙 5의 frozen baseline도 삭제·약화하지 않는다.
- Consequences: 세 표면의 줌·패닝·타일 표현과 도시 오버레이가 하나의
  구현으로 수렴한다. 게임·로비 래퍼는 API 로드, 제목·캡션, 라우팅,
  터치 두 번 탭 정책만 소유한다. 타일 파일이 없으면 다른 맵으로 바꾸지
  않고 준비/오류 상태를 보인다.
- Rollback: 문제 발생 시 이 개정 문구를 2026-08-22 본문으로 돌리고,
  `MapPreview`/`MapViewer`의 DOM·CDN 렌더 경로를 복원한 뒤 shared 캔버스는
  지도 페이지에만 제한한다. 롤백은 frozen 테스트나 CHGIS 격리 규칙을
  삭제하는 근거가 아니다. 정적 정치 레이어만 롤백할 때는 Docker의
  province 생성·패키징과 game-api의 province 서빙을 중단하고 지형 전용
  렌더링으로 돌아간다. 이 경우에도 `han-tiles.json`의 정본 지위와 커밋은
  유지한다.
- Approved by: 사용자(“ADR-LITE-044를 개정해 아이소 격자를 정본화”,
  “HanMapCanvas를 shared로 올려 로비에서 재사용”, 2026-08-25)

## ADR-LITE-046 `data/map/external-places.json` 은 ADR-LITE-039 CHGIS 격리 대상이 아니다

- Date: 2026-08-24
- Status: approved
- Decision: `data/map/external-places.json`(Wikidata SPARQL 산출, `tools/map/build_external_places.py`)
  을 커밋한다. `.gitignore` 의 `data/map/*` 블랭킷 미커밋 규칙에서 `!data/map/han-tiles.json`
  과 같은 자리에 별도 예외로 추가한다. **이 결정은 `external-places.json` 하나에만 적용된다** —
  `data/map/han-places.json`, `data/map/terrain-grid.json`, `data/chgis-source/` 는 이 결정으로
  바뀌지 않으며 계속 git-ignore·미커밋이다. ADR-LITE-040(han-tiles.json 서빙 예외)의 범위를
  넓히는 것도 아니다 — ADR-LITE-040 은 "게임이 서빙하는 타일 파일 하나"로 명시적으로 좁혀 놓은
  결정이라 이 파일에 자동 적용되지 않는다는 점을 근거로 별도 항목으로 기록한다.
- Context: `tools/map/build_external_places.py` docstring: "CHGIS 커버리지 밖 지점 — 좌표를
  Wikidata(CC0)에서 받는다. 입력 없음(Wikidata SPARQL)". CHGIS shapefile 을 전혀 읽지 않는다 —
  존재 이유 자체가 CHGIS V6 가 현대 중국 국경 안만 담아 交州 남부 3郡·樂浪·帶方이 원본에 없다는
  결손을 메우기 위함이다(항목마다 Wikidata QID 로 검증 가능). ADR-LITE-039 가 격리한 대상은
  CHGIS 직접 파생물(`han-places.json`→`terrain-grid.json`)이고, 이 파일은 그 계열이 아니다.
  실제 재생성 체인 4단계 중 1~3(원본 shapefile→han-places.json→terrain-grid.json)만 CHGIS
  라이선스 위험을 승계하고, 4(han-tiles.json)만 ADR-LITE-040 으로 승인됐다 — `external-places.json`
  은 이 체인 밖에서 독립적으로 생성되는 5번째 산출물이다. 이 구분이 흐려져서
  `han-places.json` 까지 같이 커밋 대상으로 딸려 올라가는 걸 막는 게 이 항목의 목적이다.
  `data/map/junguozhi.json` 도 CHGIS 파생이 아니라는 관찰(Wikidata `external-places.json` +
  공개 사료 코퍼스에서 생성)이 있었으나, 이번 결정은 그 파일까지 확장하지 않는다 — 필요해지면
  그때 별도 판단한다.
- Alternatives: (1) 테스트 코드에 경로 오버라이드로 gitignored 픽스처 대체 — 라이선스 위험
  자체를 없애지 못하고 CI 신선한 체크아웃에서 여전히 같은 파일 부재가 반복될 뿐이라 기각.
  (2) `assumeTrue`/`skipIf` 로 조용히 건너뛰기 — pytest 30건 회귀가 CI 에서 한 번도 실행되지
  않는 상태를 영구화하므로 기각.
- Consequences: `tools/map/tests/test_route_*` pytest 3종이 신선한 체크아웃에서
  `FileNotFoundError` 없이 실행된다 — `.github/workflows/ci.yml` 의 "Verify Han map and
  route-node data contracts" 스텝이 처음으로 실제 실행된다. `tools/map/route_network_contract.py`
  의 `EXPECTED_SOURCE_HASHES["routeNodeSelection"]` 은 이 파일 커밋과 별개로, `59ec25eb`(#501)
  이후 갱신되지 않은 상태였던 걸 파이프라인 계산으로 재동기화했다(수기 입력 없음).
- Evidence: `git show origin/main:tools/map/build_external_places.py` docstring 확인,
  `data/map/external-places.json` 65 entries/31KB/schema `{basis, begYr, conf, endYr, hub, id,
  jun, kind, lat, level, lon, nameCh, nameFt, namePy, presLoc, prov, typeCh, wikidata}` 실측.
- Approved by: team-lead(세션 피어리뷰 레인, 라이선스 구분 근거는 engine-it 이 수집) — 2026-08-24.

## ADR-LITE-045 1,180 현급 행정 카탈로그와 780성 수송망을 분리한다

- Date: 2026-08-22
- Status: approved
- Decision: 《후한서》 순제기 기준의 현·읍·도·후국 1,180은 역사 행정 카탈로그로 보존하고,
  제품 세계는 reviewed selection manifest의 stable `RouteNode` 780개를 목표로 한다. 현재 780개
  개별 identity는 결손 `zhi` parser 산술에 의존하므로 자동 승인하지 않는다. 둘은 시나리오별
  provenance mapping으로 연결하되 개수를 서로 대체하거나 자동 확장하지 않는다. 이 결정은
  ADR-LITE-041의 “현행 175+605 선정을 영구 정본으로 간주”하는 부분을 supersede한다.
- Decision: 현재 작업트리 `han.json.connections` 1,783개는 승인된 도로 자체가 아니라 geographic
  corridor 후보 snapshot이다. 과거 1,778과의 차이를 포함해 숫자만 제품 불변식으로 동결하지 않고
  승인 manifest의 count+hash를 검증한다. 도로·수로·해로·관문·나루·교량은 별도 versioned infrastructure state로 관리하고,
  이동·출병·수송·보급은 같은 `RouteNetworkSnapshot`을 소비한다.
- Consequences: 즉시 city-to-city 이동·원격 재고 이전을 v2 완료로 세지 않는다. 진행 중 작전은
  network revision을 pin하며 변경은 typed invalidation/reroute로 반영한다. 렌더러는 adjacency에서
  직선을 자동 생성하지 않고 승인된 geometry와 state를 표현한다.
- Evidence: 《후한서》 권113 「군국지」 “凡郡、國百五，縣、邑、道、侯國千一百八十”. 로컬
  권109~113 구조 검출은 105군국·1,180항목을 전수 확인했고, 기존 좌표 결합 산출물만 1,076개다.
- Approved by: 사용자("780성에 적용", "현은 천개 정도가 맞을걸", 2026-08-22)

## ADR-LITE-047 하네스는 지우고 보호층·ADR 은 남긴다

- Date: 2026-08-24
- Status: proposed
- Decision: 에이전트 하네스(`.claude/agents`·`commands`·`skills`·`workflows`, `.codex/`,
  `.agents/skills`, `docs/agent/`, `tools/agent-system/`, `skills-lock.json`, `.mcp.json`)는
  삭제한다. **보호층과 결정 기록은 남긴다** — `CLAUDE.md`, `AGENTS.md`, `.claudeignore`,
  `.ai/*`, `docs/superpowers/WORKING_SYSTEM.md`, `docs/superpowers/SESSION_HANDOFF.md`,
  `scripts/agent/protect-sensitive-files.sh`, `.claude/skills/historical-sources/SKILL.md`.
- Decision: `.claude/settings.json`·`settings.example.json` 의 PostToolUse 항목
  (`scripts/agent/verify-changes.sh`)은 **뺀다**. 그 스크립트는 `tools/agent-system/check.py`·
  `tools/agent-system/check_test_xml.py`·`.agents/skills/`·`scripts/agent/test-codex-agent-os.sh`
  를 호출하므로 하네스 없이는 동작하지 않는다. 되살리려면 그 의존 사슬을 통째로
  되살려야 하고, 그건 이 삭제의 목적과 반대다. 남는 훅은 PreToolUse 하나
  (`protect-sensitive-files.sh`)뿐이다.
- Context: 첫 삭제안은 128개 파일을 한 번에 지우면서 하드룰(`CLAUDE.md`·`AGENTS.md`)·
  ADR 등록부(`.ai/decisions.md`)·시크릿 차단 훅까지 같이 끌고 나갔다. 삭제 규모가 커서
  diff 만 보고는 그게 안 보였다. 사용자가 2026-08-24 에 「보호층·ADR 은 살려라」로 지시했다.
- Alternatives: (1) 전부 삭제 — 기각, 시크릿 차단과 승인된 결정 기록까지 잃는다.
  (2) `verify-changes.sh` 만 되살리기 — 기각, 위 의존 사슬이 하네스 전체를 되끌고 온다.
  (3) 훅 항목을 남긴 채 스크립트만 없애기 — 기각, 매 Write/Edit 마다 죽는 훅이 된다.
- Consequences: 복원된 문서들에는 삭제된 경로 언급이 남는다(`CLAUDE.md` 11건,
  `AGENTS.md` 11건, `WORKING_SYSTEM.md` 6건, `.ai/*` 다수). 본문을 재작성하면 규칙 문장
  자체를 잃을 위험이 있어 **본문은 건드리지 않고 상단 배너로 「그 경로는 역사 기록」임을
  명시**했다. 배너를 지우려면 문장 단위로 하나씩 옮겨 써야 하고, 그건 별도 작업이다.
- Approved by: (pending — 사용자가 2026-08-24 에 복원 범위를 직접 지시했으나 PR 머지 승인 전)

## ADR-LITE-048 RTK14 장수 초상을 소유자 책임으로 제품에 쓴다 (2026-09-06)

- Date: 2026-09-06
- Status: approved (사용자 명시 결정, 코드 반영 PR 대기)
- Decision: `opensamguk-images` 의 RTK14 파생 초상(`portraits/rtk14/serving/original|portrait|icon`)을
  오픈삼국 제품(웹 UI·CDN 서빙)에서 사용한다. 사용에 따르는 법적 책임은 저장소 소유자(peppone-choi)가
  진다. 저장소 정책은 이렇게 바뀐다.
  1. `opensamguk-images` 의 라이선스 경계 검사는 **유지**하되 `portraits` 항목을 `third-party` 에서
     `owner-accepted`(notice + accepted_by + accepted_on 필수) 로 재분류한다. 검사를 없애지 않는 이유는
     다른 제3자 자산이 섞여 들어오는 것을 계속 걸러야 하기 때문이다.
  2. `CLAUDE.md` 의 「Koei 자산 미커밋」 규칙에 RTK14 초상 예외를 명시한다. 이 저장소에는 여전히
     초상 파일을 커밋하지 않고 CDN 경로만 참조한다.
  3. `web/gateway`·`web/game` 의 `portrait.ts` 에 3종 변형 헬퍼(`portraitVariantUrl`, `rtk14OfficerId`,
     `RTK14_SERVING_CDN`)를 추가한다. 기존 `portraitUrl` 은 `portrait` 변형과 동일하게 유지한다.
- Context: 2026-09-06 UI 리디자인 초안(디자인 캔버스 `35136bc0`)이 초상 3종 사용 규칙을 세웠다 —
  원본은 히어로(장수 상세·리플레이 對)에 그라데이션 마스크로, 148×210 은 카드에, 96 아이콘은
  피드·표·댓글·표결자 스택에. 사용자가 「원본은 라이선스 무시, 내가 책임진다」 → 「배포 쪽도
  바꾼다」 → 경계 검사는 살리고 재분류하는 안에 「좋아」로 순서대로 승인했다. 프로덕션은 이미
  `serving/portrait` 를 CDN 에서 불러오고 있었으므로, 이 결정으로 바뀌는 것은 그림이 아니라 정책
  기록과 3종 파이프라인 노출이다.
- Alternatives: (1) 경계 검사 자체를 삭제 — 기각, 이후 다른 제3자 자산 유입을 못 거른다.
  (2) 초상을 이 저장소에 직접 커밋 — 기각, CDN 단일 출처 불변식과 이미지 정본(`opensamguk-images`)
  규칙을 깬다. (3) 현행 148×210 한 종만 계속 사용 — 기각, 시안의 히어로·아이콘 용법을 못 만든다.
- Consequences: 공개 여부(README 「Koei-IP 검토 전까지 비공개」)는 이 결정으로 바뀌지 않는다 — 별도
  결정이다. 권리자 요청 시 제거 경로는 `opensamguk-images` 의 `portraits/LICENSE-NOTICE.md` 에
  남긴다. `serving/original` 은 633×900 JPEG(장당 50~140KB)이라 히어로 한 자리에만 쓰고 목록·표에는
  쓰지 않는다.
- Approved by: 사용자(peppone-choi) 2026-09-06 대화 중 명시 승인. PR 머지 승인은 별도.

## ADR-LITE-049 UI 리디자인 정본은 야전 사령부(Concept A) 캔버스다 (2026-09-06)

- Date: 2026-09-06
- Status: approved (사용자 명시 결정 2026-09-06, 구현 착수 지시)
- Decision: 두 프런트(`web/gateway`·`web/game`)와 공유 패키지(`web/shared`)의 시각·정보구조 정본은
  디자인 캔버스 artifact `35136bc0-55c7-409f-a2e6-4e29f5939d30`(19 아트보드)이며 소스 사본은
  `docs/design/ui-redesign-2026-09/`다. 확정 규칙: (1) Concept A 팔레트·타이포, 화려함은 크롬 장식이
  아니라 초상·깃발·지도·편년체·리플레이 자산에서 낸다 (2) 메인은 「작전실」 — 지도(중앙)와 명령 목록
  (우측, 12슬롯 = 12순, 한 순 한 턴)은 메인에 고정한다 (3) 커뮤니티(서버 밖·계정) / 회의실(국가 소속
  전원) / 기밀실(permission ≥ 2·열람 기록·적갈 프레임)은 서로 다른 화면이다 (4) 초상은 3종(원본
  히어로 / 148×210 카드 / 96 아이콘)만 쓰고 국가색 링은 내 장수·군주·현재 문맥 국가에만 (5) 현행
  화면의 라벨·게이팅·API 계약은 바꾸지 않는다(S2 대조표) (6) 브랜드는 `logo-wordmark.png`만, 텍스트
  워드마크 금지 (7) 비활성은 숨기지 않고 점선 + 사유.
- Decision: OPENSAM-112/115(GitHub #255/#258)의 「롤러코스터 타이쿤풍 픽셀 UI」·「Pretendard/픽셀 폰트
  토글」 전제는 이 결정으로 폐기한다. 티켓 본문은 Concept A로 고쳐 쓴다.
- Decision: 구현 범위는 사용자 지시로 확장됐다 — 시안의 소형 기능(공지·세력 현황·커뮤니티 확장·
  회의실 글 종류/표결·기밀실 열람 기록)은 백엔드까지, 그리고 WEGO 야전 봉인·결정론 리플레이·작전·
  휘하 인물·부곡도 같은 계획(Phase 4X)에서 구현한다. 봉인·작전·휘하가 없는 경로는 오늘과 바이트
  동일해야 하며 frozen baseline·골든은 건드리지 않는다. 어드민은 아트보드가 없으므로 같은 디자인
  시스템으로 현행 백엔드가 지원하는 기능 전부를 노출한다. 게임 이미지는 `opensamguk-images`에서
  제작해 export만 가져오고, 3D는 마지막에 조건이 되면 한다. 완료 후 게임 서버를 한 번 초기화한다.
- Context: 2026-07-25 OPENSAM-113 시안 3안 중 사용자가 Concept A(야전 사령부)를 택했고, 2026-09-06
  「게임성 극대화 + 어느 정도 화려하게」 요구를 「자산에서 나오는 화려함」으로 합의한 뒤 19 아트보드
  캔버스를 확정했다. 같은 날 「이대로 구현 시작」·「제외 범위도 동시에 실행」·「어드민도 다 만들어라」·
  「이미지도 제작」·「3D도 마지막에」·「끝나면 서버 초기화」를 지시했다.
- Alternatives: (1) 픽셀 UI(OPENSAM-112 원안) — 기각, 사용자가 Concept A를 택했다. (2) 메인에서
  지도·예턴을 빼고 대시보드화 — 기각, 사용자 명시 금지. (3) 세 게시 공간 통합 — 기각. (4) WEGO·작전·
  휘하를 로드맵 4~6단계로 미루기 — 사용자가 동시 실행을 지시해 기각.
- Consequences: 토큰 교체가 두 앱 전 화면의 색을 한 번에 바꾸므로 화면별 리스타일 전까지 「옛
  레이아웃 + 새 팔레트」 상태가 잠깐 존재한다(유저 유입 전, 승격 상시 승인 범위). Phase 4X는 로드맵
  4~6단계 백엔드를 앞당기므로 트랙마다 spec → 교차 비평 → 구현 → 게이트를 지키고, 공성·해전 WEGO는
  잔여로 남는다. 뒤집기 경로: 캔버스 재발행 + 이 ADR 개정(supersede 기록).
- Approved by: 사용자 (2026-09-06, 명시적 지시). 구현 계획:
  `docs/superpowers/plans/2026-09-06-ui-redesign-implementation-plan.md`.
- Amendment (2026-09-17, ADR-LITE-057 · 사용자 결정): 규칙 (5) 「현행 화면의 라벨·게이팅·API 계약은 바꾸지 않는다」를 새 게임 화면에
  대해 다음으로 바꾼다. (1)(2)(3)(4)(6)(7)은 그대로다.
  - 부서 메뉴는 입력 6종 기준 **휘하 · 계책 · 영지 · 군단 · 조정 · 기록** 6묶음이다. 휘하 = 인물·부대·보물 카드 편성과 명망·월단평,
    계책 = 손패·설치·대응, 영지 = 縣 배치·방침·공사, 군단 = 편성·방침·작전, 조정 = 발령·관직·외교, 기록 = 지난 순·리플레이·편년체.
    회의실·기밀실·커뮤니티는 (3) 대로 별도 화면이다.
  - 새 화면은 **기존 라우트를 바로 교체**한다(별도 라우트를 두지 않는다). 단 라이브 pep 은 S3 통과 직후 전환할 때까지 삼모 규칙으로
    돌므로(재설계 §15.2), 전환 전까지 기존 명령 입력 경로는 동작해야 한다 — 교체 순서는 새 입력이 붙는 화면부터이고, 기존 명령
    입력은 같은 라우트 안에서 월드 규칙(삼모/새 게임)에 따라 갈린다.
  - 명령 목록 12순은 장수 행동을 담고, 배치·방침 예약이 효력을 갖기 시작하는 순에 표식을 단다.
  - 라벨은 재설계 용어(주공·휘하·결속·명망·순)를 쓴다. 삼모 명령 라벨은 새 게임 월드에서 쓰지 않고 대응표(재설계 §12)를 도움말에 둔다.
  - 게이팅은 (7) 대로 점선 + 사유이고, 사유 문구는 입력 registry 의 실패 사유와 같은 계약을 쓴다.

## ADR-LITE-050 게임 로그 색 토큰은 저장·와이어 계약으로 남기고 렌더만 `LogText`로 바꾼다 (2026-09-06)
- Decision: 엔진이 기록하는 로그 문자열의 devsam 색/태그 토큰(`<C>●</>`, `<Y>이름</>`, `<M>기술</>`,
  `<R1>`, `<1>`, `<b>`, `<span class='ev_failed'>`, `<span style='color:#hex'>`)은 저장 형식과 API 응답
  (`text`)에서 바꾸지 않는다. 대신 두 앱의 모든 로그 표시는 `@opensamguk/ui`의 `parseLogTokens`
  (`web/shared/src/logTokens.ts`) → `LogText`(팔레트 span, innerHTML 없음)로만 그린다. 토큰 색은
  `tokens.css`의 `--log-r … --log-w`(야전 사령부 팔레트로 사상, 색상군은 유지)로 중앙화한다.
  `formatLog`(innerHTML 문자열 생성)는 레거시 호환 유틸로 남기되 제품 화면에서는 쓰지 않는다.
- Context: 사용자가 「그 토큰 어떻게 할지 고민해봐. 수정할 수 있을거 같은데. 대체하거나.」(2026-09-06)
  라고 지시했다. 조사 결과 (1) 토큰은 엔진·`logic` 골든 208개 파일이 바이트 비교하는 오라클의 일부
  (`P2GoldenSupport`가 `<Y>…</>`를 추출)라 원천 교체는 frozen baseline을 깨고 DB 기존 행과도 갈라진다,
  (2) 전황(`/game/world-log`)·지도 중원정세·게이트웨이 `ServerLog`는 토큰을 처리하지 않은 채(원문
  innerHTML 또는 태그 제거) 그리고 있었고, 나머지는 `formatLog` + `dangerouslySetInnerHTML`로 그려
  XSS 표면이 화면마다 있었다.
- Alternatives: (1) 엔진에서 구조화 로그(JSON 세그먼트)로 교체 — 기각, 골든·DB·API 계약 동시 변경.
  (2) game-api가 응답 시 HTML로 변환 — 기각, 계약 변경 + innerHTML 유지. (3) 화면마다 `formatLog`
  유지 — 기각, 토큰 미처리 화면이 재발하고 innerHTML이 남는다.
- Consequences: 로그 색이 CSS 원색(cyan/yellow/magenta…)에서 팔레트 값으로 바뀐다(의미 색상군은
  유지). 토큰 문법이 늘면 `logTokens.ts`와 `formatLog.ts` 둘을 함께 고쳐야 한다(테스트
  `web/shared/src/__tests__/logText.test.tsx`가 문법 사례를 고정). 게이트웨이 전황 보고도 같은
  렌더러를 써 색이 붙는다.
- Approved by: 사용자 지시(토큰 처리 결정 위임, 2026-09-06). 구현: Phase 4 웨이브 A PR.

## ADR-LITE-051 — 보급은 행정선(郡)을 따라서도 흐른다 (2026-09-08)

- Context: `han-world-v3` 런타임 보급은 프로빈스 소유 격자에서 **물리적으로 맞닿은** 프로빈스끼리만
  흐른다(`SpatialSupplyNetwork.provinceAdjacency` ← 전략 위상 LAND 간선). 그런데 그 격자에서 같은
  郡의 프로빈스들이 조각으로 끊겨 있다. 프로덕션 `pep`(scenario_1020)에서 공융의 北海國 16城 중
  **14城**이 개시 시점부터 수도에 닿지 않았고, 절단된 城은 매턴 10% 쇠퇴 → 민심 30 미만 →
  중립화로 잃도록 예정돼 있었다. 사용자가 「공융이 보급이 끊겨서 증발한다」고 제보한 그 경로다.
- Decision: **보급**은 물리적 인접뿐 아니라 **행정선(郡)을 따라서도** 흐른다. 같은 郡의 프로빈스가
  소유 격자에서 조각으로 끊긴 곳을 최소 간선으로 잇고, 그 간선을
  `data/map/han-commandery-supply-links-v1.json`(생성기 `tools/map/build_commandery_supply_links.py`,
  `--check` 게이트)에 굽는다. 郡은 후한의 행정·병참 단위였다는 것이 근거다.
- Decision: **이동(traversal)은 바뀌지 않는다.** 전략 위상의 LAND 간선은 그대로이고
  `_land_owners_are_adjacent` 도 그대로다 — 부대는 여전히 래스터에서 맞닿은 프로빈스로만 움직인다.
  이 간선은 `provinceAdjacency`(보급 전용)에만 더해진다.
- Decision: 좌표가 틀린 동명이지 縣은 이 규칙에서 **제외한다**
  (`data/curated/han/county-misbinding-adjudications-v1.json`, 5건). 제외하지 않으면 郡을 가로지르는
  가짜 보급선이 생긴다 — 제외 전 최대 간선 1,190km, 제외 뒤 중앙값 47km · 90% 161km.
- Alternative considered and measured: **소유 격자를 실제로 고치는 것**(프로빈스 이설). 작동은
  하지만 금방 멎는다 — 가장 어긋난 프로빈스 31개를 옳은 자리로 옮겨도 scenario_1020 절단이
  102 → 90 이고 공융은 4城이 남는다. 남은 절단은 개별 프로빈스가 밀려서가 아니라 래스터 분할
  자체가 성겨서 생긴다. 대가는 크다 — 수역·전략 위상·경로 노드·15개 시나리오 소유권이 따라
  바뀌고 `han-tiles.json` 통파일 해시를 핀한 8개 파일과 런타임 검증을 재핀해야 한다.
  **둘 다 하기로 했고**(사용자 결정), 이 ADR 은 먼저 가는 보급선 쪽이다. 프로빈스 이설은
  지도 정확도를 올리는 후속 작업으로 남는다.
- Rejected: CityConst 그래프에만 郡 연결을 더하는 것. 구현해 보니 개시 절단이 416 → 207 로
  줄지만 **런타임은 CityConst 를 쓰지 않는다** — 40城이 `BOTH_UNSUPPLIED` → `CITY_ONLY` 로
  분류만 옮겨갔을 뿐 게임은 그대로 끊겼고, 두 모델 사이에 224건의 미판정 불일치를 만들어
  게이트를 깨뜨렸다. 되돌렸다.
- Evidence: 같은 spatial 모델 위 실측 — 현행 102/438 절단(공융 14/16) → 보급선 적용 후
  **40/438**(공융 **0/16**). 보급선 72개, 길이 중앙값 47km.
- Reversal: `data/map/han-commandery-supply-links-v1.json` 을 지우면 보급이 오늘과 같아진다
  (`CommanderySupplyLinkLoader` 가 파일 부재를 빈 목록으로 읽는다).

## ADR-LITE-052 — 빈 프로빈스 정책: 현 단위 소유권(R1)과 육지 무소속 금지(R2) (2026-09-14)

- Status: approved (사용자 명시 결정 2026-09-14; 중립 점령 기본값만 proposed로 남김)
- Context: 城을 빼앗으면 그 縣의 治所 省만 색이 바뀌고 같은 縣의 나머지 省이 공백으로 남는
  「빵꾸」가 났다. 기존 규칙은 치소와 같은 기준 소유자의 省만 함께 옮기고 심사된 분할(충돌
  허용 원장)은 live 에서도 유지했다. 스크린샷의 빈틈도 원인(지형·수역·관할·도시·점령)별로
  가려야 하며, 「원인 불명 공백」을 인접 도시 소유 보간으로 메우는 것은 금지돼 있다.
- Decision (R1 — 현 단위 소유권): 현의 소유가 바뀌면 소속 프로빈스 **전체**가 함께 바뀐다
  (현 크기는 달라도 되고, 1현=다수 프로빈스 허용). 점령 시 `jurisdiction.provinceIds`
  전체에 `city.nationId`를 적용한다. 지도 투영(`MapAdministrativeOwnership`)과 보급
  (`HanSpatialSupplyProvider`) 둘 다 같은 규칙으로 바꿔 미리보기=서버 일치를 유지한다.
  초기 정적 배치는 충돌 허용 원장대로 유지하고, 허용 원장 검증 로직(초기 데이터 검증용)도
  유지한다 — 바뀌는 것은 live 점령 투영뿐이다.
- Decision (R2 — 육지 셀 무소속 금지): 소속 없는 육지는 데이터 버그다. 타일 생성 게이트
  (`tools/map/build_tile_grid.py`의 `assert_no_orphan_land`, `--check`로 커밋본 강제)에서
  잡는다. `owner` 격자의 `-1`은 수역(SEA·LAKE)과 플레이 범위 밖(OUT_OF_SCOPE)에만 허용하고, 육지(PLAIN·MOUNTAIN·RIVER·DESERT·PLATEAU·
  BASIN·HILL) 셀의 `-1`은 실패한다. 기존 coverage check(프로빈스별 소유권 누락)는 유지한다.
- Evidence (R2 현행 실측 2026-09-14): 미소속 286,443칸 = SEA 174,997 + LAKE 1,079 +
  OUT_OF_SCOPE 110,367. 육지 미소속 **0칸** — 게이트는 가드레일이며 데이터 이주는 없다.
- Decision (공백 분류 체계): 빈틈은 넷 중 하나로만 부른다.
  1. 미검증 공백 — 원인 미분류. R2에 따라 육지면 데이터 버그로 intake 에서 잡는다.
  2. 중립 영토 — 근거 있는 owner 0. 렌더링은 중립색, 보급은 중립으로 흐르고, 점령 가능
     여부는 **미결**이다. 결정 전까지 기본값(점령 가능)으로 진행한다(proposed).
  3. 비현 거점 — 현이 아닌 관문·거점(關·津·鎭 등). 프로빈스와 점령·이동을 부여하되 기존 성
     레벨 체계 아래에 둔다. 津과 鎭을 자동으로 동일시하지 않는다.
  4. 외부 — 플레이 지도 밖. 지도 안 별도 국가 분리나 단순 좌표 이동으로 대체하지 않는다.
- Consequences: 빵꾸 방지 케이스와 심사 분할 live 유지 테스트를 R1 의미로 고친다. 중립
  칸도 점령에 따라 넘어감(중립 고정이 빵꾸 방지와 충돌하지 않음)을 테스트에 못박는다.
  시범은 동남 해안(서릉현 일대) 데이터로 든다. 187 후보 전수 분류는 별도 심사 작업이다.
- Approved by: 사용자 (2026-09-14 재개 목표 확정)
- Amendment (2026-09-17): 공백 분류 2 「중립 영토」의 점령 가능 여부를 **점령 가능**으로 확정한다(사용자 결정, ADR-LITE-057 결정 13).
  이제 proposed 가 아니다. 점령에는 현지 사병 돌파가 필요하며 수치는 새 설계에서 정한다.

## ADR-LITE-053 — 羌·氐는 플레이 지도 바깥의 변방 상호작용으로 둔다 (2026-09-15)

- Status: direction-approved (사용자 명시: 지도 안 별도 국가 분리도, 단순 좌표 이동도 아님).
  mechanics는 proposed — 이번 작업은 타당성 검토이며 즉시 구현하지 않는다.
- Evidence (원전, 로컬 data/corpus/hhs-087.txt — 後漢書 卷87 西羌傳):
  - 「或爲牦牛種，越巂羌是也；或爲白馬種，廣漢羌是也；或爲参狼種，武都羌是也」
  - 「武都塞上白馬羌攻破屯官，反叛连年」/「建和二年，白馬羌寇廣漢属國」
  - 「氂牛、白馬羌在蜀、漢」「廣漢塞外白馬羌豪楼登等率種人五千余户内属」
  - 「蜀郡徼外羌」「廣漢塞外参狼種羌…来内属」 — 활동 양식은 徼外 거주 + 寇邊 + 内属이다.
  - 白馬氐(武都=隴南) 심층 심사는 후속(元和郡縣圖志 인용은 당대 지리서라 220년 직접 증거 아님).
- Evidence (지도·게임 현황): 플레이 범위 lon 80.5–116.6/lat 17.8–45.0. 西羌 X058
  (101.78E,36.62N)·白馬氐 X059(105.35E,33.5N)는 EXTERNAL_PLACE(level 4 텐트 장식)로만
  있고 침입·외교·교역 기구에서 참조하지 않는다(코드 내 X058/X059 소비자 0).
- Decision (방향): 羌·氐를 지도 안 국가로 세우지 않고, 플레이 가능 영역 바깥의 변방
  실체로 둔다. 좌표를 옮기지 않는다. 장차 기구(접경 진입·寇邊 침입 이벤트·内属/조공
  외교 갈고리)는 별도 설계 작업으로 둔다.
- Rejected: 지도 안 별도 국가 분리 / 단순 좌표 이동 / 현행 장식의 점령 가능화.
- Consequences: 구현 없음. 후속: 氐 심층 심사, 변방 이벤트 설계, 외부 거점 37곳 전수
  (羌·氐 포함)와의 관계 정리.


## ADR-LITE-054 — 물에 갇힌 縣은 제 郡治와 잇고, 동결 848 릴리스를 제자리에서 재핀한다 (2026-09-16)

- Context: `han-world-v3` 의 城 인접은 省 경계(`adjacency.county`)를 城 번호로 투영해 만든다.
  그 표는 래스터 격자에서 **맞닿은** 省 쌍만 낸다. 제 발자국이 통째로 물에 둘러싸인 省이 11곳
  있고, 그중 제 城의 **유일한** 省인 두 곳이 고립된다 — 305 徐縣(下邳國, 육지 9칸이 전부 호수
  안) · 548 鄮縣(會稽郡, 육지 37칸이 전부 바다 안). 나머지 846城은 한 덩어리다.
  2026-09-16 프로덕션 `pep` 이 이것으로 14시간 멈췄다: 도겸이 `{305, 547}` 만 가진 채 수도 547 을
  잃자 `ConquerCity.findNextCapital` 의 BFS 링이 305 에 영영 못 닿아 `IllegalStateException` 을
  던졌고, 실패한 틱은 플러시 전이라 같은 자리에서 62,472회 재시도했다.
- Decision: 물에 갇힌 縣은 **제 郡의 治所와 직결**한다. 길을 지어낸 것이 아니다 — 續漢書 郡國志가
  그 縣을 어느 郡에 실었는지는 사료 사실이고 郡治는 그 縣을 다스리는 곳이며, v2 생성기가 이미
  같은 규칙을 쓴다(`build()` 규칙 2, 縣 인접만으로 治所에 못 닿는 縣 224곳). v3 는 그 규칙을
  **물에 갇힌 城에만** 좁혀 쓴다. 305→299 下邳, 548→536 山陰. 4행만 바뀐다.
- Decision: 「간선이 0개」라는 **증상**만 보고 잇지 않는다. 귀속 원장 버그도 같은 증상을 낸다.
  원인이 정말 물인지는 owner 격자를 직접 훑어 따로 확인하고(`water_locked_province_indices`,
  `adjacency.county` 와 다른 축), 물이 아닌 이유로 끊긴 城이 나오면 생성기가 던진다.
  생성기는 이제 **끊긴 그래프를 아예 내지 않는다**(1번에서 전수 도달 게이트).
- Decision: 보급까지 같이 닫는다. 런타임 보급은 城 그래프가 아니라 省 보급망을 쓰므로
  (ADR-LITE-051), 城 인접만 고치면 두 城은 여전히 보급이
  끊긴다. `supply-disconnection-adjudications-v3.json` 의 PROTECT 행 2건을 은퇴시켜
  (`resolvedDecisions` 에 근거 보존) `build_commandery_supply_links.py` 가 막고 있던 郡 내부
  보급선을 굽게 했다 — 下邳國 39.3km/32.0km, 會稽郡 20.9km. 두 城은 이제 두 모델 모두에서 닿는다.
  감사의 「차수 0 省」 가드는 래스터 인접이 아니라 **보급망** 차수를 보도록 고쳤다.
- Decision: 동결 `han-world-v3-848` 릴리스를 **제자리에서 재핀**한다(사용자 결정). 848 식별자·이름·
  등급·좌표·경계는 그대로이고 바뀌는 것은 경로 간선 4개와 보급 원장뿐이라 새 릴리스가 아니다.
  병행 식별자(848-r2)는 불가능하다 — `HanWorldArtifactsResolver.resolve` 가 **city id 집합**으로
  variant 를 고르므로 같은 848 집합이 둘이면 유일 선택이 깨진다.
- Consequences: blob 해시가 `StrategicTopology.contentHash` 의 입력이라 릴리스 contentHash 가
  바뀐다. **이미 pin 이 박힌 월드는 `province_control`·`general_spatial_position`·
  `water_zone_control` 의 topology_hash 가 어긋나 로드에 실패한다 — 배포 전에 월드 리셋이나 pin
  마이그레이션이 필요하다.** 재핀 절차와 같이 움직여야 하는 핀 5곳은
  `data/map/han-world-v3-848-artifacts-v1/README.md` 의 「2026-09-16 topology repair re-pin」에 있다.
- Not done: 동결 846/835/832 릴리스는 손대지 않았다(옛 월드용이고 프로덕션이 아니다) — 세 표 모두
  305·548 이 여전히 고립돼 있다. 래스터 자체를 다시 굽는 일(han-tiles.json 재분할)도 하지 않았다.
- Code-side fallback: PR #765 가 「링으로 못 닿는 城 만 남아도 던지지 않는다」를 이미 막고 있다.
  이 결정은 그 폴백을 대체하지 않는다 — 폴백 회귀는 합성 변형으로 계속 지킨다.
## ADR-LITE-055 — 城 없던 縣 관할과 수·진·관 거점을 경로 노드로 올린다 (2026-09-15, 2026-09-16 1098 로 개정)

- Status: approved (사용자 명시 2026-09-15: 「188곳 전부 城 승격」, 「縣 프로빈스를 쪼개 거점 省을 준다」,
  「津·口=수, 鎭·壘·塢=진」, 진 범위 「塞·營·戍 + 군사 城」, 孟津·舒口·安平口·梁口 포함)
- Context: 지도 빈 프로빈스의 가장 큰 원인은 han-tiles(CHGIS 220 단면) 縣 관할 188곳에 게임 城이 없던 것이다
  (省 427). 경로 노드 선정은 郡國志 식별자(HHS)에만 城을 세웠다(ADR-LITE-041). 비현 거점(關·津·鎭)은
  ADR-LITE-052 에서 분류만 되고 구현이 없었고, 關 8곳은 표시 전용(음수 id) 오버레이였다.
- Decision (w2): 郡國志 식별자가 없는 縣 관할은 HHS 결속을 흉내 내지 않고 `REVIEWED_SOURCE_CLAIM`
  결속(route-node-jurisdiction-claims-v1)으로 城 176곳(849–1024)을 세운다. 근거는 CHGIS V6 縣 점 기록 또는
  대리 治所 관할이다. 같은 자리의 동일 실체(杜↔杜陵 등 8곳)와 개명 쌍(㡉)은 새 城을 세우지 않는다.
  이미 城이던 朔方·西河·定襄(833–835)과 龜茲屬國(704)은 대리 治所 省 규칙으로 직할 省에 앉힌다.
- Decision (w3): 수(FERRY, 등급 1) 37 · 진(FORT, 등급 2) 28 · 관(PASS, 등급 3) 8 곳을 正史 근거 원장
  (strategic-strongholds-v1 · strategic-passes-v1)에서 세운다. 省은 기증 縣(또는 郡國 밖 취락) 省에서
  8칸(가는 띠면 4칸까지)을 떼어 han-tiles 배열 끝에 붙인다(carve_strategic_site_provinces, 되돌릴 수 있는 단계).
  떼어 낸 발자국은 남는 기증 省과 마른땅 경계를 하나 이상 공유해야 한다 — 런타임 보급은 마른땅 경계만 잇고,
  거점이 제 縣과 강 칸으로만 닿으면 縣과 같은 주인이어도 끊긴다. 이 규칙으로 孟津 5칸·樊城 7칸이 최소 면적(8)
  아래로 섰다.
  기존 省 인덱스·848 城 번호·이름은 바뀌지 않는다. 城 1025–1097.
- Decision (귀속·소유): 거점 城은 제 省만 갖고 남의 省 귀속 대상(郡治·최근접·이웃 郡 폴백)이 되지 않는다.
  郡 배정으로 주인이 안 정해진 거점은 기증 땅이 귀속된 城의 주인을 따른다(R1 과 같은 원리).
- Decision (같은 자리 두 城 — 1007·680): 1007 「구원(新興郡)」(CHGIS 郡 층 점)은 680 「구원」과 0.02 km 떨어져
  선다. 漆縣·汶山縣처럼 같은 실체라서 빼는 경우가 아니다. 680 은 郡國志 **五原郡** 九原의 식별자가 新興郡 자리에
  선 僑置 오결속이고, 1007 이 그 자리의 진짜 治所(建安 20년 新興郡)다. 병합하면 五原郡이 사라지고(僑置 동명이지
  규칙), 1007 을 빼면 新興郡 治所가 없어진다. 따라서 둘 다 둔다. 680 을 五原 자리로 옮기는 일은 같은 부류인
  56 「하음」(五原郡 河陰 식별자가 河南尹 河陰縣 省에 앉음) 수정과 함께 새 판에서 한다.
- Evidence: 거점 원장의 184–280 正史 인용 · CHGIS SYS_ID·연도 · 개시 보급 절단은 기존 城 기준 전 시나리오
  종전 핀 이하(늘어난 절단은 이미 끊긴 세력 땅에 선 새 城뿐). 예외 하나가 있다. 1062 孟津은 56 오결속 탓에
  1020·1021·1040·1041·1050·1060 에서 마른땅 보급이 끊긴다(56이 중립이라 R1 이 기증 縣 땅을 중립으로 만든다).
  HanStrategicSupplyProviderTest 가 정확히 그 집합만 허용한다.
- Consequences: 새 런타임 판 han-world-v3-1098(1,594 省, 아래 Amendment) 불변 번들. 848 이하 판은 그대로 선택된다.
  밸런스: w2 治所 18곳이 더해져 국가 등급을 세는 郡治가 81 → 99 곳이다. 1/13/28 郡治 문턱(ADR-LITE-043)은
  그대로 두므로 같은 영역에서 등급이 조금 빨리 오를 수 있다. 첫 1097 세계 관찰 뒤 문턱을 다시 본다.
  기존 848 세계에서는 關 표시 오버레이가 사라진다(關은 1097 세계부터 진짜 城이다).
  남은 일: 같은 자리로 빠진 縣 관할 省을 기존 城 관할로 접기, 郡治 표시 없는 郡 27곳, 江油關 등 關 후보 심사.
- Amendment (2026-09-16, 1098): 1097 판은 배포되지 않은 채 main(848 수리 #766·#768 포함) 위로 리베이스되어
  **han-world-v3-1098** 로 다시 얼렸다. 1097 식별자로 저장된 세계는 없다.
  - 五原郡 본토 복귀(사용자 결정 「사료 자리」): 220년 래스터에서 五原郡 땅은 建安 20년 新興郡 僑置 飛地(忻州)뿐이고
    본토(바오터우)는 南匈奴 땅이다. 세계는 187년이므로 郡國志 五原郡 城 680 九原(郡治)·56 河陰을 본토에 세운다.
    근거: 讀史方輿紀要 卷61 「豐州城…漢爲九原縣，五原郡治焉」·「河陰城，在豐州西南。漢縣，屬五原郡」. 漢 九原·河陰의
    CHGIS 기록은 없어 좌표는 2차 원장(나무위키 수확본)을 쓰고, 河陰이 九原 기준점의 서남인지로 교차 확인했다.
    도구: county-misbinding-rebindings-v1 `RELOCATE_TO_SOURCE_ATTESTED_EXTERNAL_LAND`(hostParentRegionId 南匈奴 省에서
    60칸씩 떼어 五原郡으로 소속 변경, 같은 郡 안 郡治 이동 허용). 忻州 飛地는 이웃에 흡수되어 1007 九原(新興郡)만 남는다
    — 위 「같은 자리 두 城」 겹침이 풀렸다.
  - 河南尹 平陰 1098(사용자 결정 「세운다」): 56 이 차지하던 CHGIS 82880 은 魏가 220년에 平陰을 개명한 河陰縣이다.
    56 을 옮기며 옛 발자국을 같은 좌표의 CHGIS 82879 平陰縣에 넘기고(rebindings `leaveBehind`), 郡國志 河南尹
    〖平陰〗(hhs-109 38행)을 HHS LOCATION_ONLY claim 으로 경로 노드 1098 에 세웠다(`w4-vacated-county-location`).
    HHS append 는 원래 claim 배치 앞에만 번호를 받는데, 1097 의 번호를 한 칸도 밀지 않으려고 w4 만 claim 배치 뒤를
    허용했다(연속성은 append 합집합으로 검사).
  - 孟津 1062 보급 절단 해소: 孟津은 이제 平陰(河南尹) 省에서 떼어진 땅이다. 위 Evidence 의 孟津 예외 집합은 1098 에서
    검증한다(HanStrategicSupplyProviderTest).
  - 省 1,594 = 1,520 + 平陰 1 + 거점 73. 城 1,098. 犍為郡 南安(651)은 main 의 수리를 그대로 이어받아 전 시나리오
    개시 절단에서 빠졌다. 개시 보급 기준선이 오른 만큼은 전부 새 城(>848)이다 — 기존 城 중 새로 끊긴 곳 0(행 단위 대조).
  - 1050–1110 북방 구멍 허용 목록(晉書 卷14)에서 흡수된 忻州 飛地 省 95698 을 뺐다. 근거는 그대로다.
  - 금선 무색 버그(2026-09-16 pep): 848 판에서 대리 治所 城 704·833–835 는 省이 없어 신생 국가가 영토를 칠하지
    못했다. 1098 판은 省 없는 城 0 이며 MapAdministrativeOwnershipTest 가 그 넷으로 건국 색칠을 고정한다.
  - 후속: 한자까지 같은 동명 縣 두 쌍이 따로 城이다 — 977/579 巴郡 漢昌(蒼溪·巴中 78 km), 989/627 北地郡 富平
    (涇陽 僑置·靈武 439 km). 한 縣의 두 城인지 사료 판정이 필요하다.
- Reversal: w2/w3 batch·claim 원장과 carve 단계를 벗기고 848 번들로 새 세계를 연다(기존 저장 세계는 판별 번들로 계속 열린다).

## ADR-LITE-056 — 소속 없는 省 0: 城 없는 관할 접기, 郡國 밖 취락의 城, 고증 뱃길 (2026-09-17)

- Status: approved (사용자 명시 2026-09-17 — 「절대 소속 없는 프로빈스가 있어선 안돼」 · 「2번은 너가 판단해」 ·
  「3번도 찾아서 넣을 수 있으면 넣어」 · 「이민족이나 중국 밖의 거점들… 점령 가능한 거점이어야」 ·
  「고증에 맞춘 해로 연결」(표의 해로 전부) · 「뱃길은 하나의 포물선이나 곡선으로」 · 「좌표 대로 따라가되, 해당 프로빈스 밖에 있으면
  안으로 밀어넣어야지」)
- Context: 1098 판 pep 에서 城 없는 관할 46곳의 省 174 가 초기 배정 색에만 묶여 주인이 영원히 바뀌지 않았다
  (MapAdministrativeOwnership 은 城이 선 관할의 省만 점령으로 칠한다). 漢 縣 9곳은 1097 판이 「같은 자리의 城」으로
  판정하고 접기를 후속(FOLD_PROVINCES_INTO_ROUTE_NODE_JURISDICTION)으로 남긴 곳이고, 37곳은 郡國 밖 취락이었다
  (route-node-review-policy forbiddenSelections 로 2026-08-23 부터 城 금지). 같은 縣이 두 번 선 城도 두 쌍 있었다.
- Decision (접기): han-tiles 마지막 단계 `tools/map/fold_cityless_jurisdictions.py`(결정 원장
  `cityless-jurisdiction-fold-decisions-v1`) — 省 칸은 그대로, 관할 소속만 같은 실체 城 관할로 옮긴다. 대상 관할이 다른 郡이면
  省의 parentRegionId 도 옮기고 郡 표면을 다시 잰다(시나리오 소유 원장·계층 검증이 省의 郡으로 소속을 읽는다). 관할이 모두 접힌
  郡國志 뒤의 郡 4곳(新平·毗陵典農校尉·汶山·章武)은 행을 남기되 jurisdictionIds 가 비고 seat 가 null 이다(런타임 통제 0).
  汶山은 綿虒道와 맞닿지 않아 유일하게 맞닿은 蜀郡 廣都(632)에 접는다.
- Decision (중복 城, 위임 판정): 郡國志 巴郡 「〖汉昌〗永元中置」·北地郡 「〖富平〗」은 각 한 縣이다. 郡國志 결속 城
  579·627 을 남기고 w2 의 977(CHGIS 44621 巴中)·989(70523 永初 寄治)를 거둔다. 寄治 富平 땅은 떨어져 있어도 같은 縣
  富平(627) 관할에 둔다(宋書 卷48 「失土寄寓馮翊置泥陽富平二縣」). UNKNOWN: 讀史方輿紀要 卷68 은 蒼溪의 漢昌을 蕭齊 개치로
  적어 579 의 治所 점은 巴中 쪽이 맞을 수 있다 — 治所 점 이동은 후속.
- Decision (郡國 밖 취락 37곳): `w5-external-settlement-route-claim`(REVIEWED_SOURCE_CLAIM, 근거 external-places.json
  비정 문장+Wikidata). 번호 1..N 연속을 지키려고 977·989 의 UUID 키를 재결속(registry `rebinding`,
  `rebindingChangesKey: false`)해 國內城·卒本이 잇고, 나머지 35곳은 1099–1133. 등급: 東夷傳 권역은 戶數 사다리, 치소 아닌
  취락은 '소', 그 밖 이민족 거점 7곳(西羌·白馬氐·哀牢·山越·烏桓·鮮卑·南匈奴)은 '이'. 이름은 관할 이름(애뢰·남흉노·서강·백마저).
  **ADR-LITE-053 의 「현행 장식의 점령 가능화」 기각을 뒤집는다** — 羌·氐도 점령 가능한 城이다(변방 이벤트 설계는 여전히 후속).
- Decision (西安平·安平口): 압록강 하구 省 1304 는 卒本 관할에 기하 배정으로만 묶여 있었고 사료는 遼東 西安平 자리다(漢書 卷28
  「馬訾水西南至遼東郡西安平縣入海」, 後漢書 卷85 「復犯遼東西安平，殺帶方令，掠得樂浪太守妻子」). 卒本이 城을 받자 이 省이 公孫氏
  樂浪·帶方 17城의 보급을 끊어 西安平 관할로 옮기고, 거점 원장이 anchorCounty 西安平縣으로 적은 安平口 관할도 遼東郡으로 옮긴다.
- Decision (뱃길): build_han_world `V3_SEA_ROUTES` — 沓氏↔東萊 黃(三國志 卷8 「越海收東萊諸縣」), 沓氏↔吳(資治通鑑 卷72
  「乘海之遼東」), 吳↔安平口(三國志 卷47 裴注), 帶方→狗邪→對馬→一大→末盧(三國志 卷30 倭人 도해 사슬), 東冶(東部侯官)↔番禺
  (後漢書 卷33 「從東冶汎海」), 徐聞↔朱崖(漢書 卷28 「自合浦徐聞南入海」), 기존 섬 뱃길 夷洲·流求↔會稽, 州胡↔辟卑離國,
  于山國↔悉直國(于山國·流求는 근거 약함 표기 유지). 城 연결(이동·공격)에 들어가고 `seaRoutes` 로 지도 API 에 실려 한 줄 곡선
  (2차 베지에)으로 그린다. 보급(省 인접망)은 바꾸지 않는다.
- Decision (아이콘 자리): 아이소 지도의 城 아이콘은 지형 씨앗 칸이 아니라 경위도 투영점을 따르고, 제 省 밖이면 제 省 칸 중 가장 가까운
  칸으로 민다(`buildProvinceSeatCells`·직할 省 城의 x/y 폴백도 같다). 투영점이 제 省 밖인 城이 489곳이었다.
- Evidence (게이트·레드 프로브): province-city-attribution 이 OWN_COUNTY_SEAT 아닌 省을 거부한다(접기 전 문서 12곳 빨강).
  MapAdministrativeOwnershipTest 「no province is left without a city that can paint it」(1133: 0, 1098: 174). web
  cityIconInsideProvince.test(밀기 끄면 489곳 빨강). 사료 게이트 16종 녹색, 개시 보급 기준선 1098 과 동일.
- Consequences: 새 불변 번들 han-world-v3-1133(省 1,594 그대로, 관할 1,144 → 1,133, 城 1,133). 1098 이하 판은 그대로 선택된다.
  밸런스: 郡國 밖 취락 31곳이 郡治로 서서 국가 등급 郡治 수가 늘 수 있다. 섬·뱃길 城은 보급망(省 인접)과 따로 이어진다.
- Reversal: 접기 단계·w5 배치·뱃길 표를 벗기고 1098 번들로 새 세계를 연다.
- 정정 (2026-09-17, ADR-LITE-057 교차 비평): 위 「보급(省 인접망)은 바꾸지 않는다」·「섬·뱃길 城은 보급망(省 인접)과 따로 이어진다」는 같은 PR 의 구현과
  다르다. 뱃길 13줄은 `data/map/han-commandery-supply-links-v1.json` 에도 실려(`build_commandery_supply_links.py`) `HanSpatialSupplyProvider` 가
  보급 인접망에 더하고, `CommanderySupplyLinkTest` 가 13줄을 고정한다. 현행 런타임은 뱃길을 城 연결과 보급 인접망 **모두**에 싣는다.

## ADR-LITE-057 — 삼모 명령 체계를 「장수·휘하」 캠페인 설계로 교체한다 (2026-09-17)

- Status: accepted (2026-09-24 사용자 승인). S3 게임 수치는 [#872](https://github.com/peppone-choi/opensamguk/issues/872)에서 확정했다. 정본은 `data/curated/han/hwiha-s3-provisional-v1.json`·`hwiha-domestic-v1.json`·`hwiha-vision-rules-v1.json`이며, 그 밖의 미정 수치는 각 원장 상태를 따른다.
- Context: 지도는 城 1,133 · 省 1,594 의 면(面) 세계가 됐지만(ADR-LITE-055·056) 규칙은 삼모전의 점(點) 구조다.
  시나리오 1020 개시 활성 장수는 231명(확장 299, `seedContract.activeGenerals`)이라 1,133城에서 城당 약 0.20명이다.
  이동은 인접 城 1칸이 1턴이고 이동 비용에 지형 계수가 없다. 지형은 전투·명령 코드에서 참조되지 않고 보급·경로 위상에 물/마른땅
  구분으로만 들어간다. 城 값은 등급(1133 판 11종)마다 하나다. 통일은 전 城 소유(`CheckEmperior`)다. 공개 알파 카탈로그 124개는
  삼모 명령 별칭 70 + 신규 54 로, 두 체계를 함께 완성하도록 요구한다.
- Decision (사용자 확정):
  1. 웹게임 정체성만 남기고 새 게임으로 교체한다. 삼모 명령·엔진·골든을 동결 회귀 기준선으로 보존하던 결정은 2026-09-25 ADR-LITE-066으로 폐기했다.
  2. 서버당 유저 30명 이하 + NPC 다수를 가정한다.
  3. 시간은 **장수별 개인 턴 시각을 유지**한다(같은 날 고른 「전역 동시 해결 순」을 사용자가 되돌렸다). 세계 시계는 순(旬)이고
     세계 처리는 순 경계에서 한다(현행 `runTick`/`runDueGeneralTurns`). 명령 목록 12순(ADR-LITE-049)은 장수 행동 한 순 1개로 유지한다.
  4. 플레이 단위는 가문(혈연)이 아니라 **장수 한 명 + 휘하 카드**다. 모든 플레이어는 **장수로 시작**(재야·출사)하고
     **주공은 성장해서 되는 지위**다 — 모두가 처음부터 주공인 「군주제」 초안을 기각했다.
  5. 결속은 혈연·향당·은의·결의·명망(인물 결속)과 의종·항중(부대 결속) 7종이다(정사·연의 사료 근거).
  6. 느낌은 TCG + 삼모전 + 전략 시뮬레이션. TCG 깊이는 편성(명망 코스트·결속 시너지) + 계책 손패.
  7. 시즌 이월은 없다(유산 포인트 폐지).
  8. v2 에 계획한 기능은 새 틀에 재배치한다(1·2·3층).
  9. 자원은 전·곡·철·목재·말 5종이고 돈도 실물로 수송한다(v2 07-13 「철·나무를 범용 원자재 숫자로 두지 않음」을 대체).
  10. 공백지는 「난세 개막」 모드다. 세력·주공 없이 시작하고 역사 인물 전원을 본관 縣에 재야로 흩어 두어 등용·거병으로 다툰다.
      지도는 `han-world-v3` 최신 판으로 새로 만든다(사용자 확정 2026-09-17 — 결정 당시 1098, 같은 날 1133). 은퇴한 공백지 15개는 삼모 전용 CHE/miniche 회귀 자료로 보존하지 않는다. 휘하가 실제 사용하는 공유 han 회귀 자료만 의존 감사 후 유지·이전한다. 추가는 ADR-LITE-043
      allowlist 검토를 거친다.
  11. 정사와 연의를 모두 사료로 대우한다. 월드를 정사·연의로 나누지 않고(`WorldContentProfile` CHRONICLE/CLASSIC 분리 미적용)
      모든 카드를 모든 월드에서 쓴다. 출처는 인용마다 책·권으로 따로 기록한다.
  12. **공개 알파는 1·2·3층을 모두 완성한 뒤 연다.**
  13. **중립 縣은 점령할 수 있다** — ADR-LITE-052 의 「중립 영토 점령 가능 여부 미결(proposed)」을 닫는다.
  14. **해전은 별도 규칙이다**(풍향·수심·승선 등, 요소·수치 미정). 해로 행군·해운 수송도 이 규칙에서 정하며 전체 지도를 여는 S4에 도입한다.
  15. **주변 세계는 지도 밖 외교 행위자다.** 거점 城은 점령할 수 있지만(ADR-LITE-056) 세력으로 세우지 않고, 침입·조공·내속·교역은 사건·외교(2층)로 일어난다.
  16. **장수 초상은 RTK14 를 유지한다**(ADR-LITE-048). 배포용 자체 초상 교체 에픽은 닫는다.
  17. **라이브 서버 pep 은 S3(한 州 슬라이스) 통과 직후 새 규칙 시험 서버로 전환한다.** 전환 때 현행 월드는 초기화한다.
- Decision (사용자 위임·수용): 주공은 휘하 구성 판정식이 아니라 사건(거병·독립·봉신 계약으로 얻고, 출사·하야·멸망으로 잃음)으로 정하는
  지위이고 縣을 모두 잃으면 방랑 주공이 된다·휘하 중첩(주공은 장수 카드 코스트만, 장수 개인 휘하의 NPC 부장·실명 부대 코스트는 장수 명망에서,
  부곡은 자원·지휘 한도)·휘하 카드는 주공을 따라 소속이 움직임·권한 분할(발령은 주공의 조정 결정이고 장수는 행동 소모 없이
  수락·거절, 자리 안 방침·개인 행동·자기 손패는 장수)·군주 → 봉신 주공 → 장수 3단계 위계, 입력 6종(장수 행동·배치·방침·공사·계책·
  조정 결정), 기존 명령 70개 대응표, 장수 턴 처리 순서(드로우·재검사 → 정치 → 계책 즉시·설치 → 이동 → 조우 전투(공격 계획과 방어
  대응 함께 공개) → 공성 → 현장 행동 → 기록)와 순 경계 처리(보급·재정 → 포위 → 내정 → 월 경계), 계책 사용 방식 즉시·설치·대응,
  카드 구조, 보급망 = 재정망과 적 군단이 선 省의 보급 통과 차단, 1·2·3층 구분(휘하 장수 발령·응답·포상, 정찰·첩보 카드, 명망·월단평은 1층),
  공백지 「난세 개막」 규칙(사람 장수 본관 선택, NPC 거병, 州별 주공 보장, 초반 문턱 완화).
- Amendment (2026-09-25, ADR-LITE-066): 위 70개 대응표는 제품 입력 원장의 의무에서 제외한다. `legacyCommands`·`retiredLegacyCommands`와 70개 대응 게이트를 제거한다. 직접 행동의 표시 이름은 원장 표시 필드가 정본이다.
- Supersedes:
  - `docs/design/roadmap.md` 2026-08-27 판 「공개 단계」 표·「커맨드 완결 규칙」.
  - `2026-08-27-public-alpha-rebaseline-design.md` §1 전체(WEGO tactical resolution 제품 정의·전 카탈로그 알파 조건), §2 명령 범위 전체
    (legacy 명령, land·siege·naval WEGO orders 포함), §6 명령 5계층(동시 봉인 WEGO battle round), §7 해결 순서, §10 단계 게이트, §11 첫 항.
    §3 delivery-state 파이프라인은 보존.
  - `2026-08-27-public-alpha-command-contract-freeze.md` P-3 해결 순서·P-7·P-13·P-15(Stage 0 카탈로그 닫기 게이트). P-4·P-10 의 sealed WEGO
    rounds 는 「공격 봉인 계획 + 방어 대응」으로 읽는다.
  - 알파 카탈로그의 제품 범위 정본 지위.
  - `2026-07-12-opensamguk-v2-product-spec.md` §6 소절 「개인턴·사령턴·전술 명령의 경계」·§9 소절 「v1 커맨드 카탈로그의 진화 규칙」.
  - `2026-07-13-v2-historical-city-army-terrain-design.md` §3 월드 콘텐츠 프로필 분리·§4 자원 명칭 표의 철·나무 행.
  - `2026-09-06-province-front-and-county-capture-design.md` §8 명령 계열 표.
  - V55 「서약한 장수는 계속 스스로 행동」·「가신 관계가 국가 소속을 바꾸지 않고 자동 전향하지 않음」 규칙(새 게임에서는 거느린 장수의
    배치·방침을 따르고, 휘하 카드는 주공을 따라 소속이 움직인다).
- Preserves: 결정론·단일 쓰기 경로, `han-world-v3-1133`(ADR-LITE-056, 고증 뱃길 포함)·R1, 省=위치·이동·점유/縣=내정 원칙, 작전실 UI 구조, 개인 턴 데몬 구조,
  V49·V50 공간 상태, V55 부곡(자기 병력·군량에서 편성)·V56 작전·V57(공격 측 城 강공 계획·리플레이로 한정)·V58·V59 전장,
  `HanSpatialSupplyProvider`·`StrategicSupplyNetwork`, v2 evidence·geo 계약 코드(샌드박스 표면).
- Consequences:
  - 정본 설계: `docs/superpowers/specs/2026-09-17-general-and-retinue-campaign-redesign.md`.
    계책 카드 초안: `docs/superpowers/specs/2026-09-17-stratagem-card-catalog-draft.md`.
  - 라이브 서버와 기존 코드(알파 카탈로그·`PublicCommandCatalogIndex`·삼모 명령)는 새 체계 활성화 전까지 그대로 운영한다.
  - V57 에는 방어 측 대응·진형·야전 조우 계획이 없다. 이것들은 새 스키마다.
  - 데이터 선행: 인물 본관 縣, 철·목재·말 산지(郡國志 본문 「有鐵/有铁」 30·「出铁」 4 는 繁·簡 합산 거친 계수이고 縣 단위 대조 전,
    지형에 숲 없음, 목장 데이터 없음). 결손을 추정으로 조용히 메우지 않는다.
  - 유저 대부분이 NPC 주공 밑 장수로 시작하므로 NPC 주공의 발령·포상 AI가 첫 경험의 품질을 정한다.
  - ADR-LITE-049 의 「현행 라벨·게이팅 불변」은 새 메뉴와 충돌하므로 한 州 슬라이스(S3) 착수 전에 개정한다.
  - `CLAUDE.md` 의 RTK14 「미매칭만 50/50」 서술은 현행 빌더와 달라 후속 PR 에서 고쳤다.
  - 수치는 게이트 임계값이 아니다. 실측 기준선과 시뮬레이션으로만 정한다.
- Reversal: 이 ADR 과 두 설계 문서를 withdrawn 으로 표시하고, 다음을 이 ADR 이전 판(PR #771 머지 직전 origin/main `a55ca2c3`)으로 되돌린다 —
  `docs/design/roadmap.md`(머리말·공개 단계 표·휘하 절·작전 목표와 교전 순서도·입력 완결 규칙·튜토리얼 절), `docs/design/README.md` 읽을
  문서 표, `README.md`(소개·지금 만드는 것·공개 알파 원칙·프로젝트 상태 표), `docs/user/README.md` 로드맵 링크 문장, ADR-LITE-052
  Amendment(2026-09-17), `CLAUDE.md` 5스탯 문장, ADR-LITE-056 정정 노트, `docs/superpowers/plans/2026-09-17-general-retinue-portfolio-plan.md`(삭제),
  대체 배너 7건(plans 08-27 포트폴리오·명령 기반·08-22 마스터, specs 08-27 rebaseline·contract-freeze·07-12 v2 product·09-06 province-front),
  `docs/design/README.md`·`roadmap.md` 의 계획 링크. 코드 변경이 없으므로 되돌릴 구현은 없다.

## ADR-LITE-058 — 산으로 칠해진 이름 있는 저지를 표고로 되돌리고, 1133 릴리스를 제자리에서 재핀한다 (2026-09-17)

- Context: `han-tiles.json` 의 지형 클래스는 표고가 아니라 Natural Earth 지리구역 폴리곤에서 온다
  (`build_terrain_grid.REGION_TERRAIN`). 폴리곤은 거칠고 `Range/mtn` 이 `Plain` 위에 덮이므로 산맥 폴리곤 안의
  분지·산전 평원이 통째로 MOUNTAIN 이 됐다. 실측(origin/main `b23e7e14`): 雒陽縣 22칸이 전부 MOUNTAIN,
  PLAIN+BASIN 0칸인 관할 195/1,133(우세 MOUNTAIN 140·PLATEAU 45·DESERT 8·RIVER 2), 그 가운데 郡治 31곳.
  縣 경제 입력(#777)이 이 클래스를 읽으면 洛陽 분지가 산악 縣으로 계산된다.
- Decision: han-tiles 를 재생성하지 않는다(판정이 얹힌 정본이다). 마지막 단계
  `tools/map/reclassify_han_lowland_terrain.py` 가 검토 원장 `data/curated/han/lowland-terrain-decisions-v1.json`
  이 **이름을 적은 저지 19곳**(伊洛·太原·上黨·臨汾·南陽 盆地, 河內·關中·太行東麓·汝潁·隨棗·淮南·鄂東·江漢西緣·
  鄱陽·皖西南 平原, 紅河·淸化乂安·廣平廣治 해안평원)의 경위도 상자 안에서만 MOUNTAIN·HILL 칸을 PLAIN/BASIN 으로
  바꾼다. 전역 DEM 규칙은 쓰지 않는다(사용자 결정) — Range 폴리곤 오탐을 검토 없이 받아들이게 된다.
- Decision: 칸을 가르는 축은 지형 클래스와 독립인 커밋된 ETOPO1 표고(`han-world-v3-metres.png`)다. 기준은
  3×3 기복 ≤ 89 m 이고, 89 는 지은 임계값이 아니라 실측 기준선이다(NE Plain/Lowland 폴리곤 안 PLAIN 23,900칸의
  p90; 같은 측정에서 Range∩MOUNTAIN 의 p50 은 371 m; `--measure-baseline` 이 다시 잰다). 단위별 표고 상한은
  출처가 적은 바닥 고도의 윗값만 옮기고, 출처가 말하지 않은 단위(6곳)에는 두지 않는다.
- Decision: DESERT·PLATEAU·물은 건드리지 않는다. 사막은 원래 평평해 기복으로 평지와 못 가르고, 고원은 정당한
  클래스다. owner·seatOwner·parentOwner·모든 행 표는 그대로다. 런타임(`HanStrategicTopologyJson`)은 지형을
  마른 땅/물로만 읽으므로 省 인접·수계·`han-world-v3.json`·Kotlin 상수는 바이트 단위로 그대로다(실측).
- Result: 1,332칸. 雒陽縣은 22칸 중 8칸이 BASIN 이 되고 14칸(邙山·萬安山 기슭)은 MOUNTAIN 으로 남는다. 0칸 관할 195 → 169, 郡治 31 → 25(雒陽·界休·長子·弋陽·陽安·西卷이 풀렸다).
- Decision: `han-world-v3-1133` 을 **제자리에서 재핀**한다(사용자 결정). 城·省·관할·인접·좌표가 그대로라 새
  릴리스가 아니고, 리졸버가 city id 집합으로 variant 를 고르므로 병행 식별자는 불가능하다(ADR-LITE-054 와 같은
  이유). 바뀐 blob 6개(han-tiles·수계 위상·수계 원장·전략 매니페스트·월드 매니페스트·시나리오 省 소유)와
  `Han1133Artifacts.CATALOG_SHA256` 가 함께 움직인다.
- Consequences: blob 해시가 `StrategicTopology.contentHash` 입력이라 1133 의 contentHash 가 바뀐다. **1133 으로
  pin 이 박힌 월드는 topology_hash 가 어긋나 로드에 실패한다 — 배포 전에 월드 리셋이 필요하다.**
- Not done (같은 증상, 다른 원인): 鄴·安邑·始平·元氏처럼 여전히 0칸인 평지 郡治는 지형 오분류가 아니라 **省
  기하가 제자리에서 10–17칸(약 50–90 km) 밀려** 실제 산지 위에 서 있는 것이다(省 중심 vs 城 경위도 투영 실측:
  p50 4.9칸·p90 17.4칸). 표고로는 못 고치고 owner 를 옮겨야 하므로 이 결정의 범위 밖이다. 沮·西城·魚復·邛都·
  金城·居延 등은 정말 산지·고원·사막이다. 검토에서 뺀 후보 7곳은 결정 원장 `reviewedAndExcluded` 에 사유와 함께 있다.
- Not done (교차 비평 지적): `adjacency.commandery` 의 `cross`/`ford` 는 `LAND_COST` A* 로 옛 지형에서 구운 값이고
  이 단계는 그것을 다시 굽지 않는다. 새 지형으로 다시 재면 7개 간선이 달라진다(河南尹–潁川·河南尹–陳留 RIVER→LAND,
  5개는 ford 위치 이동). 런타임은 이 필드를 읽지 않는다(소비자는 `build_tile_grid` 스키마 검증뿐). 앞 단계 검사가
  이 단계를 벗기고 돌기 때문에 어느 게이트도 이 낡음을 보지 못한다 — 후속 이슈로 남긴다. `_meta.note` 의
  「지형·소유는 렌더러 산출물 그대로다」도 이제 지형에 대해 거짓이지만 생성기 소유 필드라 건드리지 않았다.
- Residual risk: 汝潁平原 두 단위·太行東麓·紅河·淸化乂安·廣平廣治 해안은 출처에 바닥 고도가 없어 상자 안을 가르는 것이
  기복 기준뿐이다(재분류된 칸 최고 176 m). 현대 지형(ETOPO1)이라 후한대 하도·해안선 변화는 반영하지 않는다.
- Chain note: 이 단계의 핀은 접기 단계 출력에 묶여 있다. `fold_cityless_jurisdictions.py --prepare` 가 이 원장도 같이
  다시 쓴다. 핀이 안 맞는 문서에 이 도구를 돌리면 조용히 넘어가지 않고 실패한다(`--source-is-upstream` 로만 우회).
  카탈로그 `sourceBaseCommit` 은 이 작업이 출발한 main 커밋이다 — squash 머지로 사라질 브랜치 커밋을 적지 않는다.
  릴리스의 정체성은 blob 해시다.
- Gate: CI `python3 tools/map/reclassify_han_lowland_terrain.py --check`(벗겼다 다시 얹어 terrain 재현, owner 이동·
  결정 원장·표고 PNG 해시 변경 검출; 변조 입력으로 적색 확인). 앞 단계 검사는 `folding.peel()`/`stage_for()` 가
  이 단계를 먼저 벗긴다.
- Reversal: `--source` 로 벗긴 문서를 되쓰고(`peel`), 두 원장·도구·CI 단계를 지운 뒤 같은 절차로 사슬과 1133 을
  다시 재핀한다.

## ADR-LITE-059 — han-tiles 빌드 계약 v1 의 lock·증명 흐름을 폐기 표시한다 (2026-09-17)

- Status: accepted (사용자 결정, GH #536 / OPENSAM-235)
- Context: v1 계약(`tools/map/han_tiles_contract.py`)과 보호 실행기(`tools/map/han_tiles_protected_orchestrator.py`)는 wheel 4종
  (numpy·Pillow·PyYAML·hanja)의 파일 sha256 을 박은 lock 과, `BUILD_HAN_TILES` 단계 출력 = 정본이라는 등식을 요구한다. 2026-09-17 실측:
  (1) 당시 빌드의 wheelhouse 와 wheel 버전 기록이 어디에도 없다(UNKNOWN). (2) 커밋된 `data/map/han-tiles.json` 은 생성기 출력 위에
  frontier 縣 물질화·城 없는 관할 접기·국소 고증 수정이 얹힌 산출물이라 v1 의 등식이 구조적으로 성립하지 않는다. (3) 제한 입력 12종은
  미커밋(ADR-LITE-039)이고 `MODERN_ADMIN_ADM2` 는 로컬에도 없다.
- Decision: lock 을 만들지 않는다. 없는 환경을 오늘 머신 값으로 핀하면 지어낸 lock 이다. v1 의 lock·attestation 흐름은 **폐기 표시**하고
  새 작업의 근거로 쓰지 않는다. 정본 보호는 CI 의 단계별 결정론 `--check`(frontier 물질화·관할 접기·省→城 귀속·carve 등)가 맡는다.
  `han_tiles_contract.loads_json_strict` 같은 범용 헬퍼는 다른 도구가 쓰므로 파일은 지우지 않는다.
- Consequences: 「han-tiles 를 원본에서 통째로 재현」하는 보증은 없다 — 이미 [han-tiles 는 판정이 얹힌 정본]이라 전체 재생성이 금지된 상태와
  일치한다. 보증 범위는 「커밋된 정본에서 각 후속 단계가 결정론으로 재생성된다」다.
- Reversal: owner 가 승인한 wheelhouse 로 기반 5단계 출력(`5d888dc6…`)의 바이트 재현을 보인 뒤, v1 을 「기반 단계 재현성」 전용으로 한정해 되살린다.

## ADR-LITE-060 — 강 뱃길을 城 연결로 놓고, 1133 릴리스를 제자리에서 재핀한다 (2026-09-18)

- Context: 수로 망 원장(#826, `waterway-network-adjudications-v1`)은 長江·黃河의 구간·나루·항구를 사료로 세웠지만
  NON_ACTIVATING 이었다. 타입 간선(FERRY/EMBARK/RIVER_*)으로 활성화하면 로더가 요구하는 RiverBarrier 가 기존 LAND
  간선을 지워 연결이 오히려 줄고, 비-LAND 간선은 런타임 소비자가 없어 효과가 0 이다(2026-09-18 활성화 판정 노트).
  한편 장수 이동·출병·AI 는 전략 위상이 아니라 `han-world-v3.json` 의 城 `connections` 를 읽는다 —
  `HanWorldV31133CityConst` → `HanCityConstVariant.path` → `CalcCityDistance`(che_이동 `nearCity(1)`·강행 `nearCity(3)`),
  `SearchDistanceListToDest`(che_출병 경로), `SetNationFront`(전선), `AiDistance`·AI families, `ConquerCity`(수도 이전 후보),
  `GetConstController`(클라이언트 거리 안내). ADR-LITE-056 의 바닷길 13줄이 이미 그 방식이다.
- Decision (사용자 결정 2026-09-18 「진행해」): 강 뱃길을 바닷길과 **같은 방식**으로 지금 넣는다 — 사료로 세운 항구 城끼리
  `connections` 한 줄 + `seaRoutes` 한 줄(`kind: "RIVER"`, 기존 13줄은 `kind: "SEA"`). 형제 목록 `riverRoutes` 를 두지
  않은 이유: `seaRoutes` 의 소비자(MapJson → MapPreviewDto → IsoWorldMap 곡선, 보급선 빌더)가 전부 「城 쌍 + 근거」만 읽어
  그대로 동작하고, 구분이 필요한 곳은 `kind` 로 가른다(보급선 `canonicalGroup` SEA_ROUTE/RIVER_ROUTE).
- Decision: 표를 두 군데 두지 않는다. 원장에 `portLinks` 를 더하고 빌더가 쌍을 **유도**한다 — 검토된 PORT 노드끼리,
  flowLinks 로 이어진 구간 위에서, 물길 거리로 사이에 다른 항구가 없는 쌍. 원장의 쌍 집합이 유도 집합과 정확히 같아야
  한다(원장은 출처만 단다). `build_han_world.v3_river_routes` 는 산출물의 portLinks 만 읽고 월드 매니페스트가 그 해시를
  핀한다(`inputs.waterwayNetworkSha256`).
- Result: 3줄 — 江州(572)↔夷陵(401), 夷陵(401)↔樊口(1037), 樊口(1037)↔濡須口(1072). 夷陵–樊口 를 잇기 위해 원장에
  江水 夷陵–夏口·夏口–武昌 구간과 흐름 연결 3개를 더했다(晉書 卷42 王濬傳 「二月庚申，克吳西陵」「夏口、武昌，無相支抗。
  於是順流鼓棹，徑造三山」, 三國志 卷47 「是歳，改夷陵爲西陵」 — 코퍼스에서 원문 확인). 漢津은 沔水 구간이 江 과 흐름으로
  이어져 있지 않아 뺐다(沔口 합류 미판정). 建業·江陵·夏口·廣陵은 城 칸이 물에서 멀어 여전히 blocked 다.
- Measured: 夷陵↔江州 5→1홉, 夷陵↔樊口 7→1홉, 樊口↔濡須口 6→1홉. 城 쌍 641,278 중 98,692쌍(15.4%)의 최단 홉이 줄고
  최대 9홉 준다(江州→建業 13→6). 城 연결 3,053 → 3,056. 보급선 74 → 77(바닷길과 같이 뱃길은 보급도 싣는다).
- Not invented: 비용·용량·계절. 연결 한 줄은 다른 모든 연결과 같은 한 칸이다. 三峽 471 km 를 한 턴에 가는 것이 과한지는
  밸런스 판정이고 이 결정의 범위 밖이다 — 타입 간선·다턴 수송 설계(ADR-LITE-057 계열)가 오면 거기서 대체한다.
- Decision: `han-world-v3-1133` 을 **제자리에서 재핀**한다(사용자 결정, ADR-LITE-058 과 같은 이유 — 城 id 집합이 그대로라
  병행 식별자가 불가능하다). 바뀐 blob 3개(`han-world-v3.json`·월드 매니페스트·郡 보급선), `catalog.json`,
  `Han1133Artifacts.CATALOG_SHA256`, `HanWorldV31133CityConst` 스냅샷과 `runtime-constants.json`(+ 그 해시를 핀한
  `HanRuntimeConstantsIntegrityTest`)이 함께 움직인다. 846/848/1098 번들은 바이트 단위로 그대로다.
- Consequences: blob 해시가 `StrategicTopology.contentHash` 입력이다. **1133 으로 pin 이 박힌 월드는 로드에 실패한다 —
  배포 뒤 월드 리셋이 필요하다**(사용자가 수락, 리셋은 메인 세션이 한다).
- Gate: `test_build_han_waterway_network`(portLinks: 항구 건너뛰기·끊긴 구간 넘기·도하점 끝점·누락·출처 없음·흐름 삭제 적색),
  `test_han_world_river_routes`(세계 파일 RIVER 줄 = portLinks, 끝점 PORT 아님·출처 없음·상태 모름 적색),
  `build_han_world --check`(원장이 바뀌면 매니페스트 핀으로 STALE), `repin_han_1133_bundle.py --check`
  (`check_han_tiles_coupled` 키 `release-1133-bundle` — 번들이 작업 트리와 어긋나면 적색; 재핀 전 상태에서 적색 확인).
- Reversal: 원장에서 `portLinks` 를 비우고(유도 집합이 비지 않으므로 구간·흐름 추가분도 함께 되돌린다) 같은 순서로
  재생성·재핀한다. 역시 월드 리셋이 든다.

## ADR-LITE-061 — 조우 전투는 조작 없이 두고, 계획에 조건부 명령과 계책 공개 시점을 넣는다 (2026-09-18)

- Status: accepted (사용자 결정 2026-09-18 「승인」, GH #786 / #782). 문서 전용 — 코드·스키마 변경 없음.
- 번호: 060 은 `origin/work/opensamguk/river-routes` 가 쓰고 있어(강 뱃길, 미머지) 061 을 잡았다.
- Context: 정본 설계 §5.1 5단계의 조우 전투는 「함께 공개 → 결정론 전투 → 리플레이」 한 줄이었고, 현행 V57 계획 입력은 태세 1개 +
  퇴각 조건 2개가 전부다(`V57__battle_plan_replay.sql:10-12`, `BattlePlanRules.kt:80-91`). 전투가 단조롭다는 문제에 대해 실시간 조작을
  넣는 안은 비동기 접속·방어 측 부재(§10)와 맞지 않는다.
- Decision: 전투 중 조작은 넣지 않는다. 대신 (A) 공격 측 봉인 계획과 방어 측 진형 방침에 «조건 → 행동» 조건부 명령(방향 3–4칸)을 두고
  기존 퇴각 조건 2개를 그 보통 항목으로 흡수한다, (B) 대응·전투 단계 설치/봉인 계책은 공개할 교전 회차를 미리 지정할 수 있고 공개되지
  못한 카드는 소모·비용·노출이 없다, (C) 리플레이는 애니메이션으로 보여 주되 연출은 결과에 영향을 주지 않는다. 국면 정본 순서는
  접근 → 공개 → 교전 → 조건 발동 → 정산(교전 ↔ 조건 발동 반복)이고 「국면 경계」는 조건 발동 국면이다. 평가 순서는 공개 → 공격 측 칸 순 →
  방어 측 칸 순, 회차 끝 스냅숏 평가로 고정한다. 칸 수·어휘·문턱값·회차 상한은 **미정**(정본 §16.3) — 어휘는 초안만 실었다.
- Amendment(2026-09-18, 같은 날 사용자 추가 결정): ① 위 평가 순서와 「미공개 카드의 무효 사유는 주인 기록에만, 검증 해시는 미공개 카드 포함
  전체 입력」을 **확정**했다(정본 §16.1). ② 조우 전투는 **격자 위에서** 해결한다 — 전장은 조우 省의 han-tiles 칸·지형에서 뽑고, 부대는 칸을
  점유하는 패, 진형은 배치 모양(프리셋 + 배치 구역 안 수정), 조건부 명령의 행동은 목표 칸을 가질 수 있고, 측면·고지·도하는 위치 관계로,
  지형은 칸마다(평지 / 구릉 방어▲ / 산 통과 불가 / 강 도하▼, 계수 미정) 보정한다. 공격 측은 봉인 배치(정찰만큼만 보임), 방어 측은 지형을 알고
  배치한다. 격자 크기·추출 방법·배치 구역·회차 규칙·패 단위·공성 적용은 미정. 현행 V59 전장은 점 노드 하나(`BattlefieldCatalog.kt:5-13`)라
  격자는 새로 만든다. 전장은 저장하지 않고 전투 시점의 핀된 토폴로지에서 파생한다(#806 으로 省 기하가 바뀐다).
- Amendment (2026-09-25, ADR-LITE-066): 삼모 城 강공 전용 V57 경로는 휘하 의존 감사 뒤 제거한다. 조우 전투의 위 조건부 명령·리플레이 계약은 유지한다.
- Consequences: V57 의 확장이 아니라 새 스키마가 필요하다(방어 측 계획, 조건 칸, 공개 시점, 발동 기록). 삼모 城 강공 전용 V57 경로는 의존 감사 뒤 제거한다. 리플레이는 발동한 조건과 카드 공개 회차를 기록하고 `replay_hash` 에 넣어야 한다. 결정론은
  `docs/superpowers/specs/2026-09-18-personal-turn-determinism-contract.md` §2.2·§2.3 에 묶는다. §4 「무효 시 사유 기록」은 미공개 카드에
  한해 주인 기록에만 남기는 것으로 좁혔다.
- Reversal: 시뮬레이션에서 조건부 명령이 결과를 가르지 못하거나 계획 입력 부담이 크면 칸을 줄이고 프리셋만 남긴다. 조작 없는 해결 자체는 되돌리지 않는다.

## ADR-LITE-062 — 장수의 직접 행동은 온전한 명령 세트로 남긴다 (2026-09-18)

- Status: accepted (사용자 결정 2026-09-18 「직접 행동은 따로 있도록 해야지.」, GH #779). 문서 전용. 061 과 주제가 달라 별도 번호로 뒀다.
- Context: 정본 설계 §12.1 대응표가 기존 장수 명령 46개 대부분을 방침·배치·공사로 옮겨, 장수 행동이 8개쯤만 남은 것으로 읽혔다. 명령 화면을
  본 사용자가 「명령은... 8개만 남았어???」라고 지적했다. §4 의 「반복 내정 명령은 배치와 방침으로 흡수한다」가 원인 문장이다.
- Decision: 그 문장을 철회한다. 본인이 몸으로 하는 기존 명령 33개(내정 7·군사 8·인사 8·개인 3·국가 7 — 아래 Amendment 로 34개)는 **기존 이름 그대로 직접 행동으로
  남고 위임 형태도 함께 가진다.** 단련은 폐지에서 되살린다. 폐지는 휴식·내정특기초기화·전투특기초기화뿐이다. 계략 4종은 계책 카드,
  장비매매·군량매매·숙련전환은 새 자리 그대로. 원칙: 직접 행동 = 서 있는 곳에서 한 순에 한 번 크게, 경험·치적은 본인에게 / 방침·배치·공사 =
  맡기면 카드가 턴마다 굴린다 / 둘은 겹쳐 쓸 수 있다. 단계는 정치성 2 · 이동·출병 4 · 현장 7(§5.1).
- Consequences: 입력 원장의 GENERAL_ACTION 행이 크게 는다. `inputId` 는 기존 코드(`che_…`)를 못 쓰고(`HwihaInputRegistry.kt:142-143`) 표시
  이름만 잇는다. 표시 이름의 정본은 원장의 표시 필드다. 2026-09-25 ADR-LITE-066에 따라 `legacyCommands`·`retiredLegacyCommands` 역참조와 70개 대응 검사는 제거한다. 효과 크기·비용·합산
  규칙·직접 징병과 부대 카드의 관계·12순 슬롯 사용은 **미정**(정본 §16.3).
- Amendment(2026-09-18, 사용자 위임 「너가 판단해」 → 메인 세션 판정): 은퇴도 직접 행동(2단계)으로 남겨 **34개**가 된다. 기술연구·물자조달은 직접
  형태 없음. 증여·헌납·첩보는 7단계. 직접 첩보 = 서 있는 곳 옆을 몸소 살핌, 계책 카드 「첩보」 = 원격판. 12순 슬롯은 직접 행동 전용.
  2026-09-25 정정: 직접 행동 34개 표시 이름은 유지하지만, 34개 전수 역참조 검사와 70개 대응 검사는 제거한다. 이 문단의 과거 `replacesLegacy` 후속 과제는 폐기한다.
- Reversal: 시뮬레이션에서 직접 행동이 위임을 완전히 지배하거나(반복 클릭 회귀) 그 반대면, 목록은 두고 효과 크기·제약을 고친다.

## ADR-LITE-063 — 省 경계가 바뀌어도 1133 은 제자리에서 재핀한다 (2026-09-18)

- 맥락: GH #806 지리 재분할이 郡 안 縣 경계를 城의 실제 위치로 다시 잘라 省 1,594 → 1,331, 省 id 457 은퇴·인덱스 변동. 1133 README 는 「경계가 바뀌면 새 식별자를 등록하라」고 적었다. 그러나 리졸버(`HanWorldArtifactsResolver`)는 **城 id 집합**으로 릴리스를 고르므로 같은 1,133 城을 가진 두 번째 식별자는 만들 수 없다(ADR-LITE-058 과 같은 제약).
- 결정: 城 id 집합이 같은 경계 변경은 제자리 재핀한다. README 에 날짜 절(옛 → 새 sha 표)을 남기고, 1133 에 핀된 월드는 **리셋**한다 — 省 id·인덱스가 바뀌어 `province_control` 등 영속 핀은 마이그레이션할 수 없다. 강 뱃길 재핀(ADR-LITE-060)과 한 번에 묶었다(사용자 결정 2026-09-18).
- 대가: 운영 월드 리셋 1회. 846/848/1098 번들은 바이트 불변.
- 뒤집기: 城 id 집합 대신 콘텐츠 해시로 릴리스를 고르는 리졸버로 바꾸면 새 식별자가 가능해진다(별 작업).


## ADR-LITE-064 — web 폰트는 npm 동봉 woff2 로 self-host 하고 `next/font/google` 은 쓰지 않는다 (2026-09-22)

- 맥락: `web/gateway`·`web/game` 의 `app/layout.tsx` 가 `next/font/google` 로 Noto Serif KR·JetBrains Mono 를 받아왔다. 이 로더는 **빌드 시점에** `fonts.gstatic.com` 에 접속하고, 실패하면 3회 재시도 후 `An error occurred in next/font.` → `TypeError: Cannot read properties of null (reading '1')` → `Build failed because of webpack errors` 로 죽는다. 2026-09-22 하루에 3회 관측(PR #856 `web (game)`, main CI run `35692139026` `web (gateway)`, 배포 run `35692139028` `build-web (game)`). fail-fast 매트릭스라 형제 잡은 `cancelled` 로 함께 떨어진다. 즉 CI·배포 성공이 우리 코드가 아니라 Google CDN 가용성에 달려 있었다.
- 결정: 세 폰트 모두 **npm 패키지가 동봉한 woff2 + unicode-range 분할 CSS** 를 `layout.tsx` 에서 CSS import 한다 — `@fontsource-variable/noto-serif-kr`, `@fontsource-variable/jetbrains-mono/wght.css`, 그리고 기존 `pretendard`. `next/font` 의존은 web 에서 완전히 제거한다(vitest 스텁 2개와 `web/game/vitest.config.ts` 의 `next/font/google` 별칭도 삭제). 폰트 패밀리는 CSS 변수 주입(`--font-serif-next`) 대신 `web/shared/src/tokens.css` 에서 `'Noto Serif KR Variable'`·`'JetBrains Mono Variable'` 로 직접 지정한다. 출처·라이선스(3종 모두 OFL-1.1)와 고지 원문은 `web/licenses/` 에 커밋한다.
- 기각: (1) woff2 를 저장소에 넣고 `next/font/local` — `next/font/local` 의 `declarations` 는 `src` 배열 전체에 동일하게 붙어 호출 한 번으로 조각별 `unicode-range` 를 만들 수 없다(`@next/font/dist/local/loader.js` 확인). 한글은 통짜 woff2 한 장이 되어 모든 방문자가 첫 화면에서 수 MB 를 받는다. (2) woff2 124장(6.3 MB)을 커밋하고 `@font-face` CSS 를 생성 — 조각 로딩은 살지만 저장소에 6.3 MB 바이너리와 생성기 유지보수가 남는다. npm 패키지가 같은 결과를 의존성 두 줄로 준다(Pretendard 가 이미 쓰는 방식).
- 대가: 폰트 바이트가 `node_modules`(= npm 레지스트리)에 남는다. `pnpm install` 은 빌드 전에 이미 필요하고 lockfile 로 버전이 고정되니 새 의존이 아니다. 패밀리명이 `... Variable` 로 바뀌어 tokens.css 를 함께 고쳐야 했다. 가변 폰트라 700/900 고정 대신 200–900(serif)·100–800(mono) 전 구간을 쓸 수 있다.
- 검증(적색 프로브): CI·배포와 같은 `docker/web-*.Dockerfile` 의 build 스테이지를 `RUN pnpm build` 직전까지 이미지로 굽고, `docker run --network none` 으로 `pnpm build` 를 돌린다. 수정 전에는 이 프로브가 gstatic 실패로 **빨갛고**, 수정 후에는 두 앱 모두 통과해야 한다.
- 뒤집기: `layout.tsx` 의 CSS import 3줄을 `next/font/google` 호출로 되돌리고 tokens.css 의 패밀리명을 변수 주입으로 되돌리면 된다. 되돌리면 빌드가 다시 Google CDN 에 의존한다.

## ADR-LITE-065 — 휘하 컷오버: 유일한 제품 규칙으로 승격한다 (2026-09-24)

- Date: 2026-09-24
- Status: approved (2026-09-24 사용자 명시 결정). 구현·pep 전환 완료를 뜻하지 않는다.
- Context: [S3 豫州 슬라이스](../docs/superpowers/specs/2026-09-23-hwiha-s3-exit-yuzhou-slice.md)가 [PR #869](https://github.com/peppone-choi/opensamguk/pull/869)에서 통과했고 [#872](https://github.com/peppone-choi/opensamguk/issues/872)의 수치가 확정됐다. 현재 제품 기본 시나리오·프로필·일부 라우트와 API는 삼모를 가리켜 기획과 제품이 갈라져 있다. `hwiha-map-unify`가 main에 머지되기 전에는 겹치는 지도·화면 코드를 바꾸지 않는다.
- Decision:
  1. **휘하가 유일한 제품 규칙**이다. 휘하 월드/비휘하 월드 선택 UI와 분기를 두지 않는다. 시나리오 시드·API 기본 해석·웹 제품 경로의 기본값은 HWIHA다.
  2. 삼모 명령·알파 카탈로그·엔진 코드·골든·회귀·도구는 의존 감사 후 제거한다. 휘하가 쓰는 공유 동작은 중립 이름으로 이전한다. registry 밖 입력은 명시적으로 실패한다(2026-09-25 ADR-LITE-066 정정).
  3. 현재 `/game/<서버>/hwiha/*` 화면을 해당 서버의 메인 제품 경로로 승격하고 옛 주소는 새 주소로 리다이렉트한다. 메인 작전실은 휘하 작전실이다. 전체 지도 역사 시나리오는 S4([#596](https://github.com/peppone-choi/opensamguk/issues/596)) 몫이므로, 컷오버 기본 시나리오는 런북의 `scenario_990002`다.
  4. 삼모 복귀 env 스위치는 제거한다. 롤백은 컷오버 전 `IMAGE_TAG`·`WEB_GAME_TAG` 이미지와 백업 복원으로 수행한다. 해당 SHA 이미지는 레지스트리 정리 대상에서 영구 제외한다. [#891](https://github.com/peppone-choi/opensamguk/issues/891)은 [코드 정리 #917](https://github.com/peppone-choi/opensamguk/issues/917)로 대체·종결한다(2026-09-25 ADR-LITE-066 정정).
  5. [pep 전환 런북](../docs/admin/hwiha-pep-transition.md)의 C단계는 저장·통신 식별자 개명과 삼모 은퇴 뒤로 미룬다. 운영 월드 초기화·배포는 대상별 사용자 승인을 받은 후 백업 → 월드 초기화 → 휘하 시나리오 → 엔진 포함 승격 순서로 진행한다(2026-09-25 ADR-LITE-066 정정).
  6. **2026-09-25 PR 리뷰 루프 v1로 대체:** 현재 머지 절차는 메타 저장소 `docs/pr-review-loop.md`를 따른다. Claude의 머지 가능 판정, CI 필수 검사 전부 초록, 최신 main 반영 뒤 `bin/pr-loop status`가 `codex-merge`일 때 Codex가 `gh pr merge --merge`로 머지한다. 브랜치는 삭제하지 않는다. 운영 서버·운영 데이터·브랜치 보호 변경의 대상별 승인은 별개다.
- Supersedes: ADR-LITE-057 Consequences의 「라이브 서버와 기존 코드는 새 체계 활성화 전까지 그대로 운영」, ADR-LITE-049의 현행 메뉴·게이팅과 월드 프로필별 제품 분기 중 이 결정에 충돌하는 부분, [#249](https://github.com/peppone-choi/opensamguk/issues/249)의 RTK/devsam 기본 시나리오·제품 복귀 AC. 삼모 동결 기준선 의무는 2026-09-25 ADR-LITE-066으로 폐기했다.
- Consequences: 전환 중에는 문서·구현·pep 운영 시점이 다르다. 문서 정식화만으로 운영이 전환됐다고 표기하지 않는다. 지도 통일 작업이 main에 머지된 뒤 코드 PR을 순서대로 진행한다. pep은 위 승인된 PR이 모두 머지되기 전에는 바꾸지 않는다.
- Reversal (2026-09-25 정정): 컷오버 전 이미지 태그(`IMAGE_TAG`·`WEB_GAME_TAG`)와 백업을 함께 복원하고 턴 시계·데이터 일관성을 확인한다. 복귀 사유·이미지 SHA·복원 결과를 새 결정에 기록한다. env 스위치나 삼모 골든 보존에 기대지 않는다.

## ADR-LITE-066 — 제품 코드 이름 규칙과 삼모 은퇴 (2026-09-25)

- Date: 2026-09-25
- Status: approved (사용자 결정). 이 문서 자체는 코드 개명·삭제·pep 전환의 완료 증거가 아니다.
- Context: 휘하가 유일한 제품이 되었는데 `Hwiha*`와 설계 시기 버전(`v2` 등)이 코드 이름, 저장 키, 파일·라우트에 남아 있다. 삼모 엔진·골든과 명령 대응표도 제품의 기준선으로 남아 새 기능과 삭제 범위를 혼동시킨다.
- Decision — 코드 이름:
  1. 제품 클래스·함수·파일·패키지는 `Hwiha`/`hwiha` 제품 접두사 없이 역할을 나타내는 도메인 이름을 쓴다. 예: `HwihaDomesticRules` → `opensamguk.logic.domestic.DomesticRules`, `HwihaCourtHandler` → `opensamguk.engine.court.CourtHandler`.
  2. 도메인 패키지는 입력·원장(`input`), 턴(`turn`), 조정(`court`), 내정(`domestic`), 전쟁·조우·공성(`war`/`encounter`/`siege`), 행군·이동(`march`/`movement`), 보급·창고·경제(`supply`/`warehouse`/`economy`), 휘하·인물·카드(`retinue`/`person`/`card`), 명망(`prestige`), 시야(`vision`), 계책(`stratagem`), 지도(`map`), NPC(`npc`) 등 실제 책임을 기준으로 정한다. 기존 클래스와 충돌하면 제품 구현의 이름을 우선하고 퇴역할 삼모 구현을 임시로 옮긴다.
  3. 코드 식별자·패키지·파일명에서 옛 설계 시기 버전 표기(`v2`, `V2…` 등)를 없앤다. `schemaVersion`은 저장 형식 계약이라 유지한다. 저장 세계가 핀으로 가리키는 지도 번들·릴리스 ID(`han-world-v3-1447` 같은 값)는 이름을 바꾸면 핀이 깨지므로 유지한다. 판 식별 상수는 서로 다른 실제 판의 데이터·해시를 선택하거나 검증하는 경우에만 허용한다. 그 상수에 대응하는 핀 계약, 사용처와 제거 조건을 함께 기록하며 일반 동작의 버전 분기로 쓰지 않는다.
- Decision — 저장·통신: meta·설정 키, DB 객체, 시나리오 필드, 데이터 파일, API·웹 경로까지 도메인 이름으로 개명한다. 웹의 옛 `/game/<서버>/hwiha/*`와 서버 없는 `/game/hwiha/*`는 308 리다이렉트한다. 이 두 경로는 리다이렉트 전용 설정·파일에서만 옛 이름을 허용하고 제품 화면의 정본 경로에는 허용하지 않는다. 옛 Flyway 파일은 수정하지 않고 새 전진 마이그레이션을 쓴다. 운영 휘하 세계가 없으므로 옛 저장 식별자의 데이터 이전은 만들지 않는다. 세계 형식 가드는 가드 누락·옛 키·삼모 세계를 부팅과 API 양쪽에서 명시적으로 거절한다. 저장 세계의 `schemaVersion`과 지도 핀은 위의 예외 규칙을 따른다.
- Decision — 삼모: 삼모 명령·엔진·AI·골든·회귀·`tools/php-golden`·`che_` 명령 코드·롤백 스위치·알파 카탈로그·제품 경로·입력 원장의 `legacyCommands`/`retiredLegacyCommands`, 70개 대응 게이트와 직접 행동 34개 전수 역참조 검사를 제거한다. 직접 행동의 **표시 이름**(농지개간 등)은 제품 규칙으로 유지하고 입력 원장의 표시 필드를 정본으로 한다. 원장 행 수 핀, 원장↔핸들러 일치, 실패 사유 일치 등 제품 게이트는 유지한다. `references/sources/`와 과거 보고서·설계 문서는 기록으로 둔다.
- Sequence: 1층 후속 0단계(예약 거절 사유·턴 격리·원장 게이트) PR들이 머지된 직후 시작한다. 정리가 끝날 때까지 다른 기능 PR을 열지 않는다. #905가 열려 있으면 먼저 머지되게 하거나 개명 후 리베이스 대응표를 남긴다. 순서는 결정 문서 → 휘하 런타임의 삼모 의존 감사·공유 동작 이전 → 동작 변경 없는 순수 개명(common→logic→infra→engine→api→web) → 저장·통신 식별자 개명 → 삼모 전용 코드·데이터·테스트 삭제 → 남은 죽은 코드·중복·주석·에셋 정리다. 이름 대응표와 의존 감사표를 남기고 제품 테스트 수와 캠페인·결정론 결과를 비교한다.
- Operation: pep C단계 초기화·승격은 저장·통신 식별자 개명과 삼모 은퇴 완료 뒤로 미룬다. 운영 서버·운영 데이터·브랜치 보호 변경은 대상별 사용자 승인을 받는다. PR 머지는 메타 저장소 `docs/pr-review-loop.md`의 Claude 판정·CI·최신 main 확인 절차를 따른다. 롤백 수단은 **컷오버 전 `IMAGE_TAG`·`WEB_GAME_TAG` 이미지 태그 + 백업 복원** 하나이며 해당 SHA 이미지는 레지스트리 정리에서 영구 보호한다.
- Gates: 제품 소스의 정본 파일·클래스·패키지·저장 키·API/웹 경로에 `Hwiha`/`hwiha`가 남으면 CI를 실패시키고, 코드 식별자·패키지의 설계 버전 표기에도 명시한 예외만 허용한다. 허용 목록은 308 리다이렉트 전용 옛 경로, 세계 형식 가드의 옛 키·삼모 값 거절 목록과 적색 프로브 테스트, 수정하지 않는 옛 Flyway 파일의 정확한 경로·용도로 제한한다. 이 목록은 제품 실행 경로·새 저장 값으로 번지면 실패한다. 제품 소스·테스트·도구의 `SAMMO`·`che_`·`CommandRegistry`·알파 카탈로그 참조는 위 정확한 허용 목록(옛 Flyway 파일 포함) 밖에서 0건이어야 한다. `HwihaProbe`와 `che_` 명령 파일을 각각 주입하거나 세계 형식 가드를 제거하면 검사·테스트가 실제로 실패하는 적색 프로브를 남긴다. 옛 키·삼모·가드 누락 세계는 부팅과 API에서 모두 거절한다.
- Supersedes: ADR-LITE-042의 삼모 골든·도구 보존 부분, ADR-LITE-043의 삼모 픽스처 보존 부분, ADR-LITE-057의 동결 삼모 기준선·70개 대응표와 공백지 회귀 보존 부분, ADR-LITE-061의 삼모 城 강공 전용 V57 경로, ADR-LITE-062의 `che_` 역참조, ADR-LITE-065의 한 시즌 스위치·삼모 골든 보존·pep 선행 순서·기존 PR 머지 허가 절차, #891의 시즌 뒤 제거 계획. 직접 행동 표시 이름과 휘하 결정론·단일 쓰기 경로는 유지한다.
- Consequences: 옛 저장 형식은 fail-closed이며 자동 호환하지 않는다. API·웹 소비자와 외부 시나리오 사본은 새 계약에 맞춰야 한다. 해시 입력에 키 이름이 포함되면 변경 이유를 적어 골든 해시를 갱신하고 같은 입력 재실행으로 동작 불변을 검증한다. 별도의 `docs/development/rename-map.md`로 과거 요구사항·1~3층 문서의 이름을 추적한다.
