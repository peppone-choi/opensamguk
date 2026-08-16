# 브랜드 에셋 (logo-master + 파생 빌더) 적대적 리뷰

Scope: assets/brand/README.md · assets/brand/logo-master.png · tools/assets/build_brand_assets.py · web/gateway/app/{icon,apple-icon}.png · web/gateway/app/favicon.ico · web/gateway/public/logo-wordmark*.png · web/game/app/{icon,apple-icon}.png · web/game/app/favicon.ico · web/game/public/logo-wordmark*.png
Verdict: fix-required

- 대상: 브랜치 `feat-brand-assets`의 미커밋 변경 (전부 신규 추가, 기존 파일 수정 0). 기준 `origin/main` = `a95e5a90`.
- 리뷰어는 team-lead와 독립. `tools/agent-system/check.py` 무편집. 코드 수정 0건 — 결함만 보고한다.
- 환경: Python 3 / Pillow 12.2.0, Next.js 15.5.20, macOS Darwin 25.5.0.
- **리뷰 도중 작업 트리가 두 번 바뀌었다.** ① 02:22에 `is_seal_pixel`이 수정되고 산출물이 재생성됐다.
  ② 이후 전체가 `38adca98`로 커밋·푸시됐다(이 리뷰 문서 포함). §1~§6은 **커밋된 최종본** 기준이다.
- **§17이 2차 패스다** — 금박 오검출 수정 이후 team-lead가 추가로 요구한 3가지(다른 붉은 요소의
  통과 여부·512px 육안 검사·자동 체크 제안)에 대한 실측이며, §3과 §7을 갱신한다.

---

## 0. 통과한 항목 (증거만 기록)

판정 근거로만 남긴다.

| 공격 지점 | 결과 | 증거 |
| --- | --- | --- |
| ① 재현성 (지우고 재실행) | 10개 산출물 **바이트 동일** | 아래 §0.1 |
| ② 낙관 미탐지 시 죽는가 | **죽는다** (`SystemExit`) — mutation 실증 | §0.2 M1/M2 |
| ③ Next App Router 자동 배선 | **자동 배선 맞다**. `layout.tsx` 무수정이 옳다 | §0.3 |
| ④ ICO 유효성 | **유효한 3-엔트리 ICO** (16/32/48, PNG 인코딩) | §0.4 |
| ⑤ 리포 용량 | 1.9MB. 기존 추적 `assets/battle` 52MB 대비 정합. 마스터 커밋에 이견 없음 | §0.5 |

### 0.1 재현성

```
$ rm -f web/{gateway,game}/app/{icon.png,apple-icon.png,favicon.ico} \
        web/{gateway,game}/public/logo-wordmark{,-light}.png
$ python3 tools/assets/build_brand_assets.py
brand assets rebuilt for: gateway, game
$ diff <before sha256 x10> <after sha256 x10>
REPRODUCIBLE: byte-identical
```

`is_seal_pixel` 수정 **전 버전**과 **후 버전** 각각에 대해 1회씩 수행했고 둘 다 바이트 동일이었다.
빌더는 결정적이다. (Pillow 12.2.0 한정 — 다른 Pillow 버전에서의 동일성은 **UNKNOWN**.)

### 0.2 fail-loud mutation

```python
# M1: 마스터의 모든 낙관-붉은 픽셀을 (90,90,90)으로 치환
B.seal_bounds(flat)
→ SystemExit: 붉은 낙관을 찾지 못했다 — 마스터가 바뀌었으면 임계값을 다시 잡아라

# M2: 마스터 좌우 반전(낙관이 왼쪽으로 이동)
B.seal_bounds(m.transpose(FLIP_LEFT_RIGHT))
→ SystemExit: 붉은 낙관을 찾지 못했다 — ...
```

조용히 빈 아이콘을 내지 않는다. `build_brand_assets.py:63`의 주장은 참이다.

### 0.3 Next App Router 자동 배선

Next 공식 문서 `docs/01-app/03-api-reference/03-file-conventions/01-metadata/app-icons.mdx`:

> To set an app icon using an image file, place a `favicon`, `icon`, or `apple-icon` image file
> within your `/app` directory. Next.js automatically evaluates the file and adds the appropriate
> `<link>` tags to your app's `<head>` element. The `favicon` image is restricted to the top level of `app/`.

생성되는 태그: `<link rel="icon" href="/favicon.ico" sizes="any" />`,
`<link rel="icon" href="/icon?<generated>" ... />`, `<link rel="apple-touch-icon" href="/apple-icon?<generated>" ... />`.

두 앱 모두 `web/{gateway,game}/app/` = 루트 app 세그먼트이므로 `favicon.ico` 제약도 만족한다.
실제 빌드에서도 라우트 핸들러가 생성됐다:

```
$ pnpm exec next build   # web/gateway
$ ls web/gateway/.next/server/app/icon.png/ web/gateway/.next/server/app/favicon.ico/
route.js  route.js.map  route.js.nft.json      # 양쪽 모두
$ grep -o "image/png"  .next/server/app/icon.png/route.js      → image/png
$ grep -o "image/x-icon" .next/server/app/favicon.ico/route.js → image/x-icon
```

렌더된 HTML의 `<link>` 문자열 자체는 확보하지 못했다 — 이 빌드는 **이번 변경과 무관한 기존 결함**으로
실패한다(`components/board/BoardRichTextEditor.tsx:4` → `Module not found: Can't resolve '@tiptap/starter-kit'`).
따라서 "브라우저에 실제로 실린 `<link>` 태그"는 **UNKNOWN**이고, 근거는 공식 문서 + 라우트 핸들러 컴파일까지다.

### 0.4 ICO 파싱

`web/gateway/app/favicon.ico`를 ICONDIR/ICONDIRENTRY로 직접 파싱:

```
reserved 0  type 1  count 3
  entry0: 16x16 bpp=32 bytes=376  sig=b'\x89PNG'  → PNG decodes (16,16) RGB
  entry1: 32x32 bpp=32 bytes=942  sig=b'\x89PNG'  → PNG decodes (32,32) RGB
  entry2: 48x48 bpp=32 bytes=1722 sig=b'\x89PNG'  → PNG decodes (48,48) RGB
```

Pillow `sizes=[(16,16),(32,32),(48,48)]`는 의도대로 3개 엔트리를 만들었다. `Image.open(...).ico.sizes()`
= `{(16,16),(32,32),(48,48)}`. 엔트리가 PNG 압축(BMP/DIB 아님)이라 IE ≤ 10에서는 읽히지 않지만,
지원 대상이 아니므로 결함으로 세지 않는다.

### 0.5 용량·커밋 대상

`assets/brand` 1.9MB vs 이미 추적 중인 `assets/battle` 52MB(`git ls-files assets/battle` 279개).
마스터를 커밋하는 선택은 이 리포의 기존 패턴과 일관된다. `.gitignore`에 에셋/PNG 규칙은 없다.
파생을 gitignore 하자는 대안은 오히려 이 리포 패턴(battle/v2는 원본·런타임 산출물을 **둘 다** 추적)과
어긋난다. **이 항목은 결함 아님.**

---

## 1. F1 (high) — README가 존재하지 않는 루트 `LICENSE`를 IP 근거로 인용한다

`assets/brand/README.md:16-18`:

> 브랜드 에셋은 리포 자체 저작물이므로 루트 `LICENSE`(MIT)의 적용 대상이다.

루트에 `LICENSE` 파일이 없다.

```
$ ls LICENSE*
(eval):1: no matches found: LICENSE*
$ git ls-files | grep -i license
app/gateway-api/src/test/resources/profile-icons/invalid-license-manifest.json
docs/loops/opensam-37-evidence-contracts-2026-08-16/chgis-license-review.md
```

