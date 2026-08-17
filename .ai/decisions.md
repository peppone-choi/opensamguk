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
- Status: approved
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
