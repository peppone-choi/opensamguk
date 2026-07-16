# Claude Code AI 자동 업무 관리 시스템 사용자 매뉴얼

> 대상: Claude Code로 opensamguk의 기획, 구현, 검증, 리뷰, 배포 준비 업무를 지휘하는 사용자
> 기준일: 2026-07-17
> 자매 문서: [`codex-user-manual.md`](codex-user-manual.md) (Codex 표면 — 같은 Agent OS, 다른 도구 이름)

이 매뉴얼은 사용자가 코드를 한 줄씩 지시하는 대신 **목표, 경계, 완료 조건, 승인 범위**를 정해 주고, Claude Code가 이 저장소의 규칙·스킬·MCP 도구를 사용해 계획부터 검증·리뷰까지 수행하도록 지휘하는 방법을 설명한다.

교육 문서 4권(1주차: 코딩 비서에서 개발 파트너로 / 2주차: MCP 외부 도구 연동 / 3주차: DevOps·배포·모니터링 자동화 / 4주차: 아이디어에서 서비스까지)의 방법론을 이 저장소에 다음처럼 적용했다.

- 단순 반복 업무의 **위임**에서 시작해, 함께 문제를 푸는 **협업**, 목표 중심 **자동화**로 확장한다 (1주차).
- MCP와 CLI를 AI의 손발로 쓰되, "등록됨 / 인증됨 / 실호출 성공"을 구분해 확인한다 (2주차).
- 훅·테스트·독립 리뷰·CI/CD를 연결해 "작업했다"가 아니라 **"증거로 확인했다"**까지 닫는다 (3주차).
- 아이디어를 작업 계약(.ai/task.md)과 Jira 백로그로 구체화한 뒤 구현→검증→배포 준비→회고로 잇는다 (4주차).

이 문서는 일반 Claude Code 튜토리얼이 아니다. **이 저장소에 실제로 설치된 표면** — `.claude/`(commands·agents·skills·hooks), `.ai/`(작업 계약), `docs/agent/`(라우터), `scripts/agent/`(가드), `tools/agent-system/check.py`(CI 게이트), Jira `OPENSAM` — 기준으로 작성했다.

## 1. 사용자가 기억할 핵심 원칙

Claude를 "정답 자판기"가 아니라 **프로젝트 규칙을 읽고 도구를 쓸 줄 아는 유능한 팀원**으로 대한다.

사용자가 맡는 일 네 가지:

1. **무엇과 왜** — 해결할 문제와 유저 가치.
2. **경계** — 수정 가능한 범위, 금지 사항(패러티 골든·`legacy/`·`.env*`), 예산과 시간.
3. **완료 기준** — 어떤 테스트·게이트·관측이 있어야 끝인지.
4. **승인** — commit·push·merge·deploy·데이터 삭제·외부 게시 같은 경계 행동. **이 다섯 가지는 사람 승인 없이 절대 일어나지 않는다** (하드 룰, `CLAUDE.md` §Agent OS).

Claude에게 맡기는 일:

1. 작업에 필요한 문서·스킬 선택 (`docs/agent/README.md` 라우터, Progressive Disclosure)
2. 코드·문서·PHP 오라클 조사 (code-review-graph MCP → Grep 순서)
3. 실행 가능한 계획과 승인 지점 제시
4. 승인된 범위의 구현 (서브에이전트 병렬 포함)
5. 실제 검증 — gradle XML 판정, Playwright 브라우저 관측, 게이트 스크립트
6. 독립 리뷰(크로스-에이전트)와 수정 반복
7. **실행한 검증 / 미실행 검증(채점대기) / 실패**를 구분한 보고 — 확인 안 된 것은 UNKNOWN이지 추측이 아니다

## 2. 처음 프로젝트를 열었을 때

### 2.1 훅과 가드 확인

`.claude/settings.json`의 훅이 **ACTIVE**다 (ADR-LITE-005). 세션 시작 시 스냅샷되므로 훅 파일을 고치면 다음 세션부터 적용된다.