`CLAUDE.md`가 IP 경계를 load-bearing 규칙으로 두는 리포에서, 라이선스 귀속 문장이 없는 문서를
가리킨다. 로고가 자체 제작이라는 사실 자체는 다투지 않지만 **문서가 근거로 든 대상이 부재**하다.
루트 `LICENSE`를 추가하든지, 문장을 사실에 맞게 고쳐라.

## 2. F2 (high) — README가 낙관 탐지를 "좌표 하드코딩 아님"이라 서술하지만 좌표 창이 load-bearing이다

`assets/brand/README.md:45-47`:

> 빌더는 낙관을 좌표 하드코딩이 아니라 **붉은 픽셀 밀도**로 찾는다(우측 30% 안에서 행/열당 30픽셀 이상).
> 마스터를 교체해 낙관 위치가 바뀌어도 따라간다.

`tools/assets/build_brand_assets.py:51`이 좌표를 하드코딩한다:

```python
search_from = int(width * 0.7)
```

이 창이 결과를 결정한다. 현재 `is_seal_pixel`(수정본)로도 마스터 전체에 낙관-붉은 판정을 통과하는
픽셀이 **좌측 70% 영역 안에만 26,675개** 있다.

```
seal-red left of 0.7 cut: 26675
full-width density box (창 제거, DENSITY_FLOOR=30 그대로): (810, 93, 1751, 552)
실제 seal_bounds (창 적용):                                (1648, 386, 1751, 552)
```

창을 빼면 밀도 필터만으로는 낙관이 아니라 워드마크 절반을 감싸는 상자가 나온다. 즉 좌표가 판정의
주된 근거이고 밀도는 보조다. 두 번째 문장("낙관 위치가 바뀌어도 따라간다")은 §0.2 M2가 반증한다 —
낙관을 왼쪽으로 옮기면 따라가지 않고 죽는다. 죽는 쪽이 안전하므로 코드는 문제없다. **문서가 틀렸다.**

## 3. F3 (medium) — `DENSITY_FLOOR`가 해상도로 정규화되지 않아, 같은 그림도 축소 재출력이면 빌더가 죽는다

`tools/assets/build_brand_assets.py:35`의 `DENSITY_FLOOR = 30`은 절대 픽셀 개수다. 마스터를
동일 아트로 다시 내보내되 크기만 줄이면 행/열당 붉은 픽셀 수가 같이 줄어 임계를 못 넘긴다.

```
원본 1927x720   → bounds (1648,386,1751,552)  상대 [0.8552,0.5361,0.9087,0.7667]
50% 963x360     → bounds (824,193,874,275)    상대 [0.8552,0.5361,0.9071,0.7639]   OK
25% 481x180     → SystemExit: 붉은 낙관을 찾지 못했다
```

대략 폭 700px 아래에서 무너진다. 재현: 위 3줄을 `B.seal_bounds(m.resize((w//4, h//4), LANCZOS))`로
그대로 실행. 죽기는 하므로 사일런트 결함은 아니지만, README:22의 "마스터를 교체했으면 빌더만 다시
돌리면 된다"는 운영 약속이 성립하지 않는다. 고치려면 floor를 `max(8, round(height * 30/720))`처럼
높이 비례로 두거나, 최대 행/열 카운트의 비율(예: `max(cols.values()) * 0.2`)로 잡아라.

**§17.2에서 같은 상수의 더 날카로운 형태를 찾았다 — 유효 구간이 19~32뿐이고 현재값 30은 상한에서
2 떨어져 있다. 그쪽이 상위 심각도다.**

## 4. F4 (medium) — 512 아이콘이 104×167 크롭을 1.92배 업스케일한 가짜 해상도다

`seal_bounds` 결과 크롭은 **104×167**이다. `build_seal_tile:75-76`에서
`pad = round(max(104,167)*0.3) = 50`, `side = 167 + 100 = 267`. 그리고 `:90`에서
`tile.resize((512,512), LANCZOS)` — 267 → 512, **1.92배 업스케일**.

즉 `web/*/app/icon.png`(512, 50KB)에는 267px 이상의 실제 디테일이 없다. `apple-icon.png`(180)은
다운스케일이라 영향 없다. 마스터의 낙관 원본 해상도가 512 아이콘을 감당하지 못한다.
로고 마스터를 더 큰 원본에서 다시 뜨거나, 아이콘 목표 크기를 256으로 낮춰라.

## 5. F5 (medium) — 16px ICO 엔트리는 판독 불가다. 인장을 고른 근거가 정작 파비콘 크기에서 무너진다

`favicon.ico`의 실제 16×16 엔트리(offset 54, 376바이트)를 추출해 확대하면 三國이 전혀 읽히지 않고
어두운 붉은 덩어리에 검은 홈 두 개가 남는다. 브라우저 탭이 실제로 쓰는 크기다.

재현:
```python
d=open('web/gateway/app/favicon.ico','rb').read()
# ICONDIRENTRY[0] → offset 54, size 376
Image.open(io.BytesIO(d[54:54+376])).resize((256,256), Image.NEAREST).save('ico16.png')
```

`assets/brand/README.md:41-43`은 "워드마크를 파비콘 크기로 줄이면 뭉개지므로 인장을 쓴다"고
정당화하지만, 인장도 16px에서 똑같이 뭉개진다. 1px 외곽선 + 밀집한 國 획이 16px를 못 견딘다.
성립하는 주장은 "48~64px에서는 인장이 낫다"까지다. 16px 전용으로 획을 굵힌 단순화 마크
(예: 國 한 글자, 외곽선 2~3px)를 별도로 만들거나, README의 근거 문장 범위를 좁혀라.

## 6. F6 (low) — README:43 "세 글자"는 두 글자다

`assets/brand/README.md:43`:

> 64px에서 세 글자가 모두 판독된다.

낙관은 `三國` **두 글자**다(README:16 스스로 "三國 인장"이라 부른다). 64px 렌더에서도 두 글자다.

## 7. F7 (medium) — `logo-wordmark*.png` 4개(약 2.7MB)를 참조하는 코드가 0개다

```
$ grep -rn "logo-wordmark" web | grep -v node_modules | wc -l
0
```

`web/{gateway,game}/public/logo-wordmark.png`(729KB), `logo-wordmark-light.png`(615KB)는
어떤 `.tsx`/`.ts`/`.css`/`.json`도 참조하지 않는다. `output: 'standalone'`이라 두 컨테이너 이미지에
그대로 실린다. 소비처를 같은 변경에서 배선하든지, 배선할 때까지 커밋에서 빼라.

## 8. F8 (low) — 워드마크 PNG가 무압축 최적화 상태다

1200×448 이미지가 729KB/615KB다. Pillow 기본 deflate이고 팔레트 양자화·`oxipng`/`pngquant`
패스가 없다. `/public` 직접 서빙이라 `next/image` 최적화도 타지 않는다. F7과 겹쳐 사용되지도 않는
2.7MB가 두 이미지에 들어간다.

## 9. F9 (medium) — 원본→마스터 단계가 재현 불가이고, 입력이 리포 밖 추적되지 않는 파일이다

`assets/brand/README.md:13`은 마스터 가공을 "테두리에서 flood fill로 근백색 배경을 투명화 →
콘텐츠 bbox로 트림"이라 적지만, 리포 안에 이 단계를 수행하는 스크립트가 없다. 입력은
`/Users/apple/Downloads/ChatGPT_Image_2026_4_30_11_21_48.png`(2172×724 RGB, 리포 밖)뿐이고
README에 그 파일의 해시가 없다. 그 파일이 사라지면 마스터는 재생성 불가이고 "AI 자체 제작"
출처 주장도 검증 불가가 된다.

