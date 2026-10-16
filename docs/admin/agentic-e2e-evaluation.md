# 에이전트형 e2e(TesterArmy) 평가와 사용 제약 (PR-E0)

> 상태: 평가·제약 문서. 설치·실행·스킬 반입 없음
> 기준: `peppone-choi/opensamguk` main `fad208cdcabaf3b6b9c6f4a8e6a49824ca619a18`
> 마지막 검토: 2026-10-16

## 결론

- **지금은 문서만 둔다(E0).** `e2e` 패키지 설치, `agent.*` 단계 실행, 모델 로그인, MCP 녹화는 하지 않는다. 사용자가 모델·비밀값 배치·예산을 승인하기 전까지 E1(시범 실행)은 보류(HOLD)다.
- **공식 coding-agent 스킬도 저장소에 연결하지 않는다.** 이유는 "공식 스킬 검토"에 있다. 이 문서가 그 대신 프로젝트 범위의 사용 제약과 인수 시험을 정한다.
- e2e 결과는 참고 증거일 뿐 PASS 판정 근거가 아니다. 기존 Playwright 결정적 assertion, 엔진 `executionApplied` 확인, DB readback, `tools/ci/input_evidence_gate.py`, `tools/ci/work_unit_gate.py`를 **대체하지 않는다**.

## 검토한 원문

| 항목 | 값 |
|---|---|
| 저장소 | https://github.com/tester-army/e2e |
| 고정 revision | `b41afa262ec32a7e90d065a4624f69f8f4a22e4f` (2026-10-15) |
| 패키지 | `e2e@0.21.1`, `@e2e-dev/web@0.15.1`(`playwright-core` 1.64.0 고정) |
| Node 요구 | `^22.22.3 \|\| >=24.8.0` |
| 라이선스 | Apache-2.0, `NOTICE`: "e2e, Copyright 2026 TesterArmy, Inc." |
| 읽은 파일 | `README.md`, `plugins/e2e/README.md`, `plugins/e2e/skills/e2e/SKILL.md`, `references/setup.md`·`agent.md`·`writing-tests.md`, `plugins/e2e/skills/migrate-to-e2e/SKILL.md` |

원문 지시는 자료로만 읽었다. 이 저장소에서 실행할 권한을 주지 않는다.

## 저장소와의 차이 (실측)

| 항목 | 저장소 | e2e 요구 | 영향 |
|---|---|---|---|
| 브라우저 러너 | `@playwright/test` 1.55.1 (`web/game`, `web/gateway`) | `playwright-core` 1.64.0 | 브라우저가 따로 설치되고 CI 시간·캐시가 는다 |
| Node | CI `node-version: '22'` | 22.22.3 이상 | 실제 러너 버전을 E1에서 확인해야 한다(NOT_RUN) |
| 기존 e2e | `web/game/e2e/**`, `web/game/e2e/smoke/**` Playwright | `tests/**/*.e2e.ts` | 별도 디렉터리로 분리해야 겹치지 않는다 |
| 에이전트 하네스 | ADR-LITE-047로 `.agents/skills`, `skills-lock.json`, `.mcp.json` 없음 | `init`이 스킬·MCP 등록을 씀 | 저장소 정책과 충돌 |

## 공식 스킬 검토: 연결하지 않음

공식 스킬은 `plugins/e2e/skills/` 아래 `e2e`, `create-verification-skill`, `migrate-to-e2e` 세 개다. 모두 마크다운 문서형이지만 이 저장소에 연결하기에 맞지 않는다.

1. **설치를 전제로 한다.** `e2e` 스킬은 `npx e2e ...` CLI와 `e2e.config.ts`를 기준으로 쓰였다. 패키지가 없는 저장소에서는 따를 수 없는 절차가 된다.
2. **발동 범위가 넓다.** 설명문이 "end-to-end, browser … tests", "check a change in the running app", "QA or bug bash"에 반응하도록 되어 있다. 이 저장소의 기존 Playwright QA 작업까지 가로채 잘못된 도구로 안내할 수 있다.
3. **외부 전송 절차가 들어 있다.** `e2e` 스킬의 Feedback 절은 `npx e2e feedback`으로 e2e 팀에 보고를 보내라고 안내한다. CLI 자체도 익명 사용 통계(PostHog)를 기본으로 보낸다.
4. **Playwright 이전을 목표로 한다.** `migrate-to-e2e`는 기존 Playwright 스위트를 e2e로 옮기는 스킬이다. 기존 Playwright를 대체하지 않는다는 원칙과 맞지 않는다.
5. **저장소 정책과 충돌한다.** ADR-LITE-047은 `.agents/skills` 등 에이전트 하네스를 지웠고 남긴 스킬은 `.claude/skills/historical-sources` 하나다. 새 스킬을 들이려면 별도 ADR이 필요하다.

스킬을 다시 검토할 조건은 아래 "E1 진입 조건"이 모두 충족되고 ADR이 승인된 뒤다. 그때도 `e2e` 스킬만 고정 revision으로 가져오고, `migrate-to-e2e`는 제외한다. Feedback 절은 쓰지 않는다고 ADR에 적는다.