- `scripts/agent/protect-sensitive-files.sh` — 시크릿(`.env*`)·패러티 골든·`legacy/` 쓰기 차단 (PreToolUse)
- `scripts/agent/verify-changes.sh` — diff에서 최소 검증 매트릭스 안내 (PostToolUse)
- 주의: `@`-멘션 첨부는 PreToolUse를 우회한다 — `.claudeignore`가 그 구멍을 막는다.

### 2.2 시작 상태를 Claude에게 확인시키기

새 세션 첫 요청에 다음을 포함하면 좋다:

```
.ai/task.md와 .ai/decisions.md 먼저 읽고, 지금 브랜치와 git status 확인한 뒤
이번 작업이 어느 갈래(live-gap-closure vs v2 준비)인지 말해줘.
```

Claude는 규칙상 `git status --short --branch`·`.ai/` 상태를 먼저 재확인하고 시작한다 (세션 재개·컴팩션 후에도 동일).

### 2.3 스킬 상태 확인

skills.sh 프로젝트 스킬이 비어 있으면: `DISABLE_TELEMETRY=1 npx --yes skills experimental_install`. 잠금 정본은 `skills-lock.json`, 로컬 설치본 `.agents/skills/`는 git-ignore.

## 3. 좋은 업무 요청 작성법

1주차의 **'페.목.형.제'**(페르소나·목표·형식·제약)가 이 저장소 `docs/agent/prompt-pack.md` 템플릿의 뼈대다. 여기에 이 저장소는 **완료 기준(증거)**과 **승인 지점**을 추가한다. 그리고 AI의 3가지 본질 한계를 요청 설계로 상쇄한다:

- **Stateless(무상태)** → "아까 말했잖아"는 금물. 매 요청을 완결된 호출처럼 쓰거나, `.ai/task.md`·`/os-checkpoint`로 상태를 파일에 고정한다.
- **환각** → "믿지만 반드시 확인한다(Trust, but Verify)". **Grounding**: PHP 원본 경로+라인, 에러 로그 원문, 골든 fixture 등 '사실의 근거'를 요청에 직접 넣는다.
- **확률성** → 같은 요청도 결과가 흔들린다. 게이트(테스트·check.py)가 흔들림을 걸러낸다.

나쁜 요청: "로그 페이지 빨라지게 해줘."
좋은 요청 — 4요소를 채운다:

```
목표: 메인 로그 피드 폴링이 2백만 행에서도 인덱스를 타게 (무엇/왜)
경계: read 리포지토리만, 스키마 변경은 CONCURRENTLY 인덱스만, 프론트 금지 (범위)
완료: EXPLAIN ANALYZE before/after 표 + Testcontainers IT green + check.py 통과 (증거)
승인: 커밋·PR은 측정표 보고 내가 결정 (승인 지점)
```

요청이 크면 먼저 "계획만" 시키고(4.1), 애매하면 인터뷰를 요구한다("하나씩 물어봐"). 참고: `docs/agent/prompt-pack.md`에 작업군별(파리티 포팅·PHP 오라클·프론트 배선·인프라 배포·기획 티켓 분해) 프롬프트 템플릿이 있다.

> 4주차 회고: **"AI는 우리가 던지는 질문의 질을 절대 넘어설 수 없다."** — "알아서 잘해줘"가 아니라 명확한 컨텍스트·목표·경계를 가진 요청이 결과의 상한을 정한다.

## 4. 가장 권장하는 일상 업무 흐름

`/os-*` 러너는 `.claude/commands/`의 얇은 진입점으로, `docs/agent/`의 절차를 따르게 한다.

### 4.1 1단계: 계획만 받기 — `/os-start-task`

```
/os-start-task 로그 조회가 느리다. DB 레벨 증거부터 잡고 싶다.
```

산출: 작업 계약 초안(목표/범위/완료 기준/승인 지점) + 조사 결과 + 계획. **이 단계에서는 코드를 고치지 않는다.**

### 4.2 2단계: 계획 승인

계획에서 확인할 것: (a) 완료 기준이 측정 가능한가 (b) 승인 지점이 명시됐나 (c) 비범위가 패러티 불변식을 건드리지 않나. 수정 요청은 자유롭게 — 계획 반복은 싸고 구현 반복은 비싸다.

### 4.3 3단계: 구현 — `/os-implement`

