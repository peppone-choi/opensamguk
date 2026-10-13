# 기획 인계 — 발령·발령 응답 서버 증거 부채 청산 (#892 / OPENSAM-291)

- taskId: `opensamguk-next-server-plan-20261013-0712`
- project: opensamguk · stage: plan → 다음 담당 `opensamguk-implement`
- repository: https://github.com/peppone-choi/opensamguk
- 기획 branch: `buzz/opensamguk-next-server-plan-20261013-0712/opensamguk-plan`
- 기준 main: `a8511f6ebc64bbd8b1cf0f489f591f3ff40ef745` (2026-10-13 07:1x UTC `git ls-remote` 확인, #1589 병합 커밋)
- 대상 이슈: https://github.com/peppone-choi/opensamguk/issues/892 ↔ https://pepponechoi-jira.atlassian.net/browse/OPENSAM-291
- 이 문서는 기획 산출물이다. 제품 구현·리뷰·병합·실제 QA 완료가 아니다.

## 1. 선전포고(C5 `court.declareWar`) 확인 결과 — 미해결, 이번 선정 제외

현재 main 기준 사실:

- `data/commands/input-catalog.json`의 `court.declareWar` 행은 `deliveryState: PLANNED`, `evidence: {}`, `costSchema` 5자원 모두 `null`이다.
- API 접수는 `CommandReserveService`의 `inputCatalog.rejectionFor`로, 옵션은 `CourtActionOptionsService`로 `NOT_DELIVERED`를 돌려준다. 엔진 `CourtHandler`도 `NOT_DELIVERED`로 거절하며 `CourtActionHandlerTest`의 `war is rejected before an envoy or diplomacy state can be consumed`가 이 상태를 고정한다. 거짓 접수(202 후 소실)는 확인되지 않았다.
- 실행 코드(`CourtRules` 사자·ALREADY_AT_WAR 검사, `CourtActionExecutor`의 양방향 DECLARATION·`DEFAULT_DECLARE_WAR_TERM=24` 기록)는 이미 있으나 배달되지 않았다.
- HWIHA 교전 판정(`SiegeService.hostile`, `MarchReactionInterpreter`, `TravelExecutor`, `NpcDeploySelector`)은 `state == 0`(WAR)만 적대로 본다. DECLARATION→WAR 전환은 SAMMO 월 정산(`MonthlyPostUpdateHook` Q5–Q9)에만 있다.
- 현재 HWIHA 플레이어가 전쟁 상태를 만드는 입력 경로는 계책 `stratagem.provokeRivalry`(WAR, term 3)뿐이다.

이번 작업으로 고르지 않은 이유(blocker):

1. 승인된 HWIHA 정본에 선전포고 비용·차감 시점이 없다. #892 C5 완료 조건이 "미정 비용을 무료로 가정하지 않는다"를 요구한다.
2. HWIHA 순 단위에서 선포 기한(SAMMO 24개월 그대로인지)과 교전 가능 시점이 정본으로 정해지지 않았다.
3. 공유 `CourtHandler` hunk는 C0 소유 조율 대상이고, C5 완료 조건에 실제 UI 제출·새 조회가 포함된다(UI 작업 선정 금지와 충돌).

총괄에게 올릴 결정 질문(사용자/C0): ① 선전포고 비용과 차감 시점 ② HWIHA 선포 기한(순 또는 월)과 WAR 전환 시점 ③ 불가침 상태에서 바로 선포 가능한지(현재 `CourtRules`는 허용).
이 셋이 정해지면 C5를 서버 슬라이스와 UI 슬라이스로 나눠 다시 기획한다.

## 2. 선정 작업과 이유

- 작업: `court.dispatch`(발령)·`court.dispatchReply`(발령 응답) 두 행의 `DEBT_MISSING_EVIDENCE` 청산과 엔진 권한 경계 회귀를 추가한다.
- 근거:
  - `data/commands/input-evidence-debt-v1.json` count 45에 두 행이 `frozenState: HANDLER_READY`로 있다. `data/commands/command-work-gaps-v1.json`에도 `court.dispatch:DEBT_MISSING_EVIDENCE`, `court.dispatchReply:DEBT_MISSING_EVIDENCE`가 `PENDING_ISSUE_CREATION`으로 있다(#1589로 추가).
  - 두 행은 v3 baseline에서 HANDLER_READY라 DOMAIN_READY·HANDLER_READY 증거가 모두 있어야 부채가 아니다(`tools/ci/input_evidence_gate.py` `validate`).
  - 발령/응답은 군주→1차 부하, 부하의 응답이라는 핵심 권한·상호작용이며 #892 입력 원장 계약 범위다.
- 독립성: 열린 PR #1600(`logic/record/*`)·#1599(Retire/Flush/ChangeRecorder)와 파일이 겹치지 않는다. 열린 PR 중 `input-catalog.json`을 수정하는 PR은 없다(07:1x UTC 확인). 정본 결정·UI·배포·운영 데이터와 무관하다.

## 3. 범위

수정 허용 파일(이 밖으로 넓히지 않는다):

1. `data/commands/input-catalog.json` — `court.dispatch`, `court.dispatchReply` 두 행의 `evidence`만 바꾼다. `deliveryState`·기타 필드·다른 행은 그대로 둔다.
2. `data/commands/input-evidence-debt-v1.json` — 두 항목을 지우고 `count` 45→43.
3. `data/commands/command-work-gaps-v1.json` — 로컬 `python3 tools/ci/work_unit_gate.py gaps --write --head HEAD`로 재생성한다. 두 DEBT 항목만 사라지고 `court.dispatch:HANDLER_NO_UI`, `court.dispatchReply:HANDLER_NO_UI`는 남아야 한다. `--write`는 커밋된 tree를 읽으므로 1·2·4를 커밋한 뒤 실행하고 결과를 커밋한다.
4. 신규 `app/game-engine/src/test/kotlin/opensamguk/engine/campaign/DispatchInputBoundaryTest.kt` 한 파일(이름은 바꿔도 되나 한 파일).

제외:

- 제품 Kotlin/TS 소스, `CourtHandler`·`DispatchExecutor`·`DispatchRules` 수정, `ci.yml`, 웹, `court.reward`(#1589 문서상 별도 소유) 및 다른 부채 행.
- UI_READY 승격, `HANDLER_NO_UI` 해소, 도움말·튜토리얼 단계.
- `web/game/lib/input-delivery.generated.ts` 재생성은 필요 없다(`tools/web/gen_input_delivery.py`는 evidence를 읽지 않음). diff가 생기면 범위 이탈이므로 멈춘다.

## 4. 증거 형식

게이트 규칙(`_proof`): `역할:경로#앵커`. 경로 접두가 역할과 맞아야 하고, 파일 안에 앵커 문자열과 inputId 문자열이 모두 있어야 한다.

- DOMAIN_READY → `domain-rule:` + `logic/src/main/` 또는 `app/game-engine/src/main/`.
  후보: `app/game-engine/src/main/kotlin/opensamguk/engine/campaign/CourtHandler.kt` (`"court.dispatch" ->`, `"court.dispatchReply" ->` 분기가 있음). 앵커는 실제 존재하는 고유 문자열로 정하고, 접수/실행 규칙을 실제로 담는 위치여야 한다.
- HANDLER_READY → `handler-test:` + 엔진/로직/game-api 시험 경로. 4번 신규 파일을 1순위 증거로 쓴다. 필요하면 inputId 문자열이 이미 들어 있는 `app/game-engine/src/test/kotlin/opensamguk/engine/boot/CourtRecoveryIT.kt`의 기존 @Test를 추가 증거로 쓴다.
- 앵커는 @Test 함수 이름으로 하고, work-unit Kotlin QA 정적 하한에 맞도록 각 @Test 본문 안에 `"court.dispatch"` 또는 `"court.dispatchReply"` 리터럴을 직접 쓴다. 주석에만 ID를 쓰지 않는다.

## 5. 신규 회귀 시험 요구 (DispatchInputBoundaryTest)

기존 `CampaignWorldFixture`와 `CourtActionHandlerTest`/`AssignmentMarchCaptiveBoundaryTest`의 `CourtHandler.handle(ImmediateInput)` 사용 방식을 재사용한다. Docker 없는 단위 시험으로 실제 handler 경로를 통과시킨다. 상태 직접 주입으로 정상 producer를 대신하지 않는다(대기 발령은 실제 `court.dispatch`→`onIssuerTurn`으로 만든다).

최소 사례:

1. 정상: 군주 USER의 `court.dispatch` 접수 → `reservationAccepted`, 발령자 다음 턴 실행 → 대상 부하 USER의 `court.dispatchReply` 수락 → Applied, 대상의 배정 상태 변화 확인.
2. 권한: 대상 장수의 소유자가 아닌 `ownerUserId`로 보낸 `court.dispatchReply` → `FORBIDDEN`, 대기 발령·배정·충성·위치 불변.
3. 대상 아님: 발령 대상이 아닌 다른 부하가 응답 → 거절(현재 `DispatchRules` 사유 그대로 단언), 부작용 없음.
4. 중복: 같은 수락을 다시 보냄 → 두 번째는 효과 없음(기존 `DispatchPersistenceIT`의 반복 응답 의미와 일치). 거절/무효과 중 실제 구현 결과를 그대로 단언한다.
5. 발령 권한: 군주가 아닌 장수 또는 소유자 불일치 `court.dispatch` → 거절, `QueuedDispatch` 메타 없음.
6. 중복 발령: 대기 발령이 있을 때 두 번째 `court.dispatch` → `ALREADY_QUEUED`, 기존 대기 그대로.

판정 규칙:

- 현재 main에서 모두 PASS가 예상된다(제품 변경 없음). CI에서 하나라도 실제 FAIL이면 시험을 고치거나 단언을 완화하지 말고 멈춘 뒤, 실패 사례·로그를 결함 후보로 총괄에 보고한다. 이 PR 안에서 제품 수리를 하지 않는다.
- 각 사례는 실제 사유 코드와 상태 불변을 함께 단언한다(사유만 보는 시험 금지).

## 6. 적색 프로브 (가벼운 로컬 Python만)

0. 정적 게이트는 UI 증거 검사에 `tools/ci/ui_input_proof.mjs`(typescript 패키지)를 쓴다. CI와 같이 먼저 `npm --prefix tools/ci ci --ignore-scripts`를 실행한다. 기획 단계에서는 설치하지 않아 현재 main에서 로컬 실행 결과는 없다(설치 전 실행은 `Cannot find package 'typescript'`로 실패함을 확인). 변경 전 main에서 먼저 실행해 `pre-v4 evidence debt=45` 기준선을 기록한다.
1. 카탈로그 evidence만 추가하고 부채 파일을 그대로 둔 상태에서 `python3 tools/ci/input_evidence_gate.py` → `evidence debt report is stale` 실패를 확인·기록한다.
2. 증거 앵커를 존재하지 않는 문자열로 바꿔 실행 → `evidence anchor or input ID missing` 실패를 확인하고 되돌린다.
3. 부채 파일 갱신 후 같은 명령 → `pre-v4 evidence debt=43` 성공.
4. `python3 -m unittest discover -s tools/ci -p 'test_*.py'`, `python3 tools/ci/work_unit_gate.py gaps --head HEAD`(reasons 없음) 확인.

Gradle/Kotlin 시험·전체 빌드는 로컬에서 돌리지 않는다(GitHub-hosted CI).

## 7. 수용 조건

- [ ] AC-1: 두 행의 DOMAIN_READY·HANDLER_READY 증거가 실제 파일·@Test를 가리키고 정적 게이트가 debt=43으로 통과한다.
- [ ] AC-2: `command-work-gaps-v1.json`은 `--write` 재생성 결과와 같고, 두 DEBT 항목만 사라지며 HANDLER_NO_UI 두 항목은 남는다. 다른 행·`trackedBy`·`status` 변경 없음.
- [ ] AC-3: 신규 시험 6사례가 정상·권한·대상·중복을 상태 불변 단언과 함께 검증하고, CI game-engine job에서 실행 0 skip으로 PASS한다.
- [ ] AC-4: 적색 프로브 1·2의 실제 실패 출력과 3·4의 성공 출력을 PR 본문에 남긴다.
- [ ] AC-5: 변경 파일이 §3의 네 파일뿐이다. 제품 소스·웹·CI 정의 diff 0.
- [ ] AC-6: PR 본문에 #892·OPENSAM-291 링크, 관련 CI job 링크와 한 줄 결과를 남긴다. 현재 head 필수 CI 초록과 작성 분리 독립 리뷰 '머지 가능' 뒤에만 병합 담당이 병합한다.

완료 표기 경계: 병합돼도 #892 전체, HANDLER_NO_UI, 실제 USER 발령/응답 QA(#1462/OPENSAM-314)는 미완료다. 이 PR을 실제 플레이 검증으로 세지 않는다.

## 8. CI 경로

GitHub-hosted `.github/workflows/ci.yml`:

- contracts/tools: `python3 -m unittest discover -s tools/ci -p 'test_*.py'` (입력 증거 정적 게이트 시험 포함), `work_unit_gate.py check`(report 모드).
- game-engine job: 신규 시험 실행, `work_unit_gate.py executed-tests --lane jvm` 영수증. XML no-skip 검사 유지.
- 필수 6 checks(contracts/jvm/web game/web gateway/web-shared/naming-lint) 초록. web 레인은 경로 필터상 건너뛸 수 있으나 필수 check 결과로 확인한다.

## 9. 실행 순서 (구현 담당)

1. 자기 작업실에 `company_worktree`로 최신 원격 main 기준 worktree를 준비하고 base SHA를 기록한다. `input-catalog.json`을 수정하는 열린 PR이 새로 생겼는지 확인하고, 있으면 멈추고 총괄에 보고한다.
2. 신규 시험 파일 작성(§5) → 커밋.
3. 카탈로그 증거 추가 → 프로브 1·2 → 부채 파일 갱신 → 프로브 3 → 커밋.
4. `gaps --write`로 재생성 → diff가 두 DEBT 삭제뿐인지 확인 → 커밋 → 프로브 4.
5. push, PR 생성(§7 AC-4·6 본문), CI 확인. 실패하면 원인과 로그를 기록하고 범위 안에서만 수정한다.
6. 독립 리뷰 담당에게 인계한다. 직접 병합하지 않는다.

## 10. 다음 담당과 남은 blocker

- 다음 담당: `opensamguk-implement`.
- 이 작업의 blocker: 없음.
- 별도 blocker(총괄 보고): C5 선전포고 정본 결정 3건(§1). 열린 #1600 draft, #1599 병합 차단, RainLine/Gatekeeper/LOGH 게시 차단은 건드리지 않는다.
