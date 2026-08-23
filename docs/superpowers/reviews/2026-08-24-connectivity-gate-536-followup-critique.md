# #536 연결성 게이트 후속 커밋 재심 — a680cac0

Scope: commit a680cac09e2397e73cc9b48dc38c924d5974a4db on work/opensamguk/tiles-tier-524, single file tools/map/tests/test_han_tiles_adjacency_connectivity.py

Verdict: cleared

검증자: critic-conn-536 (독립 재심). 전용 detached 워크트리에서 실행했고 제출자 워크트리는 읽기만 했다. `7f5f57f2`(cleared)·`661631d6`(내 비평문)는 범위 밖이다.

---

## 1. 커밋 위생 — 통과

```
$ git show --stat a680cac0
 .../tests/test_han_tiles_adjacency_connectivity.py | 31 ++++++++++++++++++++--
 1 file changed, 29 insertions(+), 2 deletions(-)

$ git diff --name-only a680cac0^ a680cac0 -- data/
(빈 출력)
```

파일 하나, `data/` 무변경. **`han-tiles.json` 재생성본 동반 커밋 없음.** 최우선 확인 항목 통과.

## 2. 8/167/7 vs 9/166/8 이 맞나 — 맞다, 직접 쟀다

`juns` 배열 길이 174 를 노드 수로 잡고(간선의 최대 노드 id 173 과 정합) `adjacency.commandery` 에 같은 union-find 를 돌렸다. 재생성본은 제출자 스냅샷을 쓰지 않고, gitignored 입력을 복사해 내 워크트리에서 `build_tile_grid.py` 를 다시 돌려 만들었다.

```
$ python3 tools/map/build_tile_grid.py
... adjCounty 1230 · adjCommandery 366 ...

커밋본  : components=8 main=167/174 ratio=0.95977 isolated=7  edges=425
재생성본: components=9 main=166/174 ratio=0.95402 isolated=8  edges=366
```

제출자가 보고한 수치와 **정확히 일치**한다. 縣 쪽(고립 10 대 321)과 달리 성분·고립 모두 차이가 딱 1 이다.

## 3. "연결성 문턱이 불가능하다"는 판단 — 옳다. 반례로 확정한다

먼저 정확히 하자. **두 상태를 가르는 문턱은 존재한다.** 산술적으로:

| 문턱 형태 | 유일하게 가능한 값 | 커밋본 여유 |
|---|---|---|
| `components < X` | X = 9 | **0** (커밋본이 8, 경계 바로 밑) |
| `isolated < X` | X = 8 | **0** (커밋본이 7) |
| `main_ratio >= X` | 0.9541 ≤ X ≤ 0.9598 | **0** (main 167→166 이면 즉시 RED) |

즉 가능한 문턱은 전부 **여유 0** — 불변식이 아니라 현재 값을 그대로 박아놓은 골든 핀이다. (참고로 직관적인 `>= 0.96` 은 아예 못 쓴다. 커밋본이 0.95977 이라 그 자리에서 RED 다.)

여유 0 이 실제로 오탐을 뜻하는지 실측했다. 커밋본에서 **차수 1 짜리 郡 하나**(node 116)의 간선을 뗐다 — 도서·소국 편입 같은 정상 변경이 낼 수 있는 최소 변화(간선 425→424, -1)다.

```
committed        : (components 8, main 167, ratio 0.95977, isolated 7)  edges 425
rebuilt          : (components 9, main 166, ratio 0.95402, isolated 8)  edges 366
one-jun-detached : (components 9, main 166, ratio 0.95402, isolated 8)  edges 424
```

**郡 하나를 뗀 정상 변경의 연결성 지문이 재생성본과 완전히 동일하다.** 연결성 지표만으로는 두 경우가 구별 불가능하다 — 재생성을 잡는 모든 문턱은 郡 하나 바뀌는 정상 변경도 반드시 함께 잡는다.

**판정: "안전하게 게이트할 여유가 없다"는 제출자 판단은 옳다.** 가능한 문턱 값을 제시할 수는 있지만(`components < 9` 등) 전부 오탐 확정이라 **쓸 수 있는 값은 없다.** 여기서는 넣지 않은 게 맞다.

## 4. `MIN_COMMANDERY_EDGES = 150` 바닥값 — 좁다. 좁다고 판정한다 (차단 아님)

샌드박스에서 변조본을 만들어 실제로 돌렸다.

| 입력 | 결과 |
|---|---|
| `commandery = []` (내가 든 반례) | **FAILED** |
| 425 → 200 (**53% 부분 파손**) | OK |
| 425 → 150 | OK |
| 425 → 149 | **FAILED** |
| 425 → 445 (증가 방향) | OK |
| 커밋본 425 | OK |
| 재생성본 366 | OK (縣 검사 쪽에서 별도로 RED) |

경계는 150/149 에 정확히 있다. 잡는 것은 **간선의 70% 이상이 사라지는 경우뿐**이다. 郡 간선이 425→200 으로 반토막나도 통과한다.

**판정: 이건 사실상 "필드가 통째로/거의 통째로 비었나"만 보는 존재 검사다.** 부분 파손은 못 잡는다. 다만 —

- 커밋 메시지와 코드 주석 둘 다 이걸 **"critic 이 실제로 보인 반례만 확실히 잡는다"**고 명시한다. 과대주장이 아니다.
- 3절에서 확정했듯 **더 강한 검사는 오탐 없이 만들 수 없다.** 좁은 검사와 검사 없음 중에서 좁은 검사를 고른 것이라, 이 좁음은 설계 실패가 아니라 측정된 제약이다.
- 원래 내가 지적한 사각지대(`commandery=[]` 가 초록)는 실제로 닫혔다.