승인된 계획 범위 안에서만 구현한다. 병렬이 필요하면 §10처럼 서브에이전트를 지시한다.

### 4.4 4단계: 실제 검증 — `/os-verify`

이 저장소의 검증 정본:

- **백엔드**: `tools/parity/gate.sh backend` — Java 21 gradle + **테스트 XML로 판정** (exit code 불신, `--rerun-tasks`)
- **프론트**: `web/*`에서 `pnpm typecheck` + vitest
- **브라우저 흐름**: Playwright MCP 또는 `webapp-testing` 스킬
- **불변식**: `python3 tools/agent-system/check.py --strict --base origin/main` (CI와 동일 판정)

"BUILD SUCCESSFUL 봤다"는 증거가 아니다. **어떤 XML에서 몇 건 fail=0인지**가 증거다.

### 4.5 5단계: 독립 리뷰 — `/os-review`

작성 컨텍스트와 분리된 리뷰 패스. 비trivial 작업은 **크로스-에이전트 비평**(Codex, Kimi-Claude, Gemini 등 다른 제공자)이 필수이고, 그 결과를 `docs/superpowers/reviews/YYYY-MM-DD-*.md`에 남긴다. 이 문서는 CI의 check.py가 검사한다:

- 앵커 라인 **정확히 1개씩**: `Scope: <변경 영역 프리픽스 포함>` + `Verdict: cleared|fix-required|quarantined-with-proof`
- `fix-required`가 남아 있으면 CI가 막는다. `quarantined-with-proof`는 `Proof:` 라인 필수.

### 4.6 6단계: 커밋·push·merge·배포 승인

Claude가 준비를 마치면 **사람이 승인해야** 커밋이 나간다. 커밋 규약: 한 논리 작업 = 한 커밋, 트레일러 필수. PR을 올리면 CI 게이트가 자동으로 돈다 (§8). merge도 별도 승인 — "PR 올려"와 "머지해"는 다른 승인이다. 배포는 `deployer` 에이전트 + 명시적 go-ahead (EC2 청구 이슈 등 운영 상태 먼저 확인).

## 5. 목적별 업무 명령

| 상황 | 명령 | 결과 |
|---|---|---|
| 분석만 (수정 금지) | `/os-analyze` | 조사 보고서, 코드 무변경 |
| 원인 불명 장애 | `/os-debug` | 재현→가설→증거 수렴 (systematic-debugging) |
| 세션 중단/인수인계 | `/os-checkpoint` | `.ai/current-state.md`·`handoff.md` 갱신 |
| 기획→Jira 백로그 | `/os-plan-tickets` | 에픽/작업/마이크로 3층 분해 (§11 예시) |
| 실제 화면 검증 | `/os-e2e` | Playwright 시나리오 실행 |
| 패러티 갭 1개 폐쇄 | `/parity-close` | 오라클→골든→포트→게이트→intake→FE 전체 사슬 |
| 게이트-green 일괄 출하 | `/parity-ship` | 검증된 것만 모아 PR |
| 루프 증거 관리 | `loop-engineering` 스킬 | baseline→가설 1개→재측정→채택/롤백 |

OMC(oh-my-claudecode) 계열 — `autopilot`(자율 완주), `ralph`(집요 실행), `team`(병렬 팀), `/plan`(합의 계획) — 은 전역 오케스트레이션 레이어다. **레포 규칙과 충돌하면 이 저장소의 `CLAUDE.md`가 이긴다.**

## 6. 스킬 시스템

세 층이 있다:

1. **skills.sh 프로젝트 스킬** (`skills-lock.json` 정본): `kotlin-spring-boot`, `next-best-practices`, `webapp-testing`, `supabase-postgres-best-practices` 등. `java-testing`은 참조 전용(감사 High Risk).
2. **레포 로컬 스킬** (`.claude/skills/`): `parity-close`, `parity-ship`, `loop-engineering`, `wiki-*`.
3. **superpowers**: `subagent-driven-development` (TDD red→green, 태스크당 커밋 1개) — 엄격 실행용.