이 리포의 기존 규율은 정반대다 — `assets/battle/v2/README.md:38`은
`units/source/source-receipt-ledger.v1.json`에 "카탈로그·프롬프트·요청·생성 스크립트·PNG 지문"을
결합해 두고 그것으로 매니페스트를 재발행하며, `:46`은 영수증이 없는 지형 소스를
`adopted-existing`으로 **명시 기록**한다. 최소한 원본 sha256과 생성 프롬프트를 README 표에 넣어라.

## 10. F10 (medium) — 파생 산출물에 매니페스트·해시가 없어 손편집 드리프트를 아무도 못 잡는다

`assets/brand/README.md:22`는 "손으로 고치지 말고 빌더를 다시 돌려라"라고 하지만, 손편집을
검출하는 장치가 없다. 매니페스트도, 해시 표도, 테스트도, CI 체크도 없다.
`assets/battle/v2`는 영역마다 `manifest.json` + 해시 + `units/sprites/visual-qa.json`을 두고
README:48에서 "출력 PNG를 수동 편집했으면 매니페스트 해시를 신뢰하지 말고 전체 컴파일러를 다시
실행한다"까지 명시한다. 형식 정합성이 맞지 않는다.

재현성은 §0.1로 실증됐으니 비용은 작다 — 빌더에 `--check` 플래그(메모리로 재생성 후 기존 파일과
바이트 비교, 불일치면 비 0 종료)를 붙이고 그걸 CI/게이트에서 부르면 F10과 F9의 절반이 닫힌다.

## 11. F11 (medium) — index와 worktree가 갈라져 있다. 지금 그대로 커밋하면 스크립트와 산출물이 어긋날 수 있다

리뷰 시작 시점의 staged 블롭은 이전 `is_seal_pixel`(`r-g>45 and r-b>45 and r>90`)로 만든
산출물이었다. 리뷰 도중(02:22) 스크립트가 새 판정식
(`r>110 and g<90 and b<90 and abs(g-b)<40 and r-g>60`)으로 바뀌고 6개 산출물이 재생성됐다.

```
$ git status --short
AM tools/assets/build_brand_assets.py
AM web/{game,gateway}/app/{apple-icon.png,favicon.ico,icon.png}
$ git diff --stat
 tools/assets/build_brand_assets.py |   9 +++++++--
 web/game/app/icon.png              | Bin 54893 -> 50282 bytes
 ...
```

`git commit`을 부분 스테이지 상태에서 하면 "커밋된 스크립트로는 재생성되지 않는 커밋된 산출물"이
남는다.

**해소됨.** 이후 전체가 `38adca98`로 한 커밋에 들어갔고 `git diff --stat`이 비어 있다. 커밋된
`icon.png`와 커밋된 스크립트로 재생성한 결과가 바이트 동일임을 확인했다(`ImageChops.difference`
bbox `None`, 변경 픽셀 0).

## 12. F12 (low) — 마스터의 알파가 완전 이진이다

`assets/brand/logo-master.png`에 반투명 픽셀(8 < a < 248)이 **0개**다. flood fill이 안티에일리어싱
없이 하드컷했다는 뜻으로, 마스터를 1927px 원본 크기 그대로 쓰는 소비자는 계단진 가장자리를 얻는다.
파생 워드마크는 1200px LANCZOS 다운스케일에서 반투명 9,698px가 생겨 완화된다. 경계 픽셀 중
근백색(>225,>225,>225)은 12,771개 중 139개(1.1%)뿐이라 **흰 후광은 없다** — 어두운 배경 합성
렌더로도 확인했다. 현재 파생 경로에는 실해가 없으나, 마스터를 그대로 배포하지 마라.

## 13. F13 (low) — README:11의 원본 배경 `#F2F2F2`는 정확하지 않다

원본 코너 샘플: `(0,0) = (241,242,241)`, `(2171,723) = (243,241,242)`. 근백색이지만 평탄한
`#F2F2F2`(242,242,242)는 아니다. flood fill이 허용오차를 썼다는 뜻이므로, 표기를 "근백색(≈#F2F2F2)"로
고치는 편이 재현 조건 서술로 정확하다.

## 14. F14 (low, 판단) — 1200px 렌더에서도 `픈`의 받침 ㄴ이 먹 붓획과 분리되지 않는다

`logo-wordmark.png`를 어두운 판(15,13,12)에 합성해 1200×448 원본 크기로 확인했다. `픈` 아래
받침 ㄴ이 아래쪽 검은 붓획과 붙어 독립 획으로 읽히지 않는다. `assets/brand/README.md:42`는 이
문제를 "작은 크기에서"로 한정하지만, 최대 배포 크기에서도 나타난다. 아트 자체의 성질이라 빌더
결함은 아니고, 마스터 교체로만 닫힌다. 주관 판단이 섞인 항목으로 표시한다.

---

## 15. 확인하지 못한 것 (UNKNOWN)

- 렌더된 HTML `<link rel="icon">` 실제 문자열 — `web/gateway` 빌드가 이번 변경과 무관한
  `@tiptap/starter-kit` 미설치로 실패한다(§0.3). `web/game` 빌드는 시도하지 않았다.
- Pillow 12.2.0 이외 버전에서의 산출물 바이트 동일성. `requirements`/락 파일이 없어 빌더의
  Pillow 버전이 고정되지 않는다 — 재현성 주장은 현재 로컬 버전 한정이다.
- 실제 브라우저(Safari/iOS 홈 화면 포함)에서의 아이콘 표시.
- `tools/agent-system/check.py --strict --base origin/main` 전체 결과 — 이 리뷰 문서가
  커밋되기 전이라 유의미하게 돌릴 수 없었다.

## 16. 커밋 전 조치 요약

| # | 심각도 | 조치 |
| --- | --- | --- |
| F15 | high | `DENSITY_FLOOR` 여유가 +2뿐 (§17.2) — 상수 재산정 또는 불변식 assert |
| F16 | high | 커밋 `38adca98`이 `Verdict: fix-required`를 담은 채 푸시돼 strict 게이트가 막힌다 (§17.5) |
| F1 | high | 루트 `LICENSE` 추가 또는 README:18 문장 수정 |
| F2 | high | README:45-47을 `search_from = int(width*0.7)` 사실에 맞게 수정 |
| F7 | medium | 워드마크 4개 소비처 배선 또는 커밋 제외 |
| F9 | medium | 원본 sha256 + 생성 프롬프트를 README 표에 기록 |
| F10 | medium | 빌더 `--check` 플래그 또는 해시 표 |
| F11 | medium | `git add -A` 후 재현성 재확인 |
| F3/F4/F5 | medium | floor 정규화 · 아이콘 목표 256 하향(또는 더 큰 마스터) · 16px 전용 마크 |
| F6/F8/F12/F13/F14 | low | 문구·최적화 정리 |

---

# 17. 2차 패스 — 금박 오검출 수정 이후

`is_seal_pixel`이 `r-g>45 and r-b>45 and r>90` → `r>110 and g<90 and b<90 and abs(g-b)<40 and r-g>60`
으로 수정된 뒤, team-lead가 추가로 요구한 3가지에 대한 실측이다.

## 17.1 수정은 유효하다. 그리고 결함은 보고된 것보다 컸다

동일 마스터·동일 파이프라인에서 판정식만 갈아끼워 A/B를 떴다.

