# 묶음 1 `external-places-546` 독립 비평 — 재심

Date: 2026-08-24

Scope: 묶음 1 `work/opensamguk/external-places-546` tip `dccdbe268360dc6c14829a924b6c5750bbe9fa45` (`.gitignore` +3 / `data/map/external-places.json` +1,312, 2 files, base `origin/main` `ad751195` 확인, force-push 2회로 `b25a78e9`·`0e6e2ee6`·`41daa6ce` 무효). 검증한 것 = 라이브 Wikidata 재생성 후 커밋 블롭과 바이트 대조 · 이전 블롭 `5883acfa` 대비 전문 diff · 새 커밋 메시지의 열화 주장 3건을 주석이 아닌 코드 경로로 확인 · 하류 재생성 가능성 실행 검증 · `.gitignore`/ADR 미변경 확인. 묶음 2·4 는 이 문서 범위 밖이며 이전 cleared 판정 유지.

Reviewer: 구현자가 아닌 독립 비평 에이전트. 제출자 보고를 근거로 채택하지 않고 직접 재현했다. **종료 코드만으로 판정하지 않고 생성물을 통째로 받아 diff 했다** — 그게 초심에서 X014 한 필드를 집어낸 방법이라 재심에서도 같은 방법을 썼다. 확인 못 한 것은 UNKNOWN 으로 남긴다. 읽기 전용 — 제출자 워크트리 무수정, 내 검토용 워크트리는 `git status --porcelain` 이 이 비평문 한 줄만 내도록 원복 확인했다.

---

## 판정 요약

이전 심사에서 올린 막는 지적 2건(B1 stale 블롭, B2 커밋 메시지 거짓 주장)이 **둘 다 실제로 고쳐졌다.** 재현으로 확인했다. 막는 것 없음.

## R1 B1 해소 — 커밋 블롭이 라이브 재생성과 바이트 일치

초심 때와 같은 방식으로 검증했다. 먼저 `--check`:

```
$ python3 tools/map/build_external_places.py --check
드리프트 없음.
REAL_EXIT=0
```

**종료 코드만 믿지 않았다.** 빌더의 `build()` 를 직접 호출해 오늘 Wikidata 에서 65건을 다시 받아 산출물을 통째로 만들고, 커밋 블롭과 전문 diff 했다:

```
regen resolved 65 · unresolved 0
$ diff <(git show dccdbe26:data/map/external-places.json) <live regen>
(차이 없음)
md5   1351a24735aff247997dc07cab0e7aaa == 1351a24735aff247997dc07cab0e7aaa
bytes 31,023 == 31,023
```

**0 differing lines.** 초심에서 잡은 `298c298` X014 드리프트가 사라졌다. 커밋본은 이제 main 의 생성기 출력과 바이트 일치한다.

이 재생성은 동시에 **출처 증명을 한 번 더 확인**한다 — 65건 좌표 전건이 오늘 Wikidata P625(CC0)에서 재현되므로 ADR-LITE-039 가 격리한 「CHGIS shapefile 로부터 생성한 좌표」가 아니다.

## R2 X014 말고 바뀐 것이 없다 — 이전 블롭 대비 전문 diff

제출자 주장을 믿지 않고 이전 블롭과 직접 비교했다:

```
old blob 5883acfa5c2ff260dc516ef4fec264b558c70070  31,026 bytes
new blob 8796d32e146bf3e5186a5f32628d3aeef6d23593  31,023 bytes

$ diff <old> <new>
298c298
<    "kind": "COMMANDERY",
---
>    "kind": "KINGDOM",
```

**전문 diff 가 이 한 줄뿐이다.** 3바이트 차이(`COMMANDERY` 10자 → `KINGDOM` 7자)도 정확히 정합한다. 다른 필드·좌표·QID 를 슬쩍 바꾼 흔적 없음.

데이터도 다시 셌다(제출자 수치를 그대로 받지 않고):

