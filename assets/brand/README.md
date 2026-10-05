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

## 도시 · 상태 아이콘 — 지웠다(M2-9)

옛 지도(아이소 `WorldMapCanvas`)가 쓰던 자작 픽셀아트 城 아이콘(`public/city/`)과 상태 배지(`public/status/state-*` · `star-capital` ·
`imperial-residence`), 빌더(`tools/assets/build_city_icons.py` · `build_status_icons.py`)와 검수 시트(`city-icons/` · `status-icons/`)는
옛 지도를 지우며 함께 지웠다(#1346). 지도는 와룡전 키트(`public/map/waryong/`)로 그린다. 장수 이름의 황실 NPC 표지
(`public/status/imperial-npc.png` · `2x/`)는 지도 밖에서 쓰므로 남긴다. 옛 판은 git 이력에 있다.
