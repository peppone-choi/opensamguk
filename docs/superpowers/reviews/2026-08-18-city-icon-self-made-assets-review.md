# 도시 아이콘 자작 교체 — 자체 리뷰

Scope: tools/assets/build_city_icons.py 신규 + web/{gateway,game}/public/city/cast_1~8.png 산출물 + MapViewer/MapPreview 의 성 아이콘 src + assets/brand/README.md.
Verdict: cleared

## 1. 무엇을 했나

맵의 성 아이콘 소스를 CDN `${ICON_CDN}/cast_<lv>.gif`(= `opensamguk-images` → `devsam/image` 미러)에서
자작 픽셀아트 `/city/cast_<lv>.png` 로 바꿨다. 입력 이미지가 없다 — `tools/assets/build_city_icons.py`
의 드로잉 코드가 원본이고, 산출물 16장(앱 2 × 레벨 8)과 검수용 확대 시트
`assets/brand/city-icons/preview.png` 를 매 실행 결정적으로 재생성한다. 깃발
(`build_flag_assets.py`)과 같은 방식·같은 이유다.

## 2. 왜

`docs/superpowers/research/2026-08-17-asset-license-audit.md` 판정 — `devsam/image` 리포에는
LICENSE 파일이 없고(`cast_1.gif` md5 일치로 미러임을 확인), 도시 아이콘의 권리는 **UNKNOWN**
이었다. 20px 남짓 픽셀아트는 권리를 확인하는 것보다 다시 그리는 편이 싸다.

## 3. 레이아웃을 안 바꿨다는 근거

캔버스 크기를 레거시 자연 크기(`MapViewer.DETAIL_SIZES` 의 iconW/iconH: 16×15·20×14·14×14·
20×15·24×16·26×18·28×20·32×24)와 **동일**하게 잡았다. `.city-cast` 는 `width/height:100%` +
`image-rendering: pixelated` 로 `.city-img` 컨테이너를 채우므로, 컨테이너 크기(ICON_SCALE 적용)와
`DETAIL_SIZES`/`BASIC_SIZES` 계산에는 손대지 않았고 배율 관계도 그대로다. 상태 아이콘 크기
기준(`STATE_ICON_SCALE`, 루프 원장 67/67b)도 무변경이다.

## 4. 스스로 공격해 본 것

- **공유 도메인에서 절대경로가 새지 않나?** 이게 삭제된 `public/icons/` 가 겪던 문제다
  (`constants.ts:13` 주석). `/city/...` 도 nginx 라우팅에 따라 gateway 앱으로 갈 수 있다 —
  그래서 빌더가 **두 앱 `public/` 에 같은 파일을 쓴다**(깃발 `public/flags/` 와 동일한 처리).
  어느 앱으로 라우팅돼도 해석된다. `assetPrefix` 는 `/_next` 만 바꾸므로 `public/` 에 무관하다.
- **범위를 넘었나?** 상태 아이콘 `event*.gif`·수도별 `event51.gif`·초상·맵 타일은 **건드리지
  않았다.** 감사 문서의 UNKNOWN 판정이 그대로 남는다 — 이 브랜치는 `cast_*` 만 닫는다.
- **손편집 드리프트를 잡나?** `--check` 가 디스크에 쓰지 않고 메모리에서 재생성해 17개 산출물을
  바이트 비교한다(브랜드 빌더와 같은 계약). 불일치면 어떤 파일인지 찍고 비0 종료.
- **아이콘이 실제로 구별되나?** `preview.png`(8배 확대 시트)로 눈으로 확인했다. 1 마을(초가 3채)
  · 2 목책+망루 · 3 관문(붉은 지붕) · 4 천막 2채+토템 · 5~8 성벽+천수각의 규모 증가(6+ 곁탑,
  8 금장). 다만 **10~23px 실제 렌더 크기에서의 판독성은 라이브 화면에서 재평가가 필요하다** —
  확대 시트만으로 "작은 크기에서도 구분된다"고 주장하지 않는다.
- **회귀 핀이 있나?** `MapViewer.props.test.tsx` 에 성 아이콘 src 가 `/city/cast_8.png` 임을
  고정하는 테스트를 추가했다. CDN 으로 되돌아가면 깨진다.

## 5. 검증

- `python3 tools/assets/build_city_icons.py --check` → `17개 산출물 일치.`
- `web/game`: `pnpm test` **69 files / 390 tests passed**(추가한 아이콘 src 핀 포함), `pnpm typecheck` clean.
- `web/gateway`: `pnpm test` **19 files / 146 tests passed**, `pnpm typecheck` clean.
  주의 — gateway 첫 실행에서 1건이 실패했다. 이후 두 번 재실행에서 재현되지 않았고, 첫 출력이
  `tail -10`으로 잘려 **어느 테스트였는지는 UNKNOWN**이다. 이 변경과의 인과는 확인되지 않았다
  (변경 파일은 `MapPreview.tsx` 아이콘 src 한 줄 + 정적 png).
- 백엔드 무관(프런트 에셋 전용) — Kotlin 테스트·골든·RNG 무변경.

## 6. 남긴 것

- `event*.gif`·`event51.gif` 는 여전히 CDN·UNKNOWN 이다. 같은 방식으로 닫으려면 별도 작업이다.
- 실제 맵에서의 시각 평가(특히 lv1/lv2 가 작은 크기에서 구분되는지)는 배포 후 재평가 대상이다.
