# han-map-zoom-lod

## 요구
후한 맵(`HanMapCanvas.tsx`)에 행정 등급별 zoom LOD — 줌아웃 시 1급(郡·國·州)만,
줌인해야 2급(縣·侯國·道·邑)이 나타나게.

## 사전 확인 (구현 전 보고 대상이었던 두 항목)
1. **zoom 개념 존재 여부** — 이미 있다. `@/lib/isoMap`의 `IsoView.scale`이 줌 배율이고,
   휠/버튼 줌·클램프·MAX_SCALE=14가 다 구현돼 있다. 새로 만들 필요 없음.
2. **등급 구분 필드 존재 여부** — `HanTiles.cities[].kind`가 이미
   `AdministrativeContracts.kt:57`의 `AdministrativeLevel`과 같은 문자열
   (`PROVINCE`/`COMMANDERY`/`COUNTY`, 그리고 렌더러 전용 `EXTERNAL_PLACE`)을 담고 있다
   (`tools/map/build_han_places.py` TIER 테이블 확인). `KINGDOM`/`MARQUISATE`는 아직
   실제 데이터에 없다 — `국(國)`이 현재 `COMMANDERY`로 뭉개져 있는 것은 `jun-guo-split`
   레인이 살리는 중인 그 문제와 동일하다.
   단, `juns[]`(郡治 배열)에는 등급 필드가 아예 없다 — 郡·國 구분 없이 juns 배열
   하나로 합쳐져 있다. 그래서 1급 라벨(JUN_LABEL_ZOOM)은 등급 무관 균일 상수로 남겨뒀다.

이미 zoom·등급 필드 둘 다 있어서 범위가 렌더러 리팩터로 충분했다. 별도 승인 없이 진행.

## 구현
`web/game/components/game/HanMapCanvas.tsx`:
- 기존엔 `COUNTY_ZOOM`/`COUNTY_LABEL_ZOOM` 매직넘버 + `c.kind !== 'COUNTY'` 하드코딩
  조건문으로 2급 마커·라벨을 걸었다(1급은 애초에 郡治 마커/郡 라벨이 대표해서 항상
  숨김 — 이 부분은 그대로 유지, 회귀 없음).
- 이걸 "등급 → 최소 표시 zoom" 매핑 테이블 하나(`TIER2_MARKER_ZOOM`,
  `TIER2_LABEL_ZOOM`)로 승격하고, 조회 함수 `tierZoom(table, kind)`를 export.
  테이블에 없는 등급(1급 전부, 그리고 아직 데이터에 없는 `KINGDOM`)은 `undefined`를
  돌려주고 호출부가 "안 그림"으로 처리한다 — 郡治 마커·郡 라벨이 이미 그 자리를
  대표하고 있어서 안전한 기본값이다. `KINGDOM`이 실제로 들어와도 깨지지 않는다.
  `MARQUISATE`(侯國)는 요구사항에 있던 등급이라 테이블에 미리 추가해뒀다(현재 데이터엔
  없음, `build_han_places.py`가 侯国도 `COUNTY`로 매핑 중 — `tools/map/*`는 손대지 말라는
  지시라 그대로 둠).
- 새 등급이 늘면(州 등) 이 테이블에 한 줄만 추가하면 된다. 조건문 나열 없음.
- 성능: 매 프레임 전체 재계산이 아니라 원래 구조 그대로(스케일이 바뀔 때만 다시
  그림, `render()`가 줌/팬 콜백에서만 호출됨) — 이번 변경은 그 경로를 건드리지 않았다.

## 남은 갭 (범위 밖으로 판단해 손대지 않음)
- `cities[].kind === 'PROVINCE'`(州)인 항목은 애초에 `hubs`/`zhi`에 안 들어가서
  `c.seat`가 안 잡히고, 기존 로직상 비-seat 항목은 이 렌더러에서 아예 안 찍힌다
  (변경 전부터 그랬다). 즉 **州는 지금 지도에 전혀 안 나온다** — 이건 zoom LOD
  문제가 아니라 "州 마커를 아예 렌더링에 안 붙였다"는 별개 기능 갭이다. 이번
  티켓 범위(등급별 zoom 문턱)를 벗어나 손대지 않았다.