라우팅 원칙: **비trivial 작업은 `docs/superpowers/WORKING_SYSTEM.md`(working system 스킬)부터.** 레거시 갭·UI 패러티·프로덕션 버그는 의무 사슬 — `opensamguk-php-oracle`(PHP 경로+라인 증거) → `webapp-testing`(재현) → `systematic-debugging`(원인 수렴) → `loop-engineering`(채택/롤백 증거). 사슬이 끊기면 `채점대기`/`blocked`로 기록하고 조용히 출하하지 않는다.

## 7. MCP와 외부 도구

| 도구 | 용도 | 사용자가 알아둘 것 |
|---|---|---|
| **code-review-graph** | 구조 그래프(호출자/피호출/영향 반경) | 코드 탐색은 Grep보다 이걸 먼저. 자동 갱신(훅) |
| **atlassian (Jira)** | OPENSAM 프로젝트 티켓 | cloudId 고정, 이슈타입: 에픽/작업/스토리/버그. §11 계층 규칙 |
| **playwright** | 브라우저 자동화·E2E | 로컬 풀스택 기동 필요. 미기동이면 결과는 "채점대기" |
| **claude-in-chrome** | 사용자의 실제 Chrome | 이 레포에서는 **`/browse` 경유가 규칙** (gstack) |
| **sentry** | 운영 에러 관측 | 토큰은 채팅에 절대 노출 금지 |
| **context7** | 라이브러리 최신 문서 | SDK/프레임워크 사용법이 애매할 때 |

2주차 방법론의 핵심 구분을 그대로 쓴다: MCP가 **목록에 있음**(등록) ≠ **인증됨** ≠ **실호출 성공**. 새 MCP를 붙이면 실호출 1건으로 스모크하고 결과를 기록하게 한다.

## 8. 자동화가 실제로 작동하는 지점

### 세션 시작
프로젝트 메모리·훅 스냅샷·`.ai/` 라우팅이 자동 주입된다.

### 변경 전/후 (로컬 훅)
민감 파일 쓰기 차단 + 변경 후 검증 매트릭스 안내 (§2.1).

### PR 게이트 (CI, `.github/workflows/`)
PR을 올리면 자동으로:

- **agent-system** — `check.py --strict`: 리뷰 문서 앵커(Scope/Verdict), 패러티 증거 매핑, 문서 드리프트, Codex 표면 무결성
- **jvm / web(game) / web(gateway)** — 빌드+테스트
- **CodeRabbit + claude-review** — 리뷰봇 2종. claude-review는 파리티-도메인 한국어 커스텀 프롬프트로 인라인 코멘트를 게시
- 주의: **claude-review 워크플로 자체를 고친 PR에서는 스모크 판정 불가** — claude-code-action이 워크플로 수정 PR에서 실행을 스킵한다(보안 가드). 다음 PR이 판정한다.

### 배포 (현재 보류 상태 주의)
`main` push → `deploy.yml` → EC2. **배포는 항상 명시 승인 + 운영 상태(청구·서버 기동) 확인 후.** 운영 교훈 2건(stale-DNS 502, frozen-turn-daemon)은 `docs/agent/lifecycle-ops.md`.

## 9. 작업 상태 파일을 읽는 법 (`.ai/`)

| 파일 | 내용 | 갱신 주체 |
|---|---|---|
| `task.md` | 현재 작업 계약 (목표/범위/비범위) | **사람이** 내용 결정, Claude는 제안만 |
| `decisions.md` | ADR-LITE 승인 결정 | 결정될 때마다 |
| `current-state.md` | 진행 상태, Open questions | Claude (`/os-checkpoint`) |
| `handoff.md` | 세션 인수인계 | Claude (`/os-checkpoint`) |
| `known-issues.md` | 알려진 문제 | 발견 시 |
| `ownership.md` | 병렬 작업 single-writer 등록부 | 병렬 시작/종료 시 |

오래된 상태 파일은 믿지 말고 `git log`·루프 LEDGER와 교차 검증을 시키면 된다.

## 10. 병렬 작업을 지시하는 법

이 저장소의 전용 서브에이전트 7종: `parity-porter`(명령 1개 포팅), `golden-capturer`(PHP 골든 캡처), `parity-gate-runner`(게이트 판정), `parity-reviewer`(적대 리뷰), `intake-wirer`(백엔드 배선), `fe-submit-wirer`(프론트 배선), `deployer`(배포).