```python
B.build_seal_tile(m)                                        # 신 판정식
B.is_seal_pixel = lambda r,g,b: r-g>45 and r-b>45 and r>90  # 구 판정식 복원
B.build_seal_tile(m)
→ ImageChops.difference bbox (93,91,419,421), 변경 픽셀 41,348
```

`seal_bounds` 결과:

| 판정식 | bounds | crop | 캔버스 대비 면적 | 종횡비 |
| --- | --- | --- | --- | --- |
| 구 | `(1461, 386, 1751, 597)` | **291×212** | 4.45% | 1.37 |
| 신 | `(1648, 386, 1751, 552)` | **104×167** | 1.25% | 0.62 |

보고된 내용은 "'국'자 ㄱ 획이 붉게 칠해진 채 들어갔다"였지만, 실제 영향은 덧칠이 아니다.
금박이 통과하면서 **바운딩 박스 자체가 x축으로 187px 왼쪽까지 늘어났고**, 그 결과 낙관이 정사각
타일 안에서 면적 기준 약 1/3 크기로 쪼그라들고 오른쪽으로 밀려났다. 512px A/B 렌더에서
좌측 패널의 낙관은 우측 패널의 절반 크기이고, 그 왼쪽에 금박 ㄱ 획과 잡티 방울 3개가 남아 있다.
수정 후 512px에서 ㄱ 획·잡티 모두 소실됐고 낙관이 타일을 채운다. **수정 유효.**

## 17.2 F15 (high) — `DENSITY_FLOOR`의 유효 구간은 19~32뿐이고, 현재값 30은 상한에서 2 떨어져 있다

`seal_bounds`가 정답 상자 `(1648,386,1751,552)`를 내는 floor를 1~120 전수 탐색했다.

```
정답 상자를 내는 floor 범위: 19 ~ 32   (현재 DENSITY_FLOOR = 30)
```

경계 근거:

```
창 안 잡광 최대 col count = 14, 최대 row count = 18   → 하한 19
낙관 최소 col count      = 16, 최소 row count = 11   → 상한 32
낙관 col 중 30 미만: 21/104,  row 중 30 미만: 57/167
```

여유가 **-11 / +2**다. 마스터의 낙관 외곽선이 몇 px 얇아지거나 잉크가 살짝 옅어지면 최소 카운트가
32 아래로 내려가 상자가 줄어든다. **그때 빌더는 죽지 않는다** — `xs`/`ys`가 비지 않으므로 §0.2의
fail-loud 경로를 타지 않고, 조용히 잘린 낙관을 낸다. 이것이 이번 금박 결함과 정확히 같은 실패
형태(육안으로만 잡히는 사일런트 오크롭)다.

`DENSITY_FLOOR = 30`을 고른 근거가 코드·README 어디에도 없다. 실측 중앙값 근처의 임의값으로 보인다.
하한/상한의 중앙인 25 근처가 여유를 -6/+7로 균등하게 만든다.

## 17.3 F17 (high) — 새 판정식도 단독으로는 안전하지 않다. 창과 밀도필터가 **둘 다** load-bearing이다

team-lead의 질문(중앙 붉은 태양·칼날 반사·배경 잔불이 새 조건을 통과하는가)에 대한 직접 픽셀 스캔:

```
새 판정식 통과 픽셀: 33,898 (캔버스의 2.44%)
연결 성분 249개, 상위 5개:
   12,079 px  bbox=( 989,135,1188,309)   ← 중앙 붉은 태양/깃발
   10,969 px  bbox=( 784,128, 966,318)   ← 중앙 붉은 태양/깃발
    3,556 px  bbox=(1648,386,1751,553)   ← 낙관 외곽
    2,401 px  bbox=(1668,468,1731,534)   ← 낙관 國
    1,920 px  bbox=(1028, 60,1227,130)   ← 깃발 상단
낙관 상자 안 3,528 / 밖 30,370  →  통과 픽셀의 89.6%가 낙관이 아니다
```

**중앙의 붉은 태양은 새 조건을 그대로 통과한다.** 금박만 배제됐을 뿐 붉은 계열 잡광은 손대지 않았다.
따라서 낙관을 고르는 실제 판별자는 판정식이 아니라 좌표 창 + 밀도필터다. 4조합 실측:

| 창 `x ≥ 0.7w` | `DENSITY_FLOOR` | bounds | |
| --- | --- | --- | --- |
| ON | 30 (현재) | `(1648, 386, 1751, 552)` | ✅ 정답 |
| ON | 0 | `(1348,  70, 1751, 578)` | ❌ |
| OFF | 30 | `( 810,  93, 1751, 552)` | ❌ |
| OFF | 0 | `( 149,  50, 1751, 633)` | ❌ |

둘 중 **어느 하나만 빼도 틀리고, 틀릴 때 죽지 않는다.** `build_brand_assets.py:38-45`의 새 독스트링은
판정식이 금박과 낙관을 분리한다고만 설명하고, 판정식이 낙관과 **다른 붉은 요소**를 분리하지 못한다는
사실은 적지 않는다. §2(F2)의 README 결함과 같은 뿌리다.

## 17.4 F18 (medium) — 제안된 "비-낙관 색 픽셀 비율 상한"은 이 결함을 못 잡는다. 불변식은 색이 아니라 bbox에 있다

team-lead가 제안한 자동 체크(아이콘 내 비-낙관 색 픽셀 비율 상한)는 작동하지 않는다.
`build_seal_tile:86`이 통과 픽셀을 전부 `SEAL_RGB = (198,32,38)`로 **재도색**하기 때문에, 결함
산출물에서도 모든 픽셀은 낙관 색이다. 색 분포로는 금박 ㄱ 획과 낙관을 구별할 수 없다.

결함이 실제로 드러나는 곳은 §17.1 표의 **crop 면적과 종횡비**다. 그래서 체크는 `seal_bounds` 직후에
불변식 두 줄이면 된다 — F10의 `--check` 플래그 안에 같이 넣으면 파일 추가 0개다.

```python
x0, y0, x1, y1 = seal_bounds(master)
cw, ch = x1 - x0 + 1, y1 - y0 + 1
assert cw * ch < 0.02 * master.width * master.height, f"낙관 상자가 너무 크다 — 잡광 혼입: {cw}x{ch}"
assert 0.4 < cw / ch < 0.9, f"낙관 종횡비 이탈: {cw/ch:.2f}"
assert seal_bounds(master, floor=DENSITY_FLOOR + 8) == (x0, y0, x1, y1), "DENSITY_FLOOR 여유 부족"
```

판별력 검증 (§17.1 실측값 대입):

| | 면적 assert (< 2%) | 종횡비 assert (0.4~0.9) |
| --- | --- | --- |
| 정상 104×167 = 1.25%, 0.62 | 통과 | 통과 |
| 결함 291×212 = 4.45%, 1.37 | **실패** | **실패** |

두 assert 모두 이번 결함을 잡는다. 세 번째 줄은 §17.2를 게이트로 바꾼 것이며, 유효 구간이 19~32이므로
`30+8=38`에서 상자가 달라져 **지금 즉시 실패한다** — F15를 닫기 전까지는 그게 정상이다.
(`seal_bounds`에 `floor` 인자를 추가해야 하므로 시그니처 1줄 변경이 따른다.)

## 17.5 F16 (high) — 커밋 `38adca98`이 `Verdict: fix-required` 리뷰를 담은 채 푸시됐다

이 리뷰 문서가 브랜드 에셋과 **같은 커밋**에 들어갔고 `origin/feat-brand-assets`로 푸시됐다.

