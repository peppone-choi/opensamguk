# 2026-09-26 프론트 F1 — 누르면 열리는 비활성 사유

## 범위

ADR-LITE-049 규칙 (7)의 2026-09-26 개정(모바일 같은 게임)을 공용 부품에 먼저 반영한다. 비활성 조작도 누를 수 있고, 누르면 사유가 열린다. 데스크톱은 말풍선, 모바일(768px 미만, 캔버스 v3 시스템 보드 기준)은 하단 시트다. 호버·`title` 전용 사유를 쓰지 않는다.

## 바뀐 것

- `web/shared/src/ReasonTooltip.tsx`: 누르면 열리고 다시 누르면 닫힌다. Escape·바깥 누르기·닫기 단추로 닫힌다. 마우스 호버는 미리 보기만 한다(터치 호버로는 안 열림). 감싼 조작에 `aria-describedby`로 사유를 잇는다.
- `web/shared/src/tokens.css`: 모바일 하단 시트·배경막·44px 닫기 단추, 블록 배치(`os-reason--block`).
- `Button`: 비활성은 네이티브 `disabled` 대신 `aria-disabled` + 사유. 눌러도 onClick·폼 제출이 일어나지 않는다. `title` 사유 제거.
- `Tile`: 막힌 타일(no·sealed)은 `aria-disabled`, 사유는 타일 글자로 늘 보인다. 처리 중만 네이티브 disabled.
- web/game: 작전·부 배지, 서신 보내기, 관리 화면(쓰기 막힘·장수 조치)의 `title` 전용 사유를 누르면 열리는 사유로 옮겼다. 모병 목록·작전 종류 선택지는 사유가 이미 글자로 보여 중복 `title`만 뺐다.

## 검증

- web/shared vitest 145/145, typecheck. 적색 프로브: `Button`에 네이티브 `disabled`를 되살리면 버튼 테스트가 실패한다.
- web/game vitest 758/758, typecheck, `next lint`(기존 경고만). web/gateway vitest 263/263, typecheck, `next lint`.
- 실제 `tokens.css`를 쓰는 정적 확인 페이지로 브라우저에서 375px(하단 시트·닫기 단추·배경막)와 데스크톱(말풍선)을 눈으로 확인했다.
- `next build`는 로컬 디스크 여유 때문에 돌리지 않았다. CI `web` 잡이 돌린다.

## 남은 것

- 처리 중 표시(`title="처리 중…"`)가 남은 곳: 관리 서버 상태·작전 역할 선택, 리플레이 재생 단추, 가입 제출. 다음 F1 조각에서 옮긴다.
- 사령턴 `ChiefCommandReserve.tsx`는 대체 없이 지울 화면이라 손대지 않았다.
- `title` 전용 사유를 막는 lint 규칙은 아직 없다.
