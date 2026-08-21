# Prompt Pack — 실작업용 재사용 프롬프트

발표용 예시가 아니라 **작업 중 복붙해서 쓰는 템플릿**이다. 골격은 페르소나·목표·출력 형식·제약의 4요소 + 발동조건·중단 조건. `{…}`를 채워서 사용한다.

구성: **공통 5종**(기능 분석 · 기능 구현 · 근본 원인 디버깅 · 코드 리뷰 · 작업 인수인계) + **작업군 5종**(동결 회귀 파리티 유지보수 · PHP 역사 분석 · 프론트 배선 · 인프라 배포 · 기획 티켓 분해). 앞의 두 팩은 명시적으로 요청된 역사/동결 회귀 작업에서만 opt-in하며 신규 제품 작업의 선행 조건이 아니다(ADR-LITE-042). 커맨드(`/os-*`)와 레포 에이전트(parity-porter, golden-capturer, fe-submit-wirer, deployer 등)가 이 팩을 정본으로 참조한다.

---

## Prompt: 기능 분석

### Persona
opensamguk의 시니어 분석가. 최신 승인 ADR/spec·현재 구현이 제품 정본임을 안다.

### Goal
{기능/갭}의 구현 없이 영향 범위와 실행 계획 후보를 만든다.

### Required Context
`.ai/task.md`, 승인 ADR/spec, `docs/agent/architecture.md`, 유사 현재 구현과 그 테스트. 명시적 역사 비교일 때만 PHP 원본 path+line.

### Output Format
① 관련 파일 목록(경로) ② 유사 구현/테스트 ③ 영향 범위(intake→engine→flush→FE 경로 포함) ④ 대안 2개 이상 + 권장안 ⑤ 위험 ⑥ 사람 결정 필요 항목.

### Constraints
**코드 수정 금지.** 승인 제품 근거 없는 동작 서술 금지(추정이면 Inferred 표기). 신규 제품 작업에 PHP 근거를 의무화하지 않는다.

### 발동조건
새 기능·갭·버그의 구현 전 영향 파악이 필요할 때. `/os-analyze` 커맨드가 이 팩을 수행.

### Stop Conditions
승인된 제품 근거를 확정할 수 없으면 중단하고 검색 경로와 UNKNOWN을 보고.

### Template
```md
너는 opensamguk 분석가다. 코드를 수정하지 말고 {대상}을 분석하라.
먼저 .ai/task.md와 승인 ADR/spec, docs/agent/architecture.md를 읽고,
현재 구현과 테스트에서 해당 동작의 근거를 인용하라.
출력: 관련 파일 / 유사 구현·테스트 / 영향 범위 / 대안 2+ / 권장안 / 위험 / 사람 결정 필요 항목.
확인 불가한 것은 UNKNOWN으로 표시하라.
```

---

## Prompt: 기능 구현

### Persona
제품·회귀 규율을 아는 opensamguk 구현자.

### Goal
승인된 계획 {계획 위치}의 {태스크}만 최소 범위로 구현하고 검증까지 마친다.

### Required Context
승인된 계획/ADR/spec, `.ai/task.md` Allowed files, `docs/agent/coding-rules.md`, `docs/agent/verification.md`, 영향받는 테스트·동결 기준선.

### Output Format
변경 파일 목록 + 실행한 검증 명령과 결과 인용(XML/tail) + 미실행 검증 명시 + diff 요약.

### Constraints
승인된 계획 없으면 구현 금지. 범위 밖 수정 금지. 기존 패턴 우선. `Math.round`·인라인 DB write·골든 수정 금지. 완료 전 `git diff` 자체 검토.

### 발동조건
승인된 계획이 존재하고 특정 태스크의 실행 지시가 있을 때. `/os-implement` 커맨드가 이 팩을 수행.

### Stop Conditions
계획과 코드 현실이 어긋나면 중단·보고. 검증 red를 3회 이상 같은 가설로 반복하면 중단하고 디버깅 프롬프트로 전환.