```
$ python3 tools/agent-system/check.py --strict --base origin/main
## Findings
- **ERROR cross-agent-critique**: Unresolved Verdict: fix-required blocks completion:
  docs/superpowers/reviews/2026-08-17-brand-assets-review.md
```

`check.py:578-579`가 `fix-required`를 무조건 차단으로 처리한다. 현재 상태로는 머지 불가다.
남은 항목을 닫고 이 문서를 `cleared`로 갱신하든지, 닫지 않을 항목은 앵커된 `Proof:` 줄과 함께
`quarantined-with-proof`로 전환해야 한다. **판정을 임의로 `cleared`로 바꾸는 것은 이 게이트의
우회이며, 리뷰어인 내가 승인하지 않은 변경이다.**

## 17.6 512px 육안 검사 (요구 #2)

512px 원본으로 확인한 것과 그 결과:

- 금박 ㄱ 획·잡티 방울 **소실 확인**. 낙관만 남았다.
- 획 경계가 전반적으로 물러 있다 — §4(F4)의 267→512 1.92배 업스케일이 육안으로 확인된다.
  512px에서 외곽선 가장자리가 1~2px 뭉개져 있고 붓 터치의 갈필 질감이 뭉개진다.
- 낙관은 정사각 타일 안에서 수평·수직 모두 중앙에 있다(정수 나눗셈 오차 ≤1px).
- 세로로 긴 낙관(104×167)을 정사각에 넣으면서 좌우 여백이 상하 여백보다 크지만, 시각적으로
  불균형하지는 않다. 결함으로 세지 않는다.

**1차 실패 원인에 대한 소견:** 64px 프리뷰에서 금박 ㄱ 획이 안 보인 이유는 단순 축소가 아니다.
결함 상태에서는 낙관 자체도 함께 1/3로 줄어 두 요소가 같은 비율로 뭉개졌기 때문에, 작은 크기에서는
"낙관이 작다"는 인상만 남고 "옆에 이물이 있다"는 신호가 사라진다. 즉 **결함이 클수록 작은 프리뷰에서
덜 보이는** 구조였다. 육안 검사를 최대 크기에서 하라는 요구는 타당하고, §17.4의 bbox 불변식이
육안 검사 자체를 대체할 수 있는 이유도 같다 — 면적·종횡비는 크기에 무관하다.

---

# 18. 실행 패스 — 결함 폐쇄 기록 (executor, 이 문서의 판정은 변경하지 않는다)

아래는 executor(fable/opensamguk-review-work-brand-assets, 브랜치 `work-brand-assets` →
`feat-brand-assets`)가 이 리뷰의 미해소 항목에 대해 실제로 수행한 조치와 증거다. **이 절은
추가 기록일 뿐이며 위 `Verdict: fix-required`를 스스로 `cleared`로 바꾸지 않는다** — 판정 갱신은
독립 리뷰 패스의 몫이다.

## 18.1 F4 — 닫음: 업스케일 제거, 네이티브 해상도로 전환

`build_seal_tile`이 더 이상 `resize((512,512))`로 업스케일하지 않는다. `icon.png`는 패딩 포함
네이티브 크기(241×241, 패딩 22%)를 그대로 저장한다 — Next App Router는 파일의 실제 픽셀 크기를
`<link>`에 반영하므로 512 고정이 필요하지 않다. `apple-icon.png`(180)·`favicon.ico`(≤48)는 전부
이 상한 아래라 다운스케일만 한다. `assets/brand/README.md` §"아이콘 해상도 상한"에 실측 크롭
104×167px와 원본(2172×724)도 1.13배뿐이라 원본에서 다시 떠도 근본적으로 나아지지 않는다는 판단
근거를 기록했다.

## 18.2 F5 — 닫음(정직한 한계 인정): 크기별 패딩, 16px는 텍스트로 안 읽힌다고 명시

`PAD_RATIO_ICON=0.22`(icon/apple-icon용 241×241 타일), `PAD_RATIO_FAVICON=0.08`(favicon 전용
193×193 타일)로 분리했다 — 패딩이 작을수록 작은 출력에서 글자 자체에 더 많은 픽셀을 할당한다.
개선 전후를 실제로 렌더해서 비교했다: 패딩 30% 균일 적용 시 16px는 "붉은 덩어리 + 검은 홈 2개",
패딩 8% 전용 타일에서는 내부 홈이 살아 있는 구분되는 붉은 인장 실루엣으로 개선됐다 — **그래도
16px에서 三國 두 글자는 낱글자로 읽히지 않는다.** README에 "16px에서 최선으로 성립하는 주장은
'구분되는 붉은 정사각 인장 실루엣'까지"라고 그대로 적었다(성립 범위 48~64px는 유지). 물리적으로
불가능한 걸 가능하다고 쓰지 않았다.

## 18.3 F7 — 닫음, (a)로: 게이트웨이 로그인·회원가입 내비바에 실배선, light 변형은 제외

`web/gateway/app/login/page.tsx`·`web/gateway/app/join/page.tsx`의 `.gw-navbar`(기존 요소,
새 디자인 아님) 텍스트 `<span className="gw-brand">{BRAND}</span>`을
`<Image src="/logo-wordmark.png" alt={BRAND} .../>`로 교체했다(`globals.css`에 `.gw-brand-logo`
추가, 기존 `.gw-brand` 규칙은 소비처가 사라져 제거). 두 프런트엔드가 `#0a0a0a` 기반 다크 테마
전용이라(코드베이스에 흰 배경 컨텍스트 0개, 확인 완료) 투명 배경 워드마크 하나만 쓰면 충분하다.
`logo-wordmark-light.png`(흰 배경 합성본)는 빌더 산출물에서 제거하고 커밋된 4개 중 2개
(`web/{gateway,game}/public/logo-wordmark-light.png`)를 `git rm`했다 — 소비할 자리가 없는
산출물을 유지하지 않는다는 (b) 판단을 F7의 절반에 적용한 하이브리드다.

## 18.4 F9 — 부분 닫음: 원본 sha256 기록, 원본→마스터 변환 스크립트화는 UNKNOWN으로 남김

원본 파일(`/Users/apple/Downloads/ChatGPT_Image_2026_4_30_11_21_48.png`, 리포 밖, 미커밋)의
sha256 `f9f6c0ffc824cb1e1dcd1f429933cad74fedc61d7ff4f912778b269fd7cc9db5`를 README에 기록했다.
flood fill 허용오차 등 원본→마스터 변환의 정확한 파라미터는 손작업으로 수행됐고 스크립트화되지
않았다 — **UNKNOWN이라고 README에 명시**했고, 추정치를 지어내지 않았다. 변환 스크립트 작성은
선택 사항으로 남겨 이번에는 만들지 않았다(정확한 재현 파라미터를 모르는 채로 스크립트를 만들면
거짓 재현성 주장이 된다).

## 18.5 F10 — 닫음: `--check` 플래그 + bbox 불변식(F18) 통합

`build_brand_assets.py --check`가 전체 산출물을 메모리에서 재생성해 디스크의 커밋된 산출물과
바이트 비교하고, 불일치 파일마다 `DRIFT: <path>`를 stderr에 출력한 뒤 비0 종료한다. mutation
증명(아래 §18.7)으로 실제 동작을 확인했다. 매니페스트/해시 표는 만들지 않았다 — `--check` 자체가
그 역할을 대신한다(리뷰가 제안한 "가장 싼 수선"). §18.9(F18)의 bbox 면적·종횡비·floor 여유
불변식은 `seal_bounds` 안에 상시 내장해 `--check`뿐 아니라 일반 빌드에서도 항상 검증되게 했다.

## 18.6 F8 — 닫음: `optimize=True`