- `KINGDOM`/`MARQUISATE`는 `jun-guo-split` 레인이 데이터를 채우면 이 코드가 그대로
  받아 동작한다(테이블에 값 추가만 하면 됨). 지금은 값이 없어 안전한 fallback으로
  빠진다.

## 검증
- `web/game`: `npm run typecheck` — 통과(에러 없음).
- `npm run lint` — 기존 파일들의 사전 존재 경고만(HanMapCanvas.tsx 관련 경고 없음).
- `npx vitest run` — 76 test files / 433 tests 전부 통과, 그중
  `__tests__/HanMapCanvas.test.ts`에 `tierZoom` 매핑 테스트 2개 추가
  (테이블에 있는 등급 반환값 확인 + `COMMANDERY`/`KINGDOM`/`PROVINCE` 같은 미등록
  등급이 `undefined`로 안전하게 빠지는지 확인).

## 손댄 파일
- `web/game/components/game/HanMapCanvas.tsx`
- `web/game/__tests__/HanMapCanvas.test.ts`

`tools/map/*`, `data/curated/han/*`는 건드리지 않았다(jun-guo-split 레인과 충돌 방지).

## 후속 수정 (독립 리뷰 fix-required 대응)

첫 리뷰 라운드(critic-506)가 "동치성 확인 = 이미 있던 동작"이라는 사실 자체를
내가 보고서에 명시하지 않았다고 지적했다. 실측 결과, 실제 `data/map/han-tiles.json`
기준 郡治 175개의 최근접 거리 중앙값이 14칸이라 `initialView()`가 계산하는 첫 화면
배율이 거의 모든 실사용 뷰포트에서 `MAX_SCALE=14`(상한)에 붙어버렸다 — 즉 縣 문턱
로직 자체는 옳았지만 **첫 화면이 이미 그 문턱을 한참 지나서 시작**해 사용자가
차등을 체감할 수 없었다. 팀 리드가 방향 (a)(초기 배율을 낮춘다, 縣 문턱은 그대로)를
확정해 세 가지를 고쳤다:

1. **B1(a)** `initialView()`가 계산한 배율을 `MIN_TIER2_MARKER_ZOOM - 0.1`(=2.1)로
   상한을 씌운다. `scale`은 dpr 이 곱해진 백버퍼 픽셀 기준으로 렌더 전체가 이미
   일관되게 쓰고 있는 좌표계라(휠 줌 핸들러도 `* dpr`), 이 상한도 같은 좌표계에서
   걸어야 dpr 과 무관하게 항상 성립한다 — dpr 1/2 양쪽, 5개 대표 뷰포트에서 실측
   `2.100 < 2.2` 확인(아래 검증 항목).
2. **B2** 縣 라벨 루프에서 지웠던 조기 탈출 가드(`if (s >= 문턱) { ... }`)를
   `MIN_TIER2_LABEL_ZOOM` 기준으로 복원 — 그 문턱 아래서는 1144개 city 순회와
   `ctx.font`/`fillStyle` 세팅을 통째로 건너뛴다. 매직넘버 제거(구조 리팩터)와
   조기 탈출 가드(성능) 는 별개인데 하나로 묶어 지운 실수였다.
3. **M3** `MARQUISATE` 관련 주석을 "테이블에 있다"가 아니라 "현재 데이터엔 0건,
   `build_han_places.py:60`의 `'侯国': ('COUNTY', 5)`가 안 바뀌는 한(PR #507 도
   이 줄은 그대로다) 전방 호환용으로만 존재한다"로 사실과 맞춰 고쳤다.

## 검증(재확인)
- `npm run typecheck` — 통과.
- `npm run lint` — 기존 경고만, 신규 경고 없음.
- `npx vitest run` — 76 files / 433 tests 통과(1회는 `HanMapCanvas`와 무관한
  `GeneralBasicCard`류 테스트가 flake로 실패, 단독 재실행 및 풀스위트 재실행
  모두 그린으로 재현 확인).
- 초기 배율 실측(Node 스크립트로 `junSpanCells`/`scaleForSpan` 재구현, 실제
  `data/map/han-tiles.json` 사용): 800/1280/1600/1920 css 폭 × dpr 1·2 조합
  전부 최종 배율 2.100, `< 2.2` 성립.
