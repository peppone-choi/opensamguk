# 메인 레포 제3자 에셋 44개 해소 — 리뷰

Scope: `web/{gateway,game}/public/icons/*.gif` 28장 삭제 + `web/{gateway,game}/public/flags/*.png` 16장 자작 재생성(`tools/assets/build_flag_assets.py`), `flagTint.ts` 출처 주석 정정, 감사 문서 부록 C 추가.
Verdict: cleared

## 1. 참조 조사 결과

| 파일군 | 장수 | 참조 | 근거 |
|---|---|---|---|
| `public/icons/cast_1~8.gif` | 16 (앱 2 × 8) | **0** | `MapViewer.tsx:606`·`MapPreview.tsx:362`가 `${ICON_CDN}/cast_${level}.gif`로 CDN을 읽는다. `constants.ts:13` 주석이 "로컬 `public/icons/` 대신 CDN 단일 출처"를 명시 |
| `public/icons/event1~5.gif` | 10 (앱 2 × 5) | **0** | `MapViewer.tsx:613`·`MapPreview.tsx:369` = `${ICON_CDN}/event${state}.gif` |
| `public/icons/event51.gif` | 2 | **0** | `MapViewer.tsx:630`·`MapPreview.tsx:385` = `${ICON_CDN}/event51.gif` |
| `public/flags/flag-{cloth,pole}-0~3.png` | 16 (앱 2 × 8) | **사용 중** | `web/game/lib/flagTint.ts:64-65` · `web/gateway/lib/flagTint.ts:64-65`가 `/flags/flag-cloth-${i}.png`·`/flags/flag-pole-${i}.png`를 fetch |

레포 전체 grep(`cast_` / `event51` / `/icons/` / `/flags/` / 동적 `${...}` 조립 포함, `node_modules`·`.next` 제외)에서 로컬 `public/icons/`를 가리키는 코드·설정·Docker·nginx 참조는 나오지 않았다.

## 2. 처리 / 미처리

**처리 — 삭제 28장.** 참조 0이므로 그대로 지웠다. 코드 변경 없음.

**처리 — 재생성 16장.** 원본은 12x12 픽셀아트이고 색상 6개뿐이라(천 = 회색조 6톤, 깃대 = 탄색 12픽셀) 절차적 재생성이 권리 확인보다 싸다. `build_flag_assets.py`는 입력 이미지 없이 사인파로 천 4프레임 + 정적 깃대를 그린다. 런타임 계약은 불변이라 `flagTint.ts` 로직은 손대지 않았다(주석의 devsam 출처 표기만 정정).

**미처리 — UNKNOWN 유지.** CDN(`opensamguk-images`)의 `game/cast_*.gif`·`event*.gif`·초상 `icons/**`, `assets/battle/v2/**`는 이 브랜치 범위 밖이며 감사 문서의 UNKNOWN 판정 그대로다.

## 3. 검증

변경 영역은 `web/game`·`web/gateway` 두 프런트엔드뿐이다(백엔드·로직·골든 무변경).

- `web/game`: `corepack pnpm vitest run` — 기존 스위트 전부 green (`MapViewer.interaction.test.tsx`가 `.city-flag-img` 렌더를 덮는다).
- `web/gateway`: `corepack pnpm vitest run` — 기존 스위트 전부 green.
- 재생성 결정성: `python3 tools/assets/build_flag_assets.py && git diff --exit-code web/*/public/flags` 가 무변경이어야 한다(빌더를 고치고 산출물을 안 갱신하면 여기서 걸린다).

## 4. 확인하지 않은 것

- 재생성 깃발의 **시각적 회귀**는 픽셀 덤프로만 확인했다. 브라우저에서의 최종 인상(맵 위 9px 렌더)은 미확인 — UNKNOWN.
- `devsam/image`의 실제 권리 귀속은 여전히 미확인. 이 브랜치는 메인 레포에서 그 파일들을 제거했을 뿐 upstream 판정을 바꾸지 않는다.