모든 PNG 저장 경로(icon/apple-icon/wordmark)에 Pillow `optimize=True`를 붙였다. 워드마크가
729KB→700KB(약 4%)로 줄었다 — 큰 개선은 아니라 팔레트 양자화·oxipng 같은 추가 패스는 하지
않았다(F8은 low, 리뷰도 "cheap하면 하고 크면 스킵"이라고 명시).

## 18.7 F15 — 닫음: `DENSITY_FLOOR` 30 → 24, 근거를 코드·README에 기록

전수 탐색으로 재확인한 안전 구간(19~32)의 중앙 근처인 24로 옮겼다. 이유는 §17.4의 3번째
assert(`seal_bounds(floor=DENSITY_FLOOR+8)`이 같은 상자를 내야 한다)가 항상 안전 구간 안에서
평가되게 하기 위해서다 — `24+8=32`는 안전 구간 상한과 일치해 통과하지만, 리뷰가 예시로 든 `25`는
`25+8=33`이 구간을 벗어나 자기 검증이 즉시 실패한다. 24에서 여유는 -5/+8. 이 산정 근거를
`DENSITY_FLOOR` 상수 옆 주석과 `assets/brand/README.md` §"밀도 임계값"에 그대로 남겼다. floor
변경으로 실제 crop bounds는 변하지 않음을 재확인했다(`(1648,386,1751,552)`, floor 19~32 전부 동일).

## 18.8 F17 — 닫음: 문서화(코드 변경 없음, 사실이 이미 그러함)

`is_seal_pixel` 독스트링에 "이 판정식은 금박만 배제한다. 낙관과 다른 붉은 요소는 분리하지 못한다
— 마스터 중앙의 붉은 태양·깃발 상단도 이 조건을 통과한다"를 추가하고, `seal_bounds` 독스트링에
"우측 30% 탐색 창이 그 분리를 담당한다 — load-bearing"을 명시했다. README도 "빌더는 낙관을
붉은 픽셀 밀도로 찾는다... 다만 `is_seal_pixel`의 색 판정은 낙관 붉은색과 마스터 중앙의 태양·깃발
붉은색을 구별하지 못한다 — 우측 30% 탐색 창이 그 분리를 담당한다"로 갱신해 F2와 같은 뿌리의
서술 결함을 함께 닫았다. 코드 동작 자체는 이미 창+밀도 조합으로 정답을 냈으므로(§17.3 표) 변경하지
않았다 — 결함은 문서가 이 사실을 숨겼다는 점이었다.

## 18.9 F18 — 닫음: bbox 면적·종횡비·floor 여유 불변식을 `seal_bounds`에 상시 내장

리뷰가 제안한 3줄을 `seal_bounds(master, floor=DENSITY_FLOOR)`의 `floor == DENSITY_FLOOR`
경로에 넣었다(재귀 호출 시 `floor != DENSITY_FLOOR`라 무한 재귀 없음):

```python
assert area_ratio < 0.02, f"낙관 상자가 너무 크다 — 잡광 혼입 의심: {cw}x{ch} ({area_ratio:.4f})"
assert 0.4 < cw / ch < 0.9, f"낙관 종횡비 이탈: {cw / ch:.2f}"
assert seal_bounds(master, floor=floor + 8) == bounds, "DENSITY_FLOOR 여유 부족 — 임계값을 다시 잡아라"
```

F15(§18.7)를 먼저 닫았으므로(24, +8=32는 안전 구간 안) 세 번째 assert가 정상 빌드에서 통과한다.
`--check`뿐 아니라 매 빌드 실행마다 평가되므로 리뷰가 지적한 "색만으로는 안 죽는 실패"를 항상
막는다.

## 18.10 F1/F2 — 이미 닫힘 확인 (이번 패스 이전에 닫혀 있었음)

이번 실행을 시작한 시점의 `assets/brand/README.md`에 F1(존재하지 않는 루트 LICENSE 인용 삭제,
"루트 `LICENSE` 파일이 없다... 여기서 라이선스를 주장하지 않는다"로 수정됨)과 F2(§18.8에서 다시
확인한 "우측 30% 탐색 창이 load-bearing" 서술)가 이미 반영돼 있었다. §18.8에서 F2 서술을
F17 폐쇄와 함께 한 번 더 갱신했다. 코드에 `search_from = int(width * 0.7)`가 여전히 존재하며
README도 이를 정확히 반영한다 — 재확인만 하고 추가 변경은 없었다.

## 18.11 mutation 증명 (`--check`)

```
$ cp web/gateway/app/icon.png /tmp/icon_backup.png
$ python3 -c "... px[0,0]=(255,255,255); im.save('web/gateway/app/icon.png')"
$ python3 tools/assets/build_brand_assets.py --check
DRIFT: web/gateway/app/icon.png
1개 산출물이 빌더 재생성 결과와 다르다 — 손편집됐거나 빌더를 안 돌렸다
exit=1
$ cp /tmp/icon_backup.png web/gateway/app/icon.png
$ python3 tools/assets/build_brand_assets.py --check
brand assets check OK: 8 files byte-match
exit=0
```

## 18.12 검증

- `python3 tools/assets/build_brand_assets.py && python3 tools/assets/build_brand_assets.py --check`
  → `brand assets check OK: 8 files byte-match`.
- `web/gateway`: `pnpm exec tsc --noEmit` → 에러 없음. `pnpm exec next build` → exit 0,
  `/login`·`/join` 라우트 정상 컴파일. `pnpm exec vitest run` → 19 files / 146 tests 전부 통과.
- `web/game`: `pnpm exec vitest run` → 64 files 통과, `__tests__/live-noop-closures.test.tsx` 1건만
  실패(과제 지시대로 이 실행 이전부터 존재하는 선존재 결함, 이번 변경과 무관 — web/game 코드는
  이번 패스에서 건드리지 않았고 에셋 바이너리만 재생성됐다).

---

# 19. 3차 패스 — `e2a170a8` 검증 (리뷰어)

`origin/feat-brand-assets` = `e2a170a8`을 **별도 워크트리**(`git worktree add --detach`)에 체크아웃해
검증했다. 메인 워킹트리는 쓰지 않았다. 환경: Python 3 / Pillow 12.2.0, Next.js 15.5.20,
`web/gateway`는 이 워크트리에서 `pnpm install` 후 빌드(메인 워크트리의 `node_modules`는
`@tiptap/*` 미설치 상태라 §0.3의 빌드 실패 원인이었다 — 이번 변경과 무관한 선존재 환경 문제).

## 19.1 항목별 판정

| 항목 | 판정 | 근거 |
| --- | --- | --- |
| F1 루트 LICENSE | **PASS** | §19.2 |
| F2 창 하드코딩 서술 | **PASS** | §19.3 |
| F3 floor 해상도 미정규화 | **부분 — 문서화로 강등** | §19.4 |
| F4 512 업스케일 | **PASS** | §19.5 |
| F5 16px 판독 | **PASS** | §19.6 |
| F6 "세 글자" | **PASS** | §19.3 |
| F7 워드마크 고아 | **FAIL — 미폐쇄** | §19.7 |
| F8 PNG 최적화 | **PASS (수치 정정)** | §19.8 |
| F9 원본 출처 | **PASS** | §19.2 |
| F10 드리프트 검출 | **PASS** | §19.9 |
| F11 index/worktree 분기 | **PASS** | §7 |
| F12 이진 알파 | 변화 없음 (실해 없음) | — |
| F13 `#F2F2F2` | **PASS** | §19.2 |
| F14 `픈` 받침 | 미변경 (아트 성질) | — |
| F15 DENSITY_FLOOR 여유 | **부분 — §19.4** | §19.4 |
| F16 fix-required 푸시 | 진행 중 (이 문서 갱신으로 해소) | §19.11 |
| F17 판정식 한계 미기재 | **PASS** | §19.3 |
| F18 자동 체크 | **PASS** | §19.10 |
| F19 favicon `<link sizes>` | **신규 low** | §19.5 |