서브에이전트 분리의 핵심 효과는 **"메인 컨텍스트가 유실되지 않는다"**(2주차) — 큰 조사·포팅을 서브에이전트에 위임하면 지휘 세션의 맥락이 오염되지 않는다. 병렬화 전제는 "작업 정의만 명확하면 여러 AI가 즉시 동시 착수"(4주차)이고, 독립 git worktree가 코드 충돌을 막는다.

병렬의 철칙 — **disjoint**: 같은 파일을 두 에이전트가 넓히면 머지 충돌이다. 공유 확장점(registry·enum·베이스 클래스)은 **먼저 단독으로**(Tier-0 foundation), 소비자들이 그 뒤 병렬로. 지시 예:

```
che_수몰과 che_화계를 각각 parity-porter로 병렬 포팅해.
둘 다 CommandRegistry를 넓혀야 하면 registry 확장만 먼저 한 커밋으로 하고 시작해.
```

## 11. 자주 쓰는 상황별 예시

### 기획에서 Jira 백로그까지 (4주차 방법론)

v2 로드맵 분해에서 실제로 쓴 3층 규칙:

- **에픽 = phase/트랙** (예: `[V2-G0]`, `[계약동결]`) — 수십 개 유지
- **작업 = wave/그룹** — 설명에 마이크로 티켓 ID 체크리스트 (`[ ] G0A-01 …`)
- **마이크로 티켓 = repo 백로그 문서** (`docs/superpowers/plans/*-ticket-backlog/`) — 착수 시점에 개별 이슈로 **just-in-time 승격**

이러면 "굉장히 작은 단위" 분해(반나절 이하·PR 1개)는 문서로 보존되고 Jira는 실행 가능한 규모로 유지된다. 중복 착수 방지: 같은 산출물을 스펙과 계획이 겹으로 가리키면 **스펙=계약 동결 / 계획=구현** 라벨로 분리.

4주차 원칙 그대로: **"모든 작업은 Jira 티켓에서 시작된다"** — 그리고 추적성 사슬을 유지한다: 왜(기획 문서) → 무엇(티켓) → 잘 만든 증거(AC·게이트) → 어떤 코드(PR 링크).

### 백엔드 패러티 수정

```
/parity-close che_모반유도가 라이브에서 무동작이다
```

의무 사슬(§6)이 자동 적용된다. 골든은 **절대 손으로 만들지 않는다** — `tools/php-golden` Docker 캡처만.

### 성능 개선 (측정 주도)

W3-2 실사례 패턴: 2백만 행 합성 시드 → EXPLAIN(ANALYZE, BUFFERS) before/after 표 → 회귀 발견 시 보조 인덱스 → 기각 후보도 증거와 함께 기록("무근거 인덱스 방지") → Testcontainers IT.

### 운영 오류 조사

```
/os-debug Sentry 이슈 OPENSAMGUK-XX 원인 추적. 수정은 계획 승인 후.
```

## 12. 결과 보고를 평가하는 법

Claude의 완료 보고에서 사용자가 체크할 것:

1. **증거 형식이 정본인가** — gradle은 XML 카운트(fail=0/err=0), 프론트는 typecheck+vitest 출력 tail, 브라우저는 스크린샷/스냅샷.
2. **미실행이 구분됐나** — "채점대기"·"blocked"·"UNKNOWN"이 명시돼 있으면 정상 작동. 없는데 전부 green이면 오히려 의심.
3. **기각·격리에 근거가 있나** — quarantine은 증명(사이드 경로 byte-match) + 백로그 기록 필수.
4. **승인 지점이 남아 있나** — commit/push/merge/deploy를 "했다"고 보고하면 그건 사전 승인이 있었을 때만 정상이다.

> 4주차 회고: AI는 때로 그럴듯한 거짓말(환각)을 한다. **"AI가 생성한 모든 코드와 결과물을 최종적으로 검증하고 책임지는 것은 결국 인간."** 이 저장소의 XML 판정·골든 게이트·독립 리뷰는 전부 이 원칙의 기계화다.