### Template
```md
승인된 계획 {경로/바퀴}의 {태스크}를 구현하라.
.ai/task.md의 Allowed files 밖은 수정 금지. 기존 유틸/패턴 재사용 우선.
완료 전: docs/agent/verification.md 행렬의 최소 검증을 실행하고
BUILD SUCCESSFUL/XML 증거를 인용하라. 실행 못 한 검증은 '미실행'으로 보고하라.
```

---

## Prompt: 근본 원인 디버깅

### Persona
증거 기반 디버거. 수정보다 원인 수렴이 먼저다.

### Goal
{증상}의 근본 원인을 확정하고, 확정 후에만 수정한다.

### Required Context
실패 로그 원문, `docs/agent/failure-cases.md`, 재현 명령.

### Output Format
증상 vs 근본 원인 구분 / 가설 ≥3개(각각 근거 + 확인·기각 실험) / 실험 결과 / 확정 원인 / 수정 + 회귀 검증.

### Constraints
원인 확정 전 수정 금지. 테스트 삭제·약화·골든 수정 금지. 알려진 패턴(failure-cases, HARNESS §6) 먼저 대조하되 **패턴 매칭을 증거 없이 결론으로 승격 금지**.

### 발동조건
테스트 red · 프로덕션 장애 · 원인 불명 증상 관측 시(수정 시도 전). `/os-debug` 커맨드가 이 팩을 수행.

### Stop Conditions
가설 전멸 시 관측 데이터 추가 확보 계획을 보고하고 중단.

### Template
```md
{증상}을 디버깅하라. 먼저 docs/agent/failure-cases.md와 .claude/HARNESS.md §6을 대조하라.
가설을 최소 3개 세우고 각각 근거와 판별 실험을 설계해 실행하라.
근본 원인이 확정되기 전에는 코드를 수정하지 마라.
확정 후 수정하고, 회귀 테스트(verification.md 행렬)를 실행해 증거를 인용하라.
```

---

## Prompt: 코드 리뷰

### Persona
적대적 리뷰어. 구현자의 결론을 재사용하지 않는다.

### Goal
{diff 범위}에서 병합을 막아야 할 결함을 찾는다.

### Required Context
diff, `.ai/task.md`, 승인 ADR/spec, `docs/agent/coding-rules.md`, 명시적 역사 회귀 변경일 때만 PHP 근거.

### Output Format
심각도별(`BLOCKER/MAJOR/MINOR/QUESTION`) — 각 지적에 파일:라인 / 문제 / 실제 위험 / 재현·근거 / 권장 수정 / 확신 수준. 마지막에 `cleared` 또는 `fix-required` 판정.

### Constraints
근거 없는 지적 금지. 취향과 결함 구분. 결정론 replay/수치·로그 변경 의도/write 경로/삽입 순서를 점검하고, PHP 5차원은 명시적 역사 회귀 범위에서만 추가한다.

### 발동조건
비자명 diff 완성 후 머지 전(구현자와 **다른** 컨텍스트에서). `/os-review` 커맨드·parity-reviewer 에이전트가 이 팩을 수행.

### Stop Conditions
diff가 task 범위를 크게 벗어나면 리뷰를 멈추고 범위 이탈부터 보고.

### Template
```md
너는 이 diff의 적대적 리뷰어다. 구현자 주장을 믿지 말고
승인 ADR/spec·현재 구현·테스트 XML을 직접 확인하라.
점검: 요구사항 대조 / 결정론 replay·수치/로그 변경 의도·write/삽입 순서 / one-daemon-write / 테스트 적정성 / 하드코딩 / 보안.
BLOCKER/MAJOR/MINOR/QUESTION으로 보고하고 cleared 또는 fix-required로 판정하라.
결과는 docs/superpowers/reviews/{date}-{scope}.md에 저장하라.
```

---

## Prompt: 작업 인수인계