## 19.2 F1 · F9 · F13 — PASS

`assets/brand/README.md`가 루트 `LICENSE` 인용을 삭제하고 "이 리포에는 루트 `LICENSE` 파일이
없다 … 여기서 라이선스를 주장하지 않는다"로 바꿨다. 없는 문서를 근거로 들지 않는다.

원본 sha256이 기록됐고 **실측과 일치한다**:

```
$ shasum -a 256 /Users/apple/Downloads/ChatGPT_Image_2026_4_30_11_21_48.png
f9f6c0ffc824cb1e1dcd1f429933cad74fedc61d7ff4f912778b269fd7cc9db5
README 기재:  f9f6c0ffc824cb1e1dcd1f429933cad74fedc61d7ff4f912778b269fd7cc9db5
```

flood fill 허용오차는 `UNKNOWN`으로 명시됐다 — 재현 경로가 없다는 사실을 감추지 않았다.
"재현하려면 마스터를 그대로 쓰거나 동일 원본에서 같은 배경 제거·트림을 다시 수행해야 한다"까지
적었다. 정직한 표기다. 배경색도 "근백색 ≈#F2F2F2, 평탄하지 않음 — 코너 샘플 241~243"으로 정정.

## 19.3 F2 · F6 · F17 — PASS

`build_brand_assets.py`의 `is_seal_pixel` 독스트링과 `seal_bounds` 독스트링, README 모두에
2차 패스 실측이 정확히 반영됐다 — "실측 33,898px 중 89.6%가 낙관이 아닌 그 요소들",
"낙관만 남기는 건 이 함수가 아니라 `seal_bounds`의 우측 30% 탐색 창이다", "창이
load-bearing이라는 뜻이다". 수치·결론 모두 내 측정과 일치한다. "세 글자"는 "三國 두 글자"로 정정.

## 19.4 F15 · F3 — 부분 폐쇄. 여유 개선은 실증됐으나 자기검증 지점은 경계 정확일치다

`DENSITY_FLOOR` 30 → 24. 이 커밋 상태에서 전수 재탐색(assert 우회를 위해 `floor=` 인자 사용):

```
정답 상자 floor 구간: 19 ~ 32   (bounds (1648,386,1751,552), crop 104x167)
  → 24 기준 여유 -5 / +8        ← 레인 주장과 일치. (a) 검증 PASS
자기검증 지점 DENSITY_FLOOR+8 = 32, 구간 상한 = 32, 여유 = 0
  floor=31 -> (1648,386,1751,552) 동일
  floor=32 -> (1648,386,1751,552) 동일
  floor=33 -> (1648,387,1751,552) ★다름
최외곽 카운트 col[1648]=130 col[1751]=34 row[386]=32 row[552]=66 → min=32
```

**(a) -5/+8 주장은 참이다.** 구간 재산정도 19~32로 동일하다 — **(c) 검증 PASS**.

**(b) tautology는 아니지만 여유 0이다.** assert는 "floor 24~32 구간에서 상자 불변"을 실제로
검사한다. 실제 파손은 최외곽 카운트가 24 미만으로 떨어질 때고, assert는 32 미만에서 이미
발화하므로 **8단계 앞선 조기경보**다 — 설계 자체는 유효하다. 다만 상한을 정하는 값이
`row[386]=32` 단 하나이고 `DENSITY_FLOOR+8`이 정확히 32이므로, `+9`였으면 지금 당장 실패한다.
**`+8`이 그 실측 32에서 역산된 값이라는 team-lead의 지적은 사실이다.**

실질 영향: 마스터의 최상단 획이 1px만 옅어져 `row[386]`이 31로 떨어지면 실제 파손까지 8단계
남았는데도 빌더가 죽는다. 오탐 감도가 최대다. 다만 실패 모드가 **사일런트 오크롭 → 즉시 사망**
으로 바뀐 것은 명확한 개선이고, README §밀도 임계값이 구간 19~32와 "+8 재확인도 안전
구간(≤32) 안" 을 숫자로 공개해 독자가 경계값임을 알 수 있게 했다. 은폐가 아니다.

남는 지적 두 가지(둘 다 low, 차단 아님):
- 코드 주석 `DENSITY_FLOOR+8(=32)도 여전히 안전 구간 안이라 … 항상 통과한다`의 "여전히 안 /
  항상"은 경계 정확일치를 여유가 있는 것처럼 읽히게 한다. "구간 상한과 정확히 일치(여유 0)"가
  사실이다. README 쪽(`≤32`)은 정확하다.
- 여유를 실제로 두려면 `+8`이 아니라 구간 폭에서 유도해야 한다(예: 상한 32에 대해 `+4`).
- F3(해상도 미정규화)은 여전히 남아 있으나 README가 "절대값이라 해상도 정규화가 안 돼 있는
  점은 그대로다 — 마스터를 크게 축소한 것으로 교체하면 빌더가 죽고, 그때 임계값을 다시
  잡아야 한다"로 **알려진 한계로 명시**했다. 사일런트가 아니므로 low로 강등한다.

## 19.5 F4 — PASS. 빌드 산출물이 레인의 주장을 확인해준다. + F19 (신규 low)

`icon.png`는 241×241 네이티브(업스케일 없음), `apple-icon.png` 180(241에서 다운스케일),
`favicon.ico` 16/32/48(193×193 별도 타일에서 다운스케일) — 전부 다운스케일 전용이다.
파일 크기도 50,282B → **5,152B**로 줄었다(없던 디테일을 만들지 않으니 당연한 결과).

"App Router가 파일의 실제 픽셀 크기를 `<link>`에 반영한다"는 주장은 **실제 빌드 HTML로 확인됨**:

```html
<link rel="icon" href="/icon.png?b49be90f77461e37" type="image/png" sizes="241x241"/>
<link rel="apple-touch-icon" href="/apple-icon.png?53ef79d5794031d7" type="image/png" sizes="180x180"/>
<link rel="icon" href="/favicon.ico" type="image/x-icon" sizes="16x16"/>
```

(`web/gateway/.next/server/app/login.html` 외 23개 프리렌더 페이지 전부 동일. §0.3에서
UNKNOWN으로 남겼던 "렌더된 `<link>` 실문자열"이 이제 확보됐다.) 512 고정은 불필요했다.

**F19 (신규, low):** favicon `<link>`가 `sizes="16x16"`으로 나간다. ICO 안에는 16/32/48
세 엔트리가 다 들어 있는데(파싱 실측: entry 16/32/48, 각 528·1415·2640B) Next가 첫 엔트리만
읽어 선언한다. 브라우저는 대개 .ico의 선언 크기를 무시하고 파일을 직접 읽으므로 실해는
거의 없다. 32/48 엔트리를 확실히 쓰이게 하려면 `app/icon.png`가 이미 그 역할을 하므로
`favicon.ico`를 16px 단일 엔트리로 줄이는 편이 정직하다 — 선택 사항이다.

## 19.6 F5 — PASS. 개선은 실재하고 README 서술은 과장이 아니다

`38adca98`의 16px 엔트리와 `e2a170a8`의 16px 엔트리를 ICO에서 직접 추출해 나란히 비교했다.