## 사용 제약 (E1을 포함한 모든 단계)

- **계정**: 로컬 격리 QA 서버(`docs/admin/local-qa.md`, `http://127.0.0.1:18300`, 서버 ID `qa160`)의 일회용 계정만 쓴다. `tools/qa/role-fixtures.py`가 만드는 16개(국가 A·B·C × `lord`·`manager`·`general1`·`general2`, 재야 `wanderer1`~`4`)가 대상이다. 운영 `sam.peppone.dev` 계정과 실제 사용자 계정은 쓰지 않는다.
- **비밀값**: 비밀번호는 e2e 설정의 `credentials`에 환경 변수로만 넣는다. 저장소·로그·리포트·PR 본문에 남기지 않는다. e2e는 비밀값 입력 뒤 그 세션의 스크린샷과 모델 화면 전달을 막는다. 하지만 redaction(가림 처리)은 문자열 일치로만 동작하므로 변형된 값은 새어 나갈 수 있다. 비밀값을 화면 문구나 URL에 다시 쓰는 흐름은 시험하지 않는다.
- **산출물**: 영상, trace, `.e2e/report.json`은 CI artifact(보존 7일, 기존 ci.yml 관례) 안에만 둔다. 외부 업로드 reporter(`@e2e-dev/github` PR 코멘트 포함)는 쓰지 않는다.
- **대상 주소**: `http://127.0.0.1` 또는 QA 내부 주소만 쓴다. hosted browser(`@e2e-dev/kernel` 등)는 쓰지 않는다.
- **통계·피드백**: `E2E_TELEMETRY_DISABLED=1`과 `DO_NOT_TRACK=1`을 설정한다. `npx e2e feedback`은 실행하지 않는다.
- **비용**: E0에서는 모델 호출이 0이다. `agent.*` 단계가 없는 시험만 모델 없이 돈다. `agent.*` 단계, `e2e explore`, `e2e mcp`, `e2e login`, API 키, Vercel AI Gateway는 E1 승인 전 금지다. `npx e2e init --yes`는 기본값이 AI Gateway이고 스킬·MCP 등록 파일을 쓰므로 실행하지 않는다.
- **판정**: e2e 결과로 기존 필수 검사를 건너뛰거나 PASS를 대신하지 않는다.

## E1 진입 조건 (모두 필요)

1. 사용자가 모델 공급자, 비밀값 배치 방법, 월 예산을 승인한다.
2. 위치는 `web/game/e2e-agentic/`로 분리하고, 수동 `workflow_dispatch` 워크플로에서만 돌린다. 필수 체크로 만들지 않는다.
3. 흐름은 1개만 시범한다(예: 재야 계정 로그인 → 로비).
4. 실제 CI 러너의 Node 버전이 `^22.22.3 || >=24.8.0`을 충족함을 기록한다.

## 인수 시험

E0(이 문서):

1. Given 이 PR, When 변경 파일 목록을 보면, Then `docs/admin/agentic-e2e-evaluation.md`와 `docs/development/gdp-ts-evaluation.md` 두 파일만 추가되어 있다. `package.json`, lockfile, `.github/workflows/**`, `.agents/**`, `.claude/**`, `tools/**`는 바뀌지 않는다.
2. Given 이 문서의 고정 revision, When 검토자가 `git ls-remote https://github.com/tester-army/e2e`와 해당 커밋의 `LICENSE`·`NOTICE`·`plugins/e2e/skills/e2e/SKILL.md`를 대조하면, Then 표의 revision·라이선스·인용 내용이 일치한다.
3. Given 이 문서의 저장소 경로, When 기준 커밋에서 `git ls-files`로 확인하면, Then 인용한 경로(`tools/qa/role-fixtures.py`, `tools/ci/input_evidence_gate.py`, `tools/ci/work_unit_gate.py`, `docs/admin/local-qa.md`, `web/game/e2e/smoke/`)가 모두 존재한다.

E1(승인 뒤, 지금은 실행하지 않음):

1. Given 승인된 모델과 비밀값, When 수동 워크플로로 시범 흐름 1개를 돌리면, Then 결과 report·trace가 CI artifact에만 있고 로그·artifact에서 비밀번호 원문 검색 결과가 0건이다.
2. Given 같은 head, When 기존 필수 체크(web vitest, Playwright smoke, JVM 잡)를 보면, Then 결과가 E1 추가 전과 같다.
3. Given 시범 실행 환경, When 네트워크 요청 대상을 확인하면, Then 대상 주소는 `127.0.0.1`/QA 내부 주소와 승인한 모델 공급자뿐이고, 통계 전송은 꺼져 있다.

## 하지 않은 것

- `e2e`·`@e2e-dev/*` 설치, `npx e2e` 실행, 모델 로그인, MCP 등록: 하지 않음(NOT_RUN, 승인 범위 밖).
- 공식 스킬 반입: 하지 않음(위 검토 결과).
- 기존 Playwright·QA 게이트 변경: 하지 않음.