```
n = 65
kind = COMMANDERY 27 · KINGDOM 1 · EXTERNAL_PLACE 37     ← 커밋 메시지 주장과 일치
wikidata QID = 65/65 · basis 누락 0
X014 = 魯國 / KINGDOM / Qufu / Q210287                    ← B1 대상 필드, 고쳐짐
交趾郡·九真郡·日南郡·樂浪郡·帶方郡 = 전부 present
```

## R3 B2 해소 — 그리고 새 주장들을 코드로 검증했다

「FileNotFoundError 로 죽는다」가 사라지고 **「선택적 입력으로 가드된다 — 없어도 크래시하지 않는다, 문제는 조용히 나빠진다는 것」**으로 바뀌었다. `os.path.exists` / `except FileNotFoundError: pass` 를 정확히 인용한다. 거짓 주장이 정확한 주장으로 교체됐다.

이번엔 주장이 더 강해졌으므로 더 엄하게 봤다. 오늘 이 저장소에서 주석·헤더가 거짓말한 사례가 셋(#538 nginx 헤더, B2, `han_route_node_candidates.py` no-op)이므로 **주석이 아니라 코드가 그렇게 동작하는지** 실행 경로로 확인했다.

**주장 ② `EXTRA_ANCHOR` 공백 → `MAX_KM=400` 무력화 — 코드상 사실이다.** 제출자는 근거로 주석을 들었는데, 주석 말고 실행 경로를 따라갔다:

```
build_junguozhi.py:241-243   b['anchor'] = (pref_xy.get(key)
                                            or pref_xy.get(key.translate(VARIANT))
                                            or EXTRA_ANCHOR.get(key) or [None])[0]
build_junguozhi.py:273       d = min((km(b['anchor'], q) for q in pts), default=None) if b['anchor'] else None
build_junguozhi.py:274       if d is not None and d > MAX_KM: continue
```

CHGIS 판도 밖 郡은 `pref_xy` 에 항목이 없으므로 anchor 가 오직 `EXTRA_ANCHOR` 에서만 온다. 파일이 없으면 `EXTRA_ANCHOR = {}` → `anchor = None` → `:273` 이 `d = None` → `:274` 의 `d is not None` 이 항상 거짓 → **`continue` 에 절대 도달하지 않는다. 필터가 실제로 꺼진다.** 주석이 아니라 코드가 그렇게 동작한다.

**덧붙여 커밋 메시지가 오히려 피해를 과소평가한다.** `:278` 도 같이 열화된다:

```
build_junguozhi.py:278   q = min(pts, key=lambda q: km(b['anchor'], q)) if b['anchor'] else pts[0]
```

anchor 가 없으면 동명 후보 중 **최근접이 아니라 `pts[0]`(임의 첫 후보)** 를 좌표로 채택한다. 즉 필터가 꺼질 뿐 아니라 선택 규칙까지 무너진다. 커밋 메시지는 과장이 아니라 보수적으로 적었다.

**주장 ① 交州 남부 3郡·樂浪·帶方 누락 — 메커니즘은 코드로 확정, 실행 검증은 불가.**

```
build_han_places.py:124   if os.path.exists(ext_path):
build_han_places.py:129       seats[(e['nameFt'], round(e['lon'],4), round(e['lat'],4))] = e
build_han_places.py:132   places = sorted(seats.values(), ...)
```

파일이 없으면 `:124` 블록 전체가 건너뛰어져 그 지점들이 `seats` 에 들어가지 못하고 `places` 에도, 산출물에도 없다. **이 지점들을 공급하는 곳이 이 파일뿐이라는 전제 하에서 누락은 필연이다.** 그 전제(CHGIS 220년 레이어가 이들을 안 담는다)는 빌더 docstring·주석이 일관되게 말하고 이 파일의 존재 이유 자체지만, **CHGIS 원본이 없어 실행으로는 확인하지 못했다 — UNKNOWN 으로 남긴다.** 파일을 치우고 돌려보는 실험 자체가 R5(b)의 `FATAL` 때문에 불가능하다. 추정으로 통과시키지 않는다. 다만 코드 경로 자체는 명확하다.

**주장 ③ 「소유 격자와 도로망에서도 빠진다」 — 확장 주장, 근거 있다.** 이건 `build_han_places.py:120-121` 주석과 거의 같은 문장이라 주석 인용인지 의심하고 체인을 직접 짚었다:

```
build_terrain_grid.py:20    PLACES = 'data/map/han-places.json'        ← 격자·도로망의 입력
build_terrain_grid.py:31    SEAT_KINDS = {'COMMANDERY', 'KINGDOM'}
build_terrain_grid.py:588   if (jun_of[i] < 0 and i not in misplaced and pl.get('kind') != 'PROVINCE'
                                and (pl.get('hub') or pl.get('kind') in SEAT_KINDS)):
build_terrain_grid.py:327   def build_roads(terrain, pts, hubs)
```

소유 격자와 도로망을 만드는 `build_terrain_grid.py` 의 입력이 `han-places.json` 이고, 격자·도로 시딩이 `SEAT_KINDS` 게이트를 지난다. **han-places.json 에 없는 지점은 격자에도 도로망에도 들어갈 수 없다.** 주석 인용이 아니라 입력 체인으로 성립한다 — 확장 주장이지만 근거가 있다.

## R4 `.gitignore` 3줄과 ADR-LITE-046 — 제출자 말대로 미변경

지시대로 시간 쓰지 않고 확인만 했다. `.gitignore` 블롭이 이전 tip 과 **동일**(`b56af28f60602f0932bc0198c4bb76c66035cdbf`)하고, 커밋이 건드린 파일은 `.gitignore` + 데이터 2개뿐으로 **`.ai/` 변경 0건**(ADR 미변경). 실효 규칙은 여전히 `!data/map/external-places.json` 한 줄이고 `han-places.json`·`terrain-grid.json` 은 `.gitignore:108` 의 `data/map/*` 에 그대로 걸린다. 이전 심사에서 통과시킨 항목이라 재검증하지 않았다.

## R5 B1 수정의 파생 영향 — 없다. 그리고 이 커밋은 하류 재생성을 유발하지 않는다

초심에서 UNKNOWN 으로 남긴 항목인데, 이번엔 답이 나왔다.

**(a) X014 가 KINGDOM 이 돼도 하류 동작이 달라지지 않는다.** `kind == 'COMMANDERY'` 로 분기하는 코드가 저장소 전체에 **0건**이다(`tools/`·`web/`·`common/`·`logic/` 전수 grep). `kind` 는 언제나 집합 멤버십으로만 검사되고, `build_terrain_grid.py:31 SEAT_KINDS = {'COMMANDERY','KINGDOM'}` 는 **둘 다 포함**한다 — 魯國 은 어느 쪽이든 1급 치소로 남아 격자·도로 시딩에 그대로 들어간다. 렌더링도 마찬가지다: `HanMapCanvas.tsx:60-66` 이 1급(郡·國)은 `juns[]` 배열로 그리고 그 배열엔 등급 필드가 아예 없으며, `TIER2_MARKER_ZOOM` 은 2급 전용 테이블이다. **KINGDOM 이 그 테이블에 없는 것은 설계이지 결함이 아니다.**

**(b) 이 커밋은 `han-tiles.json` 재생성을 유발하지도, 가능하게 하지도 않는다.** #536(재생성 시 인접 간선 2662→1230, 28% 고립, 원인 UNKNOWN) 때문에 중요해진 질문이라 실행으로 확인했다:

```
$ python3 tools/map/build_han_places.py --out /tmp/hp_test.json
FATAL: data/chgis-source/v6_time_pref_pts_utf_wgs84.dbf 없음. CHGIS V6 Dataverse 배포본을 data/chgis-source/ 에 풀어라.
REAL_EXIT=1        (출력 파일 생성 안 됨)
```

체인의 **첫 단계가 CHGIS 원본에서 막힌다**(`build_han_places.py:93`). external-places.json 을 커밋해도 `han-places.json` → `terrain-grid.json` → `han-tiles.json` 재생성은 신선한 체크아웃에서 여전히 불가능하다. CI 경로도 없다 — `.github/workflows/` 에 `build_han_places`·`build_terrain_grid`·`han-tiles` 참조 0건이고, `tools/map/tests/` 에 `external-places` 참조 0건이라 이 커밋으로 새로 빨개지거나 새로 도는 검사가 없다. **#536 의 재생성 금지 상태를 이 커밋이 건드리지 않는다.**

## 머지 순서

`git merge-tree --write-tree --messages` 계열 검증은 이전 tip(`0e6e2ee6`)에서 이미 충돌 0 / EXIT 0(양방향)을 확인했고, 이번 tip 은 그때와 **`.gitignore` 블롭이 동일하고 `.ai/` 를 건드리지 않으므로** 충돌 표면이 동일하다. 정당화 ADR(046)이 묶음 4 에만 있어 중복 append 충돌이 구조적으로 없다. **순서 의존 없음.**

권고 하나만 유지한다(비블로킹): **묶음 1 만 들어가고 묶음 4 가 폐기되는 조합**은 피하는 게 좋다 — main 이 승인 ADR 없이 커밋된 지도 데이터를 안게 된다. 거버넌스 문제지 기술 문제는 아니다.

## 부기 — #507 은 계열 문제로 보인다

`5edfc30e`(#507 「split 郡/國/尹」)가 오늘 세 번째로 산출물 stale 의 진원지로 나왔다. 형태가 같다: **#507 은 `tools/map/*.py` 의 분류 로직을 바꿨는데, 그 로직이 만드는 커밋된 산출물 전부를 재생성하지는 않았다.** `administrative-units.json` 은 재생성됨(COMMANDERY 85/KINGDOM 20, 정상) · `han-tiles.json` 은 미재생성(KINGDOM 0, #536 으로 재생성 금지) · `external-places.json` 은 미재생성이었다가 이번 커밋에서 해소. 근본 처방은 「분류기를 바꾸면 그 분류기가 만드는 커밋된 산출물 전부에 `--check` 를 건다」이고, 지금 CI 에는 그런 게이트가 없다(`.github/workflows/` 에 `build_unitset`·`build_han_places`·`build_terrain_grid` 참조 0건). 이 비평의 범위 밖이라 지적으로 올리지 않고 관찰만 남긴다.

## UNKNOWN

- **交州 남부 3郡·樂浪·帶方이 실제로 누락되는지 실행 검증 못 했다.** `build_han_places.py` 가 CHGIS 원본에서 `FATAL` 로 막혀(실측) 파일을 치우고 돌려보는 실험 자체가 불가능하다. 코드 경로(`:124`)는 명확하지만 실행 증거는 없다.
- 「CHGIS 220년 레이어가 이 지점들을 안 담는다」는 전제 — docstring·주석이 일관되게 말하나 CHGIS 원본이 없어 대조 불가.
- 커밋된 65건 좌표가 CHGIS 값과 우연히 일치하는지 — 원본 부재로 대조 불가. 라이브 재현이 출처를 이미 증명하므로 판정에는 영향 없다.

## 판정 근거 요약

내가 올린 blocker 2건이 실제로 고쳐졌다. 데이터는 라이브 재생성과 **0 differing lines**, 이전 블롭 대비 전문 diff 는 X014 한 줄뿐, 커밋 메시지의 거짓 주장은 코드로 검증되는 정확한 주장으로 교체됐다. 새로 세운 열화 주장 셋 중 둘(②③)은 주석이 아닌 코드 경로로 확인했고 하나(①)는 메커니즘만 확정하고 실행 검증 불가로 UNKNOWN 에 남겼다 — 다만 그 주장이 커밋의 정당성을 좌우하지는 않는다. 오히려 `:278` 은 커밋 메시지가 실제보다 보수적으로 적었다. 초심에서 UNKNOWN 이던 파생 영향도 답이 나왔다: `kind == 'COMMANDERY'` 분기가 저장소에 0건이라 X014 의 등급 변경은 하류에 무해하고, CHGIS 원본이 없어 이 커밋이 `han-tiles.json` 재생성을 유발할 수도 없다(#536 안전).

Verdict: cleared