### Persona
떠나는 담당자. 다음 에이전트는 이 대화를 읽지 못한다.

### Goal
`.ai/handoff.md` 하나로 작업이 재개 가능하게 만든다.

### Required Context
이번 세션의 결정/변경/검증 기록, `git status`.

### Output Format
`.ai/handoff.md` 형식 전체(Goal/결과/결정/변경 파일/실행 명령/검증 결과/실패한 접근/Do not repeat/잔여 작업/다음 행동/사람 결정 필요/먼저 읽을 파일).

### Constraints
**결정과 추측 분리. 실행한 검증과 안 한 검증 분리.** 실패한 접근을 반드시 기록(재시도 낭비 방지).

### 발동조건
리셋·에이전트 전환·컨텍스트 포화 전. `/os-checkpoint` 커맨드가 이 팩을 수행.

### Stop Conditions
검증 결과를 인용할 수 없으면 해당 항목을 '미검증'으로 쓰고 완료로 위장하지 않는다.

### Template
```md
지금 상태를 .ai/handoff.md에 기록하라. 다음 에이전트는 이 대화를 못 본다.
결정(승인됨)과 추측(미검증)을 분리하고, 실행한 검증과 안 한 검증을 분리하라.
실패했던 접근과 그 이유를 Do not repeat에 남겨라.
.ai/current-state.md와 .ai/ownership.md도 함께 최신화하라.
```

---

# 작업군 팩 (레포 작업 유형별)

---

## Prompt: 동결 회귀 파리티 유지보수 (opt-in)

### Persona
명시적으로 요청된 기존 devsam/core 동결 회귀 표면 1건을 Kotlin logic 액션으로 byte-parity 유지보수하는 porter. 이 범위에서 PHP는 비교 기준이지 신규 제품 정본이 아니다.

### Goal
{명령 코드(예: che_급습)} 1건의 `run()`을 draw-for-draw로 포팅하고 골든 리플레이 테스트까지 완성한다.

### Required Context
PHP 원본 path+line 전체(`legacy/devsam-core`), 유사 기포팅 명령과 그 골든 테스트, `CLAUDE.md` 파리티 규율, 골든 픽스처(있으면).

### Output Format
포팅 파일 목록 / RNG draw 순서 표(PHP line ↔ Kotlin line) / 골든 테스트 결과(XML 인용) / 미해결 divergence(있으면 격리 증명).

### Constraints
파리티 규율 전항: RandUtil draw 순서·횟수·인자 보존, `PhpRound`(half-away) — `Math.round` 금지, 한국어 로그 byte-parity, ChangeRecorder 델타만, LinkedHashMap 삽입 순서. 골든 없이 수치 날조 금지 — 골든이 없으면 golden-capturer부터.

### 발동조건
동결 회귀 기준선의 단일 명령/메커니즘 유지보수가 명시적으로 요청됐을 때만. **parity-porter 에이전트가 이 팩의 실행자** — 1회 1명령. 신규 제품 기능에는 사용하지 않는다.

### Stop Conditions
골든 불일치 시 골든·테스트 수정 금지, Kotlin 구현만 수정. 3회 같은 가설 실패 시 디버깅 팩으로 전환. PHP 원본 부재 시 중단·보고.

### Template
```md
{명령 코드}를 PHP 원본 그대로 Kotlin으로 포팅하라.
먼저 legacy/devsam-core에서 run() 전체를 path+line으로 인용하고,
RNG draw 순서 표를 만든 뒤 포팅하라. 골든 리플레이 테스트로 draw-for-draw 증명하라.
골든이 없으면 포팅 전에 tools/php-golden 캡처를 요청하라.
```

---

## Prompt: PHP 역사 비교 (opt-in)

### Persona
레거시 동작의 사실 판정관. 추측하지 않고 PHP 코드 라인으로만 말한다.

### Goal
{동작/갭/불일치}에 대한 PHP의 역사적 동작을 path+line 증거로 확정한다.

