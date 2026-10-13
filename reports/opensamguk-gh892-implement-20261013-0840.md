# GH892 F1 독립 구현 인계

- taskId: opensamguk-gh892-implement-20261013-0840
- project: opensamguk / stage: implement
- repository: https://github.com/peppone-choi/opensamguk
- branch: buzz/opensamguk-gh892-implement-20261013-0840/opensamguk-implement
- worktree: /srv/openclaw-dev/company/agents/opensamguk-implement/worktrees/opensamguk-gh892-implement-20261013-0840
- 구현 commit: a90732130c7874aefc0cf4529315b55c294ca6f9 (최종 head는 이 문서·테스트 정리 후 PR에서 확인)
- PR: https://github.com/peppone-choi/opensamguk/pull/1606
- 다음 담당: opensamguk-review

## 산출물

기획 인계 `reports/opensamguk-gh892-contract-plan-20261013-0831.md`를 읽고 F1만 구현했다.

1. `TurnRunService.kt`: 개인 턴 거절의 reason을 Rejected.code로 전달. 성공 reason=null과 Applied.effects 유지. 원장 밖 inputId는 봉투 제외(기존 UNKNOWN kind 봉투 제거).
2. `CourtHandler.kt`·`DomesticHandler.kt`: result 함수만 변경. 외부 lifecycle.reason 문구를 보존하고 봉투에는 code를 전달. 기존 호출의 type 위치를 유지하고 뒤에 effects 선택 인자를 추가. 현재 접수·실행 반환 객체에서 추가 효과 목록이 없으므로 기존 경로는 emptyList 유지한다. 효과 이름을 제품에 새로 만들지 않았다.
3. 신규 `InputResolvedReasonContractTest.kt`: 7개 회귀. 실제 handler 거절, API admission→엔진 코드 교차(court.dispatch/policy.set), 개인 턴 result_seq=2와 wire 왕복, 성공 reason/effects, 원장 밖 입력, 적색 봉투 변이 검증. private 생산자는 reflection으로 호출하며 테스트용 공개 API는 추가하지 않았다.

원장 JSON, PR1603의 증거·게이트·문서, PR1600 record, PR1599 retire/flush, wire 타입은 수정하지 않았다. 착수 시 origin/main=8b0f9b3ac0a0c2c93dfc1adb321fc41ef294fdf9 및 OPEN #892와 열린 #1603/#1600/#1599를 재조회했다. 소스 파일 소유 충돌 없음.

## 실제 검증과 미검증

- `git diff --check`: PASS.
- 로컬 Python 원장 조회: 선택 실패의 INVALID_REQUEST·INVALID_INPUT_CHANNEL(court.dispatch/policy.set) 등록 확인. 아래 실제 누락도 확인.
- 로컬 Kotlin 테스트: NOT_RUN (java 실행 파일 없음). VPS 빌드를 시도하지 않았으며 GitHub-hosted CI를 PR 생성으로 요청했다.
- CI: PR1606의 최초 run https://github.com/peppone-choi/opensamguk/actions/runs/38371470372 착수 관측. 이 문서는 최종 head 전체 초록의 증명이 아니다. 최신 run·대상 XML의 실제 결과는 PR 본문과 최종 인계에 기록한다.
- 적색 프로브: 테스트 내 봉투 reason을 문구/미등록 코드로 변이해 assertion 실패를 확인하도록 작성. 소스 직접 변이 실행은 NOT_RUN.
- 독립 리뷰·병합·실제 UI→HTTP/JWT→DB→개인 턴→fresh readback·운영 배포·GCP·초기화: NOT_RUN. 실제 플레이 QA는 #1462 담당 범위.

## blocker / 별도 부채

A1 전 경로 원장 정합은 기존 누락으로 완결되지 않았다. 생산자가 반환하는 코드의 정확 전달과 등록된 코드 membership 검증은 분리한다. 금지된 JSON을 고치거나 코드 대신 다른 사유를 꾸며 반환하지 않았다.

- court.dispatch / placement.assign / policy.set / work.start: FORBIDDEN 누락.
- action.enlist: 즉시 채널의 INVALID_INPUT_CHANNEL 누락.

위 누락은 회귀에서 코드 전달·사람용 문구만 검증하며 membership 대상에서 제외한다. PR1603 병합 후 원장 소유 담당의 별도 후속이 필요하다. 그 밖의 전체 핸들러/원장 전수 대조는 이번 범위의 검증 결과로 주장하지 않는다. 필수 CI 전체 초록과 독립 리뷰 이전에는 병합 가능·제품 완료로 판정하지 않는다.
