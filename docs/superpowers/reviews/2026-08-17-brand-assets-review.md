# 브랜드 에셋 (logo-master + 파생 빌더) 적대적 리뷰

Scope: assets/brand/README.md · assets/brand/logo-master.png · tools/assets/build_brand_assets.py · web/gateway/app/{icon,apple-icon}.png · web/gateway/app/favicon.ico · web/gateway/public/logo-wordmark*.png · web/game/app/{icon,apple-icon}.png · web/game/app/favicon.ico · web/game/public/logo-wordmark*.png
Verdict: fix-required

- 대상: 브랜치 `feat-brand-assets`의 미커밋 변경 (전부 신규 추가, 기존 파일 수정 0). 기준 `origin/main` = `a95e5a90`.
- 리뷰어는 team-lead와 독립. `tools/agent-system/check.py` 무편집. 코드 수정 0건 — 결함만 보고한다.
- 환경: Python 3 / Pillow 12.2.0, Next.js 15.5.20, macOS Darwin 25.5.0.
- **리뷰 도중 작업 트리가 바뀌었다.** `tools/assets/build_brand_assets.py`의 `is_seal_pixel`이 02:22에
  수정되고 6개 산출물이 재생성되어, index(staged)와 worktree가 갈라진 상태다(§7 F11).
  아래 §1~§6은 **현재 worktree 버전** 기준으로 재검증한 결과다.

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
남는다. 커밋 전에 `git add -A` 후 §0.1 재현성 검사를 **다시** 돌려라(현재 worktree 조합에 대해서는
이미 바이트 동일을 확인했다).

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
| F1 | high | 루트 `LICENSE` 추가 또는 README:18 문장 수정 |
| F2 | high | README:45-47을 `search_from = int(width*0.7)` 사실에 맞게 수정 |
| F7 | medium | 워드마크 4개 소비처 배선 또는 커밋 제외 |
| F9 | medium | 원본 sha256 + 생성 프롬프트를 README 표에 기록 |
| F10 | medium | 빌더 `--check` 플래그 또는 해시 표 |
| F11 | medium | `git add -A` 후 재현성 재확인 |
| F3/F4/F5 | medium | floor 정규화 · 아이콘 목표 256 하향(또는 더 큰 마스터) · 16px 전용 마크 |
| F6/F8/F12/F13/F14 | low | 문구·최적화 정리 |