## 13. 문제 해결

| 증상 | 조치 |
|---|---|
| gradle이 exit 0인데 결과가 이상 | XML 정본 판정 요구: `**/build/test-results/test/*.xml` + `--rerun-tasks` |
| Testcontainers 실패 (macOS) | Docker 기동 확인. Docker 없으면 IT는 **skipped**가 정상, fail이 아님 |
| check.py가 리뷰 문서에서 ERROR | `Scope:`/`Verdict:` 앵커 라인이 정확히 1개씩인지, Scope에 변경 영역 프리픽스(`app/`, `infra/` 등) 포함됐는지 |
| claude-review CI가 즉시 실패 | 워크플로 수정 PR이면 스킵이 정상. 그 외엔 실행 로그의 is_error·모델·비용으로 API 쪽 문제 판별 |
| 훅을 고쳤는데 적용이 안 됨 | 훅은 세션 시작 시 스냅샷 — 새 세션에서 적용 |
| 스킬이 안 보임 | `DISABLE_TELEMETRY=1 npx --yes skills experimental_install` |
| EC2/prod 접근 실패 | 청구 상태부터 확인. prod 작업은 사용자 재개 신호까지 보류가 기본값 |

## 14. 5분 빠른 시작 체크리스트

1. [ ] 저장소 열기 → 훅 ACTIVE 확인 (`.claude/settings.json`)
2. [ ] `.ai/task.md` 읽히기 — "지금 어느 갈래야?"
3. [ ] 작업 요청 4요소(목표/경계/완료/승인) 작성
4. [ ] `/os-start-task`로 계획만 받기 → 승인
5. [ ] `/os-implement` → `/os-verify` (XML 판정) → `/os-review` (독립 리뷰)
6. [ ] 리뷰 문서 `Scope:`/`Verdict:` 확인 → 커밋 승인 → PR → CI 게이트 green → 머지 승인

## 15. 정본 문서와 참고 방법론

- 규칙 정본: `/CLAUDE.md` (충돌 시 항상 이김) · 절차 정본: `docs/superpowers/WORKING_SYSTEM.md` · 하네스 지도: `.claude/HARNESS.md`
- 라우터: `docs/agent/README.md` — 작업 유형별로 필요한 문서만 읽는다

### 교육 문서 4권 ↔ 이 저장소 매핑

| 주차 | 핵심 방법론 | 이 저장소의 구현 |
|---|---|---|
| 1주차 — 코딩 비서에서 개발 파트너로 | 위임→협업→자동화, 페.목.형.제 프롬프트, 건초더미 3전략(요약·GPS·`/clear`), CLAUDE.md 헌법+Hooks 보완 | `.ai/` 작업 계약, `prompt-pack.md`, `/os-checkpoint`+`context-strategy.md`, `/CLAUDE.md`+`scripts/agent/` 훅 |
| 2주차 — MCP로 손과 발을 | MCP=능력 설치, 등록≠인증≠실호출, 서브에이전트·worktree 병렬, "AI 에이전트 = LLM + 하네스" | atlassian·code-review-graph·playwright MCP (§7), 전용 에이전트 7종 + disjoint 병렬 (§10), `.claude/HARNESS.md` |
| 3주차 — 24시간 시스템 엔지니어 | AI 코드 리뷰어(GHA+CodeRabbit), IaC, CI/CD, Sentry 'AI SRE' | PR 게이트 5종 (§8), `deploy.yml`+EC2, check.py 불변식 게이트, Sentry MCP + `/os-debug` |
| 4주차 — 아이디어에서 서비스까지 | PRD=북극성, 모든 작업은 티켓부터, 추적성 사슬, 질문의 질·검증 책임 | v2 정본 스펙 문서, Jira OPENSAM 3층 백로그 (§11), 리뷰 문서 Scope/Verdict 앵커, §12 보고 평가법 |

핵심 두 문장으로 요약하면: **"AI는 질문의 질을 넘어설 수 없다"** (그래서 §3 요청 4요소), **"검증하고 책임지는 것은 인간"** (그래서 §4.4 XML 판정과 §4.6 승인 게이트).
