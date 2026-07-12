# 장수 생성 결과 순서·지도 marker 크기·v2 기획 리뷰

> 작성일: 2026-07-12
> 대상: `TurnRunService`, 장수 생성 화면, `MapViewer`, v2 research/spec/plan
> 상태: quarantined pending independent review
> Verdict: quarantined-with-proof

## 검토 기준

- 장수 생성 완료 신호가 DB flush보다 먼저 노출되지 않는가.
- 프론트가 1분 턴 완료를 기다리지 않고 requestId 결과를 관측하는가.
- basic/detail 지도 marker가 `legacy/devsam-core`의 실제 규칙과 일치하는가.
- v2 콘텐츠가 `docs/wiki`와 묘섭 도움말의 기존 사용자 문법을 확장하며 v1 패러티 경계를 보존하는가.

## 구현 증거

- `TurnRunService.runIntakeCommands`와 `runTick`은 `dispatchEnvelopes` 결과를 보관한 뒤 `flushExecutor.flush`와 recorder clear를 통과한 다음 `publishCommandResult`를 호출한다.
- `web/game/app/game/join/page.tsx`는 `front-info` 반복 조회 대신 `/api/command/result/{requestId}`를 250ms 간격으로 최대 20초 조회한다.
- `web/game/components/command/SelectCityField.tsx`는 레거시 `ProcessCity.vue:11`과 같은 basic map을 명시하고, 다른 호출은 detail 기본값을 유지한다.
- `legacy/devsam-core/hwe/scss/map.scss:163-185`의 `$basicMapCitySize`를 `MapViewer.BASIC_SIZES`로 옮겼다. detail 규칙은 기존 `DETAIL_SIZES`와 scale을 유지한다.
- v2 문서의 콘텐츠는 `턴 입력 → 국가 활동 → 지도 → 외교 → 전쟁 → 유저 교류` 흐름을 작전·회의·원군·도시 사건·replay로 확장한다.
- v2 전투 제안은 타일 전용 구현이 아니라 `BattleState`·topology·fixed clock·order·formation·event/replay·server authority 공통 기반 위에 전략 지도와 분리된 사각형 타일 전술 맵을 먼저 노출하는 방식이다. 같은 기반이 코삭스식 연속 좌표·fixed-tick 대형 부대 전투도 수용해야 한다.

## 실행 검증

- behavior area mapping: `app/game-engine` → `app/game-engine` targeted tests `opensamguk.engine.run.TurnRunServiceIT` and `opensamguk.engine.intake.MakeGeneralHandlerTest`.
- `git diff --check`: 통과.
- frontend targeted Vitest: 2 files, 26 tests passed.
- frontend `tsc --noEmit`: 통과.
- backend `:app:game-engine:compileKotlin` 및 targeted engine tests: `BUILD SUCCESSFUL`.
- 독립 리뷰는 별도 agent에 요청했으나 현재 대기 상태다. 그동안 테스트·컴파일·legacy source 대조 증거를 확보했으므로, 독립 리뷰가 `cleared`를 반환하기 전까지 배포 승격은 보류한다.

## 남은 리스크

- 현재 s1에는 실제 사용자 계정과 소유 장수가 없어 실계정 장수 생성 smoke는 수행하지 못한다. API·engine·frontend 테스트와 공개 read surface로 대체 검증한다.
- 기본 지도에서는 marker 아이콘 크기를 맞췄지만 aura/깃발 공통 위치는 이번 범위에서 바꾸지 않았다. 실제 브라우저 캡처에서 겹침이 확인되면 별도 루프로 분리한다.
