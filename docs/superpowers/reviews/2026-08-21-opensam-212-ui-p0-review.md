# OPENSAM-212 로비 식별·게임 메인 로딩 P0 리뷰

Scope: web/game/ web/gateway/ — 빈 서버 로비의 명시적 식별과 front-info 요청의 bounded loading
Verdict: cleared

## 문제와 원인

- `/lobby`는 올바른 `LobbyPage`를 렌더했지만 서버 registry가 비면 서버 선택 영역 전체를 숨겼다. 결과적으로 남는 가장 큰 제목이 `계 정 관 리`였어서 로비가 계정 화면처럼 보였다.
- 게임 메인의 `useFrontInfo`는 `api.frontInfo()` Promise가 settle해야만 `loading`을 끜다. 장수 생성 후 해당 요청이 끝나지 않으면 화면이 `서버 갱신 중입니다.`에 무기한 멈춰 있었다.

## 변경

- 빈 registry에서도 `게임 로비` H1과 `서버 선택` 영역을 항상 노출하고, loading/empty 상태를 `role="status"` + `aria-live="polite"`로 알린다.
- front-info를 10초로 제한하고 시간 초과 시 스피너 대신 재시도 가능한 오류를 보여준다.
- effect별 `AbortController`를 `api.frontInfo(signal)` → 공용 GET → `fetch` 까지 전달하고 timeout과 unmount에서 요청을 중단한다. 이전 요청의 늦은 응답은 새 retry 결과를 덮지 못한다.
- 게임 메인 오류 컨테이너에 `role="alert"`를 적용했다.

## TDD·검증 증거

- RED: 빈 registry 테스트가 `게임 로비` H1을 찾지 못했고, stalled front-info 테스트는 10초 후에도 `loading=true`를 관측했다. 추가 리뷰 회귀는 signal 미전달·cleanup 미중단·live-region 부재로 실패했다.
- GREEN: 게임 focused 13/13, 게이트웨이 focused 1/1, 두 패키지 typecheck, 두 Next 프로덕션 빌드가 통과했다.
- 실제 브라우저: 390×844에서 인증된 `/lobby`가 H1·status empty-state를 노출했다. `/game/pep`의 front-info를 보류하자 11.067초에 alert·`다시 시도`가 보였고 스피너 문구는 사라졌으며 브라우저가 `net::ERR_ABORTED`를 관측했다.
- full 게임 스위트의 `MapViewer.props.test.tsx` 24px/27px 한 건은 기준 SHA와 동일한 기존 실패이며 본 diff에 `MapViewer`·해당 테스트 변경은 없다. 병렬 부하에서 추가로 보인 battle-center/lobby-possession 한 건씩은 변경 없음을 확인했고 단독 재실행 3/3과 1/1을 통과했다.

## 독립 비판

분리된 read-only reviewer가 최초에 요청 abort 미전달과 보조기술 live-region 부재를 `fix-required`로 지적했다. 위 보완과 회귀 테스트 후 동일 reviewer가 현재 diff를 다시 검토해 추가 조치 사항 없음으로 `CLEARED`를 반환했다.

## 문서 영향

이 변경은 API payload, 아키텍처, 배포, 게임 규칙을 바꾸지 않는 국소 UI 식별·로딩 회복 계약이다. 따라서 README·AGENTS·CLAUDE 정본 수정은 필요하지 않고, 본 리뷰가 원인·행동·검증 증거를 남긴다.