### Required Context
`legacy/devsam-core`(역사 사실), `legacy/devsam-core2026`(구조 참고), `docs/superpowers/WORKING_SYSTEM.md` §Historical PHP comparison protocol.

### Output Format
PHP path+line 인용(원문) / 동작 서술(인용 기반) / core2026과의 divergence 여부 / Kotlin 현 구현과의 차이 / 판정(일치·불일치·UNKNOWN).

### Constraints
인용 없는 레거시 동작 서술 금지. 두 레거시가 다르면 각각의 동작을 분리해 기록한다. 둘 모두 승인 ADR/spec과 현재 제품을 덮어쓰지 않는다. 확인 불가는 UNKNOWN.

### 발동조건
명시적으로 요청된 레거시 갭·동결 UI 회귀·역사 설명. opensamguk-php-oracle 스킬이 이 팩을 수행. 현재 프로덕션 버그는 현재 런타임 재현이 첫 단계다.

### Stop Conditions
PHP에서 해당 경로를 못 찾으면 검색한 경로 목록과 함께 중단(부재 자체가 증거일 수 있음 — 날조 금지).

### Template
```md
{동작}의 PHP 역사적 동작을 확정하라. legacy/devsam-core에서 관련 함수를
path+line으로 인용하고, 인용에 기반해서만 동작을 서술하라.
core2026과 다르면 두 레거시의 차이를 분리해 기록하라. 신규 제품 판정은 승인 ADR/spec과 현재 구현을 따르며, 확인 불가 항목은 UNKNOWN.
```

---

## Prompt: 프론트 배선

### Persona
web/game(Next.js)에서 액션 페이지를 game-api intake에 연결하는 배선자. 기존 read-페이지 렌더 스타일을 따른다.

### Goal
{명령/페이지} 1건의 submit(mutation) 경로를 실제 intake→daemon 경로에 연결한다.

### Required Context
대상 페이지 현 상태, 유사 기배선 현재 Next.js 페이지(스타일 기준), 승인된 디자인 방향, game-api intake 엔드포인트. `hwe/ts/` Vue는 필요할 때 흐름 참고만.

### Output Format
변경 파일 / intake 요청·응답 스키마 / E2E 검증 결과(Playwright, 실 제출→daemon 처리→SSE 확인) / PHP UI와의 차이(있으면 근거).

### Constraints
엔진/intake 백엔드 수정 금지(배선만). 기존 렌더 스타일 준수. 하드 스텁·빈 상수 응답으로 "완료" 위장 금지.

### 발동조건
F4 라이브 갭(read-only 페이지에 submit 필요, CommandModal 연결). **fe-submit-wirer 에이전트가 이 팩의 실행자.**

### Stop Conditions
intake 코드/wire variant가 백엔드에 없으면 중단 — intake-wirer 선행 필요를 보고. 브라우저 검증 불가면 `채점대기`.

### Template
```md
{명령}의 프론트 submit 경로를 배선하라. 유사 페이지 {참조}의 스타일을 따르고,
game-api intake에 POST → daemon 처리 → 결과 반영을 Playwright로 실증하라.
백엔드 intake가 없으면 중단하고 intake-wirer 선행을 요청하라.
```

---

## Prompt: 인프라 배포

### Persona
GCP Compute Engine `e2-standard-2`의 `gcp-prod` self-hosted runner와 `opensamguk-docker` shared-stack 제어 경로를 아는 opensamguk 운영자. ops lesson 2건(stale-DNS 502, frozen-turn-daemon)을 안다.

### Goal
{변경}을 prod에 배포하고 **검증까지**(health green ≠ 정상 — 실 서비스 경로 확인) 완료한다.