수용 가능한 경계다. 다만 후속 변경자가 "郡 그래프가 보호된다"고 오해하지 않도록, 이 검사의 이름(`test_commandery_adjacency_is_populated`)과 docstring 이 "비어 있지 않은지만 본다"로 정직하게 좁게 쓰여 있는 점을 확인했다.

## 5. "간선이 느는 방향은 부등호상 자명" — 맞다

`assertGreaterEqual(len(edges), 150)` 단항 하한이라 간선 증가는 정의상 통과한다. 그래도 실측했다: 425→445 로 20개 추가한 입력에서 OK(위 표). 시뮬레이션을 생략한 판단은 옳았다.

## 6. 주석 수정 — 내 A-3 표와 일치한다

주석 주장: 고립 14 까지 통과, 15개째(추가 5개)에서 RED, 그 시점 `main_ratio` 98.34% 라 비율 문턱은 안 걸린다.

a680cac0 의 테스트 파일로 재현:

```
isolate 4: components=16 main=1127 ratio=0.98428 isolated=14 -> OK
isolate 5: components=17 main=1126 ratio=0.98341 isolated=15 -> FAILED
```

내 1차 비평 A-3 표(k=4 OK / k=5 FAILED, ratio 0.9834)와 **정확히 일치**한다. 검증되지 않았던 `14→9` 수치는 제거됐다(diff 확인). 병목이 `MAX_ISOLATED`(여유 4)라는 정정도 정확하다.

"숫자는 그때그때 실측해라, 여기 옮겨 적지 마라" 문장이 숫자가 가득한 문단 바로 아래 있어 표현상 약간 어긋나 보이지만, **거기 남은 숫자는 전부 내가 이 세션에서 독립 실측한 값**(12/1131/1145, 356/660/321, 4, 98.34%, 8/167/174/96.0%, 9/166/95.4%, 425, 366)이고 다른 그래프에서 옮겨 온 값은 없다. 실질적으로 지시대로 됐다.

`MIN_MAIN_COMPONENT_RATIO=0.98` / `MAX_ISOLATED=15` / `MAX_COMPONENTS=20` 은 그대로다 — 주석만 고쳤지 문턱을 몰래 바꾸지 않았다.

## 7. 회귀 — 통과

```
$ git checkout a680cac0^ && python3 -m unittest discover -s tools/map/tests -p 'test_*.py'
Ran 44 tests in 0.053s
OK (skipped=10)

$ git checkout a680cac0 && python3 -m unittest discover -s tools/map/tests -p 'test_*.py' -v
test_commandery_adjacency_is_populated (...)
郡 인접이 통째로 비어 있지 않은지만 본다 — 연결성 문턱은 위 주석 참고. ... ok
Ran 45 tests in 0.119s
OK (skipped=10)
```

44 → 45, `skipped=10` 불변(전부 기존 `test_junguozhi_contract`), 신규 테스트는 `... ok` 로 실제 실행된다. **skip 아님.**

오염 없음: 소스 스캔(`skip|setUp|tearDown|write|open\(|os\.environ|global|exists\(\)|try:`) 결과 코드에는 한 건도 없고 2행 주석의 "skip" 단어만 걸린다. 새 테스트는 `TILES.read_text()` 한 번 읽고 끝 — 파일 쓰기·전역·환경변수 없음. 두 테스트가 각자 파일을 파싱하지만(중복 파싱) 총 0.12초라 무시 가능.

재생성본에서 전체 스위트를 돌리면 `FAILED (failures=1, skipped=10)` — 縣 검사 하나만 죽고 郡 검사는 366 ≥ 150 이라 통과한다. 예상대로다.

## 8. 워크트리 무결성

재생성 실험 후 `git checkout -- data/map/han-tiles.json` 으로 원복, 복사해 온 gitignored 입력 5개 전부 삭제.

```
$ git status --short
(빈 출력, 이 리포트 파일 제외)
$ git hash-object data/map/han-tiles.json
3ebef25f2c206b99ac802e58b6dfcf5aadcce8db   ← 커밋 blob 과 동일
```

커밋·푸시·PR 없음. 제출자 워크트리 미변경.

## 9. UNKNOWN

- `data/natural-earth/` 부재로 `build_terrain_grid.py` 부터의 전체 재생성은 여전히 못 돌렸다. 마지막 단계(`build_tile_grid.py`)만 독립 재실행했다.
- `juns` 배열이 `adjacency.commandery` 인덱스 공간과 정확히 같다는 건 최대 노드 id(173)와 길이(174) 정합으로만 확인했다. 스크립트 수준 보증은 확인하지 않았다.
- GitHub Actions 러너 실제 실행은 로컬 재현으로 대체했다. 원격 CI 로그 미확인.

## 10. 남은 제안 (차단 아님)

郡 그래프의 부분 파손을 잡고 싶다면 연결성이 아니라 **간선 수를 골든 핀으로** 박는 편이 정직하다(`425 ± 허용치`). 지금은 재생성본 366 도 통과하므로 그 핀을 넣으려면 "재생성을 어디서 잡을지"를 縣 검사에 맡길지 郡 검사에도 중복시킬지 먼저 정해야 한다 — 이번 커밋 범위 밖이고, 후속 이슈로 충분하다.
