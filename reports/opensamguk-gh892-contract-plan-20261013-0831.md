# GH892 / OPENSAM-291 registry 계약·결과 봉투 잔여 후속 기획 인계서

- taskId: opensamguk-gh892-contract-plan-20261013-0831 / stage: plan
- 조사 기준: `origin/main` `8b0f9b3ac0a0c2c93dfc1adb321fc41ef294fdf9` (2026-10-13 fetch). 준비 base `36fa4808f`는 사용하지 않음.
- 이슈 #892: OPEN 확인. 이 문서는 기획이며 제품 구현·QA 완료가 아니다.

## 1. 결론

GH892 본문 체크박스 중 남은 공통 계약 잔여는 아래 두 가지로 좁혀진다. 나머지(필드 도입, 70 대응, 알파 카탈로그 파일 제거)는 main에서 이미 반영됐다.

1. **F1 결과 봉투 정확 계약 (구현 대상, 이번 후속 1건)**: `InputResolved.reason`이 실패 *코드*가 아니라 한국어 *문구*로 채워지고, 조정·내정 경로는 `effects`를 항상 비운다.
2. **F2 단계 승격·계책 실행 (별도, 이번 후속 아님)**: 계책 13행 전부 `PLANNED`, 전체 97행 중 `PLANNED 44 / UI_READY 37 / HANDLER_READY 16`. 실제 기능·증거가 필요하므로 행동별 대표 이슈(#1453/#1456/#1457/#1544/#1480)·#1462 QA 소유로 넘기고 이 계약 후속에 섞지 않는다.

## 2. 근거 (main 8b0f9b3)

| 항목 | 실제 상태 | 판정 |
|---|---|---|
| 원장 필드 | `data/commands/input-catalog.json` schemaVersion 5, 97행. actor~helpTopicId 11개 필드 누락 0. `tutorialObjectiveId`는 `firstStepsExplanationStepId`/`firstStepsExplanationNaReason`로 대체(#1178/#1425) | 완료 |
| resultType | 97행 모두 `InputResolved`, `logic/.../input/InputRegistry.kt:153` 강제 | 완료 |
| 알파 카탈로그 | `data/commands/public-alpha-command-catalog.json`, `PublicCommandCatalogIndex*`, `CommandCatalogRowFactory*` 파일 부재(git ls-files 0). 문서 언급만 남음(`.ai/decisions.md`, `docs/development/sammo-deletion-candidates.md`, 두 spec) | 코드 제거 완료. 이슈 체크박스 미갱신 → 총괄이 근거와 함께 체크 갱신 가능(문서 정리는 선택) |
| 봉투 wire | `common/.../wire/TurnDaemonCommandResult.kt:170` `InputResolved(inputId,kind,ok,reason?,effects)` + `InputResolvedWireTest` | 타입 완료 |
| 봉투 생산자 ① | `engine/run/TurnRunService.kt:820-823` reason=`rejected?.reason`(= `TurnOutcome.Rejected.reason`, 문구), effects는 Applied에서만 전달 | **reason 결함** |
| 봉투 생산자 ② | `engine/campaign/CourtHandler.kt:388-392` `InputResolved(inputId, kind, ok, reason)` — reason=문구(예 `applied.reason.message`), effects 미전달 | **reason·effects 결함** |
| 봉투 생산자 ③ | `engine/campaign/DomesticHandler.kt:182` 동일 패턴 | **reason·effects 결함** |
| 사유 집합 | 원장 `failureReasons`는 코드 목록(예 `action.enlist`: `WRONG_RULE_PROFILE`, `UNKNOWN_INPUT`…). `TurnOutcome.Rejected(inputId, code, reason)`에 코드가 따로 있음. 봉투 reason∈failureReasons 를 검사하는 엔진 게이트 없음(HelpController만 API 측 검사) | **누락** |
| 계책 손패 | `StratagemDraw.kt`, `StratagemHandReader/Controller` 존재. 계책 실행 행은 PLANNED라 `CourtHandler.kt:90-92`에서 핸들러 미등록 → 명시 실패 경로 | F2로 분리 |

계약 원문: `docs/superpowers/specs/2026-09-17-input-registry-contract.md:89` "접수/실행 실패를 같은 사유 집합으로 검증". 현재 봉투 reason이 문구이므로 이 문장을 만족하지 못한다.

## 3. 열린 PR 대조 (중복·충돌 회피)

| PR | head | 파일 | F1과 관계 |
|---|---|---|---|
| #1603 court 증거 | `1f5ee21b1` | `DispatchInputBoundaryTest.kt`(신규), `data/commands/{input-catalog,command-work-gaps-v1,input-evidence-debt-v1}.json`, `docs/plans/HANDOFF-*`, `tools/ci/test_input_evidence_gate.py` | court.dispatch/dispatchReply 증거 승격만. **F1은 이 파일 전부 제외**. `CourtHandler.kt`는 1603이 건드리지 않음 |
| #1600 record facts | `4f20001a8` | `logic/.../record/{EventKind,EventPayloadCodec,GameEvent}.kt` + 테스트 | 겹침 없음. F1은 record 페이로드를 바꾸지 않음 |
| #1599 은퇴 정리 | `ad6ea9546` | `RetireHandler.kt`, `DatabaseHooks.kt`, `ChangeRecorder.kt`, `JdbcFlushExecutor.kt` 등 | 겹침 없음. F1은 RetireHandler 미수정 |

용량 문서 작업·초상/역사계층/registry 다른 기획과도 파일 겹침 없음(F1은 엔진 봉투 생산 3곳 + 신규 테스트 1개).

## 4. 후속 구현 F1 명세 (한 PR)

**제목안**: `fix(input): InputResolved 봉투 reason을 원장 실패 코드로 고정하고 effects 전달`

### 파일 소유 (exact)
- 수정: `app/game-engine/src/main/kotlin/opensamguk/engine/run/TurnRunService.kt` (InputResolved 생성부 ~L820)
- 수정: `app/game-engine/src/main/kotlin/opensamguk/engine/campaign/CourtHandler.kt` (`result()` ~L387-392만)
- 수정: `app/game-engine/src/main/kotlin/opensamguk/engine/campaign/DomesticHandler.kt` (~L182만)
- 신규: `app/game-engine/src/test/kotlin/opensamguk/engine/campaign/InputResolvedReasonContractTest.kt`
- 필요 시 수정: 기존 단언이 문구를 기대하는 테스트(`DomesticEngineTest.kt`, `CouncilDispatchTest.kt`)의 inputResolved 단언 줄만
- 금지: `data/commands/*.json`, `tools/ci/test_input_evidence_gate.py`, `DispatchInputBoundaryTest.kt`, `docs/plans/*`(PR1603 소유), `logic/.../record/*`(PR1600), `RetireHandler.kt`·flush 계열(PR1599), wire 타입 변경(호환 유지)

### 동작
1. 실패 봉투의 `reason`은 실패 코드(`TurnOutcome.Rejected.code` 또는 `result(code=…)`)로 채운다. 사람용 문구는 기존 `CommandLifecycleResult.reason`에 그대로 둔다(UI 표시 회귀 없음).
2. 조정·내정 성공 봉투는 실행이 아는 effects를 전달한다. 효과 목록을 모르는 경로는 `emptyList()`를 유지하되 새 효과 문자열을 지어내지 않는다.
3. 원장에 없는 inputId는 기존처럼 봉투를 만들지 않는다(현 `catalog[inputId]?.let`) — 동작 변경 금지.

### 인수 조건
- A1: 세 생산자 모두에서 `ok=false` 봉투의 `reason`이 해당 행 `failureReasons`에 포함됨을 단위 테스트로 검증. 원장에 없는 코드가 나오면 테스트 실패(적색 프로브: 코드를 문구로 되돌리면 실패하는 것 확인 후 기록).
- A2: `ok=true` 봉투는 `reason == null`.
- A3: `CommandLifecycleResult.reason`(사람용 문구)은 기존 값과 동일 — 기존 테스트 통과로 확인.
- A4: 접수 거절 코드와 실행 거절 코드가 같은 원장 집합에 속함을 최소 1개 입력(court 1, domestic 1)으로 교차 확인.
- A5: GitHub Actions 필수 CI 전체 초록. 로컬은 대상 모듈 단위 테스트만(빌드는 CI).
- 원장 사유 누락이 발견되면 원장 수정은 이 PR에서 하지 말고 목록만 PR 본문에 남겨 PR1603 병합 뒤 별도 처리.

### 의존성·순서
- 선행 없음. PR1603/1600/1599와 독립 병렬 가능. 단 원장 JSON 보강이 필요해지면 PR1603 병합 후.
- 실제 턴 readback(프론트→HTTP/JWT→DB→개인 턴 실행→새 조회에서 봉투 코드 확인)은 F1 범위 밖, #1462 QA로 NOT_RUN 표시.

## 5. 다음 담당
- 구현: 오픈삼국 구현 담당(Codex GPT-6.1 Sol, 엔진/백엔드) — F1 한 PR.
- 리뷰: 독립 리뷰 담당 1회.
- 총괄: #892 "알파 카탈로그·`PublicCommandCatalogIndex` 제거" 체크박스를 본 §2 근거로 갱신할지 결정.

## 6. 미검증 / blocker
- NOT_RUN: 로컬 빌드·테스트, CI, 실제 턴·readback QA, 운영 반영. 코드 읽기 근거만 있음.
- 미확인: 각 handler 경로의 `effects` 산출 가능 여부(실행 결과 객체별로 구현 담당이 확인).
- blocker 없음. 운영 배포·GCP·초기화 해당 없음.