- 이전(패딩 30%): 형체 없는 붉은 덩어리에 검은 홈 두 개 — 인장이 아니라 얼굴처럼 읽힌다.
- 현재(패딩 8%, 193×193 소스): **직사각 외곽선과 내부 가로획의 리듬이 살아 있어 "인장"으로
  읽힌다.** 三國 낱글자는 여전히 판독 불가.

README의 서술 — "16px ICO 엔트리에서는 三國 두 글자가 판독되지 않는다 … 16px에서 최선으로
성립하는 주장은 '구분되는 붉은 정사각 인장 실루엣'까지다" — 는 **실제 렌더와 정확히 일치한다.
과장 없음.** 48px 엔트리에서 三國 두 글자가 또렷이 판독되는 것도 확인했다("48px 이상에서는
또렷이 판독된다" 역시 참).

## 19.7 F7 — **FAIL. 미폐쇄.** 배선은 됐으나 낭비가 오히려 커졌고 game 앱은 그대로 고아다

**(a) 렌더 — PASS.** 빌드된 `login.html`·`join.html`에 실제로 들어간다:

```html
<img alt="오픈삼국" width="1200" height="448" class="gw-brand-logo" style="color:transparent"
  srcSet="/_next/image?url=%2Flogo-wordmark.png&w=1200&q=75 1x,
          /_next/image?url=%2Flogo-wordmark.png&w=3840&q=75 2x"
  src="/_next/image?url=%2Flogo-wordmark.png&w=3840&q=75"/>
```

**(c) alt — PASS.** `alt="오픈삼국"`(`BRAND` 상수). **(d) light 삭제 — PASS.** 잔존 코드 참조 0건
(`grep -rn logo-wordmark-light`는 설명 문서·주석만 히트).

**(b) 낭비 — FAIL, 의심보다 나쁘다.** `.gw-brand-logo`가 `height:32px; width:auto`이므로 실제
렌더 폭은 `32 × 1200/448 = 85.7px`다. 그런데 `src` 폴백이 **`w=3840`**이다.

```
85.7px 슬롯에 3840px 이미지  →  선형 44.8배, 면적 2007배
1x 후보(w=1200)조차          →  선형 14.0배
```

원인은 `sizes` prop 부재다. Next 공식 문서(`docs/01-app/03-api-reference/02-components/image.mdx`):

> Omitting `sizes` can lead to unnecessarily large images being downloaded, as the browser
> defaults to `100vw`. When `sizes` is present, Next.js generates a full `srcset` optimized
> for responsive layouts, unlike the limited `srcset` generated without it.

같은 문서상 `imageSizes`(32·48·64·96·128·256·384 — 86px 슬롯이 정확히 여기 속한다)는
**`sizes`가 있을 때만** `srcset` 후보에 들어간다. 지금은 `deviceSizes`(최소 640)만 후보라
86px 슬롯에 맞는 크기가 아예 생성되지 않는다.

F7의 원래 지적은 "2.7MB가 아무 데도 안 쓰인다"였다. 지금은 **쓰이되 로그인 랜딩 페이지에서
슬롯의 2000배 면적을 받아온다.** 미사용이 낭비 사용으로 바뀐 것이지 낭비가 사라진 게 아니다.
한 줄이면 닫힌다 — `width={86} height={32}`로 선언하거나 `sizes="86px"`를 추가하라.

**(e) game 앱 — FAIL.** `web/game/public/logo-wordmark.png`(714,596B)는 **여전히 소비처 0건**이다.

```
$ grep -rn "logo-wordmark" web/game --include="*.tsx" --include="*.ts" --include="*.css"
(0건)
```

빌더가 두 앱 모두에 무조건 쓰고 있고(`build()`의 `for app in APPS`), `web/game`에는 배선이
없다. `output: 'standalone'` 컨테이너 이미지에 714KB가 그대로 실린다. F7이 지적한 바로 그
상태가 절반 남았다. gateway처럼 배선하든지, 빌더의 워드마크 출력을 gateway로 한정하라.

## 19.8 F8 — PASS (수치 정정)

`optimize=True` 적용. 실측 감소는 **729,365B → 714,596B = 2.02%**다(레인 보고 "~4%"는 과대).
실질 절감은 압축이 아니라 light 변종 2개(각 615,042B) 삭제 쪽이다 — 커밋 기준 총 -1,259,853B.

## 19.9 F10 — PASS

```
$ python3 tools/assets/build_brand_assets.py --check
brand assets check OK: 8 files byte-match                     exit 0
$ <icon.png의 (0,0) 픽셀 1개를 흰색으로 변조>
$ python3 tools/assets/build_brand_assets.py --check
DRIFT: web/gateway/app/icon.png                               exit 1
$ git checkout -- web/gateway/app/icon.png && ... --check
brand assets check OK: 8 files byte-match                     exit 0
$ python3 tools/assets/build_brand_assets.py && git status --short
(출력 없음 — 전량 재생성 결과가 커밋 내용과 바이트 동일)
```

**1픽셀 변조도 잡는다.** 레인 보고의 검증 꼬리를 그대로 재현했다. 재현성 + 드리프트 검출
둘 다 성립하므로 F10은 매니페스트 없이 닫힌다.

## 19.10 F18 — PASS. 판별력 재현됨

`seal_bounds`에 assert 3개가 상시 내장됐고 `floor == DENSITY_FLOOR` 경로에서만 평가돼
무한 재귀가 없다. 2차 패스에서 제시한 판별력을 그대로 재현했다 — 구 판정식으로 되돌리면:

```python
B.is_seal_pixel = lambda r,g,b: r-g>45 and r-b>45 and r>90
B.seal_bounds(m)
→ AssertionError: 낙관 상자가 너무 크다 — 잡광 혼입 의심: 402x212 (0.0614)
```

면적 6.14% > 2% 상한에서 죽는다. 이번 금박 결함이 다시 들어오면 **육안 검사 없이 빌드가
멈춘다.** (상자가 402×212로 2차 패스의 291×212보다 넓은 것은 `DENSITY_FLOOR`가 30→24로
내려가 구 판정식 기준 상자가 더 커지기 때문 — 일관된 결과다.)

한 가지만 기록해 둔다: `assert`는 `python -O`에서 통째로 제거된다. 이 빌더를 `-O`로 부르는
경로는 현재 없으므로 실해 없음. 게이트에서 부를 때 `-O`를 쓰지 마라.

## 19.11 F16 — 이 갱신으로 해소

판정 줄은 리뷰어만 바꾼다. `e2a170a8`에서 판정 줄이 미변경 상태였음을 확인했고
(`Verdict: fix-required` 1개, `Scope:` 1개), 수정 레인이 §18을 덧붙이면서 내 발견을 지운
흔적은 없다(문서 diff의 삭제 줄은 전부 내 2차 패스 자체 수정분).

## 19.12 결론

**F1·F2·F4·F5·F6·F8·F9·F10·F11·F17·F18 폐쇄.** F3·F15는 알려진 한계로 문서화돼 low로 강등.
F19는 신규 low(실해 없음).

**차단 항목은 F7 하나다.** 두 갈래 모두 미폐쇄이고 둘 다 한 줄짜리 수정이다:

1. `web/gateway/app/login/page.tsx` · `join/page.tsx` — `width={1200} height={448}` →
   `width={86} height={32}`(또는 `sizes="86px"` 추가). 지금은 86px 슬롯에 `w=3840`을 받는다.
2. `web/game/public/logo-wordmark.png` — 소비처 0건. 배선하거나 빌더 출력에서 제외.

이 둘이 닫히면 `cleared`다. 그 외 남은 항목은 전부 문서화된 알려진 한계이거나 실해 없는 low다.