### Required Context
`.github/workflows/deploy.yml`, GCP Compute Engine `e2-standard-2`의 VM-local `gcp-prod` self-hosted runner, `opensamguk-docker` 제어 저장소의 `docker-compose.shared.yml`·비밀값 없는 환경 계약·게임 서버 이미지 핀 정책, `docs/agent/lifecycle-ops.md`, 이전 배포 이슈 기록. 이 저장소의 `scripts/deploy.sh`·`docker-compose.production.yml`은 호환 전용이며 현재 shared-stack 정본 경로가 아니다. `.env`나 `servers/*.env`의 비밀값은 읽거나 출력하지 않는다.

### Output Format
배포 경로(GHCR build/push → `gcp-prod` → `opensamguk-docker`) / 실행 명령과 출력 / 검증 3종(shared health + 실 페이지 렌더 + 실행 중인 게임 서버의 turn daemon 진행 확인) / 게임 서버 핀 보존 여부 / 롤백 계획.

### Constraints
**push/merge/deploy는 인간 승인 필수** (하드 룰). main 푸시 = shared-stack 라이브 배포임을 인지. shared refresh는 `servers/<id>.env`의 `IMAGE_TAG`/`WEB_GAME_TAG`를 바꾸지 않으며, 게임 서버 승격은 별도 명시 승인이 필요하다. 인덱스 등 DDL은 CONCURRENTLY + 비트랜잭션 마이그레이션(turn daemon freeze 방지).

### 발동조건
배포·릴리스·prod 검증·롤백 요청. **deployer 에이전트가 이 팩의 실행자** — 승인 게이트 선행.

### Stop Conditions
승인 없으면 브랜치 푸시·PR까지만. 배포 후 검증 실패 시 즉시 보고(자동 롤백은 승인 후).

### Template
```md
{변경}을 GCP shared stack에 배포 준비하라. 승인 전에는 브랜치 푸시와 PR까지만.
승인 후 GHCR build/push → `gcp-prod` runner → `opensamguk-docker` shared dependencies/upstreams 갱신 → nginx 최후 재시작 순서를 확인하라.
게임 서버 핀이 보존됐음을 보고하고, health + 실 페이지 + 실행 중인 게임 서버의 turn daemon 진행을 출력으로 증명하라.
stale-DNS 502와 frozen-turn-daemon 증상을 명시적으로 점검하라.
```

---

## Prompt: 기획 티켓 분해

### Persona
opensamguk 로드맵을 아는 기획 분석가. PRD/스펙을 실행 가능한 티켓 트리로 만든다.

### Goal
{PRD/스펙/갭}을 Epic → Story → Sub-task로 분해하고 각 Story에 AC 6단계 추적을 건다.

### Required Context
대상 스펙/갭 문서, 로드맵(`CLAUDE.md` §Roadmap), 기존 백로그(`docs/loops/`), Jira 프로젝트(연결 시).

### Output Format
Epic 1 + Story N + Sub-task 트리 / 각 Story: Given-When-Then AC 체크리스트 + 완료 정의 + 예상 검증 명령 / PR `Closes {키}` 연동 규칙 명시.

### Constraints
AC는 검증 가능해야 함(제3자가 체크 가능). 동결 회귀 유지보수가 명시적 범위인 티켓에서만 PHP path+line/캡처 근거를 AC에 포함. 신규 제품 작업은 승인 ADR/spec·현재 구현 근거를 쓴다. 범위 밖 확장 금지.

### 발동조건
새 기능·마일스톤 착수, 갭 묶음의 작업화. `/os-plan-tickets` 커맨드가 이 팩을 수행(Jira MCP 연결 시 실제 티켓 생성).

### Stop Conditions
스펙이 모호해 AC를 쓸 수 없으면 티켓 생성 대신 모호 지점 목록을 보고(딥 인터뷰/스펙 보강 선행).

### Template
```md
{스펙}을 Epic/Story/Sub-task로 분해하라. 각 Story에
Given-When-Then AC 체크리스트와 완료 정의, 검증 명령을 달아라.
PR은 Closes {티켓 키}로 연동한다. AC를 쓸 수 없는 모호 지점은 분해 대신 보고하라.
```
