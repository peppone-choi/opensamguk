# 브랜드 에셋

## 워드마크 · 인장 아이콘 — 정본은 opensamguk-images

오픈삼국 워드마크(AI 생성 자체 제작물, 제3자 파생 아님)는 2026-10-01 사용자 결정 D22 로 **MIT** 가 되었고, 정본은
opensamguk-images `assets/brand/logo-master.png`(sha256 `5c1fea2d…`)다. 출처 · 가공 기록은 그 저장소 `assets/brand/WORDMARK.md` 에 있다.
이 저장소는 **export 사본만** 둔다 — 마스터 사본과 인장 빌더(`tools/assets/build_brand_assets.py`)는 2026-10-02 인장 이전과 함께 지웠다.
사본을 손으로 고치지 말고, 바꿀 때는 opensamguk-images 에서 빌더를 돌린 뒤 받는다.

| 사본 | 크기 | 만드는 곳(opensamguk-images) | 용도 |
| --- | --- | --- | --- |
| `web/{gateway,game}/public/logo-wordmark.webp` | 840×314, WebP q88(≈81 KB) | `tools/assets/build_wordmark.py` | 로그인(420×157) · 가입(360×134) 표시의 2배 |
| `web/{gateway,game}/public/logo-wordmark.png` | 840×314, 256색(≈72 KB) | 〃 | 위 WebP 의 대체본(`<picture>`) |
| `web/{gateway,game}/public/logo-wordmark-sm.png` | 172×64, 256색(≈6.5 KB) | 〃 | 공유 `Brand`(머리줄 86×32 · 64×24)의 2배 |
| `web/{gateway,game}/app/icon.png` | 241×241(낙관 네이티브 + 패딩, 무업스케일) | `tools/assets/build_seal_icons.py` | Next App Router 아이콘 |
| `web/{gateway,game}/app/apple-icon.png` | 180×180 | 〃 | iOS 홈 화면 |
| `web/{gateway,game}/app/favicon.ico` | 16 · 32 · 48 | 〃 | 브라우저 탭 |

App Router 는 `app/icon.png` · `app/apple-icon.png` · `app/favicon.ico` 를 파일 이름만 보고 `<link>` 에 싣는다. `layout.tsx` 의
`metadata.icons` 는 쓰지 않는다.

- **정사각 마크가 인장인 이유:** 워드마크 전체를 파비콘 크기로 줄이면 「오픈삼국」 네 글자와 부제가 뭉개진다(`픈` 이 `프` 로 읽힌다).
  그래서 정본 오른쪽의 붉은 三國 낙관(104×167 — 정본의 해상도 상한)만 쓴다. 48px 이상에서는 三國 이 읽히고, **16px 에서는 글자로
  읽히지 않는다**(붉은 인장 실루엣까지). 판정식 · 밀도 임계값 · 크기별 패딩의 근거는 그 빌더의 설명에 있다.
- **워드마크 소비처:** 공유 `Brand`(`web/shared/src/Brand.tsx`)가 `logo-wordmark-sm.png` 를 그린다. 로그인 · 가입의 큰 워드마크는
  `<picture>` 로 WebP 를 먼저, 256색 PNG 를 대체로 쓴다. 2026-10-01 전에는 1200×448 · 714 KB PNG 하나를 머리줄에도 그대로 써서 로그인
  전송 바이트의 38% 였다(K10 운영 측정). 두 앱이 다 쓰므로 같은 사본을 두 앱 `public/` 에 모두 둔다.
- 흰 배경 합성본(`logo-wordmark-light.png`)은 만들지 않는다 — 두 앱 모두 다크 테마라 소비할 자리가 없다.

## 도시 아이콘 (`city-icons/`)

`web/{gateway,game}/public/city/cast_{1..8}.png` 16장은 **입력 이미지 없이 코드로 그린
자작 픽셀아트**다 — 마스터 이미지가 없고, `tools/assets/build_city_icons.py`의 드로잉
코드 자체가 원본이다(깃발 `build_flag_assets.py`와 같은 방식).

```sh
python3 tools/assets/build_city_icons.py
python3 tools/assets/build_city_icons.py --check   # 손편집 드리프트 검사, 불일치면 비0 종료
```

`city-icons/preview.png`는 **산출물이지 입력이 아니다** — 16~32px 아이콘을 8배 확대해
한 장에 늘어놓은 검수용 시트다. 빌더가 매 실행 재생성하므로 손으로 고치지 마라.

교체 이유: 기존 도시 아이콘은 CDN(`opensamguk-images`)의 `game/cast_*.gif`이고 그 출처
`devsam/image` 리포에는 LICENSE가 없어 권리가 **UNKNOWN**이었다
(`docs/superpowers/research/2026-08-17-asset-license-audit.md`). 20px 남짓 픽셀아트는
권리 확인보다 다시 그리는 편이 싸다는 깃발 때와 같은 판단이다. CDN의 상태 아이콘
`event*.gif`·수도별 `event51.gif`는 **이번 범위 밖**이며 UNKNOWN 판정 그대로다.

| 레벨 | 라벨 | 모양 | 캔버스 |
| --- | --- | --- | --- |
| 1 | 수 | 초가 세 채의 마을(담장 없음) | 16×15 |
| 2 | 진 | 통나무 목책 + 망루 | 20×14 |
| 3 | 관 | 좌우 절벽에 낀 관문 | 14×14 |
| 4 | 이 | 이민족 천막 두 채 + 토템 | 20×15 |
| 5~8 | 소·중·대·특 | 같은 성 실루엣의 규모 차이(곁탑 6+, 금장 8) | 24×16 ~ 32×24 |

캔버스 크기는 레거시 자산의 자연 크기(`MapViewer.DETAIL_SIZES`의 iconW/iconH)와 같다 —
`.city-cast`가 `width/height:100%` + `image-rendering: pixelated`로 렌더하므로 크기를
그대로 두어야 기존 배율·레이아웃이 바뀌지 않는다.

**두 앱에 같은 파일을 둔다.** 참조는 절대경로 `/city/cast_<lv>.png`이고, 공유 도메인
(`sam.peppone.dev`)에서는 이 경로가 nginx 라우팅에 따라 어느 앱으로도 갈 수 있다. 양쪽
`public/`에 동일 파일을 두면 어디로 가든 해석된다(삭제된 `public/icons/`가 겪던 누수의
해법이다). `assetPrefix`는 `/_next` 에셋만 바꾸므로 `public/` 경로에는 관여하지 않는다.
